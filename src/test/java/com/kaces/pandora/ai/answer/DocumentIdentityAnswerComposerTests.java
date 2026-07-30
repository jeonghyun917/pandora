package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentIdentityAnswerComposerTests {

	@Test
	void composesOneVerifiedTitleSentenceForADocumentLookup() {
		String answer = DocumentIdentityAnswerComposer.compose(
			"공공소프트웨어사업 과업심의의 가이드 문서 찾아줘",
			List.of(ground("공공소프트웨어사업 과업심의 가이드(2022. 12.)"))
		);

		assertThat(answer)
			.isEqualTo("찾으시는 문서는 “공공소프트웨어사업 과업심의 가이드(2022. 12.)”입니다.");
	}

	@Test
	void doesNotComposeForContentSearchOrAnUnmatchedTitle() {
		assertThat(DocumentIdentityAnswerComposer.compose(
			"근로기준법 문서에서 연차휴가의 근거 조항을 찾아줘",
			List.of(ground("근로기준법"))
		)).isNull();
		assertThat(DocumentIdentityAnswerComposer.compose(
			"공공소프트웨어사업 과업심의의 가이드 문서 찾아줘",
			List.of(ground("개인정보 처리 가이드"))
		)).isNull();
	}

	private LawAiAnswerGround ground(String title) {
		return new LawAiAnswerGround(
			1,
			1,
			1,
			"official_doc",
			title,
			"기관",
			"공식 가이드 문서",
			null,
			null,
			"page 1",
			"본문",
			1,
			"본문 근거",
			null,
			null,
			0.9
		);
	}
}
