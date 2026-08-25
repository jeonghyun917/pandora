package com.kaces.pandora.semantic.config;

import com.kaces.pandora.semantic.lexical.CoverageAwareFusion;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "law-ai.retrieval.coverage-aware")
public record LawAiCoverageAwareProperties(
	boolean enabled,
	int maxRescues,
	int maxRescuesPerDocument,
	int sourceRankLimit
) {
	public LawAiCoverageAwareProperties {
		if (maxRescues < 0) {
			throw new IllegalArgumentException("maxRescues must not be negative");
		}
		if (maxRescues > 2) {
			throw new IllegalArgumentException("maxRescues must not exceed 2");
		}
		if (maxRescuesPerDocument <= 0) {
			throw new IllegalArgumentException("maxRescuesPerDocument must be positive");
		}
		if (maxRescuesPerDocument > 1) {
			throw new IllegalArgumentException("maxRescuesPerDocument must not exceed 1");
		}
		if (maxRescues > 0 && maxRescuesPerDocument > maxRescues) {
			throw new IllegalArgumentException("maxRescuesPerDocument must not exceed maxRescues");
		}
		if (sourceRankLimit <= 0) {
			throw new IllegalArgumentException("sourceRankLimit must be positive");
		}
	}

	public CoverageAwareFusion.Policy policy() {
		return new CoverageAwareFusion.Policy(enabled, maxRescues, maxRescuesPerDocument, sourceRankLimit);
	}
}
