package com.kaces.pandora.semantic.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaces.pandora.semantic.retrieval.DocumentCandidateExpansion;
import com.kaces.pandora.semantic.retrieval.Bm25TitleDocumentSeedSelector;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class LawAiDocumentExpansionPropertiesTests {

	@Test
	void exposesTheConfiguredDocumentExpansionPolicy() throws Exception {
		Properties configured = applicationProperties();
		LawAiDocumentExpansionProperties defaults = new LawAiDocumentExpansionProperties(
			Boolean.parseBoolean(configured.getProperty("law-ai.retrieval.document-expansion.enabled")),
			Boolean.parseBoolean(configured.getProperty("law-ai.retrieval.document-expansion.authoritative")),
			Integer.parseInt(configured.getProperty("law-ai.retrieval.document-expansion.max-documents")),
			Integer.parseInt(configured.getProperty("law-ai.retrieval.document-expansion.max-chunks-per-document")),
			Integer.parseInt(configured.getProperty("law-ai.retrieval.document-expansion.max-total-chunks")),
			Boolean.parseBoolean(configured.getProperty("law-ai.retrieval.document-expansion.bm25-title-enabled")),
			Integer.parseInt(configured.getProperty("law-ai.retrieval.document-expansion.bm25-title-max-hits")),
			Integer.parseInt(configured.getProperty("law-ai.retrieval.document-expansion.bm25-title-minimum-terms")),
			Double.parseDouble(configured.getProperty("law-ai.retrieval.document-expansion.bm25-title-ambiguity-ratio"))
		);

		assertThat(defaults.validBounds()).isTrue();
		assertThat(defaults.policy())
			.isEqualTo(new DocumentCandidateExpansion.Policy(true, false, 3, 8, 24));
		assertThat(defaults.bm25TitlePolicy())
			.isEqualTo(new Bm25TitleDocumentSeedSelector.Policy(true, 100, 2, 0.05, 3));
	}

	@Test
	void rejectsBm25TitlePolicyOutsideVerifiedBounds() {
		assertThatThrownBy(() -> properties(101, 2, 0.05)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> properties(100, 1, 0.05)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> properties(100, 7, 0.05)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> properties(100, 2, -0.01)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> properties(100, 2, 0.26)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void failsClosedForEachNonPositiveBound() {
		assertThat(new LawAiDocumentExpansionProperties(true, false, 0, 8, 24).validBounds()).isFalse();
		assertThat(new LawAiDocumentExpansionProperties(true, false, 3, 0, 24).validBounds()).isFalse();
		assertThat(new LawAiDocumentExpansionProperties(true, false, 3, 8, 0).validBounds()).isFalse();
		assertThat(new LawAiDocumentExpansionProperties(true, false, -1, 8, 24).validBounds()).isFalse();
		assertThat(new LawAiDocumentExpansionProperties(true, false, 3, -1, 24).validBounds()).isFalse();
		assertThat(new LawAiDocumentExpansionProperties(true, false, 3, 8, -1).validBounds()).isFalse();
	}

	@Test
	void rejectsBoundsAboveTheVerifiedMaximums() {
		assertThatThrownBy(() -> new LawAiDocumentExpansionProperties(true, false, 4, 8, 24))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LawAiDocumentExpansionProperties(true, false, 3, 9, 24))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LawAiDocumentExpansionProperties(true, false, 3, 8, 25))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private Properties applicationProperties() throws Exception {
		Properties properties = new Properties();
		try (InputStream input = getClass().getResourceAsStream("/application.properties")) {
			properties.load(input);
		}
		return properties;
	}

	private LawAiDocumentExpansionProperties properties(int maxHits, int minimumTerms, double ambiguityRatio) {
		return new LawAiDocumentExpansionProperties(
			true, false, 3, 8, 24, true, maxHits, minimumTerms, ambiguityRatio
		);
	}
}
