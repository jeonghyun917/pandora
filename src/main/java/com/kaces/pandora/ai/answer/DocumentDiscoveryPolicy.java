package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import com.kaces.pandora.common.text.QuestionIntentProfile;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DocumentDiscoveryPolicy {

	private DocumentDiscoveryPolicy() {
	}

	static double scoreBoost(String question, String target) {
		if (!QuestionIntentProfile.from(question).documentDiscoveryQuestion()) {
			return 0.0;
		}
		return switch (targetPriority(question, target)) {
			case 0 -> 1.2;
			case 1 -> 0.55;
			case 2 -> 0.15;
			default -> 0.0;
		};
	}

	static List<LawAiAnswerGround> orderGrounds(
		String question,
		List<LawAiAnswerGround> grounds
	) {
		List<LawAiAnswerGround> safeGrounds = grounds == null
			? List.of()
			: grounds.stream().filter(ground -> ground != null).toList();
		if (safeGrounds.isEmpty()
			|| !QuestionIntentProfile.from(question).documentDiscoveryQuestion()) {
			return List.copyOf(safeGrounds);
		}

		List<LawAiAnswerGround> ordered = safeGrounds.stream()
			.sorted(Comparator
				.comparingInt((LawAiAnswerGround ground) -> targetPriority(question, ground.target()))
				.thenComparing(Comparator.comparingDouble(LawAiAnswerGround::score).reversed())
				.thenComparingInt(LawAiAnswerGround::number))
			.toList();
		LinkedHashMap<String, LawAiAnswerGround> uniqueDocuments = new LinkedHashMap<>();
		for (LawAiAnswerGround ground : ordered) {
			uniqueDocuments.putIfAbsent(documentKey(ground), ground);
		}
		ArrayList<LawAiAnswerGround> renumbered = new ArrayList<>();
		int number = 1;
		for (LawAiAnswerGround ground : uniqueDocuments.values()) {
			renumbered.add(withNumber(ground, number++));
		}
		return List.copyOf(renumbered);
	}

	static List<LawSemanticChunkRow> orderChunks(
		String question,
		List<LawSemanticChunkRow> chunks,
		Map<String, Double> scoreByChunkId
	) {
		List<LawSemanticChunkRow> safeChunks = chunks == null
			? List.of()
			: chunks.stream().filter(chunk -> chunk != null).toList();
		if (safeChunks.isEmpty()
			|| !QuestionIntentProfile.from(question).documentDiscoveryQuestion()) {
			return List.copyOf(safeChunks);
		}
		Map<String, Double> safeScores = scoreByChunkId == null ? Map.of() : scoreByChunkId;
		List<LawSemanticChunkRow> ordered = safeChunks.stream()
			.sorted(Comparator
				.comparingInt((LawSemanticChunkRow chunk) -> targetPriority(question, chunk.target()))
				.thenComparing(Comparator.comparingDouble(
					(LawSemanticChunkRow chunk) -> safeScores.getOrDefault(chunkKey(chunk), 0.0)
				).reversed())
				.thenComparingLong(LawSemanticChunkRow::chunkId))
			.toList();
		LinkedHashMap<String, LawSemanticChunkRow> uniqueDocuments = new LinkedHashMap<>();
		for (LawSemanticChunkRow chunk : ordered) {
			uniqueDocuments.putIfAbsent(documentKey(chunk), chunk);
		}
		return List.copyOf(uniqueDocuments.values());
	}

	static List<LawSemanticChunkRow> preserveHeadingCandidates(
		String question,
		List<LawSemanticChunkRow> judgedChunks,
		List<LawSemanticChunkRow> lexicalChunks
	) {
		QuestionIntentProfile profile = QuestionIntentProfile.from(question);
		List<LawSemanticChunkRow> safeJudged = judgedChunks == null
			? List.of()
			: judgedChunks.stream().filter(chunk -> chunk != null).toList();
		List<List<String>> entityAliasGroups = profile.entities().stream()
			.map(entity -> entity.aliases())
			.filter(aliases -> aliases != null && !aliases.isEmpty())
			.toList();
		if (!profile.documentDiscoveryQuestion()
			|| lexicalChunks == null
			|| lexicalChunks.isEmpty()
			|| entityAliasGroups.isEmpty()) {
			return List.copyOf(safeJudged);
		}
		List<LawSemanticChunkRow> headingMatches = lexicalChunks.stream()
			.filter(chunk -> chunk != null)
			.filter(chunk -> matchesConfiguredHeading(chunk, entityAliasGroups))
			.toList();
		if (headingMatches.isEmpty()) {
			return List.copyOf(safeJudged);
		}
		LinkedHashMap<String, LawSemanticChunkRow> preserved = new LinkedHashMap<>();
		for (LawSemanticChunkRow chunk : headingMatches) {
			preserved.putIfAbsent(chunkKey(chunk), chunk);
		}
		for (LawSemanticChunkRow chunk : safeJudged) {
			preserved.putIfAbsent(chunkKey(chunk), chunk);
		}
		return List.copyOf(preserved.values());
	}

	static String sourceLabel(String target) {
		return switch (String.valueOf(target == null ? "" : target)) {
			case "law" -> "법령";
			case "admrul" -> "행정규칙";
			case "official_doc" -> "공식 문서";
			case "internal_doc" -> "내부 문서";
			case "reference_doc" -> "참고자료";
			default -> "문서";
		};
	}

	private static int targetPriority(String question, String target) {
		String normalized = KoreanQueryNormalizer.normalizeForMatch(question);
		String safeTarget = String.valueOf(target == null ? "" : target);
		if (containsAny(normalized, "행정규칙", "규정")) {
			return switch (safeTarget) {
				case "admrul" -> 0;
				case "law" -> 1;
				case "official_doc" -> 2;
				case "internal_doc" -> 3;
				case "reference_doc" -> 4;
				default -> 5;
			};
		}
		if (containsAny(normalized, "가이드라인", "가이드", "안내서", "해설서", "매뉴얼", "자료", "문서")) {
			return switch (safeTarget) {
				case "official_doc" -> 0;
				case "internal_doc" -> 1;
				case "reference_doc" -> 2;
				case "law" -> 3;
				case "admrul" -> 4;
				default -> 5;
			};
		}
		return switch (safeTarget) {
			case "law" -> 0;
			case "admrul" -> 1;
			case "official_doc" -> 2;
			case "internal_doc" -> 3;
			case "reference_doc" -> 4;
			default -> 5;
		};
	}

	private static boolean containsAny(String value, String... candidates) {
		for (String candidate : candidates) {
			if (value.contains(KoreanQueryNormalizer.normalizeForMatch(candidate))) {
				return true;
			}
		}
		return false;
	}

	private static String documentKey(LawAiAnswerGround ground) {
		String target = String.valueOf(ground.target() == null ? "" : ground.target());
		if (ground.documentId() > 0) {
			return target + ":" + ground.documentId();
		}
		return target + ":" + KoreanQueryNormalizer.normalizeForMatch(ground.title());
	}

	private static String documentKey(LawSemanticChunkRow chunk) {
		String target = String.valueOf(chunk.target() == null ? "" : chunk.target());
		if (chunk.documentId() > 0) {
			return target + ":" + chunk.documentId();
		}
		return target + ":" + KoreanQueryNormalizer.normalizeForMatch(chunk.title());
	}

	private static String chunkKey(LawSemanticChunkRow chunk) {
		return String.valueOf(chunk.target() == null ? "" : chunk.target()) + ":" + chunk.chunkId();
	}

	private static boolean matchesConfiguredHeading(
		LawSemanticChunkRow chunk,
		List<List<String>> anchorGroups
	) {
		String headingText = KoreanQueryNormalizer.normalizeForMatch(
			String.join(" ",
				String.valueOf(chunk.title() == null ? "" : chunk.title()),
				String.valueOf(chunk.chunkTitle() == null ? "" : chunk.chunkTitle()),
				String.valueOf(chunk.parentSectionTitle() == null ? "" : chunk.parentSectionTitle())
			)
		);
		return anchorGroups.stream()
			.flatMap(List::stream)
			.filter(alias -> alias != null && !alias.isBlank())
			.map(KoreanQueryNormalizer::normalizeForMatch)
			.filter(alias -> !alias.isBlank())
			.anyMatch(headingText::contains);
	}

	private static LawAiAnswerGround withNumber(LawAiAnswerGround ground, int number) {
		return new LawAiAnswerGround(
			number,
			ground.chunkId(),
			ground.documentId(),
			ground.target(),
			ground.title(),
			ground.agencyName(),
			ground.categoryName(),
			ground.sourceDate(),
			ground.effectiveStatus(),
			ground.chunkNo(),
			ground.chunkTitle(),
			ground.pageNo(),
			ground.snippet(),
			ground.sourcePath(),
			ground.sourceUrl(),
			ground.score(),
			ground.matchedChildText(),
			ground.parentContextText(),
			ground.contextChunkIds(),
			ground.contextPolicy(),
			ground.evidenceRole()
		);
	}
}
