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
	void omittedApplicationRoleQuestionRequiresBothDutiesUnderTheSameTrigger() {
		String question = "정보화사업 사전협의 신청 누락 발견 이후 통보 주체와 즉시 신청 주체는 누구인가요?";
		var complete = chunk(21L, "CURRENT", "admrul",
			"정보화사업의 사전협의 신청 누락이 있는 경우에는 협의기관의 장은 해당기관의 장에게 이를 통보하고, 해당기관의 장은 즉시 신청하여야 한다.");
		var reversed = chunk(22L, "CURRENT", "admrul",
			"정보화사업의 사전협의 신청 누락이 있는 경우에는 해당기관의 장은 협의기관의 장에게 이를 통보하고, 협의기관의 장은 즉시 신청하여야 한다.");
		var unconditioned = chunk(23L, "CURRENT", "admrul",
			"협의기관의 장은 해당기관의 장에게 이를 통보하고, 해당기관의 장은 즉시 신청하여야 한다.");
		var result = policy.apply(question, QuestionIntentProfile.from(question), List.of(),
			List.of(reversed, unconditioned, complete), Map.of(), Set.of("admrul"), 10);
		assertThat(result.chunks()).extracting(LawSemanticChunkRow::chunkId).containsExactly(21L);
	}

	@Test
	void neverReintroducesNonSearchableQualityAsDirectEvidence() {
		String question = "계약상대자는 완료 후 통지해야 하는가?";
		for (String quality : List.of("CONTEXT_ONLY", "REJECT")) {
			var excluded = new LawSemanticChunkRow(
				2L, 102L, "law", "ext-2", "법령", "기관", "법률", "2026-01-01", "CURRENT",
				"제1조", "통지", "계약상대자는 완료 후 통지해야 한다.", 1, "source", "url", 2, "hash-2",
				"제1조", "body", quality);
			for (var selected : List.of(List.<LawSemanticChunkRow>of(), List.of(excluded))) {
				var result = policy.apply(question, QuestionIntentProfile.from(question), selected,
					List.of(excluded), Map.of(), Set.of("law"), 10);
				assertThat(result.chunks()).as("quality=%s initiallySelected=%s", quality, selected.size()).isEmpty();
			}
		}
	}

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

	@Test
	void bindsOpenDutyToOwnedTopicAndExplicitActor() {
		var owned = chunk(11L, "CURRENT", "law", "시설관리자는 정기 장비 점검 업무를 수행하여야 한다.");
		var wrongActor = chunk(12L, "CURRENT", "law", "감독기관은 정기 장비 점검 업무를 수행하여야 한다.");
		var unrelatedDuty = chunk(13L, "CURRENT", "law", "시설관리자는 정기 장비 점검을 위하여 연락처를 등록하여야 한다.");
		var permission = chunk(14L, "CURRENT", "law", "시설관리자는 정기 장비 점검 업무를 수행할 수 있다.");
		var split = chunk(15L, "CURRENT", "law", "정기 장비 점검 업무\n시설관리자는 연락처를 등록하여야 한다.");
		var incidental = chunk(16L, "CURRENT", "law", "시설관리자는 정기 장비 점검 업무를 안내하고 연락처를 등록하여야 한다.");
		var candidates = List.of(wrongActor, unrelatedDuty, permission, split, incidental, owned);
		for (String question : List.of("정기 장비 점검은 뭘 해야해?", "시설관리자는 정기 장비 점검에 관해 무엇을 해야 하나요?")) {
			var result = policy.apply(question, QuestionIntentProfile.from(question), List.of(), candidates, Map.of(), Set.of("law"), 10);
			assertThat(result.chunks()).as(question).contains(owned).doesNotContain(unrelatedDuty, permission, split, incidental);
			if (question.startsWith("시설관리자는")) assertThat(result.chunks()).doesNotContain(wrongActor);
		}
	}

	private LawSemanticChunkRow chunk(long id, String status, String target, String text) {
		return new LawSemanticChunkRow(
			id, 100L + id, target, "ext-" + id, "법령", "기관", "법률", "2026-01-01", status,
			"제1조", "통지", text, 1, "source", "url", (int) id, "hash-" + id,
			"제1조", "body", "PASS"
		);
	}
}
