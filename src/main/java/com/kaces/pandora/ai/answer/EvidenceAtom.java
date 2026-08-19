package com.kaces.pandora.ai.answer;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record EvidenceAtom(
	String sourceText,
	Set<String> subjects,
	Set<String> objects,
	Set<String> recipients,
	Set<String> actions,
	Set<String> relations,
	Set<String> targetScopes,
	Set<String> conditions,
	Set<String> exceptions,
	Set<String> numericAnchors,
	Modality modality,
	Polarity polarity,
	ParseStatus parseStatus,
	List<String> reasonCodes
) {
	public EvidenceAtom {
		sourceText = sourceText == null ? "" : sourceText;
		subjects = immutable(subjects);
		objects = immutable(objects);
		recipients = immutable(recipients);
		actions = immutable(actions);
		relations = immutable(relations);
		targetScopes = immutable(targetScopes);
		conditions = immutable(conditions);
		exceptions = immutable(exceptions);
		numericAnchors = immutable(numericAnchors);
		modality = modality == null ? Modality.UNSPECIFIED : modality;
		polarity = polarity == null ? Polarity.UNSPECIFIED : polarity;
		parseStatus = parseStatus == null ? ParseStatus.PARTIAL : parseStatus;
		reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
	}

	private static Set<String> immutable(Set<String> values) {
		return values == null
			? Set.of()
			: Collections.unmodifiableSet(new LinkedHashSet<>(values));
	}

	public enum Modality {
		REQUIRED,
		PERMITTED,
		PROHIBITED,
		OPTIONAL,
		UNSPECIFIED
	}

	public enum Polarity {
		POSITIVE,
		NEGATIVE,
		UNSPECIFIED
	}

	public enum ParseStatus {
		COMPLETE,
		PARTIAL,
		AMBIGUOUS
	}
}
