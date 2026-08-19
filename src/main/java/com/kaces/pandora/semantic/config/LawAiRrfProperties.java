package com.kaces.pandora.semantic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "law-ai.retrieval")
public record LawAiRrfProperties(
	boolean rrfShadowEnabled,
	boolean rrfAuthoritative,
	int rrfK,
	double rrfVectorWeight,
	double rrfLexicalWeight,
	int rrfFusedLimit
) {
	public LawAiRrfProperties {
		if (rrfK <= 0) {
			rrfK = 60;
		}
		if (rrfVectorWeight <= 0) {
			rrfVectorWeight = 1.0;
		}
		if (rrfLexicalWeight <= 0) {
			rrfLexicalWeight = 1.0;
		}
		if (rrfFusedLimit <= 0) {
			rrfFusedLimit = 100;
		}
	}

	public boolean enabled() {
		return rrfShadowEnabled || rrfAuthoritative;
	}
}
