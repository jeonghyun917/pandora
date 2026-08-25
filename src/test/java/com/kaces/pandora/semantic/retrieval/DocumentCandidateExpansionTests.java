package com.kaces.pandora.semantic.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.common.text.DocumentSearchAnchor;
import com.kaces.pandora.common.text.DocumentSearchAnchorExtractor;
import com.kaces.pandora.common.text.QuestionIntentProfile;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DocumentCandidateExpansionTests {

	private final DocumentCandidateExpansion expansion = new DocumentCandidateExpansion();
	private final DocumentCandidateExpansion.Policy policy =
		new DocumentCandidateExpansion.Policy(true, false, 3, 8, 24);

	@Test
	void ranksOnlyChunksBelongingToBoundedBm25TitleSeeds() {
		DocumentCandidateExpansion.Result result = expansion.rankSeededChunks(
			evidenceAnchor(),
			List.of(seed("law", 10), seed("official_doc", 20)),
			List.of(
				chunk(101, 10, "law", "제1조", "목적", "", 1),
				chunk(201, 20, "official_doc", "1", "절차", "", 1),
				chunk(301, 30, "law", "제2조", "기타", "", 1)
			),
			Set.of("law:101"),
			policy
		);

		assertThat(result.status()).isEqualTo(DocumentCandidateExpansion.Status.BM25_TITLE_APPLIED);
		assertThat(result.chunks()).extracting(LawSemanticChunkRow::documentId).containsOnly(10L, 20L);
		assertThat(result.hits()).allMatch(hit -> "BM25_TITLE".equals(hit.anchorType()));
		assertThat(result.hits()).extracting(DocumentCandidateExpansion.Hit::seedTermCount).containsOnly(2);
		assertThat(result.hits()).extracting(DocumentCandidateExpansion.Hit::seedBm25Score).containsOnly(9.0);
		assertThat(result.hits()).extracting(DocumentCandidateExpansion.Hit::seedBm25Rank).containsOnly(1);
	}

	@Test
	void seededRankingRejectsDuplicateOverBoundAndWrongTargetSeeds() {
		DocumentExpansionSeed lawSeed = seed("law", 10);

		assertThat(expansion.rankSeededChunks(
			evidenceAnchor(), List.of(lawSeed, lawSeed), List.of(), Set.of(), policy
		).status()).isEqualTo(DocumentCandidateExpansion.Status.BM25_TITLE_INVALID_INPUT);

		assertThat(expansion.rankSeededChunks(
			evidenceAnchor(),
			List.of(seed("law", 10), seed("law", 20), seed("law", 30), seed("law", 40)),
			List.of(), Set.of(), policy
		).status()).isEqualTo(DocumentCandidateExpansion.Status.BM25_TITLE_INVALID_INPUT);

		assertThat(expansion.rankSeededChunks(
			evidenceAnchor(), List.of(seed("admrul", 10)), List.of(), Set.of(), policy
		).status()).isEqualTo(DocumentCandidateExpansion.Status.BM25_TITLE_INVALID_INPUT);
	}

	@Test
	void seededRankingPreservesPerDocumentAndGlobalBounds() {
		List<LawSemanticChunkRow> candidates = new ArrayList<>();
		for (long documentId = 1; documentId <= 3; documentId++) {
			for (int order = 1; order <= 9; order++) {
				candidates.add(chunk(documentId * 100 + order, documentId, "law", "제" + order + "조", "", "", order));
			}
		}

		DocumentCandidateExpansion.Result result = expansion.rankSeededChunks(
			evidenceAnchor(),
			List.of(seed("law", 1), seed("law", 2), seed("law", 3)),
			candidates, Set.of(), policy
		);

		assertThat(result.status()).isEqualTo(DocumentCandidateExpansion.Status.BM25_TITLE_APPLIED);
		assertThat(result.chunks()).hasSize(24);
		assertThat(result.chunks().stream().filter(row -> row.documentId() == 1).count()).isEqualTo(8);
		assertThat(result.chunks().stream().filter(row -> row.documentId() == 2).count()).isEqualTo(8);
		assertThat(result.chunks().stream().filter(row -> row.documentId() == 3).count()).isEqualTo(8);
	}

	@Test
	void selectsExactTitleBeforeAllTermAndProvisionMatches() {
		DocumentCandidateExpansion.DocumentSelection selection = expansion.selectDocuments(
			anchor(List.of("전자정부법"), List.of("제12조"), List.of("대상"), List.of("law")),
			List.of(
				document(30, "law", "전자정부법 시행령", false),
				document(20, "law", "전자정부법 시행규칙", true),
				document(10, "law", "전자정부법", false)
			),
			policy
		);

		assertThat(selection.status()).isEqualTo(DocumentCandidateExpansion.Status.APPLIED);
		assertThat(selection.documents()).extracting(DocumentIdentityCandidate::documentId)
			.containsExactly(10L, 20L, 30L);
	}

	@Test
	void matchesFormalTitleOnlyWhenEveryExplicitTitleTokenIsPresent() {
		String question = "인공지능 데이터 기반 행정 활성화 법은 언제부터 효력이 있어?";
		DocumentSearchAnchor extracted = DocumentSearchAnchorExtractor.extract(
			question,
			QuestionIntentProfile.from(question),
			List.of("인공지능", "데이터", "행정", "활성화", "효력"),
			List.of("효력")
		);

		DocumentCandidateExpansion.DocumentSelection selection = expansion.selectDocuments(
			extracted,
			List.of(
				document(1, "law", "인공지능 및 데이터 기반 행정 활성화에 관한 법률", false),
				document(2, "law", "인공지능 기반 행정 활성화에 관한 법률", false)
			),
			policy
		);

		assertThat(selection.status()).isEqualTo(DocumentCandidateExpansion.Status.APPLIED);
		assertThat(selection.documents()).extracting(DocumentIdentityCandidate::documentId).containsExactly(1L);
	}

	@Test
	void ordersEqualDocumentMatchesByDocumentIdAndRejectsAmbiguousBoundary() {
		DocumentCandidateExpansion.DocumentSelection ordered = expansion.selectDocuments(
			anchor(List.of("보안 지침"), List.of(), List.of(), List.of("admrul")),
			List.of(
				document(20, "admrul", "보안 지침", false),
				document(10, "admrul", "보안 지침", false)
			),
			policy
		);

		DocumentCandidateExpansion.DocumentSelection ambiguous = expansion.selectDocuments(
			anchor(List.of("보안 지침"), List.of(), List.of(), List.of("admrul")),
			List.of(
				document(4, "admrul", "보안 지침", false),
				document(3, "admrul", "보안 지침", false),
				document(2, "admrul", "보안 지침", false),
				document(1, "admrul", "보안 지침", false)
			),
			policy
		);

		assertThat(ordered.documents()).extracting(DocumentIdentityCandidate::documentId).containsExactly(10L, 20L);
		assertThat(ambiguous.status()).isEqualTo(DocumentCandidateExpansion.Status.DOCUMENT_MATCH_AMBIGUOUS);
		assertThat(ambiguous.documents()).isEmpty();
		assertThat(ambiguous.reasonCodes()).containsExactly("DOCUMENT_MATCH_AMBIGUOUS");
	}

	@Test
	void isolatesTargetsAndRejectsMalformedDocumentIdentities() {
		DocumentCandidateExpansion.DocumentSelection selection = expansion.selectDocuments(
			anchor(List.of("전자정부법"), List.of(), List.of(), List.of("law")),
			List.of(
				document(-1, "law", "전자정부법", false),
				document(2, "admrul", "전자정부법", false),
				document(3, "law", "전자정부법", false)
			),
			policy
		);

		assertThat(selection.documents()).extracting(DocumentIdentityCandidate::documentId).containsExactly(3L);
		assertThat(selection.reasonCodes()).contains("INVALID_DOCUMENT_IDENTITY");
	}

	@Test
	void ranksProvisionThenHeadingThenEvidenceAndStableChunkOrder() {
		DocumentCandidateExpansion.DocumentSelection documents = selected(document(1, "law", "전자정부법", false));
		DocumentCandidateExpansion.Result result = expansion.rankChunks(
			anchor(List.of("전자정부법"), List.of("제12조"), List.of("사전협의 대상"), List.of("사전협의", "대상"), List.of("law")),
			documents,
			List.of(
				chunk(5, 1, "law", "제1조", "목적", "", 1),
				chunk(4, 1, "law", "제2조", "사전협의 대상", "", 9),
				chunk(3, 1, "law", "제3조", "기타", "사전협의 대상", 8),
				chunk(2, 1, "law", "제4조", "기타", "사전협의 대상", 7),
				chunk(1, 1, "law", "제12조", "목적", "", 99)
			),
			Set.of(),
			policy
		);

		assertThat(result.status()).isEqualTo(DocumentCandidateExpansion.Status.APPLIED);
		assertThat(result.chunks()).extracting(LawSemanticChunkRow::chunkId).containsExactly(1L, 2L, 3L, 4L, 5L);
	}

	@Test
	void enforcesDocumentPerDocumentAndGlobalBoundsWithDeterministicUniqueKeys() {
		List<DocumentIdentityCandidate> identities = List.of(
			document(1, "law", "문서 1", true), document(2, "law", "문서 2", true),
			document(3, "law", "문서 3", true), document(4, "law", "문서 4", false)
		);
		DocumentCandidateExpansion.DocumentSelection documents = new DocumentCandidateExpansion.DocumentSelection(
			identities, DocumentCandidateExpansion.Status.APPLIED, List.of()
		);
		List<LawSemanticChunkRow> chunks = new ArrayList<>();
		for (long documentId = 1; documentId <= 4; documentId++) {
			for (long chunkId = 1; chunkId <= 9; chunkId++) {
				chunks.add(chunk(documentId * 100 + chunkId, documentId, "law", "제" + chunkId + "조", "제목", "", (int) chunkId));
			}
		}

		DocumentCandidateExpansion.Result result = expansion.rankChunks(
			anchor(List.of("문서"), List.of(), List.of(), List.of(), List.of("law")), documents, chunks, Set.of(), policy
		);

		assertThat(result.chunks()).hasSize(24);
		assertThat(result.chunks()).extracting(LawSemanticChunkRow::documentId).containsOnly(1L, 2L, 3L);
		assertThat(result.hits()).extracting(DocumentCandidateExpansion.Hit::candidateKey).doesNotHaveDuplicates();
		assertThat(result.reasonCodes()).contains("DOCUMENT_LIMIT", "DOCUMENT_CHUNK_LIMIT");
	}

	@Test
	void keepsOverlapsOnceAndMarksThemWithoutUsingAnExtraCandidateKey() {
		DocumentCandidateExpansion.Result result = expansion.rankChunks(
			anchor(List.of("전자정부법"), List.of(), List.of(), List.of(), List.of("law")),
			selected(document(1, "law", "전자정부법", false)),
			List.of(
				chunk(101, 1, "law", "제1조", "목적", "", 1),
				chunk(101, 1, "law", "제1조", "목적", "", 2),
				chunk(102, 1, "law", "제2조", "범위", "", 3)
			),
			Set.of("law:101"),
			policy
		);

		assertThat(result.chunks()).extracting(LawSemanticChunkRow::chunkId).containsExactly(101L, 102L);
		assertThat(result.hits()).extracting(DocumentCandidateExpansion.Hit::candidateKey).containsExactly("law:101", "law:102");
		assertThat(result.hits().get(0).overlapsExistingSource()).isTrue();
		assertThat(result.reasonCodes()).contains("DOCUMENT_DUPLICATE_OVERLAP");
	}

	@Test
	void fallsBackWhenPolicyExceedsHardBounds() {
		DocumentCandidateExpansion.Policy invalid = new DocumentCandidateExpansion.Policy(true, false, 4, 8, 24);
		DocumentCandidateExpansion.DocumentSelection documents = selected(document(1, "law", "전자정부법", false));

		assertThat(expansion.selectDocuments(anchor(List.of("전자정부법"), List.of(), List.of(), List.of("law")), documents.documents(), invalid).status())
			.isEqualTo(DocumentCandidateExpansion.Status.INVALID_BOUNDS);
		assertThat(expansion.rankChunks(
			anchor(List.of("전자정부법"), List.of(), List.of(), List.of("law")), documents,
			List.of(chunk(101, 1, "law", "제1조", "목적", "", 1)), Set.of(), invalid
		).status()).isEqualTo(DocumentCandidateExpansion.Status.INVALID_BOUNDS);
	}

	@Test
	void rejectsCallerSuppliedAppliedSelectionThatDoesNotMatchTheAnchor() {
		DocumentSearchAnchor anchor = anchor(List.of("전자정부법"), List.of(), List.of(), List.of("law"));
		DocumentCandidateExpansion.DocumentSelection forgedSelection = selected(
			document(9, "law", "전혀 관계없는 지침", false)
		);

		DocumentCandidateExpansion.Result result = expansion.rankChunks(
			anchor,
			forgedSelection,
			List.of(chunk(901, 9, "law", "제1조", "목적", "", 1)),
			Set.of(),
			policy
		);

		assertThat(result.status()).isEqualTo(DocumentCandidateExpansion.Status.DOCUMENT_NOT_FOUND);
		assertThat(result.chunks()).isEmpty();
	}

	@Test
	void deduplicatesRepeatedDocumentIdentityBeforeAmbiguityAndLimits() {
		DocumentCandidateExpansion.DocumentSelection selection = expansion.selectDocuments(
			anchor(List.of("전자정부법"), List.of(), List.of(), List.of("law")),
			List.of(
				document(1, "law", "전자정부법", false),
				document(1, "law", "전자정부법", true),
				document(2, "law", "전자정부법", false),
				document(3, "law", "전자정부법", false)
			),
			policy
		);

		assertThat(selection.status()).isEqualTo(DocumentCandidateExpansion.Status.APPLIED);
		assertThat(selection.documents()).extracting(DocumentIdentityCandidate::documentId).containsExactly(1L, 2L, 3L);
		assertThat(selection.documents().get(0).provisionAnchorMatch()).isTrue();
	}

	private DocumentCandidateExpansion.DocumentSelection selected(DocumentIdentityCandidate... documents) {
		return new DocumentCandidateExpansion.DocumentSelection(List.of(documents), DocumentCandidateExpansion.Status.APPLIED, List.of());
	}

	private DocumentSearchAnchor anchor(
		List<String> titles,
		List<String> provisions,
		List<String> headings,
		List<String> targets
	) {
		return anchor(titles, provisions, headings, headings, targets);
	}

	private DocumentSearchAnchor evidenceAnchor() {
		return new DocumentSearchAnchor(
			List.of(), List.of("제1조"), List.of("목적"), List.of("전자정부", "절차"),
			List.of("law", "official_doc"), DocumentSearchAnchor.AnchorType.NONE,
			DocumentSearchAnchor.Status.NO_STRONG_ANCHOR
		);
	}

	private DocumentExpansionSeed seed(String target, long documentId) {
		return new DocumentExpansionSeed(
			target, documentId, "정보화사업 사전협의 지침", List.of("정보화사업", "사전협의"),
			9.0, 1, "BM25_TITLE", "BM25_TITLE_SEED"
		);
	}

	private DocumentSearchAnchor anchor(
		List<String> titles,
		List<String> provisions,
		List<String> headings,
		List<String> evidence,
		List<String> targets
	) {
		return new DocumentSearchAnchor(
			titles, provisions, headings, evidence, targets,
			DocumentSearchAnchor.AnchorType.TITLE_WITH_PROVISION,
			DocumentSearchAnchor.Status.ELIGIBLE
		);
	}

	private DocumentIdentityCandidate document(long id, String target, String title, boolean provisionMatch) {
		return new DocumentIdentityCandidate(id, target, title, title.replace(" ", ""), 1, false, provisionMatch);
	}

	private LawSemanticChunkRow chunk(
		long chunkId, long documentId, String target, String chunkNo, String chunkTitle, String parentTitle, int sortOrder
	) {
		return new LawSemanticChunkRow(
			chunkId, documentId, target, String.valueOf(documentId), "전자정부법", "", "", "", "CURRENT",
			chunkNo, chunkTitle, "", null, null, null, sortOrder, "hash-" + chunkId, parentTitle, "provision"
		);
	}
}
