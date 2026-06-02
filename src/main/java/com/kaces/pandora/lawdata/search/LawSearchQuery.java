package com.kaces.pandora.lawdata.search;

import org.springframework.util.StringUtils;

public record LawSearchQuery(
	String target,
	String query,
	int page,
	int display,
	int offset,
	boolean searchAll,
	boolean titleOnly
) {
	private static final java.util.Set<String> SUPPORTED_TARGETS = java.util.Set.of(
		"law",
		"admrul",
		"official_doc",
		"internal_doc",
		"reference_doc"
	);

	
	// 메소드 설명: normalize 처리 흐름을 수행합니다.
	public static LawSearchQuery normalize(String target, String query, int page, int display) {
		return normalize(target, query, page, display, false);
	}

	public static LawSearchQuery normalize(String target, String query, int page, int display, boolean titleOnly) {
		String safeTarget = StringUtils.hasText(target) ? target.trim() : "law";
		if (!SUPPORTED_TARGETS.contains(safeTarget)) {
			throw new IllegalArgumentException("Unsupported law data target: " + safeTarget);
		}
		String safeQuery = StringUtils.hasText(query) ? query.trim() : "*";
		int safePage = Math.max(page, 1);
		int safeDisplay = Math.min(Math.max(display, 1), 100);
		
		return new LawSearchQuery(
			safeTarget,
			safeQuery,
			safePage,
			safeDisplay,
			(safePage - 1) * safeDisplay,
			"*".equals(safeQuery),
			titleOnly
		);
	}
}
