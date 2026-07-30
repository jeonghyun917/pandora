package com.kaces.pandora.ai.answer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class RetrievalAuditTermMatcher {

	static final int MAX_GROUPS = 32;
	static final int MAX_ALIASES_PER_GROUP = 16;
	static final int MAX_ALIAS_LENGTH = 160;

	private static final Pattern NON_TEXT = Pattern.compile("[\\p{P}\\p{S}\\sㆍᆞ]+");

	private RetrievalAuditTermMatcher() {
	}

	static List<GroupMatch> matchGroups(List<List<String>> groups, String chunkText) {
		List<List<String>> safeGroups = groups == null ? List.of() : groups;
		validateGroups(safeGroups);
		String normalizedBody = normalize(chunkText);
		if (safeGroups.isEmpty() || normalizedBody.isBlank()) {
			return List.of();
		}

		List<GroupMatch> matches = new ArrayList<>();
		for (int groupIndex = 0; groupIndex < safeGroups.size(); groupIndex += 1) {
			List<String> aliases = safeGroups.get(groupIndex);
			if (aliases == null) {
				continue;
			}
			for (String alias : aliases) {
				String normalizedAlias = normalize(alias);
				if (!normalizedAlias.isBlank() && normalizedBody.contains(normalizedAlias)) {
					matches.add(new GroupMatch(groupIndex, alias.trim()));
					break;
				}
			}
		}
		return List.copyOf(matches);
	}

	static void validateGroups(List<List<String>> groups) {
		List<List<String>> safeGroups = groups == null ? List.of() : groups;
		if (safeGroups.size() > MAX_GROUPS) {
			throw new IllegalArgumentException("auditTermGroups must contain at most " + MAX_GROUPS + " groups");
		}
		for (List<String> aliases : safeGroups) {
			if (aliases == null) {
				continue;
			}
			if (aliases.size() > MAX_ALIASES_PER_GROUP) {
				throw new IllegalArgumentException(
					"each audit term group must contain at most " + MAX_ALIASES_PER_GROUP + " aliases"
				);
			}
			for (String alias : aliases) {
				if (alias != null && alias.length() > MAX_ALIAS_LENGTH) {
					throw new IllegalArgumentException(
						"each audit term alias must contain at most " + MAX_ALIAS_LENGTH + " characters"
					);
				}
			}
		}
	}

	private static String normalize(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		return NON_TEXT.matcher(
			Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
		).replaceAll("");
	}

	record GroupMatch(int groupIndex, String matchedAlias) {
	}
}
