package com.kaces.pandora.semantic.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LawAiLexicalPropertiesTests {

	@Test
	void exposesDocumentedLexicalBm25Defaults() {
		LawAiLexicalProperties lexical = new LawAiLexicalProperties(0, -1, 0, 0, 0, 0, 0, 0);

		assertThat(lexical.k1()).isEqualTo(1.2);
		assertThat(lexical.b()).isEqualTo(0.75);
		assertThat(lexical.documentTitleWeight()).isEqualTo(8);
		assertThat(lexical.parentTitleWeight()).isEqualTo(6);
		assertThat(lexical.chunkTitleWeight()).isEqualTo(7);
		assertThat(lexical.bodyWeight()).isEqualTo(1);
		assertThat(lexical.maxQueryTerms()).isEqualTo(24);
		assertThat(lexical.maxResultLimit()).isEqualTo(100);
	}
}
