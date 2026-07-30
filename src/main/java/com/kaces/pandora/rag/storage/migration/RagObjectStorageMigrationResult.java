package com.kaces.pandora.rag.storage.migration;

public record RagObjectStorageMigrationResult(int updatedCount, int failedCount, int skippedCount) {
}
