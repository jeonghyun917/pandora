package com.kaces.pandora.common.text;

import java.util.List;

public record QuestionEntity(
	String id,
	String label,
	String type,
	List<String> aliases,
	List<String> answerAnchors,
	List<String> preferredTargets,
	List<String> focusedKeywords,
	List<String> sectionTypes,
	List<List<String>> directEvidenceGroups
) {
}
