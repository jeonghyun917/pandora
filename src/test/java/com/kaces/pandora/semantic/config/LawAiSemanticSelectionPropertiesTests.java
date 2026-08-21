package com.kaces.pandora.semantic.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LawAiSemanticSelectionPropertiesTests {

	@Test
	void defaultsInvalidPreserveLimitWithoutEnablingAuthority() {
		LawAiSemanticSelectionProperties properties = new LawAiSemanticSelectionProperties(true, false, 0);

		assertThat(properties.shadowEnabled()).isTrue();
		assertThat(properties.authoritative()).isFalse();
		assertThat(properties.preserveLimit()).isEqualTo(4);
	}
}
