package com.kaces.pandora.rag.persistence;


import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.chunk.LawChunkSearchRow;
import com.kaces.pandora.lawdata.persistence.LawDocumentRow;
import com.kaces.pandora.rag.document.RagDocumentChunkRow;
import com.kaces.pandora.rag.document.RagDocumentRow;
import com.kaces.pandora.rag.importing.RagImportJobKey;
import com.kaces.pandora.rag.search.RagChunkSearchTermRow;
import com.kaces.pandora.rag.search.RagChunkSearchIndexStateRow;
import com.kaces.pandora.semantic.provenance.IndexContentSnapshot;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RagDocumentMapper {
	void insertImportJob(
		@Param("job") RagImportJobKey job,
		@Param("path") String path,
		@Param("documentType") String documentType
	);

	void finishImportJob(
		@Param("importJobId") long importJobId,
		@Param("status") String status,
		@Param("discoveredCount") int discoveredCount,
		@Param("importedCount") int importedCount,
		@Param("skippedCount") int skippedCount,
		@Param("failedCount") int failedCount,
		@Param("indexedCount") int indexedCount,
		@Param("lastErrorMessage") String lastErrorMessage
	);

	RagDocumentRow findDocumentByHash(@Param("fileHash") String fileHash);

	void upsertDocument(
		@Param("documentType") String documentType,
		@Param("title") String title,
		@Param("sourceOrg") String sourceOrg,
		@Param("documentCategory") String documentCategory,
		@Param("documentTopic") String documentTopic,
		@Param("publishedDate") String publishedDate,
		@Param("version") String version,
		@Param("trustLevel") int trustLevel,
		@Param("fileName") String fileName,
		@Param("filePath") String filePath,
		@Param("fileHash") String fileHash,
		@Param("mimeType") String mimeType,
		@Param("sourceUrl") String sourceUrl,
		@Param("importStatus") String importStatus,
		@Param("lastErrorMessage") String lastErrorMessage
	);

	long findDocumentIdByHash(@Param("fileHash") String fileHash);

	RagDocumentRow findDocumentById(@Param("documentId") long documentId);

	List<RagDocumentRow> findDocumentsForReimport(@Param("documentType") String documentType);

	List<RagDocumentRow> findActiveDocumentsForObjectStorage();

	int updateObjectKey(
		@Param("documentId") long documentId,
		@Param("objectKey") String objectKey
	);

	int countDocuments(
		@Param("documentType") String documentType,
		@Param("query") String query,
		@Param("searchAll") boolean searchAll,
		@Param("titleOnly") boolean titleOnly
	);

	List<LawDocumentRow> searchDocuments(
		@Param("documentType") String documentType,
		@Param("query") String query,
		@Param("searchAll") boolean searchAll,
		@Param("titleOnly") boolean titleOnly,
		@Param("limit") int limit,
		@Param("offset") int offset
	);

	int countChunkSearch(
		@Param("documentType") String documentType,
		@Param("query") String query
	);

	List<LawChunkSearchRow> searchChunks(
		@Param("documentType") String documentType,
		@Param("query") String query,
		@Param("limit") int limit,
		@Param("offset") int offset
	);

	List<Long> findChunkIdsByDocumentId(@Param("documentId") long documentId);

	void deleteChunks(@Param("documentId") long documentId);

	List<Long> findActiveChunkIdsByDocumentIdAndVersion(
		@Param("documentId") long documentId,
		@Param("chunkVersion") int chunkVersion
	);

	List<Long> findActiveChunkIdsByFilePathsAndVersion(
		@Param("filePaths") List<String> filePaths,
		@Param("chunkVersion") int chunkVersion
	);

	int countActiveChunksByDocumentIdAndVersion(
		@Param("documentId") long documentId,
		@Param("chunkVersion") int chunkVersion
	);

	int deactivateChunksByDocumentIdAndVersion(
		@Param("documentId") long documentId,
		@Param("chunkVersion") int chunkVersion
	);

	int deactivateChunksByFilePathsAndVersion(
		@Param("filePaths") List<String> filePaths,
		@Param("chunkVersion") int chunkVersion
	);

	int markEmbeddingsSupersededByChunkIds(@Param("chunkIds") List<Long> chunkIds);

	int deactivateDocumentsByFilePaths(@Param("filePaths") List<String> filePaths);

	void insertChunk(@Param("chunk") RagDocumentChunkRow chunk);

	void deleteChunkSearchTermsByDocumentId(@Param("documentId") long documentId);

	void deleteChunkSearchIndexStateByDocumentId(@Param("documentId") long documentId);

	void deleteChunkSearchTermsByChunkIds(@Param("chunkIds") List<Long> chunkIds);

	void deleteChunkSearchIndexStateByChunkIds(@Param("chunkIds") List<Long> chunkIds);

	void insertChunkSearchTerms(@Param("terms") List<RagChunkSearchTermRow> terms);

	void upsertChunkSearchIndexStates(@Param("states") List<RagChunkSearchIndexStateRow> states);

	int countMissingChunkSearchTerms();

	String findChunkSearchIndexStatus();

	void markChunkSearchIndexBuilding();

	void markChunkSearchIndexReady();

	List<LawSemanticChunkRow> findChunkSearchTermBackfillCandidates(
		@Param("limit") int limit
	);

	List<LawSemanticChunkRow> findSemanticChunksByDocumentId(@Param("documentId") long documentId);

	List<LawSemanticChunkRow> findSemanticContextChunks(
		@Param("documentId") long documentId,
		@Param("sortOrder") int sortOrder,
		@Param("window") int window
	);

	List<LawSemanticChunkRow> findSemanticIndexChunksByDocumentId(
		@Param("documentId") long documentId,
		@Param("chunkVersion") int chunkVersion
	);

	List<LawSemanticChunkRow> findSemanticIndexCandidates(
		@Param("target") String target,
		@Param("query") String query,
		@Param("model") String model,
		@Param("vectorStore") String vectorStore,
		@Param("limit") int limit
	);

	List<LawSemanticChunkRow> findSemanticChunksByIds(@Param("chunkIds") List<Long> chunkIds);

	List<LawSemanticChunkRow> findSemanticChunksByText(
		@Param("documentTypes") List<String> documentTypes,
		@Param("keywords") List<String> keywords,
		@Param("limit") int limit
	);

	List<LawSemanticChunkRow> findSemanticChunksByLegacyText(
		@Param("documentTypes") List<String> documentTypes,
		@Param("keywords") List<String> keywords,
		@Param("limit") int limit
	);

	List<LawSemanticChunkRow> findSemanticChunksByDocumentTitleAndText(
		@Param("documentTypes") List<String> documentTypes,
		@Param("titleKeywords") List<String> titleKeywords,
		@Param("textKeywords") List<String> textKeywords,
		@Param("limit") int limit
	);

	List<LawSemanticChunkRow> findSemanticChunksByDocumentTitleAndTextScoped(
		@Param("documentTypes") List<String> documentTypes,
		@Param("titleKeywords") List<String> titleKeywords,
		@Param("textKeywords") List<String> textKeywords,
		@Param("limit") int limit
	);

	List<LawSemanticChunkRow> findSemanticChunksByDocumentTitleScoped(
		@Param("documentTypes") List<String> documentTypes,
		@Param("titleKeywords") List<String> titleKeywords,
		@Param("limit") int limit
	);

	List<LawSemanticChunkRow> findSemanticChunksByDocumentTitleWithTextHints(
		@Param("documentTypes") List<String> documentTypes,
		@Param("titleKeywords") List<String> titleKeywords,
		@Param("textKeywords") List<String> textKeywords,
		@Param("limit") int limit
	);

	List<LawSemanticChunkRow> findSemanticChunksByHeadingText(
		@Param("documentTypes") List<String> documentTypes,
		@Param("keywords") List<String> keywords,
		@Param("limit") int limit
	);

	IndexContentSnapshot findCurrentIndexedSnapshot(
		@Param("model") String model,
		@Param("vectorStore") String vectorStore
	);

	void upsertEmbeddingStatus(
		@Param("chunkId") long chunkId,
		@Param("model") String model,
		@Param("vectorStore") String vectorStore,
		@Param("vectorPointId") String vectorPointId,
		@Param("contentHash") String contentHash,
		@Param("status") String status,
		@Param("errorMessage") String errorMessage
	);

	int markFullyIndexedDocuments(
		@Param("documentType") String documentType,
		@Param("model") String model,
		@Param("vectorStore") String vectorStore
	);

	int recoverStaleSubmittedEmbeddings(
		@Param("documentType") String documentType,
		@Param("model") String model,
		@Param("vectorStore") String vectorStore,
		@Param("staleMinutes") int staleMinutes
	);
}
