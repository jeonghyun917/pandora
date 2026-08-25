package com.kaces.pandora.semantic.retrieval;

import com.kaces.pandora.common.text.DocumentSearchAnchor;
import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class DocumentCandidateExpansion {

	private static final int MAX_DOCUMENTS = 3;
	private static final int MAX_CHUNKS_PER_DOCUMENT = 8;
	private static final int MAX_TOTAL_CHUNKS = 24;

	public DocumentSelection selectDocuments(
		DocumentSearchAnchor anchor,
		List<DocumentIdentityCandidate> candidates,
		Policy policy
	) {
		if (!enabled(policy)) {
			return documentSelection(List.of(), Status.DISABLED, List.of());
		}
		if (!validBounds(policy)) {
			return documentSelection(List.of(), Status.INVALID_BOUNDS, List.of());
		}
		if (!isAnchored(anchor)) {
			return documentSelection(List.of(), Status.NO_STRONG_ANCHOR, List.of("DOCUMENT_NOT_ANCHORED"));
		}

		LinkedHashSet<String> reasonCodes = new LinkedHashSet<>();
		List<String> titleTerms = normalizedTerms(anchor.titleTerms());
		Set<String> targetRestrictions = normalizedTargets(anchor.targets());
		Map<String, ScoredDocument> matchesByDocumentIdentity = new HashMap<>();
		for (DocumentIdentityCandidate candidate : safeList(candidates)) {
			if (!validDocument(candidate)) {
				reasonCodes.add("INVALID_DOCUMENT_IDENTITY");
				continue;
			}
			if (!targetRestrictions.isEmpty() && !targetRestrictions.contains(normalizeTarget(candidate.target()))) {
				continue;
			}
			String candidateTitle = KoreanQueryNormalizer.normalizeForMatch(candidate.title());
			boolean exactTitle = titleTerms.stream().anyMatch(candidateTitle::equals);
			boolean allTitleTerms = !titleTerms.isEmpty() && titleTerms.stream().allMatch(candidateTitle::contains);
			if (!exactTitle && !allTitleTerms) {
				continue;
			}
			ScoredDocument scored = new ScoredDocument(candidate, exactTitle, allTitleTerms, candidate.provisionAnchorMatch());
			matchesByDocumentIdentity.merge(documentKey(candidate), scored, this::preferredDocument);
		}

		List<ScoredDocument> matches = new ArrayList<>(matchesByDocumentIdentity.values());
		if (matches.isEmpty()) {
			return documentSelection(List.of(), Status.DOCUMENT_NOT_FOUND, reasonCodes);
		}
		matches.sort(documentComparator());
		if (matches.size() > policy.maxDocuments()
			&& matches.get(policy.maxDocuments() - 1).sameEligibility(matches.get(policy.maxDocuments()))) {
			reasonCodes.add("DOCUMENT_MATCH_AMBIGUOUS");
			return documentSelection(List.of(), Status.DOCUMENT_MATCH_AMBIGUOUS, reasonCodes);
		}

		if (matches.size() > policy.maxDocuments()) {
			reasonCodes.add("DOCUMENT_LIMIT");
		}
		return documentSelection(
			matches.stream().limit(policy.maxDocuments()).map(ScoredDocument::candidate).toList(),
			Status.APPLIED,
			reasonCodes
		);
	}

	public Result rankChunks(
		DocumentSearchAnchor anchor,
		DocumentSelection documents,
		List<LawSemanticChunkRow> candidates,
		Set<String> existingCandidateKeys,
		Policy policy
	) {
		if (!enabled(policy)) {
			return result(List.of(), List.of(), Status.DISABLED, List.of());
		}
		if (!validBounds(policy)) {
			return result(List.of(), List.of(), Status.INVALID_BOUNDS, List.of());
		}
		if (!isAnchored(anchor)) {
			return result(List.of(), List.of(), Status.NO_STRONG_ANCHOR, List.of("DOCUMENT_NOT_ANCHORED"));
		}
		if (documents == null) {
			return result(List.of(), List.of(), Status.FALLBACK_BASELINE, List.of("INVALID_DOCUMENT_IDENTITY"));
		}
		if (documents.status() != Status.APPLIED) {
			return result(List.of(), List.of(), documents.status(), documents.reasonCodes());
		}

		DocumentSelection verifiedDocuments = selectDocuments(anchor, documents.documents(), policy);
		if (verifiedDocuments.status() != Status.APPLIED) {
			return result(List.of(), List.of(), verifiedDocuments.status(), verifiedDocuments.reasonCodes());
		}
		LinkedHashSet<String> reasonCodes = new LinkedHashSet<>(documents.reasonCodes());
		reasonCodes.addAll(verifiedDocuments.reasonCodes());
		List<DocumentIdentityCandidate> selectedDocuments = verifiedDocuments.documents();
		Map<String, List<LawSemanticChunkRow>> byDocument = safeList(candidates).stream()
			.filter(row -> validChunk(row, reasonCodes))
			.collect(Collectors.groupingBy(
				this::documentKey,
				Collectors.toCollection(ArrayList::new)
			));
		List<String> provisions = normalizedTerms(anchor.provisionTerms());
		List<String> headings = normalizedTerms(anchor.headingTerms());
		List<String> evidence = normalizedTerms(anchor.evidenceTerms());
		Set<String> existingKeys = safeExistingKeys(existingCandidateKeys);
		List<LawSemanticChunkRow> chunks = new ArrayList<>();
		List<Hit> hits = new ArrayList<>();
		Set<String> emittedKeys = new HashSet<>();

		for (DocumentIdentityCandidate document : selectedDocuments) {
			if (chunks.size() >= policy.maxTotalChunks()) {
				reasonCodes.add("DOCUMENT_GLOBAL_LIMIT");
				break;
			}
			List<ScoredChunk> ranked = byDocument.getOrDefault(documentKey(document), List.of()).stream()
				.map(row -> scoreChunk(row, provisions, headings, evidence))
				.sorted(chunkComparator())
				.toList();
			if (ranked.size() > policy.maxChunksPerDocument()) {
				reasonCodes.add("DOCUMENT_CHUNK_LIMIT");
			}
			int emittedForDocument = 0;
			for (ScoredChunk scored : ranked) {
				if (emittedForDocument >= policy.maxChunksPerDocument()) {
					break;
				}
				if (chunks.size() >= policy.maxTotalChunks()) {
					reasonCodes.add("DOCUMENT_GLOBAL_LIMIT");
					break;
				}
				String candidateKey = candidateKey(scored.row());
				if (!emittedKeys.add(candidateKey)) {
					reasonCodes.add("DOCUMENT_DUPLICATE_OVERLAP");
					continue;
				}
				boolean overlapsExisting = existingKeys.contains(candidateKey);
				if (overlapsExisting) {
					reasonCodes.add("DOCUMENT_DUPLICATE_OVERLAP");
				}
				chunks.add(scored.row());
				hits.add(new Hit(candidateKey, hits.size() + 1, anchor.anchorType().name(), overlapsExisting, scored.reason()));
				emittedForDocument++;
			}
		}

		if (!validResult(chunks, hits, policy)) {
			return result(List.of(), List.of(), Status.FALLBACK_BASELINE, reasonCodes);
		}
		return result(chunks, hits, Status.APPLIED, reasonCodes);
	}

	public Result rankSeededChunks(
		DocumentSearchAnchor anchor,
		List<DocumentExpansionSeed> seeds,
		List<LawSemanticChunkRow> candidates,
		Set<String> existingCandidateKeys,
		Policy policy
	) {
		if (!enabled(policy)) {
			return result(List.of(), List.of(), Status.DISABLED, List.of());
		}
		if (!validBounds(policy) || anchor == null) {
			return result(List.of(), List.of(), Status.BM25_TITLE_INVALID_INPUT, List.of("BM25_TITLE_INVALID_INPUT"));
		}
		List<DocumentExpansionSeed> safeSeeds = safeList(seeds);
		if (safeSeeds.isEmpty()) {
			return result(List.of(), List.of(), Status.BM25_TITLE_NO_MATCH, List.of("BM25_TITLE_NO_MATCH"));
		}
		if (safeSeeds.size() > policy.maxDocuments()) {
			return result(List.of(), List.of(), Status.BM25_TITLE_INVALID_INPUT, List.of("BM25_TITLE_DOCUMENT_LIMIT"));
		}

		Set<String> targetRestrictions = normalizedTargets(anchor.targets());
		Map<String, DocumentExpansionSeed> seedByDocument = new HashMap<>();
		for (DocumentExpansionSeed seed : safeSeeds) {
			if (!validSeed(seed, targetRestrictions)
				|| seedByDocument.putIfAbsent(documentKey(seed), seed) != null) {
				return result(List.of(), List.of(), Status.BM25_TITLE_INVALID_INPUT, List.of("BM25_TITLE_INVALID_SEED"));
			}
		}

		LinkedHashSet<String> reasonCodes = new LinkedHashSet<>();
		Map<String, List<LawSemanticChunkRow>> byDocument = new HashMap<>();
		for (LawSemanticChunkRow row : safeList(candidates)) {
			if (!validChunk(row, reasonCodes)) {
				return result(List.of(), List.of(), Status.BM25_TITLE_INVALID_INPUT, reasonCodes);
			}
			String key = documentKey(row);
			if (seedByDocument.containsKey(key)) {
				byDocument.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
			}
		}

		List<String> provisions = normalizedTerms(anchor.provisionTerms());
		List<String> headings = normalizedTerms(anchor.headingTerms());
		List<String> evidence = normalizedTerms(anchor.evidenceTerms());
		Set<String> existingKeys = safeExistingKeys(existingCandidateKeys);
		List<LawSemanticChunkRow> chunks = new ArrayList<>();
		List<Hit> hits = new ArrayList<>();
		Set<String> emittedKeys = new HashSet<>();

		for (DocumentExpansionSeed seed : safeSeeds) {
			if (chunks.size() >= policy.maxTotalChunks()) {
				reasonCodes.add("DOCUMENT_GLOBAL_LIMIT");
				break;
			}
			List<ScoredChunk> ranked = byDocument.getOrDefault(documentKey(seed), List.of()).stream()
				.map(row -> scoreChunk(row, provisions, headings, evidence))
				.sorted(chunkComparator())
				.toList();
			if (ranked.size() > policy.maxChunksPerDocument()) {
				reasonCodes.add("DOCUMENT_CHUNK_LIMIT");
			}
			int emittedForDocument = 0;
			for (ScoredChunk scored : ranked) {
				if (emittedForDocument >= policy.maxChunksPerDocument() || chunks.size() >= policy.maxTotalChunks()) {
					if (chunks.size() >= policy.maxTotalChunks()) {
						reasonCodes.add("DOCUMENT_GLOBAL_LIMIT");
					}
					break;
				}
				String candidateKey = candidateKey(scored.row());
				if (!emittedKeys.add(candidateKey)) {
					reasonCodes.add("DOCUMENT_DUPLICATE_OVERLAP");
					continue;
				}
				boolean overlapsExisting = existingKeys.contains(candidateKey);
				if (overlapsExisting) {
					reasonCodes.add("DOCUMENT_DUPLICATE_OVERLAP");
					continue;
				}
				chunks.add(scored.row());
				hits.add(new Hit(
					candidateKey,
					hits.size() + 1,
					"BM25_TITLE",
					overlapsExisting,
					"BM25_TITLE_SEED",
					seed.matchedTitleTerms().size(),
					seed.bm25Score(),
					seed.bm25Rank()
				));
				emittedForDocument++;
			}
		}

		if (!validResult(chunks, hits, policy)) {
			return result(List.of(), List.of(), Status.BM25_TITLE_INVALID_INPUT, reasonCodes);
		}
		if (chunks.isEmpty()) {
			if (reasonCodes.contains("DOCUMENT_DUPLICATE_OVERLAP")) {
				reasonCodes.add("BM25_TITLE_NO_NOVEL_CHUNK");
			}
			return result(List.of(), List.of(), Status.BM25_TITLE_NO_MATCH, reasonCodes);
		}
		return result(chunks, hits, Status.BM25_TITLE_APPLIED, reasonCodes);
	}

	private boolean validResult(List<LawSemanticChunkRow> chunks, List<Hit> hits, Policy policy) {
		if (chunks.size() != hits.size() || chunks.size() > policy.maxTotalChunks()) {
			return false;
		}
		Set<String> keys = new HashSet<>();
		Map<String, Long> perDocument = chunks.stream().collect(Collectors.groupingBy(this::documentKey, Collectors.counting()));
		return hits.stream().map(Hit::candidateKey).allMatch(keys::add)
			&& perDocument.values().stream().allMatch(count -> count <= policy.maxChunksPerDocument());
	}

	private ScoredChunk scoreChunk(
		LawSemanticChunkRow row,
		List<String> provisions,
		List<String> headings,
		List<String> evidence
	) {
		boolean provisionMatch = provisions.stream().anyMatch(provision -> provision.equals(normalizeProvision(row.chunkNo())));
		boolean headingMatch = headings.stream().anyMatch(heading -> heading.equals(KoreanQueryNormalizer.normalizeForMatch(row.chunkTitle()))
			|| heading.equals(KoreanQueryNormalizer.normalizeForMatch(row.parentSectionTitle())));
		String searchable = KoreanQueryNormalizer.normalizeForMatch(String.join(" ",
			nullToEmpty(row.chunkNo()), nullToEmpty(row.chunkTitle()), nullToEmpty(row.parentSectionTitle()), nullToEmpty(row.chunkText())));
		int evidenceCount = (int) evidence.stream().filter(searchable::contains).count();
		String reason = provisionMatch ? "EXACT_PROVISION"
			: headingMatch ? "EXACT_HEADING"
			: evidenceCount > 0 ? "EVIDENCE_TERMS" : "DOCUMENT_ORDER";
		return new ScoredChunk(row, provisionMatch, headingMatch, evidenceCount, reason);
	}

	private Comparator<ScoredDocument> documentComparator() {
		return Comparator.comparing(ScoredDocument::exactTitle).reversed()
			.thenComparing(Comparator.comparing(ScoredDocument::allTitleTerms).reversed())
			.thenComparing(Comparator.comparing(ScoredDocument::provisionMatch).reversed())
			.thenComparing(scored -> scored.candidate().documentId())
			.thenComparing(scored -> KoreanQueryNormalizer.normalizeForMatch(scored.candidate().title()));
	}

	private ScoredDocument preferredDocument(ScoredDocument first, ScoredDocument second) {
		return documentComparator().compare(first, second) <= 0 ? first : second;
	}

	private Comparator<ScoredChunk> chunkComparator() {
		return Comparator.comparing(ScoredChunk::provisionMatch).reversed()
			.thenComparing(Comparator.comparing(ScoredChunk::headingMatch).reversed())
			.thenComparing(Comparator.comparing(ScoredChunk::evidenceCount).reversed())
			.thenComparing(scored -> scored.row().sortOrder())
			.thenComparing(scored -> scored.row().chunkId());
	}

	private boolean isAnchored(DocumentSearchAnchor anchor) {
		return anchor != null && anchor.eligible() && !normalizedTerms(anchor.titleTerms()).isEmpty();
	}

	private boolean enabled(Policy policy) {
		return policy != null && policy.enabled();
	}

	private boolean validBounds(Policy policy) {
		return policy != null
			&& policy.maxDocuments() > 0 && policy.maxDocuments() <= MAX_DOCUMENTS
			&& policy.maxChunksPerDocument() > 0 && policy.maxChunksPerDocument() <= MAX_CHUNKS_PER_DOCUMENT
			&& policy.maxTotalChunks() > 0 && policy.maxTotalChunks() <= MAX_TOTAL_CHUNKS;
	}

	private boolean validDocument(DocumentIdentityCandidate candidate) {
		return candidate != null && candidate.documentId() > 0
			&& !normalizeTarget(candidate.target()).isBlank()
			&& !KoreanQueryNormalizer.normalizeForMatch(candidate.title()).isBlank();
	}

	private boolean validSeed(DocumentExpansionSeed seed, Set<String> targetRestrictions) {
		if (seed == null
			|| seed.documentId() <= 0
			|| normalizeTarget(seed.target()).isBlank()
			|| KoreanQueryNormalizer.normalizeForMatch(seed.title()).isBlank()
			|| seed.matchedTitleTerms().size() < 2
			|| seed.matchedTitleTerms().size() > 6
			|| !Double.isFinite(seed.bm25Score())
			|| seed.bm25Score() <= 0.0
			|| seed.bm25Rank() < 1
			|| seed.bm25Rank() > 100
			|| !"BM25_TITLE".equals(seed.anchorType())
			|| !"BM25_TITLE_SEED".equals(seed.reason())) {
			return false;
		}
		return targetRestrictions.isEmpty() || targetRestrictions.contains(normalizeTarget(seed.target()));
	}

	private boolean validChunk(LawSemanticChunkRow row, Set<String> reasonCodes) {
		boolean valid = row != null && row.chunkId() > 0 && row.documentId() > 0 && !normalizeTarget(row.target()).isBlank();
		if (!valid) {
			reasonCodes.add("INVALID_DOCUMENT_IDENTITY");
		}
		return valid;
	}

	private String candidateKey(LawSemanticChunkRow row) {
		return normalizeTarget(row.target()) + ":" + row.chunkId();
	}

	private String documentKey(LawSemanticChunkRow row) {
		return normalizeTarget(row.target()) + ":" + row.documentId();
	}

	private String documentKey(DocumentIdentityCandidate candidate) {
		return normalizeTarget(candidate.target()) + ":" + candidate.documentId();
	}

	private String documentKey(DocumentExpansionSeed seed) {
		return normalizeTarget(seed.target()) + ":" + seed.documentId();
	}

	private String normalizeProvision(String value) {
		String beforeHeading = nullToEmpty(value);
		int headingStart = beforeHeading.indexOf('(');
		if (headingStart >= 0) {
			beforeHeading = beforeHeading.substring(0, headingStart);
		}
		return KoreanQueryNormalizer.normalizeForMatch(beforeHeading);
	}

	private String normalizeTarget(String target) {
		return nullToEmpty(target).trim().toLowerCase(Locale.ROOT);
	}

	private List<String> normalizedTerms(List<String> terms) {
		return safeList(terms).stream()
			.map(KoreanQueryNormalizer::normalizeForMatch)
			.filter(term -> !term.isBlank())
			.distinct()
			.toList();
	}

	private Set<String> normalizedTargets(List<String> targets) {
		return safeList(targets).stream().map(this::normalizeTarget).filter(target -> !target.isBlank())
			.collect(Collectors.toUnmodifiableSet());
	}

	private Set<String> safeExistingKeys(Set<String> existingKeys) {
		return existingKeys == null ? Set.of() : existingKeys.stream()
			.filter(key -> key != null && !key.isBlank())
			.collect(Collectors.toUnmodifiableSet());
	}

	private <T> List<T> safeList(List<T> values) {
		return values == null ? List.of() : values;
	}

	private String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private DocumentSelection documentSelection(List<DocumentIdentityCandidate> documents, Status status, Set<String> reasonCodes) {
		return new DocumentSelection(documents, status, List.copyOf(reasonCodes));
	}

	private DocumentSelection documentSelection(List<DocumentIdentityCandidate> documents, Status status, List<String> reasonCodes) {
		return new DocumentSelection(documents, status, reasonCodes);
	}

	private Result result(List<LawSemanticChunkRow> chunks, List<Hit> hits, Status status, Set<String> reasonCodes) {
		return new Result(chunks, hits, status, List.copyOf(reasonCodes));
	}

	private Result result(List<LawSemanticChunkRow> chunks, List<Hit> hits, Status status, List<String> reasonCodes) {
		return new Result(chunks, hits, status, reasonCodes);
	}

	public record Policy(boolean enabled, boolean authoritative, int maxDocuments, int maxChunksPerDocument, int maxTotalChunks) {
	}

	public record DocumentSelection(List<DocumentIdentityCandidate> documents, Status status, List<String> reasonCodes) {
		public DocumentSelection {
			documents = List.copyOf(documents == null ? List.of() : documents);
			reasonCodes = List.copyOf(reasonCodes == null ? List.of() : reasonCodes);
		}
	}

	public record Hit(
		String candidateKey,
		int sourceRank,
		String anchorType,
		boolean overlapsExistingSource,
		String reason,
		Integer seedTermCount,
		Double seedBm25Score,
		Integer seedBm25Rank
	) {
		public Hit(String candidateKey, int sourceRank, String anchorType, boolean overlapsExistingSource, String reason) {
			this(candidateKey, sourceRank, anchorType, overlapsExistingSource, reason, null, null, null);
		}
	}

	public record Result(List<LawSemanticChunkRow> chunks, List<Hit> hits, Status status, List<String> reasonCodes) {
		public Result {
			chunks = List.copyOf(chunks == null ? List.of() : chunks);
			hits = List.copyOf(hits == null ? List.of() : hits);
			reasonCodes = List.copyOf(reasonCodes == null ? List.of() : reasonCodes);
		}
	}

	public enum Status {
		DISABLED, NO_STRONG_ANCHOR, DOCUMENT_NOT_FOUND,
		DOCUMENT_MATCH_AMBIGUOUS, APPLIED, DB_FALLBACK_BASELINE,
		INVALID_BOUNDS, FALLBACK_BASELINE, BM25_TITLE_APPLIED,
		BM25_TITLE_NO_MATCH, BM25_TITLE_AMBIGUOUS,
		BM25_TITLE_INVALID_INPUT, BM25_TITLE_DB_FALLBACK
	}

	private record ScoredDocument(
		DocumentIdentityCandidate candidate,
		boolean exactTitle,
		boolean allTitleTerms,
		boolean provisionMatch
	) {
		private boolean sameEligibility(ScoredDocument other) {
			return exactTitle == other.exactTitle
				&& allTitleTerms == other.allTitleTerms
				&& provisionMatch == other.provisionMatch;
		}
	}

	private record ScoredChunk(
		LawSemanticChunkRow row,
		boolean provisionMatch,
		boolean headingMatch,
		int evidenceCount,
		String reason
	) {
	}
}
