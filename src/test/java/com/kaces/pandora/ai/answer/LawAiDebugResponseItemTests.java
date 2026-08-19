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
			.contains(
				"parentSectionTitle",
				"sectionType",
				"vectorRank",
				"lexicalRank",
				"fusedRank",
				"bm25Score",
				"rrfScore",
				"matchedAuditGroupIndexes",
				"matchedAuditAliases"
			);
	}
}
