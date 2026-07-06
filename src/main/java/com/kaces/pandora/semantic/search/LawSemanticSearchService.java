package com.kaces.pandora.semantic.search;


import com.kaces.pandora.infra.openai.OpenAiEmbeddingClient;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.rag.common.HwpxTextCleaner;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.lawdata.search.LawSearchQuery;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class LawSemanticSearchService {

	private final LawChunkMapper lawChunkMapper;
	private final RagDocumentMapper ragDocumentMapper;
	private final OpenAiEmbeddingClient embeddingClient;
	private final QdrantClient qdrantClient;

	public LawSemanticSearchService(
		LawChunkMapper lawChunkMapper,
		RagDocumentMapper ragDocumentMapper,
		OpenAiEmbeddingClient embeddingClient,
		QdrantClient qdrantClient
	) {
		this.lawChunkMapper = lawChunkMapper;
		this.ragDocumentMapper = ragDocumentMapper;
		this.embeddingClient = embeddingClient;
		this.qdrantClient = qdrantClient;
	}

	// 메소드 설명: search 처리 흐름을 수행합니다.
	public Map<String, LawSemanticSearchResponse> search(String target, String query, int limit) {
		return search(target, query, limit, true);
	}

	// 메소드 설명: search 처리 흐름을 수행합니다.
	public Map<String, LawSemanticSearchResponse> search(String target, String query, int limit, boolean includeFuture) {
		LawSearchQuery normalized = LawSearchQuery.normalize(target, query, 1, Math.max(1, Math.min(limit, 50)), false, includeFuture);
		if (normalized.searchAll()) {
			return Map.of("SemanticSearch", new LawSemanticSearchResponse("00", "EMPTY_QUERY", normalized.target(), normalized.query(), 0, List.of()));
		}

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		List<Double> queryVector = embeddingClient.embed(List.of(normalized.query())).get(0);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		List<QdrantSearchHit> hits = qdrantClient.search(queryVector, searchTargets(normalized.target()), normalized.display());
		if (hits.isEmpty()) {
			return Map.of("SemanticSearch", new LawSemanticSearchResponse("00", "QDRANT", normalized.target(), normalized.query(), 0, List.of()));
		}

		Map<String, Double> scoreByChunkId = new HashMap<>();
		for (QdrantSearchHit hit : hits) {
			scoreByChunkId.put(scoreKey(hit.target(), hit.chunkId()), hit.score());
		}
		Map<String, LawSemanticChunkRow> chunkById = new HashMap<>();
		List<Long> lawChunkIds = hits.stream().filter(hit -> isLawTarget(hit.target())).map(QdrantSearchHit::chunkId).distinct().toList();
		if (!lawChunkIds.isEmpty()) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			for (LawSemanticChunkRow chunk : lawChunkMapper.findSemanticChunksByIds(lawChunkIds, normalized.includeFuture())) {
				chunkById.put(scoreKey(chunk.target(), chunk.chunkId()), chunk);
			}
		}
		List<Long> ragChunkIds = hits.stream().filter(hit -> isRagTarget(hit.target())).map(QdrantSearchHit::chunkId).distinct().toList();
		if (!ragChunkIds.isEmpty()) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			for (LawSemanticChunkRow chunk : ragDocumentMapper.findSemanticChunksByIds(ragChunkIds)) {
				chunkById.put(scoreKey(chunk.target(), chunk.chunkId()), chunk);
			}
		}

		List<LawSemanticSearchItem> items = hits.stream()
			.map(hit -> chunkById.get(scoreKey(hit.target(), hit.chunkId())))
			.filter(chunk -> chunk != null)
			.map(chunk -> toItem(chunk, scoreByChunkId.getOrDefault(scoreKey(chunk.target(), chunk.chunkId()), 0.0)))
			.limit(normalized.display())
			.toList();

		return Map.of("SemanticSearch", new LawSemanticSearchResponse("00", "QDRANT", normalized.target(), normalized.query(), items.size(), items));
	}

	// 메소드 설명: toItem 처리 흐름을 수행합니다.
	private LawSemanticSearchItem toItem(LawSemanticChunkRow chunk, double score) {
		return new LawSemanticSearchItem(
			chunk.chunkId(),
			chunk.documentId(),
			chunk.target(),
			chunk.title(),
			chunk.agencyName(),
			chunk.categoryName(),
			chunk.sourceDate(),
			chunk.chunkNo(),
			chunk.chunkTitle(),
			snippet(chunk.chunkText()),
			chunk.sourcePath(),
			score
		);
	}

	// 메소드 설명: snippet 처리 흐름을 수행합니다.
	private String snippet(String text) {
		String cleaned = HwpxTextCleaner.clean(text);
		if (cleaned.length() <= 260) {
			return cleaned;
		}
		return cleaned.substring(0, 260) + "...";
	}

	// 메소드 설명: searchTargets 처리 흐름을 수행합니다.
	private List<String> searchTargets(String requestedTarget) {
		if (requestedTarget == null || requestedTarget.isBlank() || "law".equals(requestedTarget)) {
			return List.of("law", "admrul", "official_doc", "internal_doc");
		}
		return List.of(requestedTarget);
	}

	// 메소드 설명: isLawTarget 처리 흐름을 수행합니다.
	private boolean isLawTarget(String target) {
		return "law".equals(target) || "admrul".equals(target);
	}

	// 메소드 설명: isRagTarget 처리 흐름을 수행합니다.
	private boolean isRagTarget(String target) {
		return "official_doc".equals(target) || "internal_doc".equals(target) || "reference_doc".equals(target);
	}

	// 메소드 설명: scoreKey 처리 흐름을 수행합니다.
	private String scoreKey(String target, long chunkId) {
		return target + ":" + chunkId;
	}
}
