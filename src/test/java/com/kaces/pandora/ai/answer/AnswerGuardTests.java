package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnswerGuardTests {

	// 메소드 설명: AnswerGuard 처리 흐름을 수행합니다.
	private final AnswerGuard guard = new AnswerGuard();

	@Test
	// 메소드 설명: removesCitationNumbersThatAreNotInReturnedGrounds 처리 흐름을 수행합니다.
	void removesCitationNumbersThatAreNotInReturnedGrounds() {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		String answer = guard.guard(
			"첫 번째 근거는 유지합니다 [1]. 존재하지 않는 근거는 제거합니다 [9].",
			List.of(ground(1), ground(2))
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(answer).contains("[1]");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(answer).doesNotContain("[9]");
	}

	@Test
	// 메소드 설명: normalizesMixedCitationGroup 처리 흐름을 수행합니다.
	void normalizesMixedCitationGroup() {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		String answer = guard.guard(
			"여러 근거를 한 번에 적어도 유효한 번호만 남깁니다 [1, 3, 99].",
			List.of(ground(1), ground(2), ground(3))
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(answer).contains("[1, 3]");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(answer).doesNotContain("99");
	}

	@Test
	// 메소드 설명: normalizesCitationLabelsGeneratedByTheModel 처리 흐름을 수행합니다.
	void normalizesCitationLabelsGeneratedByTheModel() {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		String answer = guard.guard(
			"라벨이 붙은 근거도 정리합니다 [근거 2]. 번호 표현도 정리합니다 [3번]. 없는 근거는 제거합니다 [근거 9].",
			List.of(ground(1), ground(2), ground(3))
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(answer).contains("[2]");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(answer).contains("[3]");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(answer).doesNotContain("근거 9");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(answer).doesNotContain("[9]");
	}

	@Test
	// 메소드 설명: keepsNonCitationBracketsUntouched 처리 흐름을 수행합니다.
	void keepsNonCitationBracketsUntouched() {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		String answer = guard.guard("세부 서식은 [별표 1]을 확인해야 합니다 [1].", List.of(ground(1)));

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(answer).contains("[별표 1]");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(answer).contains("[1]");
	}

	@Test
	// 메소드 설명: appendsPrimaryCitationWhenAnswerHasNoCitation 처리 흐름을 수행합니다.
	void appendsPrimaryCitationWhenAnswerHasNoCitation() {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		String answer = guard.guard("제공된 근거 기준으로는 신청 대상에 해당할 수 있습니다.", List.of(ground(1)));

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(answer).endsWith("[1]");
	}

	@Test
	// 메소드 설명: returnsSafeMessageWhenAnswerIsBlank 처리 흐름을 수행합니다.
	void returnsSafeMessageWhenAnswerIsBlank() {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		String answer = guard.guard("   ", List.of(ground(1)));

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(answer).contains("제공된 근거만으로는 답변을 확정하기 어렵습니다");
	}

	@Test
	// 메소드 설명: softensFinalLegalJudgmentPhrases 처리 흐름을 수행합니다.
	void softensFinalLegalJudgmentPhrases() {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		String answer = guard.guard("이 답변은 법률 자문입니다. 최종 판단입니다. 문제가 없습니다 [1].", List.of(ground(1)));

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(answer).contains("법률 자문은 아니며");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(answer).contains("제공된 근거 기준의 판단입니다");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(answer).contains("제공된 근거만으로는 문제가 확인되지 않습니다");
	}

	@Test
	// 메소드 설명: replacesDashSeparatorsWithNaturalSentenceBreaks 처리 흐름을 수행합니다.
	void replacesDashSeparatorsWithNaturalSentenceBreaks() {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		String answer = guard.guard("대상으로 보지 않습니다 — 정보화사업이 아닌 경우입니다 [1].", List.of(ground(1)));

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(answer).contains("대상으로 보지 않습니다. 정보화사업이 아닌 경우입니다 [1]");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(answer).doesNotContain("—");
	}

	// 메소드 설명: ground 처리 흐름을 수행합니다.
	// 메소드 설명: ground 처리 흐름을 수행합니다.
	private LawAiAnswerGround ground(int number) {
		return new LawAiAnswerGround(
			number,
			number,
			1,
			"official_doc",
			"문서 " + number,
			"기관",
			"공식 가이드 문서",
			null,
			"page " + number,
			"p." + number,
			number,
			"근거 본문",
			null,
			null,
			0.9
		);
	}
}
