package com.kaces.pandora.semantic.indexing;

import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Proxied transaction boundary for the two relational writes that record one indexed chunk. */
@Service
public class LawSemanticIndexStatusPersistenceService {
	private final LawChunkMapper lawChunkMapper;

	public LawSemanticIndexStatusPersistenceService(LawChunkMapper lawChunkMapper) {
		this.lawChunkMapper = lawChunkMapper;
	}

	@Transactional
	public void markIndexed(long chunkId, String model, String vectorStore, String contentHash) {
		lawChunkMapper.upsertEmbeddingStatus(
			chunkId, model, vectorStore, String.valueOf(chunkId), contentHash, "INDEXED", null);
		if (lawChunkMapper.updateChunkIndexStatus(chunkId, "INDEXED", null) != 1) {
			throw new IllegalStateException("Unable to persist indexed chunk status.");
		}
	}
}
