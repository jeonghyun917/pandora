package com.kaces.pandora.semantic.config;

import com.kaces.pandora.semantic.retrieval.DocumentCandidateExpansion;
import com.kaces.pandora.semantic.retrieval.Bm25TitleDocumentSeedSelector;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "law-ai.retrieval.document-expansion")
public record LawAiDocumentExpansionProperties(
	boolean enabled,
	boolean authoritative,
	int maxDocuments,
	int maxChunksPerDocument,
	int maxTotalChunks,
	boolean bm25TitleEnabled,
	int bm25TitleMaxHits,
	int bm25TitleMinimumTerms,
	double bm25TitleAmbiguityRatio
) {
	private static final int MAX_DOCUMENTS = 3;
	private static final int MAX_CHUNKS_PER_DOCUMENT = 8;
	private static final int MAX_TOTAL_CHUNKS = 24;
	private static final int MAX_BM25_TITLE_HITS = 100;
	private static final int MIN_BM25_TITLE_TERMS = 2;
	private static final int MAX_BM25_TITLE_TERMS = 6;
	private static final double MAX_BM25_TITLE_AMBIGUITY_RATIO = 0.25;

	@ConstructorBinding
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
		if (bm25TitleMaxHits < 1 || bm25TitleMaxHits > MAX_BM25_TITLE_HITS) {
			throw new IllegalArgumentException("bm25TitleMaxHits must be between 1 and " + MAX_BM25_TITLE_HITS);
		}
		if (bm25TitleMinimumTerms < MIN_BM25_TITLE_TERMS || bm25TitleMinimumTerms > MAX_BM25_TITLE_TERMS) {
			throw new IllegalArgumentException(
				"bm25TitleMinimumTerms must be between " + MIN_BM25_TITLE_TERMS + " and " + MAX_BM25_TITLE_TERMS
			);
		}
		if (!Double.isFinite(bm25TitleAmbiguityRatio)
			|| bm25TitleAmbiguityRatio < 0.0
			|| bm25TitleAmbiguityRatio > MAX_BM25_TITLE_AMBIGUITY_RATIO) {
			throw new IllegalArgumentException(
				"bm25TitleAmbiguityRatio must be between 0.0 and " + MAX_BM25_TITLE_AMBIGUITY_RATIO
			);
		}
	}

	public LawAiDocumentExpansionProperties(
		boolean enabled,
		boolean authoritative,
		int maxDocuments,
		int maxChunksPerDocument,
		int maxTotalChunks
	) {
		this(enabled, authoritative, maxDocuments, maxChunksPerDocument, maxTotalChunks, false, 100, 2, 0.05);
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

	public Bm25TitleDocumentSeedSelector.Policy bm25TitlePolicy() {
		return new Bm25TitleDocumentSeedSelector.Policy(
			bm25TitleEnabled, bm25TitleMaxHits, bm25TitleMinimumTerms, bm25TitleAmbiguityRatio, maxDocuments
		);
	}
}
