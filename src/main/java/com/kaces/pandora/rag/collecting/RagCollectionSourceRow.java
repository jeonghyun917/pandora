package com.kaces.pandora.rag.collecting;

public record RagCollectionSourceRow(
	long sourceId,
	String sourceKey,
	String sourceType,
	String agencyCode,
	String agencyName,
	String sourceUrl,
	String enabled,
	String lastCheckedAt,
	String lastSuccessAt,
	String lastErrorMessage
) {
}
