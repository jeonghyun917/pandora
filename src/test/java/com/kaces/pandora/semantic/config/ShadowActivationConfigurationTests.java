package com.kaces.pandora.semantic.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ShadowActivationConfigurationTests {

	@Test
	void enablesBothShadowsWithoutGrantingAuthoritativeControl() throws Exception {
		Properties properties = new Properties();
		try (InputStream input = getClass().getResourceAsStream("/application.properties")) {
			assertThat(input).isNotNull();
			properties.load(input);
		}

		assertThat(properties.getProperty("law-ai.retrieval.rrf-shadow-enabled")).isEqualTo("true");
		assertThat(properties.getProperty("law-ai.retrieval.rrf-authoritative")).isEqualTo("false");
		assertThat(properties.getProperty("law-ai.retrieval.coverage-aware.enabled")).isEqualTo("false");
		assertThat(properties.getProperty("law-ai.verification.semantic-shadow-enabled")).isEqualTo("true");
		assertThat(properties.getProperty("law-ai.verification.semantic-authoritative")).isEqualTo("false");
	}
}
