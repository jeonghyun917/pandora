package com.kaces.pandora.law.search;

import org.springframework.util.StringUtils;

public record LawSearchQuery(
	String target,
	String query,
	int page,
	int display,
	int offset,
	boolean searchAll
) {

	public static LawSearchQuery normalize(String target, String query, int page, int display) {
		String safeTarget = StringUtils.hasText(target) ? target.trim() : "law";
		String safeQuery = StringUtils.hasText(query) ? query.trim() : "*";
		int safePage = Math.max(page, 1);
		int safeDisplay = Math.min(Math.max(display, 1), 100);
		return new LawSearchQuery(
			safeTarget,
			safeQuery,
			safePage,
			safeDisplay,
			(safePage - 1) * safeDisplay,
			"*".equals(safeQuery)
		);
	}
}
