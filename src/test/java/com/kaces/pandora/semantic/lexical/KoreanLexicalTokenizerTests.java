package com.kaces.pandora.semantic.lexical;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class KoreanLexicalTokenizerTests {

	private final KoreanLexicalTokenizer tokenizer = new KoreanLexicalTokenizer();

	@Test
	void preservesKoreanLegalNamesArticlesAndTerms() {
		assertThat(tokenizer.tokenize("국가계약법 시행령 제55조 검사·완료 통지"))
			.containsEntry("국가계약법", 1)
			.containsEntry("시행령", 1)
			.containsEntry("제55조", 1)
			.containsEntry("검사", 1)
			.containsEntry("완료", 1)
			.containsEntry("통지", 1);
	}

	@Test
	void normalizesUnicodeAndCountsRepeatedTerms() {
		assertThat(tokenizer.tokenize("제５５조 ＡＩ 검사 검사"))
			.containsEntry("제55조", 1)
			.containsEntry("ai", 1)
			.containsEntry("검사", 2);
	}

	@Test
	void dropsWeakQuestionWordsButKeepsNumericAndDeadlineTerms() {
		Map<String, Integer> tokens = tokenizer.tokenize(
			"어떻게 알려줘 계약금액 1,000만원 지급기한 30일 이내"
		);

		assertThat(tokens)
			.doesNotContainKeys("어떻게", "알려줘")
			.containsEntry("계약금액", 1)
			.containsEntry("1000만원", 1)
			.containsEntry("지급기한", 1)
			.containsEntry("30일", 1)
			.containsEntry("이내", 1);
	}

	@Test
	void leavesConfiguredSynonymsForQueryExpansionInsteadOfCorpusDuplication() {
		assertThat(tokenizer.tokenize("개인정보"))
			.containsOnly(Map.entry("개인정보", 1));
		assertThat(tokenizer.version()).isEqualTo("korean-lexical-v1");
	}
}
