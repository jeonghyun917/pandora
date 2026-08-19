package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.common.text.QuestionIntentProfile;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DirectEvidenceSelectionPolicyTests {

	private final DirectEvidenceSelectionPolicy policy = new DirectEvidenceSelectionPolicy();

	@Test
	void preservesAnOmittedCurrentChunkOnlyWhenItsSemanticAtomDirectlyMatches() {
		LawSemanticChunkRow judged = chunk(1L, "CURRENT", "law", "계약상대자는 검사 결과를 보관한다.");
		LawSemanticChunkRow direct = chunk(2L, "CURRENT", "law", "계약상대자는 완료 후 통지해야 한다.");

		DirectEvidenceSelectionPolicy.Result result = policy.apply(
			"계약상대자는 완료 후 통지해야 하는가?",
			QuestionIntentProfile.from("계약상대자는 완료 후 통지해야 하는가?"),
			List.of(judged),
			List.of(judged, direct),
			Map.of("law:1", 2.0d, "law:2", 1.0d),
			Set.of("law"),
			10
		);

		assertThat(result.chunks()).extracting(LawSemanticChunkRow::chunkId).containsExactly(2L, 1L);
		assertThat(result.scoreByCandidateKey().get("law:2")).isGreaterThan(2.0d);
		assertThat(result.reasonByCandidateKey()).containsEntry("law:2", "DIRECT_ATOM_PRESERVED");
	}

	@Test
	void rejectsOppositeMeaningObsoleteNoiseAndForbiddenTargetsFailClosed() {
		String question = "계약상대자는 완료 후 통지해야 하는가?";
		LawSemanticChunkRow opposite = chunk(2L, "CURRENT", "law", "계약상대자는 완료 후 통지하지 않는다.");
		LawSemanticChunkRow obsolete = chunk(3L, "EXPIRED", "law", "계약상대자는 완료 후 통지해야 한다.");
		LawSemanticChunkRow noise = chunk(4L, "CURRENT", "law", "1");
		LawSemanticChunkRow forbidden = chunk(5L, "CURRENT", "rag", "계약상대자는 완료 후 통지해야 한다.");

		DirectEvidenceSelectionPolicy.Result result = policy.apply(
			question,
			QuestionIntentProfile.from(question),
			List.of(),
			List.of(opposite, obsolete, noise, forbidden),
			Map.of(),
			Set.of("law"),
			10
		);

		assertThat(result.chunks()).isEmpty();
		assertThat(result.reasonByCandidateKey())
			.containsEntry("law:2", "DIRECT_ATOM_REJECTED_CONTRADICTION")
			.containsEntry("law:3", "DIRECT_ATOM_REJECTED_OBSOLETE")
			.containsEntry("law:4", "DIRECT_ATOM_REJECTED_NOISE")
			.containsEntry("rag:5", "DIRECT_ATOM_REJECTED_TARGET");
	}

	@Test
	void leavesDocumentDiscoveryAndUnparseableQuestionsUntouched() {
		LawSemanticChunkRow candidate = chunk(2L, "CURRENT", "law", "계약상대자는 완료 후 통지해야 한다.");
		String question = "개인정보보호 관련 법령 찾아줘";

		DirectEvidenceSelectionPolicy.Result result = policy.apply(
			question,
			QuestionIntentProfile.from(question),
			List.of(),
			List.of(candidate),
			Map.of(),
			Set.of("law"),
			10
		);

		assertThat(result.changed()).isFalse();
		assertThat(result.chunks()).isEmpty();
		assertThat(result.reasonByCandidateKey()).isEmpty();
	}

	@Test
	void preservesAtMostTheConfiguredLimitInCandidateOrder() {
		String question = "계약상대자는 완료 후 통지해야 하는가?";
		List<LawSemanticChunkRow> candidates = List.of(
			chunk(2L, "CURRENT", "law", "계약상대자는 완료 후 통지해야 한다."),
			chunk(3L, "CURRENT", "law", "계약상대자는 완료 후 통지해야 한다."),
			chunk(4L, "CURRENT", "law", "계약상대자는 완료 후 통지해야 한다.")
		);

		DirectEvidenceSelectionPolicy.Result result = policy.apply(
			question,
			QuestionIntentProfile.from(question),
			List.of(),
			candidates,
			Map.of(),
			Set.of("law"),
			2
		);

		assertThat(result.chunks()).extracting(LawSemanticChunkRow::chunkId).containsExactly(2L, 3L);
		assertThat(result.reasonByCandidateKey()).containsEntry("law:4", "DIRECT_ATOM_REJECTED_LIMIT");
	}

	private LawSemanticChunkRow chunk(long id, String status, String target, String text) {
		return new LawSemanticChunkRow(
			id, 100L + id, target, "ext-" + id, "법령", "기관", "법률", "2026-01-01", status,
			"제1조", "통지", text, 1, "source", "url", (int) id, "hash-" + id,
			"제1조", "body", "PASS"
		);
	}
}
