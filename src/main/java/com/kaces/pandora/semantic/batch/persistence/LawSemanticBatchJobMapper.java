package com.kaces.pandora.semantic.batch.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LawSemanticBatchJobMapper {

	void insertJob(
		@Param("openaiBatchId") String openaiBatchId,
		@Param("inputFileId") String inputFileId,
		@Param("status") String status,
		@Param("target") String target,
		@Param("queryText") String queryText,
		@Param("embeddingModel") String embeddingModel,
		@Param("vectorStore") String vectorStore,
		@Param("inputFilePath") String inputFilePath,
		@Param("requestedCount") int requestedCount,
		@Param("submittedCount") int submittedCount
	);

	long lastInsertId();

	LawSemanticBatchJobRow findByOpenaiBatchId(@Param("openaiBatchId") String openaiBatchId);

	List<LawSemanticBatchJobRow> findAllJobs();

	List<LawSemanticBatchJobRow> findActiveJobs();

	void updateStatus(
		@Param("openaiBatchId") String openaiBatchId,
		@Param("status") String status,
		@Param("outputFileId") String outputFileId,
		@Param("errorFileId") String errorFileId,
		@Param("completedCount") int completedCount,
		@Param("failedCount") int failedCount
	);

	void markIngested(
		@Param("openaiBatchId") String openaiBatchId,
		@Param("outputFilePath") String outputFilePath,
		@Param("ingestedCount") int ingestedCount
	);

	void markFailed(
		@Param("openaiBatchId") String openaiBatchId,
		@Param("status") String status,
		@Param("lastErrorMessage") String lastErrorMessage
	);

	void recordLocalError(
		@Param("openaiBatchId") String openaiBatchId,
		@Param("lastErrorMessage") String lastErrorMessage
	);
}
