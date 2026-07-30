package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentDiscoveryAnswerComposerTests {

	@Test
	void composesATypePrioritizedDeduplicatedMetadataList() {
		String answer = DocumentDiscoveryAnswerComposer.compose(
			"CCTV 관련 법령",
			List.of(
				ground(1, 101, 1001, "official_doc", "CCTV 설치 운영 가이드", 0.98),
				ground(2, 102, 1002, "law", "개인정보 보호법", 0.72),
				ground(3, 103, 1002, "law", "개인정보 보호법", 0.70),
				ground(4, 104, 1003, "admrul", "표준 개인정보 보호지침", 0.68)
			)
		);

		assertThat(answer).isEqualTo("""
			관련 문서 검색 결과입니다.

			1. [법령] 개인정보 보호법 — 개인정보보호위원회 [근거 1]
			2. [행정규칙] 표준 개인정보 보호지침 — 개인정보보호위원회 [근거 2]
			3. [공식 문서] CCTV 설치 운영 가이드 — 개인정보보호위원회 [근거 3]

			확인할 주제를 입력해 주세요: 설치 목적 · 촬영범위 · 촬영시간 · 보관기간 · 안내판""");
		assertThat(answer).doesNotContain("30일 이내");
	}

	@Test
	void composesOnlySelectedGroundMetadataAndNormalizesLineBreaks() {
		String answer = DocumentDiscoveryAnswerComposer.compose(
			"보조금 관련 자료",
			List.of(new LawAiAnswerGround(
				1,
				201,
				2001,
				"official_doc",
				"보조금 집행\n안내서",
				"행정안전부\n보조금과",
				"공식 문서",
				null,
				null,
				"page 1",
				"본문",
				1,
				"본문 근거",
				null,
				null,
				0.9
			))
		);

		assertThat(answer)
			.contains("[공식 문서] 보조금 집행 안내서 — 행정안전부 보조금과 [근거 1]")
			.doesNotContain("\n안내서", "\n보조금과");
	}

	@Test
	void doesNotComposeForSubstantiveQuestionsOrEmptyGrounds() {
		assertThat(DocumentDiscoveryAnswerComposer.compose(
			"CCTV 관련 법령상 설치 조건은?",
			List.of(ground(1, 301, 3001, "law", "개인정보 보호법", 0.9))
		)).isNull();
		assertThat(DocumentDiscoveryAnswerComposer.compose(
			"CCTV 관련 법령",
			List.of()
		)).isNull();
	}

	private LawAiAnswerGround ground(
		int number,
		long chunkId,
		long documentId,
		String target,
		String title,
		double score
	) {
		return new LawAiAnswerGround(
			number,
			chunkId,
			documentId,
			target,
			title,
			"개인정보보호위원회",
			"테스트",
			"20260101",
			"CURRENT",
			"제1조",
			"목적",
			null,
			"CCTV 관련 근거",
			null,
			null,
			score
		);
	}
}
