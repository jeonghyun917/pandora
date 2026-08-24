package com.kaces.pandora.semantic.retrieval;

import com.kaces.pandora.common.text.DocumentSearchAnchor;
import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.semantic.config.LawAiDocumentExpansionProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DocumentExpansionSearchService {

	private static final Logger log = LoggerFactory.getLogger(DocumentExpansionSearchService.class);

	private final LawChunkMapper lawChunkMapper;
	private final RagDocumentMapper ragDocumentMapper;
	private final DocumentCandidateExpansion expansion;
	private final LawAiDocumentExpansionProperties properties;

	public DocumentExpansionSearchService(
		LawChunkMapper lawChunkMapper,
		RagDocumentMapper ragDocumentMapper,
		DocumentCandidateExpansion expansion,
		LawAiDocumentExpansionProperties properties
	) {
		this.lawChunkMapper = lawChunkMapper;
		this.ragDocumentMapper = ragDocumentMapper;
		this.expansion = expansion;
		this.properties = properties;
	}

	public DocumentCandidateExpansion.Result search(
		DocumentSearchAnchor anchor,
		List<String> targets,
		boolean includeFuture,
		Set<String> existingCandidateKeys
	) {
		long startedAt = System.nanoTime();
		DocumentCandidateExpansion.Policy policy = properties.policy();
		int targetCount = requestedTargets(targets).size();
		if (!properties.enabled() || !properties.validBounds() || !isStrongAnchor(anchor)) {
			return logged(
				expansion.rankChunks(anchor, null, List.of(), existingCandidateKeys, policy),
				targetCount,
				0,
				startedAt
			);
		}

		List<String> lawTargets = requestedTargets(targets).stream().filter(this::isLawTarget).toList();
		List<String> ragTargets = requestedTargets(targets).stream().filter(this::isRagTarget).toList();
		MapperRead<DocumentIdentityCandidate> lawDocuments = readLawDocuments(anchor, lawTargets, includeFuture, policy.maxDocuments() + 1);
		MapperRead<DocumentIdentityCandidate> ragDocuments = readRagDocuments(anchor, ragTargets, policy.maxDocuments() + 1);
		if (lawDocuments.failed() || ragDocuments.failed()) {
			return logged(dbFallback(), targetCount, 0, startedAt);
		}

		List<DocumentIdentityCandidate> identities = new ArrayList<>(lawDocuments.values());
		identities.addAll(ragDocuments.values());
		DocumentCandidateExpansion.DocumentSelection selection = expansion.selectDocuments(anchor, identities, policy);
		if (selection.status() != DocumentCandidateExpansion.Status.APPLIED) {
			return logged(expansion.rankChunks(anchor, selection, List.of(), existingCandidateKeys, policy), targetCount, 0, startedAt);
		}

		List<Long> lawDocumentIds = selectedDocumentIds(selection.documents(), true);
		List<Long> ragDocumentIds = selectedDocumentIds(selection.documents(), false);
		MapperRead<LawSemanticChunkRow> lawChunks = readLawChunks(anchor, lawDocumentIds, includeFuture, policy);
		MapperRead<LawSemanticChunkRow> ragChunks = readRagChunks(anchor, ragDocumentIds, policy);
		if (lawChunks.failed() || ragChunks.failed()) {
			return logged(dbFallback(), targetCount, 0, startedAt);
		}

		List<LawSemanticChunkRow> chunks = new ArrayList<>(lawChunks.values());
		chunks.addAll(ragChunks.values());
		return logged(expansion.rankChunks(anchor, selection, chunks, existingCandidateKeys, policy), targetCount, selection.documents().size(), startedAt);
	}

	public DocumentCandidateExpansion.Result searchBm25Seeded(
		DocumentSearchAnchor anchor,
		List<DocumentExpansionSeed> seeds,
		boolean includeFuture,
		Set<String> existingCandidateKeys
	) {
		long startedAt = System.nanoTime();
		DocumentCandidateExpansion.Policy policy = properties.policy();
		List<DocumentExpansionSeed> safeSeeds = seeds == null ? List.of() : List.copyOf(seeds);
		if (!properties.bm25TitleEnabled()) {
			return logged(
				new DocumentCandidateExpansion.Result(
					List.of(), List.of(), DocumentCandidateExpansion.Status.DISABLED, List.of()
				),
				distinctSeedTargets(safeSeeds), safeSeeds.size(), startedAt
			);
		}
		DocumentCandidateExpansion.Result validation = expansion.rankSeededChunks(
			anchor, safeSeeds, List.of(), existingCandidateKeys, policy
		);
		if (validation.status() == DocumentCandidateExpansion.Status.DISABLED
			|| validation.status() == DocumentCandidateExpansion.Status.BM25_TITLE_INVALID_INPUT
			|| safeSeeds.isEmpty()) {
			return logged(validation, distinctSeedTargets(safeSeeds), safeSeeds.size(), startedAt);
		}

		List<Long> lawDocumentIds = seedDocumentIds(safeSeeds, true);
		List<Long> ragDocumentIds = seedDocumentIds(safeSeeds, false);
		if (lawDocumentIds.size() + ragDocumentIds.size() != safeSeeds.size()) {
			return logged(
				new DocumentCandidateExpansion.Result(
					List.of(), List.of(), DocumentCandidateExpansion.Status.BM25_TITLE_INVALID_INPUT,
					List.of("BM25_TITLE_INVALID_TARGET")
				),
				distinctSeedTargets(safeSeeds), safeSeeds.size(), startedAt
			);
		}
		MapperRead<LawSemanticChunkRow> lawChunks = readLawChunks(anchor, lawDocumentIds, includeFuture, policy);
		MapperRead<LawSemanticChunkRow> ragChunks = readRagChunks(anchor, ragDocumentIds, policy);
		if (lawChunks.failed() || ragChunks.failed()) {
			return logged(bm25TitleDbFallback(), distinctSeedTargets(safeSeeds), safeSeeds.size(), startedAt);
		}

		List<LawSemanticChunkRow> chunks = new ArrayList<>(lawChunks.values());
		chunks.addAll(ragChunks.values());
		return logged(
			expansion.rankSeededChunks(anchor, safeSeeds, chunks, existingCandidateKeys, policy),
			distinctSeedTargets(safeSeeds), safeSeeds.size(), startedAt
		);
	}

	private MapperRead<DocumentIdentityCandidate> readLawDocuments(
		DocumentSearchAnchor anchor,
		List<String> targets,
		boolean includeFuture,
		int limit
	) {
		if (targets.isEmpty()) {
			return MapperRead.success(List.of());
		}
		try {
			return MapperRead.success(lawChunkMapper.findDocumentExpansionDocuments(
				targets, anchor.titleTerms(), anchor.provisionTerms(), includeFuture, limit
			));
		} catch (RuntimeException exception) {
			return MapperRead.failure();
		}
	}

	private MapperRead<DocumentIdentityCandidate> readRagDocuments(
		DocumentSearchAnchor anchor,
		List<String> targets,
		int limit
	) {
		if (targets.isEmpty()) {
			return MapperRead.success(List.of());
		}
		try {
			return MapperRead.success(ragDocumentMapper.findDocumentExpansionDocuments(
				targets, anchor.titleTerms(), anchor.provisionTerms(), limit
			));
		} catch (RuntimeException exception) {
			return MapperRead.failure();
		}
	}

	private MapperRead<LawSemanticChunkRow> readLawChunks(
		DocumentSearchAnchor anchor,
		List<Long> documentIds,
		boolean includeFuture,
		DocumentCandidateExpansion.Policy policy
	) {
		if (documentIds.isEmpty()) {
			return MapperRead.success(List.of());
		}
		try {
			return MapperRead.success(lawChunkMapper.findDocumentExpansionChunks(
				documentIds, anchor.provisionTerms(), anchor.headingTerms(), anchor.evidenceTerms(), includeFuture,
				policy.maxChunksPerDocument(), policy.maxTotalChunks()
			));
		} catch (RuntimeException exception) {
			return MapperRead.failure();
		}
	}

	private MapperRead<LawSemanticChunkRow> readRagChunks(
		DocumentSearchAnchor anchor,
		List<Long> documentIds,
		DocumentCandidateExpansion.Policy policy
	) {
		if (documentIds.isEmpty()) {
			return MapperRead.success(List.of());
		}
		try {
			return MapperRead.success(ragDocumentMapper.findDocumentExpansionChunks(
				documentIds, anchor.provisionTerms(), anchor.headingTerms(), anchor.evidenceTerms(),
				policy.maxChunksPerDocument(), policy.maxTotalChunks()
			));
		} catch (RuntimeException exception) {
			return MapperRead.failure();
		}
	}

	private List<Long> selectedDocumentIds(List<DocumentIdentityCandidate> documents, boolean law) {
		return documents.stream()
			.filter(document -> law ? isLawTarget(document.target()) : isRagTarget(document.target()))
			.map(DocumentIdentityCandidate::documentId)
			.toList();
	}

	private List<Long> seedDocumentIds(List<DocumentExpansionSeed> seeds, boolean law) {
		return seeds.stream()
			.filter(seed -> law ? isLawTarget(seed.target()) : isRagTarget(seed.target()))
			.map(DocumentExpansionSeed::documentId)
			.toList();
	}

	private int distinctSeedTargets(List<DocumentExpansionSeed> seeds) {
		return (int) seeds.stream().map(DocumentExpansionSeed::target).distinct().count();
	}

	private List<String> requestedTargets(List<String> targets) {
		return targets == null ? List.of() : targets.stream()
			.filter(target -> target != null && !target.isBlank())
			.map(String::trim)
			.distinct()
			.toList();
	}

	private boolean isStrongAnchor(DocumentSearchAnchor anchor) {
		return anchor != null && anchor.eligible() && anchor.titleTerms() != null
			&& anchor.titleTerms().stream().map(KoreanQueryNormalizer::normalizeForMatch).anyMatch(term -> !term.isBlank());
	}

	private boolean isLawTarget(String target) {
		return "law".equals(target) || "admrul".equals(target);
	}

	private boolean isRagTarget(String target) {
		return "official_doc".equals(target) || "internal_doc".equals(target) || "reference_doc".equals(target);
	}

	private DocumentCandidateExpansion.Result dbFallback() {
		return new DocumentCandidateExpansion.Result(
			List.of(), List.of(), DocumentCandidateExpansion.Status.DB_FALLBACK_BASELINE, List.of("DOCUMENT_EXPANSION_DB_FAILURE")
		);
	}

	private DocumentCandidateExpansion.Result bm25TitleDbFallback() {
		return new DocumentCandidateExpansion.Result(
			List.of(), List.of(), DocumentCandidateExpansion.Status.BM25_TITLE_DB_FALLBACK,
			List.of("BM25_TITLE_EXPANSION_DB_FAILURE")
		);
	}

	private DocumentCandidateExpansion.Result logged(
		DocumentCandidateExpansion.Result result,
		int targetCount,
		int documentCount,
		long startedAt
	) {
		log.info(
			"Document expansion search status={} targetCount={} documentCount={} chunkCount={} elapsedMs={}",
			result.status(), targetCount, documentCount, result.chunks().size(), elapsedMillis(startedAt)
		);
		return result;
	}

	private long elapsedMillis(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}

	private record MapperRead<T>(List<T> values, boolean failed) {
		private MapperRead {
			values = List.copyOf(values == null ? List.of() : values);
		}

		private static <T> MapperRead<T> success(List<T> values) {
			return new MapperRead<>(values, false);
		}

		private static <T> MapperRead<T> failure() {
			return new MapperRead<>(List.of(), true);
		}
	}
}
