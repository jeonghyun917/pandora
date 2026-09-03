package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;

class ProcedureEvidenceCompletenessPolicyTests {

	private final ProcedureEvidenceCompletenessPolicy policy = new ProcedureEvidenceCompletenessPolicy();

	@Test
	void preservesOneCompleteProcedureGroundFromExistingCandidates() {
		LawSemanticChunkRow partial = chunk(
			101L,
			"정보화사업 보안성 검토 안내서",
			"요청기관은 보안성 검토를 요청하고 검토기관은 검토를 수행한다."
		);
		LawSemanticChunkRow complete = chunk(
			102L,
			"정보화사업 보안성 검토 안내서",
			"요청기관이 보안성 검토를 요청하면 검토기관이 검토를 수행한 뒤 요청기관에 검토 결과를 통보한다."
		);
		LawSemanticChunkRow looseWordMatch = chunk(
			103L,
			"정보화사업 보안성 검토 안내서",
			"검토 요청 후 조치 결과를 반영한다. 관련 일반 사항은 담당자에게 별도로 통보한다."
		);

		ProcedureEvidenceCompletenessPolicy.Result result = policy.apply(
			"보안성검토 절차는 어떻게 돼?",
			List.of(partial),
			List.of(partial, looseWordMatch, complete),
			Map.of("official_doc:101", 8.0, "official_doc:102", 3.0, "official_doc:103", 4.0),
			10
		);

		assertThat(result.changed()).isTrue();
		assertThat(result.chunks()).extracting(LawSemanticChunkRow::chunkId).containsExactly(102L, 101L);
		assertThat(result.scoreByCandidateKey().get("official_doc:102"))
			.isGreaterThan(result.scoreByCandidateKey().get("official_doc:101"));
	}

	@Test
	void doesNotPreserveIncompleteOrWrongDomainProcedureGrounds() {
		LawSemanticChunkRow selected = chunk(
			201L,
			"정보화사업 보안성 검토 안내서",
			"요청기관은 보안성 검토를 요청하고 검토기관은 검토한다."
		);
		LawSemanticChunkRow incomplete = chunk(
			202L,
			"정보화사업 보안성 검토 안내서",
			"요청기관은 검토를 요청하고 검토기관은 검토를 수행한다."
		);
		LawSemanticChunkRow wrongDomain = chunk(
			203L,
			"개인정보 처리 절차 안내서",
			"처리를 신청하면 담당자가 검토한 뒤 신청인에게 결과를 통보한다."
		);

		ProcedureEvidenceCompletenessPolicy.Result result = policy.apply(
			"보안성검토 절차는 어떻게 돼?",
			List.of(selected),
			List.of(selected, incomplete, wrongDomain),
			Map.of(),
			10
		);

		assertThat(result.changed()).isFalse();
		assertThat(result.chunks()).containsExactly(selected);
	}

	@Test
	void isNoOpForNonProcedureQuestionsAndAlreadyCompleteSelection() {
		LawSemanticChunkRow complete = chunk(
			301L,
			"정보화사업 보안성 검토 안내서",
			"요청기관이 검토를 요청하면 검토기관이 검토를 수행한 뒤 결과를 통보한다."
		);

		ProcedureEvidenceCompletenessPolicy.Result nonProcedure = policy.apply(
			"보안성검토 대상 시스템은?",
			List.of(),
			List.of(complete),
			Map.of(),
			10
		);
		ProcedureEvidenceCompletenessPolicy.Result alreadyComplete = policy.apply(
			"보안성검토 절차는 어떻게 돼?",
			List.of(complete),
			List.of(complete),
			Map.of(),
			10
		);

		assertThat(nonProcedure.changed()).isFalse();
		assertThat(nonProcedure.chunks()).isEmpty();
		assertThat(alreadyComplete.changed()).isFalse();
		assertThat(alreadyComplete.chunks()).containsExactly(complete);
	}

	@Test
	void promotesCompleteProcedureGroundWhenItWouldFallOutsideAnswerLimit() {
		List<LawSemanticChunkRow> selected = new java.util.ArrayList<>();
		for (long chunkId = 401L; chunkId <= 408L; chunkId++) {
			selected.add(chunk(
				chunkId,
				"정보화사업 보안성 검토 안내서",
				"보안성 검토 요청과 관련된 일부 안내다."
			));
		}
		LawSemanticChunkRow complete = chunk(
			409L,
			"정보화사업 보안성 검토 안내서",
			"보안성 검토를 요청하고 검토기관이 총괄 검토한 뒤 검토 결과를 통보한다."
		);
		selected.add(complete);

		ProcedureEvidenceCompletenessPolicy.Result result = policy.apply(
			"보안성검토 절차는 어떻게 돼?",
			selected,
			selected,
			Map.of(),
			8
		);

		assertThat(result.changed()).isTrue();
		assertThat(result.chunks()).hasSize(8);
		assertThat(result.chunks()).extracting(LawSemanticChunkRow::chunkId)
			.containsExactly(409L, 401L, 402L, 403L, 404L, 405L, 406L, 407L);
	}

	@Test
	void doesNotTreatOutOfOrderProcedureTermsAsACompleteProcedure() {
		LawSemanticChunkRow misleading = chunk(
			501L,
			"국가정보보안기본지침 제출 문서",
			"관련 문서를 제출하여야 한다. 검토결과를 통보받은 경우 보완하고, "
				+ "보안성 검토 기관의 장은 반영 여부를 확인할 수 있다."
		);
		LawSemanticChunkRow complete = chunk(
			502L,
			"정보화사업 보안성 검토 안내서",
			"보안성 검토를 신청하고 검토기관이 총괄 검토한 뒤 검토결과를 통보한다."
		);

		ProcedureEvidenceCompletenessPolicy.Result result = policy.apply(
			"보안성검토 절차는 어떻게 돼?",
			List.of(misleading),
			List.of(misleading, complete),
			Map.of(),
			8
		);

		assertThat(result.changed()).isTrue();
		assertThat(result.chunks()).extracting(LawSemanticChunkRow::chunkId)
			.containsExactly(502L, 501L);
	}

	private LawSemanticChunkRow chunk(long chunkId, String title, String text) {
		return new LawSemanticChunkRow(
			chunkId,
			8L,
			"official_doc",
			String.valueOf(chunkId),
			title,
			"",
			"",
			"20260101",
			"CURRENT",
			"p.1",
			"절차",
			text,
			1,
			"",
			"",
			1,
			"hash" + chunkId,
			"절차",
			"procedure"
		);
	}
}
