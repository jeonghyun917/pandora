package com.kaces.pandora.lawdata.persistence;


import com.kaces.pandora.lawdata.chunk.LawChunkSearchRow;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.sync.StoredChunk;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LawChunkMapper {
	
	void deleteChunks(@Param("documentId") long documentId);
	
	void insertChunk(@Param("chunk") StoredChunk chunk);
	
	int countChunkSearch(
		@Param("target") String target,
		@Param("query") String query
	);
	
	List<LawChunkSearchRow> searchChunks(
		@Param("target") String target,
		@Param("query") String query,
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

	List<LawSemanticChunkRow> findSemanticChunksByIds(@Param("chunkIds") List<Long> chunkIds);

	List<LawSemanticChunkRow> findSemanticChunksByText(
		@Param("targets") List<String> targets,
		@Param("keywords") List<String> keywords,
		@Param("limit") int limit
	);

	List<LawSemanticChunkRow> findSemanticChunksByDocumentTitle(
		@Param("targets") List<String> targets,
		@Param("keywords") List<String> keywords,
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

	int recoverStaleSubmittedEmbeddings(
		@Param("target") String target,
		@Param("model") String model,
		@Param("vectorStore") String vectorStore,
		@Param("staleMinutes") int staleMinutes
	);
}
