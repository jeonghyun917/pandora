package com.kaces.pandora.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentSearchAnchorExtractorTests {

	@Test
	void extractsQuotedTitleProvisionAndExplicitHeading() {
		String question = "「전자정부법」 제67조의2(사전협의 대상)에 따른 사전협의 대상은?";

		DocumentSearchAnchor anchor = DocumentSearchAnchorExtractor.extract(
			question,
			QuestionIntentProfile.from(question),
			List.of("전자정부법", "사전협의", "대상"),
			List.of("사전협의", "대상")
		);

		assertThat(anchor.status()).isEqualTo(DocumentSearchAnchor.Status.ELIGIBLE);
		assertThat(anchor.anchorType()).isEqualTo(DocumentSearchAnchor.AnchorType.TITLE_WITH_PROVISION);
		assertThat(anchor.titleTerms()).containsExactly("전자정부법");
		assertThat(anchor.provisionTerms()).containsExactly("제67조의2");
		assertThat(anchor.headingTerms()).containsExactly("사전협의 대상");
		assertThat(anchor.evidenceTerms()).containsExactly("사전협의", "대상", "전자정부법");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"전자정부법",
		"전자정부법 시행령",
		"전자정부법 시행규칙",
		"정보보호 규정",
		"전자정부 지침",
		"전자정부 고시"
	})
	void extractsConfiguredDocumentTitleSuffixes(String title) {
		String question = title + "의 적용 대상은?";

		DocumentSearchAnchor anchor = DocumentSearchAnchorExtractor.extract(
			question,
			QuestionIntentProfile.from(question),
			List.of(title, "적용 대상"),
			List.of("적용 대상")
		);

		assertThat(anchor.status()).isEqualTo(DocumentSearchAnchor.Status.ELIGIBLE);
		assertThat(anchor.anchorType()).isEqualTo(DocumentSearchAnchor.AnchorType.EXPLICIT_TITLE);
		assertThat(anchor.titleTerms()).containsExactly(title.split("\\s+"));
	}

	@Test
	void splitsMultiWordExplicitTitleIntoStrictOrderedTerms() {
		String question = "인공지능 데이터 기반 행정 활성화 법은 언제부터 효력이 있어?";

		DocumentSearchAnchor anchor = DocumentSearchAnchorExtractor.extract(
			question,
			QuestionIntentProfile.from(question),
			List.of("인공지능", "데이터", "행정", "활성화", "효력"),
			List.of("효력")
		);

		assertThat(anchor.status()).isEqualTo(DocumentSearchAnchor.Status.ELIGIBLE);
		assertThat(anchor.anchorType()).isEqualTo(DocumentSearchAnchor.AnchorType.EXPLICIT_TITLE);
		assertThat(anchor.titleTerms()).containsExactly("인공지능", "데이터", "기반", "행정", "활성화", "법");
	}

	@Test
	void extractsDictionaryBackedStableAlias() {
		String question = "정보자원관리시스템 IRM 등록 절차는?";

		DocumentSearchAnchor anchor = DocumentSearchAnchorExtractor.extract(
			question,
			QuestionIntentProfile.from(question),
			List.of("정보자원관리시스템", "IRM", "등록", "절차"),
			List.of("등록", "절차")
		);

		assertThat(anchor.status()).isEqualTo(DocumentSearchAnchor.Status.ELIGIBLE);
		assertThat(anchor.anchorType()).isEqualTo(DocumentSearchAnchor.AnchorType.STABLE_ALIAS);
		assertThat(anchor.titleTerms()).containsExactly("정보자원관리시스템", "IRM");
		assertThat(anchor.targets()).containsExactly("official_doc", "internal_doc");
	}

	@Test
	void extractsArticleAndAppendixProvisionsInStableOrder() {
		String question = "전자정부법 제12조 제12조의2 별표 3(사전협의 대상)은?";

		DocumentSearchAnchor anchor = DocumentSearchAnchorExtractor.extract(
			question,
			QuestionIntentProfile.from(question),
			List.of("전자정부법", "사전협의", "사전 협의", "대상"),
			List.of("사전협의", "사전 협의", "대상")
		);

		assertThat(anchor.provisionTerms()).containsExactly("제12조", "제12조의2", "별표 3");
		assertThat(anchor.headingTerms()).containsExactly("사전협의 대상");
		assertThat(anchor.evidenceTerms()).containsExactly("사전협의", "대상", "전자정부법");
	}

	@Test
	void preservesProvisionEncounterOrderWhenAppendixPrecedesArticle() {
		String question = "전자정부법 별표 3 제12조의 적용 대상은?";

		DocumentSearchAnchor anchor = DocumentSearchAnchorExtractor.extract(
			question,
			QuestionIntentProfile.from(question),
			List.of("전자정부법", "대상"),
			List.of("대상")
		);

		assertThat(anchor.provisionTerms()).containsExactly("별표 3", "제12조");
	}

	@Test
	void boundsTermsAndKeepsFirstDisplaySafeValueForNormalizedDuplicates() {
		String question = "가법 나법 다법 라법 마법 바법 사법의 대상은?";
		List<String> lexicalKeywords = List.of(
			"증거 1", "증거 2", "증거 3", "증거 4", "증거 5", "증거 6", "증거 7", "증거 8", "증거 9",
			"증거 10", "증거 11", "증거 12", "증거 13", "증거 14", "증거 15", "증거 16", "증거 17", "증거 18", "증거 19"
		);

		DocumentSearchAnchor anchor = DocumentSearchAnchorExtractor.extract(
			question,
			QuestionIntentProfile.from(question),
			lexicalKeywords,
			List.of("사전협의", "사전 협의", "대상")
		);

		assertThat(anchor.titleTerms()).containsExactly("가법", "나법", "다법", "라법", "마법", "바법");
		assertThat(anchor.evidenceTerms()).hasSize(18).startsWith("사전협의", "대상", "증거 1");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"사전협의는 언제 하나요?",
		"전자정부",
		"사전협의 대상",
		"존재하지않는 약칭의 대상은?"
	})
	void failsClosedWithoutAnExplicitTitleOrDictionaryBackedStableAlias(String question) {
		DocumentSearchAnchor anchor = DocumentSearchAnchorExtractor.extract(
			question,
			QuestionIntentProfile.from(question),
			List.of("사전협의", "대상"),
			List.of("사전협의", "대상")
		);

		assertThat(anchor.status()).isEqualTo(DocumentSearchAnchor.Status.NO_STRONG_ANCHOR);
		assertThat(anchor.eligible()).isFalse();
		assertThat(anchor.anchorType()).isEqualTo(DocumentSearchAnchor.AnchorType.NONE);
		assertThat(anchor.titleTerms()).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"공공데이터는 왜 제공하나요?",
		"소프트웨어사업은 과업심의 대상인가요?"
	})
	void failsClosedForBroadDictionaryRecallAliases(String question) {
		DocumentSearchAnchor anchor = DocumentSearchAnchorExtractor.extract(
			question,
			QuestionIntentProfile.from(question),
			List.of("공공데이터", "소프트웨어사업", "대상"),
			List.of("대상")
		);

		assertThat(anchor.status()).isEqualTo(DocumentSearchAnchor.Status.NO_STRONG_ANCHOR);
		assertThat(anchor.anchorType()).isEqualTo(DocumentSearchAnchor.AnchorType.NONE);
		assertThat(anchor.titleTerms()).isEmpty();
	}

	@Test
	void failsClosedForQuotedTopicWithoutDocumentTitleIndicator() {
		String question = "「사전협의」는 언제 하나요?";

		DocumentSearchAnchor anchor = DocumentSearchAnchorExtractor.extract(
			question,
			QuestionIntentProfile.from(question),
			List.of("사전협의", "언제"),
			List.of("사전협의")
		);

		assertThat(anchor.status()).isEqualTo(DocumentSearchAnchor.Status.NO_STRONG_ANCHOR);
		assertThat(anchor.anchorType()).isEqualTo(DocumentSearchAnchor.AnchorType.NONE);
		assertThat(anchor.titleTerms()).isEmpty();
	}
}
