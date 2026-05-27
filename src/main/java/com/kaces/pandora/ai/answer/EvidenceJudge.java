package com.kaces.pandora.ai.answer;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.rag.common.HwpxTextCleaner;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class EvidenceJudge {

	private static final int MIN_RELEVANT_RESULTS = 2;

	public Result judge(
		String question,
		List<LawSemanticChunkRow> chunks,
		Map<String, Double> baseScoreByChunkId,
		int requestedLimit
	) {
		if (chunks == null || chunks.isEmpty()) {
			return new Result(List.of(), Map.of(), false, false);
		}

		QuestionProfile profile = QuestionProfile.from(question);
		List<JudgedChunk> judgedChunks = chunks.stream()
			.map(chunk -> judgeChunk(profile, chunk, baseScoreByChunkId.getOrDefault(scoreKey(chunk), 0.0)))
			.sorted(Comparator.comparingDouble(JudgedChunk::score).reversed())
			.toList();

		List<JudgedChunk> relevantChunks = judgedChunks.stream()
			.filter(JudgedChunk::relevant)
			.toList();
		List<JudgedChunk> directEvidenceChunks = judgedChunks.stream()
			.filter(JudgedChunk::directEvidence)
			.toList();
		boolean directEvidenceRequired = !profile.directEvidenceGroups().isEmpty();
		List<JudgedChunk> directSupportingChunks = judgedChunks.stream()
			.filter(chunk -> chunk.directEvidenceMatches() > 0)
			.toList();
		boolean directEvidenceFound = !directEvidenceChunks.isEmpty()
			|| countCoveredGroups(directSupportingChunks, profile.directEvidenceGroups()) >= profile.requiredDirectEvidenceMatches();
		boolean useRelevantOnly = relevantChunks.size() >= Math.min(MIN_RELEVANT_RESULTS, Math.max(1, requestedLimit));
		List<JudgedChunk> selectedChunks = directEvidenceRequired
			? !directEvidenceChunks.isEmpty() ? directEvidenceChunks : directEvidenceFound ? directSupportingChunks : List.of()
			: !profile.conceptGroups().isEmpty() && !relevantChunks.isEmpty() ? relevantChunks
			: useRelevantOnly ? relevantChunks : judgedChunks;

		Map<String, Double> scoreByChunkId = new HashMap<>(baseScoreByChunkId);
		for (JudgedChunk judgedChunk : judgedChunks) {
			scoreByChunkId.put(scoreKey(judgedChunk.chunk()), judgedChunk.score());
		}

		return new Result(
			selectedChunks.stream().map(JudgedChunk::chunk).toList(),
			scoreByChunkId,
			directEvidenceRequired,
			directEvidenceFound
		);
	}

	// 메소드 설명: judgeChunk 처리 흐름을 수행합니다.
	private JudgedChunk judgeChunk(QuestionProfile profile, LawSemanticChunkRow chunk, double baseScore) {
		String body = normalize(chunk.chunkText());
		String title = normalize(chunk.title() + " " + chunk.chunkTitle());
		String text = title + body;

		int conceptMatches = countMatchedGroups(text, profile.conceptGroups());
		int intentMatches = countMatchedGroups(text, profile.intentGroups());
		int directEvidenceMatches = countMatchedGroups(text, profile.directEvidenceGroups());
		int termMatches = countMatchedTerms(text, profile.terms());
		int titleMatches = countMatchedTerms(title, profile.terms());

		boolean conceptOk = profile.conceptGroups().isEmpty()
			|| conceptMatches >= profile.requiredConceptMatches();
		boolean directEvidence = !profile.directEvidenceGroups().isEmpty()
			&& directEvidenceMatches >= profile.requiredDirectEvidenceMatches()
			&& conceptOk;
		boolean intentOk = profile.intentGroups().isEmpty()
			|| intentMatches > 0
			|| termMatches >= Math.min(3, profile.terms().size());
		boolean hasAnySignal = termMatches > 0 || conceptMatches + intentMatches >= 2;
		boolean relevant = directEvidence || (conceptOk && intentOk && hasAnySignal);

		double score = baseScore
			+ (directEvidenceMatches * 0.72)
			+ (conceptMatches * 0.26)
			+ (intentMatches * 0.18)
			+ (termMatches * 0.035)
			+ (titleMatches * 0.025);

		if (!conceptOk && !profile.conceptGroups().isEmpty()) {
			score -= 0.55;
		}
		if (!intentOk && !profile.intentGroups().isEmpty()) {
			score -= 0.25;
		}
		if (body.length() < 80) {
			score -= 0.04;
		}

		return new JudgedChunk(chunk, score, relevant, directEvidence, directEvidenceMatches);
	}

	// 메소드 설명: countCoveredGroups 처리 흐름을 수행합니다.
	private int countCoveredGroups(List<JudgedChunk> chunks, List<List<String>> groups) {
		int count = 0;
		for (List<String> group : groups) {
			boolean covered = chunks.stream()
				.anyMatch(chunk -> containsAny(normalize(chunk.chunk().title() + " " + chunk.chunk().chunkTitle() + " " + chunk.chunk().chunkText()), group));
			if (covered) {
				count++;
			}
		}
		return count;
	}

	// 메소드 설명: countMatchedGroups 처리 흐름을 수행합니다.
	private int countMatchedGroups(String text, List<List<String>> groups) {
		int count = 0;
		for (List<String> group : groups) {
			if (containsAny(text, group)) {
				count++;
			}
		}
		return count;
	}

	// 메소드 설명: countMatchedTerms 처리 흐름을 수행합니다.
	private int countMatchedTerms(String text, List<String> terms) {
		int count = 0;
		for (String term : terms) {
			if (text.contains(term)) {
				count++;
			}
		}
		return count;
	}

	// 메소드 설명: containsAny 처리 흐름을 수행합니다.
	private boolean containsAny(String text, List<String> values) {
		for (String value : values) {
			if (text.contains(normalize(value))) {
				return true;
			}
		}
		return false;
	}

	// 메소드 설명: scoreKey 처리 흐름을 수행합니다.
	private static String scoreKey(LawSemanticChunkRow chunk) {
		return chunk.target() + ":" + chunk.chunkId();
	}

	// 메소드 설명: queryTerms 처리 흐름을 수행합니다.
	private static List<String> queryTerms(String question) {
		List<String> terms = new ArrayList<>();
		for (String token : String.valueOf(question).split("\\s+")) {
			String term = stripTrailingJosa(normalize(token));
			if (term.length() >= 2 && !isWeakTerm(term)) {
				terms.add(term);
			}
		}
		String compact = stripTrailingJosa(normalize(question));
		if (compact.length() >= 4 && !terms.contains(compact)) {
			terms.add(compact);
		}
		return terms.stream().distinct().toList();
	}

	// 메소드 설명: isWeakTerm 처리 흐름을 수행합니다.
	private static boolean isWeakTerm(String term) {
		return Set.of(
			"알려줘",
			"알수있어",
			"알수있나요",
			"어떻게",
			"어떤",
			"무엇",
			"뭐야",
			"있나",
			"있나요",
			"되나요",
			"있어",
			"관련",
			"대한"
		).contains(term);
	}

	// 메소드 설명: stripTrailingJosa 처리 흐름을 수행합니다.
	private static String stripTrailingJosa(String term) {
		if (term == null || term.length() < 3) {
			return term;
		}
		for (String suffix : List.of("으로", "에서", "에게", "까지", "부터", "하고", "하면", "은", "는", "이", "가", "을", "를", "에", "의", "와", "과", "도")) {
			if (term.endsWith(suffix) && term.length() > suffix.length() + 1) {
				return term.substring(0, term.length() - suffix.length());
			}
		}
		return term;
	}

	// 메소드 설명: normalize 처리 흐름을 수행합니다.
	private static String normalize(String value) {
		return HwpxTextCleaner.clean(String.valueOf(value == null ? "" : value))
			.replaceAll("[^\\p{IsHangul}\\p{Alnum}]", "")
			.toLowerCase();
	}

	public record Result(
		List<LawSemanticChunkRow> chunks,
		Map<String, Double> scoreByChunkId,
		boolean directEvidenceRequired,
		boolean directEvidenceFound
	) {
	}

	private record JudgedChunk(
		LawSemanticChunkRow chunk,
		double score,
		boolean relevant,
		boolean directEvidence,
		int directEvidenceMatches
	) {
	}

	private record QuestionProfile(
		List<String> terms,
		List<List<String>> conceptGroups,
		List<List<String>> intentGroups,
		List<List<String>> directEvidenceGroups
	) {

		static QuestionProfile from(String question) {
			String normalized = normalize(question);
			List<String> terms = queryTerms(question);
			List<List<String>> conceptGroups = new ArrayList<>();
			List<List<String>> intentGroups = new ArrayList<>();
			List<List<String>> directEvidenceGroups = new ArrayList<>();

			if (normalized.contains("사전협의")) {
				conceptGroups.add(List.of("사전협의"));
			}
			if (normalized.contains("과업심의")) {
				conceptGroups.add(List.of("과업심의", "과업내용", "과업범위", "대상사업", "소프트웨어사업", "sw사업"));
			}
			if (normalized.contains("제안요청서") || normalized.contains("rfp")) {
				conceptGroups.add(List.of("제안요청서", "rfp"));
			}
			if (normalized.contains("하드웨어") || normalized.contains("hw") || normalized.contains("appliance")) {
				conceptGroups.add(List.of("하드웨어", "hw", "appliance"));
			}
			if (normalized.contains("소프트웨어사업") || normalized.contains("sw사업") || normalized.contains("공공소프트웨어")) {
				conceptGroups.add(List.of("소프트웨어사업", "sw사업", "소프트웨어와관련된서비스", "소프트웨어진흥법"));
			}
			if (normalized.contains("정보화사업")) {
				conceptGroups.add(List.of("정보화사업", "정보시스템", "전자정부"));
			}
			if (normalized.contains("기타공공기관")) {
				conceptGroups.add(List.of("기타공공기관", "공공기관", "중앙공공기관", "대상기관"));
			}
			if (isSecurityReviewQuestion(normalized)) {
				conceptGroups.add(List.of("보안성검토", "보안성검토가이드", "정보화사업보안성검토", "국가정보보안기본지침"));
			}
			addSpecificConceptGroups(terms, conceptGroups);

			if (normalized.contains("대상")) {
				intentGroups.add(List.of("대상사업", "대상기관", "적용대상", "대상", "비대상", "제외"));
			}
			if (normalized.contains("포함") || normalized.contains("해당")) {
				intentGroups.add(List.of("포함", "해당", "비대상", "제외", "볼수없는"));
			}
			if (normalized.contains("필수") || normalized.contains("요소") || normalized.contains("항목")) {
				intentGroups.add(List.of("명시하여야", "기재사항", "필수", "과업내용", "요구사항", "계약조건", "평가요소"));
			}
			if (normalized.contains("절차") || normalized.contains("방법")) {
				intentGroups.add(List.of("절차", "방법", "신청", "제출", "검토", "통보"));
			}
			if (normalized.contains("서류")) {
				intentGroups.add(List.of("서류", "신청서", "제출서류", "첨부"));
			}
			if (normalized.contains("금액") || normalized.contains("비용")) {
				intentGroups.add(List.of("금액", "비용", "만원", "대가", "지급"));
			}
			if (normalized.contains("기한") || normalized.contains("기간")) {
				intentGroups.add(List.of("기한", "기간", "일이내", "까지"));
			}
			if (normalized.contains("정의") || normalized.contains("무엇")) {
				intentGroups.add(List.of("정의", "이란", "란"));
			}

			if (normalized.contains("사전협의") && normalized.contains("대상")) {
				directEvidenceGroups.add(List.of(
					"사전협의의 대상사업",
					"사전협의 대상사업",
					"전자정부 사전협의 대상 사업"
				));
				directEvidenceGroups.add(List.of(
					"대상기관이 추진하는 모든 정보화사업",
					"대상기관",
					"추진하는 모든 정보화사업",
					"중앙공공기관",
					"공공기관"
				));
			}
			if (normalized.contains("과업심의") && normalized.contains("대상")) {
				directEvidenceGroups.add(List.of(
					"적용 대상 사업",
					"대상사업 국가기관등의 장이 발주하는 소프트웨어사업",
					"대상사업",
					"대상 사업",
					"과업심의 대상",
					"과업심의대상"
				));
				directEvidenceGroups.add(List.of(
					"국가기관 등이 발주하는 모든 SW사업",
					"국가기관등이 발주하는 모든 SW사업",
					"국가기관등의 장이 발주하는 소프트웨어사업",
					"국가기관등의장이발주하는소프트웨어사업",
					"모든 SW사업",
					"SW개발, 제작, 생산, 유통, 운영 및 유지",
					"소프트웨어와 관련된 서비스"
				));
			}
			if (isSecurityReviewQuestion(normalized) && normalized.contains("대상")) {
				directEvidenceGroups.add(List.of(
					"대상사업및시기",
					"보안성검토대상",
					"보안성검토대상사업",
					"국가정보원검토대상",
					"문화체육관광부검토대상"
				));
				directEvidenceGroups.add(List.of(
					"정보통신망또는정보시스템구축",
					"정보시스템구축",
					"주요데이터베이스구축",
					"민감정보",
					"고유식별정보",
					"주요정보통신기반시설",
					"제어시스템"
				));
			}
			if ((normalized.contains("제안요청서") || normalized.contains("rfp"))
				&& (normalized.contains("필수") || normalized.contains("요소") || normalized.contains("항목") || normalized.contains("작성"))) {
				directEvidenceGroups.add(List.of(
					"제안요청서에는 다음 각 호의 사항",
					"제안요청서 기재사항",
					"제안요청서에는"
				));
				directEvidenceGroups.add(List.of(
					"제안요청서에는 과업내용",
					"제안요청서에는 과업내용, 요구사항",
					"과업내용, 요구사항 2. 계약조건",
					"평가요소, 평가방법"
				));
			}

			return new QuestionProfile(terms, conceptGroups, intentGroups, directEvidenceGroups);
		}

		int requiredConceptMatches() {
			if (conceptGroups.isEmpty()) {
				return 0;
			}
			return conceptGroups.size() >= 2 ? 2 : 1;
		}

		int requiredDirectEvidenceMatches() {
			if (directEvidenceGroups.isEmpty()) {
				return 0;
			}
			return Math.min(2, directEvidenceGroups.size());
		}

		// 메소드 설명: isSecurityReviewQuestion 처리 흐름을 수행합니다.
		private static boolean isSecurityReviewQuestion(String normalized) {
			return normalized.contains("보안성검토")
				|| (normalized.contains("보안성") && normalized.contains("검토"));
		}

		// 메소드 설명: addSpecificConceptGroups 처리 흐름을 수행합니다.
		private static void addSpecificConceptGroups(List<String> terms, List<List<String>> conceptGroups) {
			for (String candidateTerm : terms) {
				String term = stripIntentSuffix(candidateTerm);
				if (term.length() < 5 || isIntentLikeTerm(term)) {
					continue;
				}
				boolean alreadyCovered = conceptGroups.stream()
					.flatMap(List::stream)
					.map(EvidenceJudge::normalize)
					.anyMatch(value -> value.equals(term) || value.contains(term) || term.contains(value));
				if (!alreadyCovered) {
					conceptGroups.add(List.of(term));
				}
			}
		}

		// 메소드 설명: stripIntentSuffix 처리 흐름을 수행합니다.
		private static String stripIntentSuffix(String term) {
			if (term == null || term.length() < 4) {
				return term;
			}
			for (String suffix : List.of("대상사업", "대상시스템", "대상기관", "적용대상", "필수요소", "검토내용", "추진절차", "신청방법", "제출서류", "대상", "시스템", "사업", "기관", "요소", "항목", "절차", "방법", "서류")) {
				if (term.endsWith(suffix) && term.length() > suffix.length() + 2) {
					return term.substring(0, term.length() - suffix.length());
				}
			}
			return term;
		}

		// 메소드 설명: isIntentLikeTerm 처리 흐름을 수행합니다.
		private static boolean isIntentLikeTerm(String term) {
			return Set.of(
				"대상사업",
				"대상기관",
				"정보시스템",
				"시스템",
				"필수요소",
				"검토내용",
				"추진절차",
				"신청방법",
				"제출서류"
			).contains(term);
		}
	}
}
