package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.rag.common.HwpxTextCleaner;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ParentContextAssembler {
	private static final int CHILD_LIMIT = 1_200;
	private static final String NUMBERED_DUTY_TEXT =
		"([①-⑳]\\s*)([^①-⑳.!?\\r\\n]{1,120}다음\\s*각\\s*호의\\s*사항을\\s*명시하여야\\s*한다\\.)"
			+ "(\\s*1\\.\\s+[^①-⑳]{1,1050}?)";
	private static final Pattern NUMBERED_DUTY = Pattern.compile(
		NUMBERED_DUTY_TEXT + "(?=\\s*(?:제\\d+조(?:의\\d+)?\\s*\\(|[①-⑳]))");
	private static final Pattern EXACT_NUMBERED_DUTY = Pattern.compile(NUMBERED_DUTY_TEXT);
	private static final Pattern LIST_ITEM = Pattern.compile(
		"\\G\\s*(\\d{1,2})\\.\\s+([^.!?。①-⑳]{1,240}?)(?=\\d{1,2}\\.\\s|\\z)"
	);
	public List<LawAiAnswerGround> toGrounds(
		List<LawSemanticChunkRow> chunks,
		Map<String, LawSemanticChunkRow> matchedChunkByKey,
		Map<String, Double> scoreByChunkId,
		Function<LawSemanticChunkRow, String> snippetFactory,
		String evidenceRole,
		Map<String, List<Long>> contextChunkIdsByKey
	) {
		return assemble(chunks, matchedChunkByKey, scoreByChunkId, snippetFactory, evidenceRole, contextChunkIdsByKey);
	}

	public List<LawAiAnswerGround> toGrounds(
		List<LawSemanticChunkRow> chunks,
		Map<String, LawSemanticChunkRow> matchedChunkByKey,
		Map<String, Double> scoreByChunkId,
		Function<LawSemanticChunkRow, String> snippetFactory
	) {
		return toGrounds(chunks, matchedChunkByKey, scoreByChunkId, snippetFactory, "direct");
	}

	public List<LawAiAnswerGround> toGrounds(
		List<LawSemanticChunkRow> chunks,
		Map<String, LawSemanticChunkRow> matchedChunkByKey,
		Map<String, Double> scoreByChunkId,
		Function<LawSemanticChunkRow, String> snippetFactory,
		String evidenceRole
	) {
		return assemble(chunks, matchedChunkByKey, scoreByChunkId, snippetFactory, evidenceRole, Map.of());
	}

	private List<LawAiAnswerGround> assemble(
		List<LawSemanticChunkRow> chunks,
		Map<String, LawSemanticChunkRow> matchedChunkByKey,
		Map<String, Double> scoreByChunkId,
		Function<LawSemanticChunkRow, String> snippetFactory,
		String evidenceRole,
		Map<String, List<Long>> contextChunkIdsByKey
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
				snippetFactory,
				evidenceRole,
				contextChunkIdsByKey == null ? List.of()
					: contextChunkIdsByKey.getOrDefault(scoreKey(chunk.target(), chunk.chunkId()), List.of())
			))
			.toList();
	}

	private LawAiAnswerGround toGround(
		int number,
		LawSemanticChunkRow chunk,
		LawSemanticChunkRow matchedChunk,
		Map<String, Double> scoreByChunkId,
		Function<LawSemanticChunkRow, String> snippetFactory,
		String evidenceRole,
		List<Long> explicitContextChunkIds
	) {
		String displayKey = scoreKey(chunk.target(), chunk.chunkId());
		String matchedKey = matchedChunk == null ? displayKey : scoreKey(matchedChunk.target(), matchedChunk.chunkId());
		String matchedChildText = matchedChunk == null ? chunk.chunkText() : matchedChunk.chunkText();
		String parentContextText = sameNormalizedText(matchedChildText, chunk.chunkText()) ? null : chunk.chunkText();
		String contextPolicy = parentContextText == null ? "matched_child_only" : "parent_context_expanded";
        boolean literalLegalProof=("law".equals(chunk.target()) || "admrul".equals(chunk.target()))
            && SourceOwnedConditionalScope.original(0,matchedChildText).isPresent();
		String snippet = snippetFactory == null ? "" : snippetFactory.apply(chunk);
		String childProof = !literalLegalProof && ("law".equals(chunk.target()) || "admrul".equals(chunk.target()))
			? selectedNumberedDuty(matchedChildText, snippet) : null;
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
			snippet,
			chunk.sourcePath(),
			chunk.sourceUrl(),
			score(scoreByChunkId, matchedKey, displayKey),
			childProof == null ? limitText(literalLegalProof?matchedChildText:cleanDisplayText(matchedChildText), CHILD_LIMIT) : childProof,
			parentContextText == null ? null : limitText(cleanDisplayText(parentContextText), 2_800),
			contextChunkIds(chunk, matchedChunk, parentContextText == null ? List.of() : explicitContextChunkIds),
			contextPolicy,
			normalizeEvidenceRole(evidenceRole)
		);
	}

	private static String selectedNumberedDuty(String child, String citation) {
		if (child == null || child.length() <= CHILD_LIMIT || citation == null || citation.isBlank()) {
			return null;
		}
		var paragraphs = NUMBERED_DUTY.matcher(child);
		String selected = null;
		String compactCitation = citation.replaceAll("\\s+", "");
		while (paragraphs.find()) {
			String paragraph = paragraphs.group().stripTrailing();
			if (paragraphs.end() <= CHILD_LIMIT || paragraph.length() > CHILD_LIMIT
				|| !compactCitation.contains((paragraphs.group(2) + paragraphs.group(3)).replaceAll("\\s+", ""))) {
				continue;
			}
			if (!consecutiveItems(paragraphs.group(3))) {
				continue;
			}
			if (selected != null) {
				return null;
			}
			selected = paragraph;
		}
		return selected;
	}

	static List<String> exactNumberedDutyParts(String body) {
		if (body == null || body.length() > CHILD_LIMIT) return List.of();
		var paragraph = EXACT_NUMBERED_DUTY.matcher(body.strip());
		if (!paragraph.matches() || !consecutiveItems(paragraph.group(3))) return List.of();
		return List.of(paragraph.group(2).strip(), paragraph.group(3).strip().replaceFirst("^1\\.\\s+", ""));
	}

	private static boolean consecutiveItems(String text) {
		String list = text.strip();
		var items = LIST_ITEM.matcher(list);
		int expected = 1;
		int end = 0;
		while (items.find() && Integer.parseInt(items.group(1)) == expected && !items.group(2).isBlank()) {
			expected++;
			end = items.end();
		}
		return expected >= 3 && end == list.length();
	}

	private String normalizeEvidenceRole(String role) {
		return "related_definition".equals(role) ? "related_definition" : "direct";
	}

	private double score(Map<String, Double> scoreByChunkId, String matchedKey, String displayKey) {
		if (scoreByChunkId == null || scoreByChunkId.isEmpty()) {
			return 0.0;
		}
		Double matchedScore = scoreByChunkId.get(matchedKey);
		if (matchedScore != null) {
			return matchedScore;
		}
		return scoreByChunkId.getOrDefault(displayKey, 0.0);
	}

	private List<Long> contextChunkIds(LawSemanticChunkRow chunk, LawSemanticChunkRow matchedChunk, List<Long> explicitIds) {
		List<Long> originalIds = matchedChunk == null || matchedChunk.chunkId() == chunk.chunkId()
			? List.of(chunk.chunkId()) : List.of(matchedChunk.chunkId(), chunk.chunkId());
		return java.util.stream.Stream.concat(originalIds.stream(), explicitIds == null ? java.util.stream.Stream.empty() : explicitIds.stream())
			.filter(id -> id != null && id > 0).distinct().toList();
	}

	private boolean sameNormalizedText(String left, String right) {
		return normalizeForMatch(left).equals(normalizeForMatch(right));
	}

	private String scoreKey(String target, long chunkId) {
		return target + ":" + chunkId;
	}

	private String cleanDisplayText(String text) {
		return cleanDisplayTextForGround(text);
	}

	static String cleanDisplayTextForGround(String text) {
		return HwpxTextCleaner.clean(text)
			.replace('\u0007', ' ')
			.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]+", " ")
			.replace("\r\n", "\n")
			.replace('\r', '\n')
			.replaceAll("[^\\S\\r\\n]+", " ")
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
