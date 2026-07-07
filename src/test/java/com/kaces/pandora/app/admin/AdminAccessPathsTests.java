package com.kaces.pandora.app.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AdminAccessPathsTests {
	@Test
	void includesBatchAutomationMutationPaths() {
		assertThat(Arrays.asList(AdminAccessPaths.PATTERNS))
			.contains(
				"/api/rag-collection/**",
				"/api/rag-documents/import-folder",
				"/api/law-data/semantic/batches/**"
			);
	}
}
