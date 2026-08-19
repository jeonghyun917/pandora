package com.kaces.pandora.semantic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "law-ai.semantic-selection")
public record LawAiSemanticSelectionProperties(
	boolean shadowEnabled,
	boolean authoritative,
	int preserveLimit
) {
	public LawAiSemanticSelectionProperties {
		if (preserveLimit <= 0) {
			preserveLimit = 4;
		}
	}
}
