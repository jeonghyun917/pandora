package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Removes duplicate candidate rows while preserving the caller's ranking order. */
@Component
public class EvidenceCandidateDiversifier {

	private static final int TEXT_KEY_LIMIT = 140;

	public List<LawSemanticChunkRow> diversify(List<LawSemanticChunkRow> chunks, int limit) {
		if (chunks == null || chunks.isEmpty() || limit <= 0) {
			return List.of();
		}
		List<LawSemanticChunkRow> selected = new ArrayList<>(Math.min(chunks.size(), limit));
		Set<String> exactKeys = new HashSet<>();
		Set<String> textKeys = new HashSet<>();
		for (LawSemanticChunkRow chunk : chunks) {
			if (chunk == null || selected.size() >= limit) {
				continue;
			}
			String exactKey = exactKey(chunk);
			String textKey = textKey(chunk);
			if (exactKeys.contains(exactKey) || textKeys.contains(textKey)) {
				continue;
			}
			selected.add(chunk);
			exactKeys.add(exactKey);
			textKeys.add(textKey);
		}
		return List.copyOf(selected);
	}

	private String exactKey(LawSemanticChunkRow chunk) {
		String page = chunk.pageNo() == null ? "" : String.valueOf(chunk.pageNo());
		return String.join("|",
			nullToEmpty(chunk.target()),
			String.valueOf(chunk.documentId()),
			normalize(chunk.title()),
			normalize(chunk.chunkNo()),
			page
		);
	}

	private String textKey(LawSemanticChunkRow chunk) {
		String normalized = normalize(chunk.chunkText());
		return normalized.length() <= TEXT_KEY_LIMIT ? normalized : normalized.substring(0, TEXT_KEY_LIMIT);
	}

	private String normalize(String value) {
		return KoreanQueryNormalizer.normalizeForMatch(nullToEmpty(value));
	}

	private String nullToEmpty(Object value) {
		return value == null ? "" : String.valueOf(value);
	}
}
