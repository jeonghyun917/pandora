package com.kaces.pandora.semantic.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class LawAiPropertiesTests {

	@Test
	void disablesBatchSchedulerByDefault() {
		LawAiProperties properties = new LawAiProperties(null, null, null, null);

		assertThat(properties.batch().schedulerEnabled()).isFalse();
		assertThat(properties.batch().autoEnabled()).isFalse();
		assertThat(Arrays.stream(LawAiProperties.Qdrant.class.getRecordComponents())
			.map(java.lang.reflect.RecordComponent::getName))
			.doesNotContain("indexRevision");
	}
}
