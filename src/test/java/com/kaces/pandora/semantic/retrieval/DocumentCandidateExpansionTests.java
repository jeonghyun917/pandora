package com.kaces.pandora.semantic.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.common.text.DocumentSearchAnchor;
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
			document(1, "law", "문서 1", false), document(2, "law", "문서 2", false),
			document(3, "law", "문서 3", false), document(4, "law", "문서 4", false)
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
