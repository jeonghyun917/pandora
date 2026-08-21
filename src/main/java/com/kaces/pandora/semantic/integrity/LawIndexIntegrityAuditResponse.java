package com.kaces.pandora.semantic.integrity;

import java.util.List;
import java.util.Map;

public record LawIndexIntegrityAuditResponse(
	String target,
	int limit,
	int scannedRows,
	long lastScannedChunkId,
	List<LawIndexIntegrityIssue> issues,
	Map<LawIndexIntegrityIssue.Cause, Long> causeCounts,
	String runtimeInstanceId,
	String indexRevision
) {
}
