package com.kaces.pandora.common.text;

import java.util.List;

public record DocumentSearchAnchor(
	List<String> titleTerms,
	List<String> provisionTerms,
	List<String> headingTerms,
	List<String> evidenceTerms,
	List<String> targets,
	AnchorType anchorType,
	Status status
) {
	public enum AnchorType { EXPLICIT_TITLE, STABLE_ALIAS, TITLE_WITH_PROVISION, NONE }
	public enum Status { ELIGIBLE, NO_STRONG_ANCHOR, INVALID }

	public boolean eligible() {
		return status == Status.ELIGIBLE;
	}
}
