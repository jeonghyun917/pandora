package com.kaces.pandora.rag.collecting;

import java.util.List;

public record RagCollectionResponse(
	long runId,
	String status,
	String agencyCode,
	int discoveredArticles,
	int newArticles,
	int attachmentsDiscovered,
	int downloadedCount,
	int importedCount,
	int skippedCount,
	int failedCount,
	int submittedBatches,
	String lastErrorMessage,
	List<RagCollectionSourceRow> sources
) {
}
