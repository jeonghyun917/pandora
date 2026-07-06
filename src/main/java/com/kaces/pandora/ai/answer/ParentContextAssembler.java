package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.rag.common.HwpxTextCleaner;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class ParentContextAssembler {

	public List<LawAiAnswerGround> toGrounds(
		List<LawSemanticChunkRow> chunks,
		Map<String, LawSemanticChunkRow> matchedChunkByKey,
		Map<String, Double> scoreByChunkId,
		Function<LawSemanticChunkRow, String> snippetFactory
	) {
		if (chunks == null || chunks.isEmpty()) {
			return List.of();
		}
		int[] number = {1};
		return chunks.stream()
			.map(chunk -> toGround(
				number[0]++,
				chunk,
				matchedChunkByKey == null ? null : matchedChunkByKey.get(scoreKey(chunk.target(), chunk.chunkId())),
				scoreByChunkId,
				snippetFactory
			))
			.toList();
	}

	private LawAiAnswerGround toGround(
		int number,
		LawSemanticChunkRow chunk,
		LawSemanticChunkRow matchedChunk,
		Map<String, Double> scoreByChunkId,
		Function<LawSemanticChunkRow, String> snippetFactory
	) {
		String key = scoreKey(chunk.target(), chunk.chunkId());
		String matchedChildText = matchedChunk == null ? chunk.chunkText() : matchedChunk.chunkText();
		String parentContextText = sameNormalizedText(matchedChildText, chunk.chunkText()) ? null : chunk.chunkText();
		String contextPolicy = parentContextText == null ? "matched_child_only" : "parent_context_expanded";
		return new LawAiAnswerGround(
			number,
			chunk.chunkId(),
			chunk.documentId(),
			chunk.target(),
			chunk.title(),
			chunk.agencyName(),
			chunk.categoryName(),
			chunk.sourceDate(),
			chunk.effectiveStatus(),
			chunk.chunkNo(),
			cleanHwpxText(chunk.chunkTitle()),
			chunk.pageNo(),
			snippetFactory == null ? "" : snippetFactory.apply(chunk),
			chunk.sourcePath(),
			chunk.sourceUrl(),
			scoreByChunkId == null ? 0.0 : scoreByChunkId.getOrDefault(key, 0.0),
			limitText(cleanDisplayText(matchedChildText), 1_200),
			parentContextText == null ? null : limitText(cleanDisplayText(parentContextText), 2_800),
			List.of(chunk.chunkId()),
			contextPolicy
		);
	}

	private boolean sameNormalizedText(String left, String right) {
		return normalizeForMatch(left).equals(normalizeForMatch(right));
	}

	private String scoreKey(String target, long chunkId) {
		return target + ":" + chunkId;
	}

	private String cleanDisplayText(String text) {
		return cleanHwpxText(text)
			.replace('\u0007', ' ')
			.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]+", " ")
			.replaceAll("\\s+", " ")
			.replace("소프트웨어사 업", "소프트웨어사업")
			.replace("과 학기술", "과학기술")
			.replaceAll("([가-힣])\\s+(을|를|은|는|이|가|의|에|와|과|로|으로|도|만|부터|까지|에서|에게|보다)(?=\\s|$)", "$1$2")
			.trim();
	}

	private String cleanHwpxText(String value) {
		return HwpxTextCleaner.clean(value);
	}

	private String limitText(String text, int limit) {
		if (text == null || text.length() <= limit) {
			return text;
		}
		return text.substring(0, Math.max(0, limit - 3)).trim() + "...";
	}

	private String normalizeForMatch(String value) {
		return KoreanQueryNormalizer.normalizeForMatch(value);
	}
}
