package com.kaces.pandora.rag.search;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RagChunkSearchTermExtractor {

	public List<RagChunkSearchTermRow> extract(LawSemanticChunkRow chunk) {
		if (chunk == null || chunk.chunkId() <= 0) {
			return List.of();
		}
		Map<String, RagChunkSearchTermRow> terms = new LinkedHashMap<>();
		addTerms(terms, chunk, chunk.parentSectionTitle(), "parent_title", 6);
		addTerms(terms, chunk, chunk.chunkTitle(), "chunk_title", 7);
		addTerms(terms, chunk, chunk.chunkText(), "body", 4);
		return List.copyOf(terms.values());
	}

	private void addTerms(
		Map<String, RagChunkSearchTermRow> terms,
		LawSemanticChunkRow chunk,
		String value,
		String fieldKind,
		int weight
	) {
		for (String raw : tokenize(value)) {
			String term = KoreanQueryNormalizer.normalizeQueryTerm(raw);
			if (!isIndexable(term)) {
				continue;
			}
			RagChunkSearchTermRow candidate = new RagChunkSearchTermRow(
				chunk.chunkId(),
				chunk.documentId(),
				term,
				fieldKind,
				weight
			);
			terms.merge(term, candidate, (left, right) -> left.weight() >= right.weight() ? left : right);
		}
	}

	private List<String> tokenize(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}
		List<String> tokens = new ArrayList<>();
		for (String token : value.split("[^\\p{IsHangul}\\p{Alnum}]+")) {
			if (!token.isBlank()) {
				tokens.add(token);
			}
		}
		return tokens;
	}

	private boolean isIndexable(String term) {
		if (term == null || term.length() < 2 || term.length() > 80) {
			return false;
		}
		if (KoreanQueryNormalizer.isWeakQuestionTerm(term)) {
			return false;
		}
		return !term.endsWith("합니다")
			&& !term.endsWith("됩니다")
			&& !term.endsWith("입니다")
			&& !term.endsWith("있습니다")
			&& !term.endsWith("없습니다");
	}
}
