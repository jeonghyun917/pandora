package com.kaces.pandora.semantic.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LawAiPropertiesTests {

	@Test
	void disablesBatchSchedulerByDefault() {
		LawAiProperties properties = new LawAiProperties(null, null, null, null);

		assertThat(properties.batch().schedulerEnabled()).isFalse();
		assertThat(properties.batch().autoEnabled()).isFalse();
	}
}
