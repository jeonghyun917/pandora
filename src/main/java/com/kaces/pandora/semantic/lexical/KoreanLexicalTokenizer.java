package com.kaces.pandora.semantic.lexical;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import java.text.Normalizer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class KoreanLexicalTokenizer {

	public static final String VERSION = "korean-lexical-v1";

	public String version() {
		return VERSION;
	}

	public Map<String, Integer> tokenize(String value) {
		if (value == null || value.isBlank()) {
			return Map.of();
		}
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
			.toLowerCase(Locale.ROOT)
			.replaceAll("(?<=\\d),(?=\\d)", "");
		Map<String, Integer> frequencies = new LinkedHashMap<>();
		for (String raw : normalized.split("[^\\p{IsHangul}\\p{L}\\p{N}]+")) {
			String term = KoreanQueryNormalizer.normalizeQueryTerm(raw);
			if (!isIndexable(term)) {
				continue;
			}
			frequencies.merge(term, 1, Integer::sum);
		}
		return Collections.unmodifiableMap(frequencies);
	}

	private boolean isIndexable(String term) {
		return term != null
			&& term.length() >= 2
			&& term.length() <= 80
			&& !KoreanQueryNormalizer.isWeakQuestionTerm(term);
	}
}
