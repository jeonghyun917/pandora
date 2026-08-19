package com.kaces.pandora.semantic.lexical;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SemanticLexicalMapper {

	List<LexicalChunkDocument> findActiveSearchableChunks();

	String findReadyRevision();

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

}
