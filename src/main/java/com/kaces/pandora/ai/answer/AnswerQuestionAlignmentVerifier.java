package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import com.kaces.pandora.common.text.QuestionEntity;
import com.kaces.pandora.common.text.QuestionIntentProfile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class AnswerQuestionAlignmentVerifier {

	private static final String SUPPORTED = "SUPPORTED";
	private static final String SUBJECT = "SUBJECT";
	private static final String RELATION = "RELATION";
	private static final String CONDITION = "CONDITION";
	private static final String DIRECT_CONCLUSION = "DIRECT_CONCLUSION";
	private static final Pattern DIRECT_CONCLUSION_PATTERN = Pattern.compile(
		"(?:입니다|합니다|합니다만|하여야|해야|된다|됩니다|아니다|없습니다|있습니다|할\s*수\s*있|할\s*수\s*없|하지\s*않|금지|제외|비대상|대상)(?:[.?!]|$)"
	);
	private static final Set<String> SUBJECT_STOP_TERMS = Set.of(
		"사업", "기관", "정보", "내용", "기준", "관련", "경우", "여부", "설명", "안내",
		"대상", "범위", "포함", "해당", "제외", "예외", "비대상", "면제",
		"절차", "방법", "신청", "제출", "등록", "처리", "통보", "보관", "분리보관", "별도",
		"의무", "필요", "반드시", "언제", "시기", "기한", "기간", "마감", "까지",
		"금액", "비용", "예산", "한도", "얼마", "가격", "대가", "가능", "불가능"
	);
	private static final List<String> CONDITION_SUFFIXES = List.of(
		"이전에", "이전", "전까지", "전에", "후에", "이후에", "이후", "때에", "때", "시에", "중에", "경우에", "경우"
	);

	public AlignmentResult verify(String question, ClaimVerifier.VerificationResult claimResult) {
		AlignmentProfile alignmentProfile = AlignmentProfile.from(question);
		if (!alignmentProfile.usable()) {
			return AlignmentResult.failed(
				"QUESTION_PROFILE_EMPTY",
				List.of(SUBJECT, RELATION)
			);
		}
		List<ClaimVerifier.ClaimEvidenceLink> supportedLinks = claimResult == null
			? List.of()
			: claimResult.evidenceLinks().stream()
				.filter(link -> SUPPORTED.equals(link.relation()))
				.toList();
		if (supportedLinks.isEmpty()) {
			return AlignmentResult.failed("NO_SUPPORTED_CLAIMS", List.of(SUBJECT, RELATION));
		}

		List<CandidateResult> candidates = supportedLinks.stream()
			.map(link -> assess(link, alignmentProfile))
			.toList();
		for (CandidateResult candidate : candidates) {
			if (candidate.missingGroups().isEmpty()) {
				return AlignmentResult.aligned(candidate.claim());
			}
		}
		CandidateResult best = candidates.stream()
			.min(Comparator
				.comparingInt((CandidateResult candidate) -> candidate.missingGroups().size())
				.thenComparingInt(candidate -> reasonPriority(candidate.missingGroups())))
			.orElseThrow();
		return AlignmentResult.failed(reasonCode(best.missingGroups()), best.missingGroups());
	}

	private CandidateResult assess(
		ClaimVerifier.ClaimEvidenceLink link,
		AlignmentProfile profile
	) {
		String claim = normalize(link.claim());
		String combined = claim + normalize(link.evidenceSentence());
		LinkedHashSet<String> missing = new LinkedHashSet<>();
		if (!matchesAllGroups(combined, profile.subjectGroups())) {
			missing.add(SUBJECT);
		}
		boolean claimHasRelation = matchesAllGroups(claim, profile.relationGroups());
		if (!claimHasRelation) {
			missing.add(RELATION);
			if (matchesAllGroups(combined, profile.relationGroups())) {
				missing.add(DIRECT_CONCLUSION);
			}
		}
		if (!profile.conditionAnchors().stream().allMatch(claim::contains)) {
			missing.add(CONDITION);
		}
		if (claimHasRelation && !hasDirectConclusion(link.claim())) {
			missing.add(DIRECT_CONCLUSION);
		}
		return new CandidateResult(link.claim(), List.copyOf(missing));
	}

	private boolean matchesAllGroups(String text, List<List<String>> groups) {
		return !groups.isEmpty() && groups.stream().allMatch(group -> group.stream().anyMatch(text::contains));
	}

	private boolean hasDirectConclusion(String claim) {
		return claim != null && DIRECT_CONCLUSION_PATTERN.matcher(claim.trim()).find();
	}

	private static int reasonPriority(List<String> missingGroups) {
		if (missingGroups.contains(SUBJECT)) {
			return 0;
		}
		if (missingGroups.contains(CONDITION)) {
			return 1;
		}
		if (missingGroups.contains(DIRECT_CONCLUSION)) {
			return 2;
		}
		return 3;
	}

	private static String reasonCode(List<String> missingGroups) {
		if (missingGroups.contains(SUBJECT)) {
			return "MISSING_SUBJECT";
		}
		if (missingGroups.contains(CONDITION)) {
			return "MISSING_CONDITION";
		}
		if (missingGroups.contains(DIRECT_CONCLUSION)) {
			return "MISSING_DIRECT_CONCLUSION";
		}
		return "MISSING_RELATION";
	}

	private static String normalize(String value) {
		return KoreanQueryNormalizer.normalizeForMatch(value);
	}

	public record AlignmentResult(
		boolean evaluated,
		boolean aligned,
		String reasonCode,
		List<String> missingGroups,
		String matchedClaim
	) {
		public AlignmentResult {
			missingGroups = missingGroups == null ? List.of() : List.copyOf(missingGroups);
			matchedClaim = matchedClaim == null ? "" : matchedClaim;
		}

		public static AlignmentResult claimOnly() {
			return new AlignmentResult(false, true, "NOT_EVALUATED_CLAIM_ONLY", List.of(), "");
		}

		public static AlignmentResult claimInsufficient() {
			return new AlignmentResult(false, false, "NOT_EVALUATED_CLAIM_INSUFFICIENT", List.of(), "");
		}

		private static AlignmentResult aligned(String claim) {
			return new AlignmentResult(true, true, "ALIGNED", List.of(), claim);
		}

		private static AlignmentResult failed(String reasonCode, List<String> missingGroups) {
			return new AlignmentResult(true, false, reasonCode, missingGroups, "");
		}
	}

	private record CandidateResult(String claim, List<String> missingGroups) {
	}

	private record AlignmentProfile(
		List<List<String>> subjectGroups,
		List<List<String>> relationGroups,
		List<String> conditionAnchors
	) {
		private static AlignmentProfile from(String question) {
			QuestionIntentProfile questionProfile = QuestionIntentProfile.from(question);
			List<List<String>> subjectGroups = subjectGroups(questionProfile);
			List<List<String>> relationGroups = relationGroups(questionProfile, question);
			return new AlignmentProfile(
				subjectGroups,
				relationGroups,
				conditionAnchors(question)
			);
		}

		private boolean usable() {
			return !subjectGroups.isEmpty() && !relationGroups.isEmpty();
		}

		private static List<List<String>> subjectGroups(QuestionIntentProfile profile) {
			List<List<String>> groups = new ArrayList<>();
			for (QuestionEntity entity : profile.entities()) {
				List<String> aliases = normalizedValues(entity.aliases());
				if (!aliases.isEmpty()) {
					groups.add(aliases);
				}
			}
			for (String term : profile.terms()) {
				String normalized = normalize(term);
				if (normalized.length() < 2
					|| isSubjectStopTerm(normalized)
					|| isConditionTerm(normalized)
					|| coveredByEntity(normalized, profile.entities())
					|| coveredByIntent(normalized, profile.intentGroups())) {
					continue;
				}
				groups.add(List.of(normalized));
			}
			if (groups.isEmpty()) {
				for (List<String> conceptGroup : profile.conceptGroups()) {
					List<String> normalized = normalizedValues(conceptGroup);
					if (!normalized.isEmpty()) {
						groups.add(normalized);
						break;
					}
				}
			}
			return distinctGroups(groups);
		}

		private static boolean coveredByEntity(String term, List<QuestionEntity> entities) {
			return entities.stream()
				.flatMap(entity -> entity.aliases().stream())
				.map(AnswerQuestionAlignmentVerifier::normalize)
				.anyMatch(alias -> alias.contains(term) || term.contains(alias));
		}

		private static boolean coveredByIntent(String term, List<List<String>> intentGroups) {
			return intentGroups.stream()
				.flatMap(List::stream)
				.map(AnswerQuestionAlignmentVerifier::normalize)
				.anyMatch(intent -> intent.equals(term) || intent.contains(term));
		}

		private static boolean isSubjectStopTerm(String term) {
			return SUBJECT_STOP_TERMS.contains(term)
				|| term.startsWith("별도")
				|| term.startsWith("보관")
				|| term.startsWith("분리보관")
				|| term.endsWith("해야")
				|| term.endsWith("하여야")
				|| term.endsWith("받아야");
		}

		private static List<List<String>> relationGroups(QuestionIntentProfile profile, String question) {
			String normalizedQuestion = normalize(question);
			Set<String> intents = profile.intentTypes();
			if (intents.contains("period") || containsAny(normalizedQuestion, "언제", "시기", "기한", "기간", "마감", "까지")) {
				return groups("전에", "이전에", "전까지", "후에", "이후에", "때에", "시기", "기한", "기간", "마감", "까지", "년", "월", "일");
			}
			if (intents.contains("amount")) {
				return groups("금액", "비용", "예산", "한도", "원", "만원", "억", "%", "퍼센트");
			}
			if (intents.contains("exception_scope") || containsAny(normalizedQuestion, "제외", "예외", "비대상", "면제", "안해도", "불필요")) {
				return groups("제외", "예외", "비대상", "면제", "생략", "불필요", "안해도", "아닙니다");
			}
			if (intents.contains("contract_method")) {
				return groups("수의계약", "계약방법", "계약방식", "경쟁입찰", "입찰");
			}
			if (intents.contains("purchase_channel")) {
				return groups("조달청", "나라장터", "종합쇼핑몰", "디지털서비스몰", "구매경로", "구매");
			}
			if (intents.contains("penalty")) {
				return groups("불이익", "제재", "처분", "처벌", "과태료", "벌칙", "감점", "책임", "위반", "금지");
			}
			if (intents.contains("review_required")) {
				return groups("심의대상", "과업심의대상", "심의가필요", "심의를받아야", "심의해야", "비대상", "제외");
			}
			if (explicitTargetScope(normalizedQuestion)) {
				return groups("대상", "적용대상", "범위", "포함", "해당", "비대상", "제외");
			}
			if (intents.contains("obligation")) {
				return groups("의무", "하여야", "해야", "반드시", "필요", "준수해야", "보관해야", "분리하여보관");
			}
			if (intents.contains("procedure")) {
				return groups("절차", "방법", "신청", "제출", "등록", "처리", "통보", "진행");
			}
			if (intents.contains("required_documents")) {
				return groups("제출서류", "필수항목", "기재사항", "요구사항", "평가요소", "평가방법");
			}
			if (intents.contains("definition")) {
				return groups("정의", "개념", "의미", "말한다", "이란", "입니다");
			}
			if (intents.contains("operation_rule")) {
				return groups("기준", "요건", "관리", "운영", "준수", "부여", "회수", "등록");
			}
			if (!profile.intentGroups().isEmpty()) {
				return List.of(profile.intentGroups().stream()
					.flatMap(List::stream)
					.map(AnswerQuestionAlignmentVerifier::normalize)
					.filter(value -> value.length() >= 2)
					.distinct()
					.toList());
			}
			return List.of();
		}

		private static boolean explicitTargetScope(String normalizedQuestion) {
			return containsAny(normalizedQuestion, "대상", "범위", "포함", "해당");
		}

		private static List<String> conditionAnchors(String question) {
			String[] tokens = String.valueOf(question == null ? "" : question.trim()).split("\\s+");
			for (int index = 0; index < tokens.length; index++) {
				String token = normalize(tokens[index]);
				if (!isConditionTerm(token)) {
					continue;
				}
				int start = Math.max(0, index - 2);
				List<String> anchors = new ArrayList<>();
				for (int anchorIndex = start; anchorIndex <= index; anchorIndex++) {
					String anchor = normalize(tokens[anchorIndex]);
					if (anchor.length() >= 2 && !KoreanQueryNormalizer.isWeakQuestionTerm(anchor)) {
						anchors.add(anchor);
					}
				}
				if (anchors.size() >= 2) {
					return anchors.stream().distinct().toList();
				}
			}
			return List.of();
		}

		private static boolean isConditionTerm(String normalized) {
			return CONDITION_SUFFIXES.stream().anyMatch(normalized::endsWith)
				|| (normalized.length() >= 3 && (normalized.endsWith("하면") || normalized.endsWith("되면")));
		}

		private static List<List<String>> groups(String... terms) {
			return List.of(normalizedValues(List.of(terms)));
		}

		private static boolean containsAny(String text, String... values) {
			for (String value : values) {
				if (text.contains(normalize(value))) {
					return true;
				}
			}
			return false;
		}

		private static List<String> normalizedValues(List<String> values) {
			return values.stream()
				.map(AnswerQuestionAlignmentVerifier::normalize)
				.filter(value -> !value.isBlank())
				.distinct()
				.toList();
		}

		private static List<List<String>> distinctGroups(List<List<String>> groups) {
			LinkedHashSet<String> seen = new LinkedHashSet<>();
			List<List<String>> result = new ArrayList<>();
			for (List<String> group : groups) {
				String key = String.join("|", group);
				if (!group.isEmpty() && seen.add(key)) {
					result.add(List.copyOf(group));
				}
			}
			return List.copyOf(result);
		}
	}
}
