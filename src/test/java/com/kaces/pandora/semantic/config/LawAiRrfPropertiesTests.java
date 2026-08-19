package com.kaces.pandora.semantic.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LawAiRrfPropertiesTests {

	@Test
	void keepsShadowAndAuthorityDisabledByDefault() {
		LawAiRrfProperties properties = new LawAiRrfProperties(false, false, 0, 0, 0, 0);

		assertThat(properties.rrfShadowEnabled()).isFalse();
		assertThat(properties.rrfAuthoritative()).isFalse();
		assertThat(properties.rrfK()).isEqualTo(60);
		assertThat(properties.rrfVectorWeight()).isEqualTo(1.0);
		assertThat(properties.rrfLexicalWeight()).isEqualTo(1.0);
		assertThat(properties.rrfFusedLimit()).isEqualTo(100);
	}
}
