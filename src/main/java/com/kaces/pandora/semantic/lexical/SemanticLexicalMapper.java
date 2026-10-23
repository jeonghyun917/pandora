package com.kaces.pandora.semantic.lexical;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SemanticLexicalMapper {

	List<LexicalChunkDocument> findActiveSearchableChunks();

	String findReadyRevision();
    int recordRecoveryIntegrity(@Param("indexVersion") String indexVersion,@Param("chunkCount") int chunkCount,@Param("termCount") int termCount);
    boolean recoveryAvailable(@Param("indexVersion") String indexVersion,@Param("revision") String revision);
	List<TermStatisticRow> findRecoveryTermStatistics(@Param("indexVersion") String indexVersion,
		@Param("revision") String revision,@Param("terms") List<String> terms);
	List<Bm25TermMatchRow> findRecoveryBm25TermMatches(@Param("indexVersion") String indexVersion,
		@Param("revision") String revision,@Param("terms") List<String> terms,@Param("targets") List<String> targets);

	IndexStateRow findRecoveryState(@Param("indexVersion") String indexVersion);
	List<ChunkRow> findRecoveryChunks(@Param("indexVersion") String indexVersion, @Param("documents") List<LexicalChunkDocument> documents);
	List<TermRow> findRecoveryTerms(@Param("indexVersion") String indexVersion, @Param("documents") List<LexicalChunkDocument> documents);
	RecoveryCounts recoveryCounts(@Param("indexVersion") String indexVersion);
	List<TermStatisticRow> findRecoveryStatisticsForVerification(@Param("indexVersion") String indexVersion, @Param("terms") List<String> terms);
	record RecoveryCounts(long chunkCount, long termCount, long statisticCount) { }

	List<TermStatisticRow> findTermStatistics(
		@Param("revision") String revision,
		@Param("terms") List<String> terms
	);

	List<Bm25TermMatchRow> findBm25TermMatches(
		@Param("revision") String revision,
		@Param("terms") List<String> terms,
		@Param("targets") List<String> targets
	);

	int insertIndexState(@Param("state") IndexStateRow state);

	int insertChunks(
		@Param("indexVersion") String indexVersion,
		@Param("chunks") List<ChunkRow> chunks
	);

	int insertTerms(
		@Param("indexVersion") String indexVersion,
		@Param("terms") List<TermRow> terms
	);

	int populateTermStats(@Param("indexVersion") String indexVersion);

	int markChunksReady(@Param("indexVersion") String indexVersion);

	int markIndexReady(
		@Param("indexVersion") String indexVersion,
		@Param("contentFingerprint") String contentFingerprint,
		@Param("activeChunkCount") int activeChunkCount,
		@Param("averageWeightedLength") double averageWeightedLength
	);

	int markIndexFailed(@Param("indexVersion") String indexVersion);

	int markRecoveryIndexReady(
		@Param("indexVersion") String indexVersion,
		@Param("contentFingerprint") String contentFingerprint,
		@Param("activeChunkCount") int activeChunkCount,
		@Param("averageWeightedLength") double averageWeightedLength
	);

	record IndexStateRow(
		String indexVersion,
		String tokenizerVersion,
		int activeChunkCount,
		double averageWeightedLength,
		String contentFingerprint,
		String status,
		LocalDateTime completedAt
	) {
	}

	record ChunkRow(
		String target,
		long chunkId,
		long documentId,
		String parentKey,
		String contentHash,
		int weightedLength
	) {
	}

	record TermRow(
		String target,
		long chunkId,
		String term,
		String fieldKind,
		int termFrequency,
		int fieldWeight
	) {
	}

	record Bm25TermMatchRow(
		String target,
		long chunkId,
		long documentId,
		String term,
		double weightedTermFrequency,
		int documentFrequency,
		int activeChunkCount,
		double averageWeightedLength,
		int weightedLength
	) {
	}

	record TermStatisticRow(
		String term,
		int documentFrequency
	) {
	}

}
