package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.semantic.config.LawAiCoverageAwareProperties;
import com.kaces.pandora.semantic.config.LawAiDocumentExpansionProperties;
import com.kaces.pandora.semantic.config.LawAiLexicalProperties;
import com.kaces.pandora.semantic.config.LawAiProperties;
import com.kaces.pandora.semantic.config.LawAiRrfProperties;
import org.junit.jupiter.api.Test;

class RuntimeConfigurationIdentityTests {

	@Test
	void fingerprintsBehaviorConfigurationButExcludesApiKeys() {
		LawAiProperties first = properties("secret-one", "http://127.0.0.1:6333");
		LawAiProperties changedSecret = properties("secret-two", "http://127.0.0.1:6333");
		LawAiProperties changedStore = properties("secret-one", "http://127.0.0.1:7333");

		String fingerprint = RuntimeConfigurationIdentity.sha256(first);

		assertThat(fingerprint).matches("[0-9a-f]{64}");
		assertThat(RuntimeConfigurationIdentity.sha256(changedSecret)).isEqualTo(fingerprint);
		assertThat(RuntimeConfigurationIdentity.sha256(changedStore)).isNotEqualTo(fingerprint);
	}

	@Test
	void processInstanceIdIsStableWithinOneJvm() {
		assertThat(RuntimeConfigurationIdentity.instanceId()).isNotBlank();
		assertThat(RuntimeConfigurationIdentity.instanceId())
			.isEqualTo(RuntimeConfigurationIdentity.instanceId());
	}

	@Test
	void fingerprintsLexicalAndRrfBehaviorConfiguration() {
		LawAiProperties base = properties("secret", "http://127.0.0.1:6333");
		LawAiLexicalProperties lexical = new LawAiLexicalProperties(1.2, 0.75, 8, 6, 7, 1, 24, 100);
		LawAiRrfProperties shadow = new LawAiRrfProperties(true, false, 60, 1.0, 1.0, 100);
		LawAiRrfProperties authoritative = new LawAiRrfProperties(true, true, 60, 1.0, 1.0, 100);

		assertThat(RuntimeConfigurationIdentity.sha256(base, lexical, shadow))
			.isNotEqualTo(RuntimeConfigurationIdentity.sha256(base, lexical, authoritative));
		assertThat(RuntimeConfigurationIdentity.sha256(base, lexical, shadow))
			.isNotEqualTo(RuntimeConfigurationIdentity.sha256(
				base,
				new LawAiLexicalProperties(1.6, 0.75, 8, 6, 7, 1, 24, 100),
				shadow
			));
	}

	@Test
	void fingerprintsEveryCoverageAwarePolicyField() {
		LawAiProperties base = properties("secret", "http://127.0.0.1:6333");
		LawAiLexicalProperties lexical = new LawAiLexicalProperties(1.2, 0.75, 8, 6, 7, 1, 24, 100);
		LawAiRrfProperties rrf = new LawAiRrfProperties(true, false, 60, 1.0, 1.0, 100);
		LawAiCoverageAwareProperties disabled = new LawAiCoverageAwareProperties(false, 0, 1, 30);
		String fingerprint = RuntimeConfigurationIdentity.sha256(base, lexical, rrf, disabled);

		assertThat(RuntimeConfigurationIdentity.sha256(
			base, lexical, rrf, new LawAiCoverageAwareProperties(true, 0, 1, 30)
		)).isNotEqualTo(fingerprint);
		assertThat(RuntimeConfigurationIdentity.sha256(
			base, lexical, rrf, new LawAiCoverageAwareProperties(true, 1, 1, 30)
		)).isNotEqualTo(fingerprint);
		assertThat(RuntimeConfigurationIdentity.sha256(
			base, lexical, rrf, new LawAiCoverageAwareProperties(true, 2, 1, 30)
		)).isNotEqualTo(fingerprint);
		assertThat(RuntimeConfigurationIdentity.sha256(
			base, lexical, rrf, new LawAiCoverageAwareProperties(true, 2, 1, 20)
		)).isNotEqualTo(fingerprint);
	}

	@Test
	void fingerprintsEveryDocumentExpansionPolicyField() {
		LawAiProperties base = properties("secret", "http://127.0.0.1:6333");
		LawAiLexicalProperties lexical = new LawAiLexicalProperties(1.2, 0.75, 8, 6, 7, 1, 24, 100);
		LawAiRrfProperties rrf = new LawAiRrfProperties(true, false, 60, 1.0, 1.0, 100);
		LawAiCoverageAwareProperties coverage = new LawAiCoverageAwareProperties(false, 0, 1, 30);
		LawAiDocumentExpansionProperties baseline = new LawAiDocumentExpansionProperties(true, false, 3, 8, 24);
		String fingerprint = RuntimeConfigurationIdentity.sha256(base, lexical, rrf, coverage, baseline);

		assertThat(RuntimeConfigurationIdentity.sha256(
			base, lexical, rrf, coverage, new LawAiDocumentExpansionProperties(false, false, 3, 8, 24)
		)).isNotEqualTo(fingerprint);
		assertThat(RuntimeConfigurationIdentity.sha256(
			base, lexical, rrf, coverage, new LawAiDocumentExpansionProperties(true, true, 3, 8, 24)
		)).isNotEqualTo(fingerprint);
		assertThat(RuntimeConfigurationIdentity.sha256(
			base, lexical, rrf, coverage, new LawAiDocumentExpansionProperties(true, false, 2, 8, 24)
		)).isNotEqualTo(fingerprint);
		assertThat(RuntimeConfigurationIdentity.sha256(
			base, lexical, rrf, coverage, new LawAiDocumentExpansionProperties(true, false, 3, 7, 24)
		)).isNotEqualTo(fingerprint);
		assertThat(RuntimeConfigurationIdentity.sha256(
			base, lexical, rrf, coverage, new LawAiDocumentExpansionProperties(true, false, 3, 8, 23)
		)).isNotEqualTo(fingerprint);
	}

	private LawAiProperties properties(String apiKey, String qdrantBaseUrl) {
		return new LawAiProperties(
			new LawAiProperties.OpenAi(apiKey, "embedding", "answer", "low", "low", 700),
			new LawAiProperties.Qdrant(qdrantBaseUrl, "law", "rag", 1536),
			null,
			null
		);
	}
}
