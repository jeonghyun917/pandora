package com.kaces.pandora.rag.search;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RagChunkSearchIndexService {

	private static final int INSERT_BATCH_SIZE = 500;
	private static final int MAX_BACKFILL_CHUNKS = 2_000;

	private final RagDocumentMapper mapper;
	private final RagChunkSearchTermExtractor extractor;

	public RagChunkSearchIndexService(RagDocumentMapper mapper, RagChunkSearchTermExtractor extractor) {
		this.mapper = mapper;
		this.extractor = extractor;
	}

	@Transactional
	public int rebuildDocument(long documentId, int chunkVersion) {
		mapper.markChunkSearchIndexBuilding();
		List<LawSemanticChunkRow> chunks = mapper.findSemanticIndexChunksByDocumentId(documentId, chunkVersion);
		mapper.deleteChunkSearchTermsByDocumentId(documentId);
		mapper.deleteChunkSearchIndexStateByDocumentId(documentId);
		int inserted = indexChunks(chunks);
		markReadyWhenComplete();
		return inserted;
	}

	@Transactional
	public BackfillResult backfill(int requestedLimit) {
		mapper.markChunkSearchIndexBuilding();
		int limit = Math.max(1, Math.min(requestedLimit, MAX_BACKFILL_CHUNKS));
		List<LawSemanticChunkRow> chunks = mapper.findChunkSearchTermBackfillCandidates(limit);
		List<Long> chunkIds = chunks.stream().map(LawSemanticChunkRow::chunkId).toList();
		if (!chunkIds.isEmpty()) {
			mapper.deleteChunkSearchTermsByChunkIds(chunkIds);
			mapper.deleteChunkSearchIndexStateByChunkIds(chunkIds);
		}
		int insertedTerms = indexChunks(chunks);
		int remaining = mapper.countMissingChunkSearchTerms();
		if (remaining == 0) {
			mapper.markChunkSearchIndexReady();
		}
		return new BackfillResult(
			chunks.size(),
			insertedTerms,
			remaining
		);
	}

	public int countMissingChunks() {
		return mapper.countMissingChunkSearchTerms();
	}

	public boolean isReady() {
		try {
			return "READY".equalsIgnoreCase(mapper.findChunkSearchIndexStatus());
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private int indexChunks(List<LawSemanticChunkRow> chunks) {
		if (chunks == null || chunks.isEmpty()) {
			return 0;
		}
		List<RagChunkSearchTermRow> batch = new ArrayList<>(INSERT_BATCH_SIZE);
		List<RagChunkSearchIndexStateRow> states = new ArrayList<>(chunks.size());
		int inserted = 0;
		for (LawSemanticChunkRow chunk : chunks) {
			List<RagChunkSearchTermRow> extracted = extractor.extract(chunk);
			for (RagChunkSearchTermRow term : extracted) {
				batch.add(term);
				if (batch.size() >= INSERT_BATCH_SIZE) {
					mapper.insertChunkSearchTerms(batch);
					inserted += batch.size();
					batch = new ArrayList<>(INSERT_BATCH_SIZE);
				}
			}
			states.add(new RagChunkSearchIndexStateRow(
				chunk.chunkId(),
				chunk.documentId(),
				chunk.contentHash(),
				extracted.size()
			));
		}
		if (!batch.isEmpty()) {
			mapper.insertChunkSearchTerms(batch);
			inserted += batch.size();
		}
		mapper.upsertChunkSearchIndexStates(states);
		return inserted;
	}

	private void markReadyWhenComplete() {
		if (mapper.countMissingChunkSearchTerms() == 0) {
			mapper.markChunkSearchIndexReady();
		}
	}

	public record BackfillResult(
		int processedChunks,
		int indexedTerms,
		int remainingChunks
	) {
	}
}
