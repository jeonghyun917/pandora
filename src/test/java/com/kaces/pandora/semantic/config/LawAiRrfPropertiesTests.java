package com.kaces.pandora.semantic.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Properties;

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

	@Test
	void configuresTheVerifiedBaselineShadowLexicalWeight() throws Exception {
		Properties properties = new Properties();
		try (InputStream input = getClass().getResourceAsStream("/application.properties")) {
			properties.load(input);
		}

		assertThat(properties.getProperty("law-ai.retrieval.rrf-vector-weight")).isEqualTo("1.0");
		assertThat(properties.getProperty("law-ai.retrieval.rrf-lexical-weight")).isEqualTo("1.0");
	}
}
