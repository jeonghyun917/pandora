package com.kaces.pandora.app.admin;

import java.util.List;

public record AdminOverviewResponse(
	String generatedAt,
	List<AdminMetric> metrics,
	List<AdminPipelineStatus> pipelines,
	List<AdminSourceStatus> sources,
	List<AdminBatchStatus> batches,
	List<AdminImportStatus> imports
) {
	public record AdminMetric(
		String key,
		String label,
		long value,
		String detail,
		String tone
	) {
	}

	public record AdminPipelineStatus(
		String key,
		String pageName,
		String sourceType,
		String fetchMethod,
		String target,
		long documents,
		long chunkedDocuments,
		long chunks,
		long indexedChunks,
		long pendingChunks,
		long failedEmbeddings,
		long activeBatches,
		long submittedBatches,
		long ingestedBatches,
		String lastUpdatedAt,
		String status,
		List<AdminPipelineBreakdown> breakdowns
	) {
	}

	public record AdminPipelineBreakdown(
		String label,
		long documents,
		long chunks,
		long indexedChunks
	) {
	}

	public record AdminSourceStatus(
		String sourceKey,
		String sourceType,
		String agencyCode,
		String agencyName,
		String sourceUrl,
		String enabled,
		long articles,
		long importedArticles,
		long attachments,
		long importedAttachments,
		String lastCheckedAt,
		String lastSuccessAt,
		String status
	) {
	}

	public record AdminBatchStatus(
		long batchJobId,
		String target,
		String status,
		int submitted,
		int completed,
		int failed,
		int ingested,
		String submittedAt,
		String completedAt,
		String ingestedAt
	) {
	}

	public record AdminImportStatus(
		long importJobId,
		String documentType,
		String status,
		int discovered,
		int imported,
		int skipped,
		int failed,
		int indexed,
		String startedAt,
		String finishedAt
	) {
	}
}
