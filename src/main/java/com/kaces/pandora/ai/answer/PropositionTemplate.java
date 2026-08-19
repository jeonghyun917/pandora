package com.kaces.pandora.ai.answer;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public record PropositionTemplate(
	Set<String> subjects,
	Set<String> actions,
	Set<String> relations,
	Set<String> targetScopes,
	Set<String> conditions,
	Set<RequiredSlot> requiredSlots
) {
	public PropositionTemplate {
		subjects = immutable(subjects);
		actions = immutable(actions);
		relations = immutable(relations);
		targetScopes = immutable(targetScopes);
		conditions = immutable(conditions);
		requiredSlots = immutable(requiredSlots);
	}

	public static PropositionTemplate empty() {
		return new PropositionTemplate(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
	}

	public boolean isEmpty() {
		return requiredSlots.isEmpty();
	}

	private static <T> Set<T> immutable(Set<T> values) {
		return values == null
			? Set.of()
			: Collections.unmodifiableSet(new LinkedHashSet<>(values));
	}

	public enum RequiredSlot {
		SUBJECT,
		ACTION,
		RELATION,
		TARGET_SCOPE,
		CONDITION,
		MODALITY,
		POLARITY,
		NUMERIC_ANCHOR
	}
}
