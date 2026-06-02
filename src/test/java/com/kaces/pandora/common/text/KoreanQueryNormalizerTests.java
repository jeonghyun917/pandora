package com.kaces.pandora.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KoreanQueryNormalizerTests {

	@Test
	void stripsColloquialDefinitionSuffixesFromCoreTerms() {
		assertThat(KoreanQueryNormalizer.normalizeQueryTerm("인공지능위원회라는건")).isEqualTo("인공지능위원회");
		assertThat(KoreanQueryNormalizer.normalizeQueryTerm("인공지능위원회라는건 뭐야?")).isEqualTo("인공지능위원회");
		assertThat(KoreanQueryNormalizer.normalizeQueryTerm("보안성검토라는게뭐야")).isEqualTo("보안성검토");
	}

	@Test
	void expandsCommitteeLikeSearchKeywords() {
		assertThat(KoreanQueryNormalizer.expandSearchKeywords("인공지능위원회"))
			.contains("인공지능위원회", "국가인공지능전략위원회", "인공지능전략위원회");
	}

	@Test
	void expandsKnownCompoundTermsWithSpacingVariants() {
		assertThat(KoreanQueryNormalizer.expandSearchKeywords("보안성검토라는게 뭐야?"))
			.contains("보안성검토", "보안성 검토", "정보화사업 보안성 검토");
		assertThat(KoreanQueryNormalizer.isWeakQuestionTerm("가능해")).isTrue();
	}
}
