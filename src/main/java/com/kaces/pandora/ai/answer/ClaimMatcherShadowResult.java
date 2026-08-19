package com.kaces.pandora.ai.answer;

public record ClaimMatcherShadowResult(
	String claim,
	ClaimEvidenceMatcher.Status controlStatus,
	ClaimEvidenceMatcher.Status semanticStatus,
	boolean unsafeDisagreement,
	String semanticReasonCode,
	int groundNumber,
	String evidenceSentence
) {
	public static ClaimMatcherShadowResult from(
		String claim,
		ClaimEvidenceMatcher.Match control,
		SemanticEvidenceMatcher.SemanticMatch semantic
	) {
		boolean unsafe = control.status() == ClaimEvidenceMatcher.Status.SUPPORTED
			&& semantic.status() != ClaimEvidenceMatcher.Status.SUPPORTED;
		return new ClaimMatcherShadowResult(
			claim,
			control.status(),
			semantic.status(),
			unsafe,
			semantic.reasonCode(),
			semantic.groundNumber(),
			semantic.evidenceSentence()
		);
	}
}
