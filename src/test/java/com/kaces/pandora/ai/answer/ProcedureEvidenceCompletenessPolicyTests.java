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
			"요청기관이 보안성 검토를 요청하면 검토기관이 검토를 수행한 뒤 요청기관에 결과를 통보한다."
		);

		ProcedureEvidenceCompletenessPolicy.Result result = policy.apply(
			"보안성검토 절차는 어떻게 돼?",
			List.of(partial),
			List.of(partial, complete),
			Map.of("official_doc:101", 8.0, "official_doc:102", 3.0),
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
