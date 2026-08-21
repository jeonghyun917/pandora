package com.kaces.pandora.semantic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "law-ai.retrieval.lexical")
public record LawAiLexicalProperties(
	double k1,
	double b,
	int documentTitleWeight,
	int parentTitleWeight,
	int chunkTitleWeight,
	int bodyWeight,
	int maxQueryTerms,
	int maxResultLimit
) {
	public LawAiLexicalProperties {
		if (k1 <= 0) {
			k1 = 1.2;
		}
		if (b < 0 || b > 1) {
			b = 0.75;
		}
		if (documentTitleWeight <= 0) {
			documentTitleWeight = 8;
		}
		if (parentTitleWeight <= 0) {
			parentTitleWeight = 6;
		}
		if (chunkTitleWeight <= 0) {
			chunkTitleWeight = 7;
		}
		if (bodyWeight <= 0) {
			bodyWeight = 1;
		}
		if (maxQueryTerms <= 0) {
			maxQueryTerms = 24;
		}
		if (maxResultLimit <= 0) {
			maxResultLimit = 100;
		}
	}
}
