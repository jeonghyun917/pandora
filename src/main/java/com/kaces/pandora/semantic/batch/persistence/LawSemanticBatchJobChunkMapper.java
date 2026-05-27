package com.kaces.pandora.semantic.batch.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LawSemanticBatchJobChunkMapper {

	void insertChunks(@Param("chunks") List<LawSemanticBatchJobChunkRow> chunks);

	int countByBatchJobId(@Param("batchJobId") long batchJobId);

	int countByStatus(
		@Param("batchJobId") long batchJobId,
		@Param("status") String status
	);

	List<Long> findChunkIdsByStatus(
		@Param("batchJobId") long batchJobId,
		@Param("status") String status
	);

	void markOutputReady(
		@Param("batchJobId") long batchJobId,
		@Param("customId") String customId
	);

	void markIndexed(
		@Param("batchJobId") long batchJobId,
		@Param("chunkIds") List<Long> chunkIds
	);

	void markFailed(
		@Param("batchJobId") long batchJobId,
		@Param("customId") String customId,
		@Param("errorCode") String errorCode,
		@Param("errorMessage") String errorMessage
	);

	void markIndexedFromEmbeddingStatus(
		@Param("batchJobId") long batchJobId,
		@Param("model") String model,
		@Param("vectorStore") String vectorStore
	);
}
