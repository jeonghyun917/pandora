package com.kaces.pandora.lawdata.persistence;


import com.kaces.pandora.lawdata.chunk.LawChunkSearchRow;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.chunk.LawChunkVersionRow;
import com.kaces.pandora.lawdata.chunk.LawChunkVersionVerification;
import com.kaces.pandora.lawdata.sync.StoredChunk;
import com.kaces.pandora.semantic.provenance.IndexContentSnapshot;
import com.kaces.pandora.semantic.integrity.LawIndexIntegrityRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LawChunkMapper {
	
	List<Long> findChunkIdsByDocumentId(@Param("documentId") long documentId);

	List<Long> findChunkIdsByDocumentIdAndVersion(
		@Param("documentId") long documentId,
		@Param("chunkVersion") int chunkVersion
	);

	int findActiveChunkVersion(@Param("documentId") long documentId);

	int findNextChunkVersion(@Param("documentId") long documentId);

	String findChunkVersionStatus(@Param("documentId") long documentId, @Param("chunkVersion") int chunkVersion);
	
	void deleteChunks(@Param("documentId") long documentId);
	
	void insertChunk(@Param("chunk") StoredChunk chunk);

	void upsertChunkVersion(@Param("version") LawChunkVersionRow version);

	LawChunkVersionVerification findChunkVersionVerification(
		@Param("documentId") long documentId,
		@Param("chunkVersion") int chunkVersion,
		@Param("model") String model,
		@Param("vectorStore") String vectorStore
	);

	void activateChunkVersion(@Param("documentId") long documentId, @Param("chunkVersion") int chunkVersion);

	void retireOtherChunkVersions(@Param("documentId") long documentId, @Param("chunkVersion") int chunkVersion);

	void reactivateChunkVersion(@Param("documentId") long documentId, @Param("chunkVersion") int chunkVersion);

	void retireActiveChunkVersionsExcept(@Param("documentId") long documentId, @Param("chunkVersion") int chunkVersion);

	void updateChunkVersionStatus(
		@Param("documentId") long documentId,
		@Param("chunkVersion") int chunkVersion,
		@Param("activationStatus") String activationStatus
	);

	void retireOtherActiveChunkVersionStates(
		@Param("documentId") long documentId,
		@Param("chunkVersion") int chunkVersion
	);
	
	int countChunkSearch(
		@Param("target") String target,
		@Param("query") String query,
		@Param("includeFuture") boolean includeFuture
	);
	
	List<LawChunkSearchRow> searchChunks(
		@Param("target") String target,
		@Param("query") String query,
		@Param("includeFuture") boolean includeFuture,
		@Param("limit") int limit,
		@Param("offset") int offset
	);

	List<LawSemanticChunkRow> findSemanticIndexCandidates(
		@Param("target") String target,
		@Param("query") String query,
		@Param("model") String model,
		@Param("vectorStore") String vectorStore,
		@Param("limit") int limit
	);

	List<LawSemanticChunkRow> findSemanticIndexCandidatesByDocumentIds(
		@Param("target") String target,
		@Param("documentIds") List<Long> documentIds,
		@Param("model") String model,
		@Param("vectorStore") String vectorStore,
		@Param("limit") int limit
	);

	List<LawSemanticChunkRow> findSemanticChunksByIds(
		@Param("chunkIds") List<Long> chunkIds,
		@Param("includeFuture") boolean includeFuture
	);

	List<LawSemanticChunkRow> findSemanticChunksByIdsForIndexing(
		@Param("chunkIds") List<Long> chunkIds
	);

	default List<LawSemanticChunkRow> findSemanticChunksByIds(List<Long> chunkIds) {
		return findSemanticChunksByIds(chunkIds, true);
	}

	List<LawSemanticChunkRow> findSemanticContextChunks(
		@Param("documentId") long documentId,
		@Param("sortOrder") int sortOrder,
		@Param("radius") int radius
	);

	List<LawSemanticChunkRow> findSemanticChunksByText(
		@Param("targets") List<String> targets,
		@Param("keywords") List<String> keywords,
		@Param("includeFuture") boolean includeFuture,
		@Param("limit") int limit
	);

	List<LawSemanticChunkRow> findSemanticIndexCandidatesByDocumentIdAndVersion(
		@Param("target") String target,
		@Param("documentId") long documentId,
		@Param("chunkVersion") int chunkVersion,
		@Param("model") String model,
		@Param("vectorStore") String vectorStore,
		@Param("limit") int limit
	);

	List<LawSemanticChunkRow> findSemanticChunksByHeadingOrDocumentTitle(
		@Param("targets") List<String> targets,
		@Param("keywords") List<String> keywords,
		@Param("includeFuture") boolean includeFuture,
		@Param("limit") int limit
	);

	List<LawSemanticChunkRow> findSemanticChunksByDocumentTitle(
		@Param("targets") List<String> targets,
		@Param("keywords") List<String> keywords,
		@Param("includeFuture") boolean includeFuture,
		@Param("limit") int limit
	);

	List<LawSemanticChunkRow> findSemanticChunksByDocumentTitleAndText(
		@Param("targets") List<String> targets,
		@Param("titleKeywords") List<String> titleKeywords,
		@Param("textKeywords") List<String> textKeywords,
		@Param("includeFuture") boolean includeFuture,
		@Param("limit") int limit
	);

	IndexContentSnapshot findCurrentIndexedSnapshot(
		@Param("model") String model,
		@Param("vectorStore") String vectorStore
	);

	List<LawIndexIntegrityRow> findLawIndexIntegrityRows(
		@Param("target") String target,
		@Param("model") String model,
		@Param("vectorStore") String vectorStore,
		@Param("limit") int limit
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

	int updateChunkIndexStatus(
		@Param("chunkId") long chunkId,
		@Param("status") String status,
		@Param("errorMessage") String errorMessage
	);

	int recoverStaleSubmittedEmbeddings(
		@Param("target") String target,
		@Param("model") String model,
		@Param("vectorStore") String vectorStore,
		@Param("staleMinutes") int staleMinutes
	);
}
