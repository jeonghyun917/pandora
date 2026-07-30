package com.kaces.pandora.rag.collecting;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RagCollectionMapper {
	void insertDefaultSource(
		@Param("sourceKey") String sourceKey,
		@Param("sourceType") String sourceType,
		@Param("agencyCode") String agencyCode,
		@Param("agencyName") String agencyName,
		@Param("sourceUrl") String sourceUrl
	);

	List<RagCollectionSourceRow> findEnabledSources(@Param("agencyCode") String agencyCode);

	List<RagCollectionSourceRow> findSources();

	void markSourceChecked(
		@Param("sourceId") long sourceId,
		@Param("success") boolean success,
		@Param("errorMessage") String errorMessage
	);

	void insertRun(@Param("run") RagCollectionRunKey run, @Param("agencyCode") String agencyCode);

	void finishRun(
		@Param("runId") long runId,
		@Param("status") String status,
		@Param("discoveredArticles") int discoveredArticles,
		@Param("newArticles") int newArticles,
		@Param("attachmentsDiscovered") int attachmentsDiscovered,
		@Param("downloadedCount") int downloadedCount,
		@Param("importedCount") int importedCount,
		@Param("skippedCount") int skippedCount,
		@Param("failedCount") int failedCount,
		@Param("submittedBatches") int submittedBatches,
		@Param("lastErrorMessage") String lastErrorMessage
	);

	void upsertArticle(
		@Param("key") RagSourceArticleKey key,
		@Param("sourceId") long sourceId,
		@Param("externalId") String externalId,
		@Param("title") String title,
		@Param("link") String link,
		@Param("publishedAt") java.time.LocalDateTime publishedAt,
		@Param("detailHash") String detailHash
	);

	Long findArticleId(@Param("sourceId") long sourceId, @Param("externalId") String externalId);

	void updateArticleStatus(
		@Param("articleId") long articleId,
		@Param("status") String status,
		@Param("errorMessage") String errorMessage
	);

	RagCollectedAttachmentRow findAttachment(
		@Param("articleId") long articleId,
		@Param("url") String url
	);

	void upsertAttachment(
		@Param("articleId") long articleId,
		@Param("url") String url,
		@Param("fileName") String fileName,
		@Param("extension") String extension,
		@Param("mimeType") String mimeType,
		@Param("fileHash") String fileHash,
		@Param("localPath") String localPath,
		@Param("documentId") Long documentId,
		@Param("status") String status,
		@Param("errorMessage") String errorMessage
	);
}
