package com.kaces.pandora.semantic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "law-ai.retrieval.lexical-variant")
public record LawAiLexicalVariantProperties(
	boolean shadowEnabled,
	boolean authoritative,
	int maxVariants,
	double rrfK
) {
	public boolean valid() {
		return maxVariants >= 1
			&& maxVariants <= 4
			&& Double.isFinite(rrfK)
			&& rrfK > 0;
	}
}
