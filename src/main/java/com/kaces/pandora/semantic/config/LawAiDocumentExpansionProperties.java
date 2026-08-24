package com.kaces.pandora.semantic.config;

import com.kaces.pandora.semantic.retrieval.DocumentCandidateExpansion;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "law-ai.retrieval.document-expansion")
public record LawAiDocumentExpansionProperties(
	boolean enabled,
	boolean authoritative,
	int maxDocuments,
	int maxChunksPerDocument,
	int maxTotalChunks
) {
	private static final int MAX_DOCUMENTS = 3;
	private static final int MAX_CHUNKS_PER_DOCUMENT = 8;
	private static final int MAX_TOTAL_CHUNKS = 24;

	public LawAiDocumentExpansionProperties {
		if (maxDocuments > MAX_DOCUMENTS) {
			throw new IllegalArgumentException("maxDocuments must not exceed " + MAX_DOCUMENTS);
		}
		if (maxChunksPerDocument > MAX_CHUNKS_PER_DOCUMENT) {
			throw new IllegalArgumentException("maxChunksPerDocument must not exceed " + MAX_CHUNKS_PER_DOCUMENT);
		}
		if (maxTotalChunks > MAX_TOTAL_CHUNKS) {
			throw new IllegalArgumentException("maxTotalChunks must not exceed " + MAX_TOTAL_CHUNKS);
		}
	}

	public boolean validBounds() {
		return maxDocuments > 0 && maxDocuments <= MAX_DOCUMENTS
			&& maxChunksPerDocument > 0 && maxChunksPerDocument <= MAX_CHUNKS_PER_DOCUMENT
			&& maxTotalChunks > 0 && maxTotalChunks <= MAX_TOTAL_CHUNKS;
	}

	public DocumentCandidateExpansion.Policy policy() {
		return new DocumentCandidateExpansion.Policy(
			enabled, authoritative, maxDocuments, maxChunksPerDocument, maxTotalChunks
		);
	}
}
