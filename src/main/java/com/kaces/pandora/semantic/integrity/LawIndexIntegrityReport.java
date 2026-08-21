package com.kaces.pandora.semantic.integrity;

import java.util.EnumMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public record LawIndexIntegrityReport(
	String target,
	int limit,
	int scannedRows,
	long lastScannedChunkId,
	List<LawIndexIntegrityIssue> issues
) {
	public LawIndexIntegrityReport {
		issues = List.copyOf(issues);
	}

	public LawIndexIntegrityReport(String target, int limit, List<LawIndexIntegrityIssue> issues) {
		this(target, limit, issues.size(), 0L, issues);
	}

	public Map<LawIndexIntegrityIssue.Cause, Long> causeCounts() {
		Map<LawIndexIntegrityIssue.Cause, Long> counts = new EnumMap<>(LawIndexIntegrityIssue.Cause.class);
		for (LawIndexIntegrityIssue issue : issues) {
			counts.merge(issue.cause(), 1L, Long::sum);
		}
		return Collections.unmodifiableMap(counts);
	}

	public Map<LawIndexIntegrityIssue.Cause, Long> getCauseCounts() {
		return causeCounts();
	}
}
