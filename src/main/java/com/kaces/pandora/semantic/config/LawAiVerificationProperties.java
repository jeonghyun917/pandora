package com.kaces.pandora.semantic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "law-ai.verification")
public record LawAiVerificationProperties(
	boolean semanticShadowEnabled,
	boolean semanticAuthoritative,
	int maxShadowDisagreements
) {
	public LawAiVerificationProperties {
		if (maxShadowDisagreements <= 0) {
			maxShadowDisagreements = 20;
		}
	}
}
