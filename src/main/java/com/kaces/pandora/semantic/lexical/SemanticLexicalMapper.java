package com.kaces.pandora.semantic.lexical;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SemanticLexicalMapper {

	List<LexicalChunkDocument> findActiveSearchableChunks();

	String findReadyRevision();

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

}
