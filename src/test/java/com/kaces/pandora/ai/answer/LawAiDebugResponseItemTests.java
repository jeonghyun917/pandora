package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class LawAiDebugResponseItemTests {

	@Test
	void debugItemExposesStructuralSectionFieldsForRetrievalMeasurement() {
		assertThat(Arrays.stream(LawAiDebugResponse.Item.class.getRecordComponents())
			.map(component -> component.getName())
			.toList())
			.contains("parentSectionTitle", "sectionType");
	}

	@Test
	void matchedChildTextMeasurementFlagDefaultsToFalse() {
		LawAiDebugRequest request = new LawAiDebugRequest(null, null, null, null, null, null);

		assertThat(request.includeMatchedChildTextEnabled()).isFalse();
	}
}
