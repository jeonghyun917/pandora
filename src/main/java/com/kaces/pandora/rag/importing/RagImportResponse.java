package com.kaces.pandora.rag.importing;

public record RagImportResponse(
	long importJobId,
	String status,
	String importPath,
	String documentType,
	int discoveredCount,
	int importedCount,
	int skippedCount,
	int failedCount,
	int indexedCount,
	String message
) {
}
