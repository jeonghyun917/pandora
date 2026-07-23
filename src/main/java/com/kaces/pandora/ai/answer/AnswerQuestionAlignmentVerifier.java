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
		"(?:입니다|합니다|한다|된다|됩니다|아니다|없습니다|있습니다|"
			+ "하여야\s*한다|해야\s*한다|할\s*수\s*있다|할\s*수\s*없다|"
			+ "하지\s*않는다|금지된다|제외된다|비대상이다)(?:[.?!]|$)"
	);
	private static final Pattern NUMERIC_AMOUNT_PATTERN = Pattern.compile(
		"(?:\\d[\\d,.]*\\s*(?:원|만원|억원|조원|%|퍼센트)|"
			+ "(?:금액|비용|예산|한도|가격|대가)(?:은|는|이|가)?\\s*[^.!?]{0,30}(?:입니다|이다|한다|됩니다))"
	);
	private static final Pattern PERIOD_VALUE_PATTERN = Pattern.compile(
		"(?:\\d{1,4}(?:년|월|일|시간|분|주|개월)|"
			+ "(?:이전|이후|전|후)(?:에|까지|입니다|이다|부터)|"
			+ "(?:까지|기한은|기간은|시기는)\\s*[^.!?]{0,30}(?:입니다|이다|한다|됩니다))"
	);
	private static final Pattern PROPOSITION_BOUNDARY = Pattern.compile(
		"(?:별개로|무관하게|관계없이|반면(?:에)?|한편|다만|그러나|하지만)[,，]?\\s*"
	);
	private static final Set<String> SUBJECT_STOP_TERMS = Set.of(
		"사업", "기관", "정보", "내용", "기준", "관련", "경우", "여부", "설명", "안내", "그리고",
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
		String claimProposition = directProposition(link.claim());
		String evidenceProposition = directProposition(link.evidenceSentence());
		LinkedHashSet<String> missing = new LinkedHashSet<>();
		if (!matchesAllGroups(combined, profile.subjectGroups())) {
			missing.add(SUBJECT);
		}
		boolean claimHasRelation = profile.relationRequirements().stream()
			.allMatch(requirement -> requirement.matches(claimProposition));
		if (!claimHasRelation) {
			missing.add(RELATION);
			if (profile.relationRequirements().stream()
				.allMatch(requirement -> requirement.matches(evidenceProposition))) {
				missing.add(DIRECT_CONCLUSION);
			}
		}
		if (!profile.conditionGroups().stream()
			.allMatch(group -> group.stream().allMatch(claim::contains))) {
			missing.add(CONDITION);
		}
		if (claimHasRelation && !hasDirectConclusion(claimProposition, profile.relationRequirements())) {
			missing.add(DIRECT_CONCLUSION);
		}
		return new CandidateResult(link.claim(), List.copyOf(missing));
	}

	private boolean matchesAllGroups(String text, List<List<String>> groups) {
		return !groups.isEmpty() && groups.stream().allMatch(group -> group.stream().anyMatch(text::contains));
	}

	private boolean hasDirectConclusion(
		String proposition,
		List<RelationRequirement> relationRequirements
	) {
		if (proposition == null || proposition.isBlank()) {
			return false;
		}
		String trimmed = proposition.trim();
		if (DIRECT_CONCLUSION_PATTERN.matcher(trimmed).find()) {
			return true;
		}
		String normalized = normalize(trimmed);
		return relationRequirements.stream().allMatch(requirement -> requirement.matches(normalized))
			&& List.of("대상", "비대상", "제외", "면제", "금지", "허용", "가능", "불가능")
				.stream()
				.anyMatch(normalized::endsWith);
	}

	private String directProposition(String claim) {
		String source = String.valueOf(claim == null ? "" : claim).trim();
		String[] propositions = PROPOSITION_BOUNDARY.split(source);
		for (int index = propositions.length - 1; index >= 0; index--) {
			if (!propositions[index].isBlank()) {
				return propositions[index].trim();
			}
		}
		return source;
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

	private enum RelationKind {
		LEXICAL,
		PERIOD_VALUE,
		AMOUNT_VALUE
	}

	private record RelationRequirement(RelationKind kind, List<String> alternatives) {
		private boolean matches(String proposition) {
			String source = String.valueOf(proposition == null ? "" : proposition);
			String normalized = normalize(source);
			return switch (kind) {
				case PERIOD_VALUE -> PERIOD_VALUE_PATTERN.matcher(source).find();
				case AMOUNT_VALUE -> NUMERIC_AMOUNT_PATTERN.matcher(source).find()
					|| List.of("금액은", "비용은", "예산은", "한도는", "가격은", "대가는")
						.stream()
						.map(AnswerQuestionAlignmentVerifier::normalize)
						.anyMatch(normalized::contains);
				case LEXICAL -> alternatives.stream().anyMatch(normalized::contains);
			};
		}
	}

	private record AlignmentProfile(
		List<List<String>> subjectGroups,
		List<RelationRequirement> relationRequirements,
		List<List<String>> conditionGroups
	) {
		private static AlignmentProfile from(String question) {
			QuestionIntentProfile questionProfile = QuestionIntentProfile.from(question);
			List<List<String>> conditionGroups = conditionGroups(question);
			List<List<String>> subjectGroups = subjectGroups(questionProfile, conditionGroups);
			List<RelationRequirement> relationRequirements = relationRequirements(questionProfile, question);
			return new AlignmentProfile(
				subjectGroups,
				relationRequirements,
				conditionGroups
			);
		}

		private boolean usable() {
			return !subjectGroups.isEmpty() && !relationRequirements.isEmpty();
		}

		private static List<List<String>> subjectGroups(
			QuestionIntentProfile profile,
			List<List<String>> conditionGroups
		) {
			List<List<String>> groups = new ArrayList<>();
			List<List<String>> configuredAnchors = profile.configuredEntityAnchorGroups().stream()
				.map(AlignmentProfile::normalizedValues)
				.filter(group -> !group.isEmpty())
				.toList();
			if (!configuredAnchors.isEmpty()) {
				groups.addAll(configuredAnchors);
			}
			else {
				String question = profile.normalizedQuestion();
				for (QuestionEntity entity : profile.entities()) {
					List<String> explicitAliases = normalizedValues(entity.aliases()).stream()
						.filter(alias -> alias.length() >= 3 && question.contains(alias))
						.toList();
					if (!explicitAliases.isEmpty()) {
						groups.add(explicitAliases);
					}
				}
			}
			for (String term : profile.terms()) {
				String normalized = normalize(term);
				if (normalized.length() < 2
					|| isSubjectStopTerm(normalized)
					|| coveredByConditions(normalized, conditionGroups)
					|| coveredBySubjectGroups(normalized, groups)
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

		private static boolean coveredBySubjectGroups(String term, List<List<String>> groups) {
			return groups.stream()
				.flatMap(List::stream)
				.anyMatch(anchor -> anchor.contains(term) || term.contains(anchor));
		}

		private static boolean coveredByConditions(String term, List<List<String>> conditionGroups) {
			return conditionGroups.stream()
				.flatMap(List::stream)
				.anyMatch(anchor -> anchor.equals(term) || anchor.contains(term) || term.contains(anchor));
		}

		private static boolean coveredByIntent(String term, List<List<String>> intentGroups) {
			return intentGroups.stream()
				.flatMap(List::stream)
				.map(AnswerQuestionAlignmentVerifier::normalize)
				.anyMatch(intent -> intent.equals(term) || intent.contains(term));
		}

		private static boolean isSubjectStopTerm(String term) {
			return SUBJECT_STOP_TERMS.contains(term)
				|| term.startsWith("대상")
				|| term.startsWith("금액")
				|| term.startsWith("비용")
				|| term.startsWith("기간")
				|| term.startsWith("시기")
				|| term.startsWith("얼마")
				|| term.startsWith("어떤")
				|| term.startsWith("무슨")
				|| term.endsWith("인가")
				|| term.endsWith("하나")
				|| term.endsWith("있나")
				|| term.startsWith("별도")
				|| term.startsWith("보관")
				|| term.startsWith("분리보관")
				|| term.endsWith("해야")
				|| term.endsWith("하여야")
				|| term.endsWith("받아야");
		}

		private static List<RelationRequirement> relationRequirements(
			QuestionIntentProfile profile,
			String question
		) {
			String normalizedQuestion = normalize(question);
			Set<String> intents = profile.intentTypes();
			List<RelationRequirement> requirements = new ArrayList<>();
			if (intents.contains("period") || containsAny(normalizedQuestion, "언제", "시기", "기한", "기간", "마감", "까지")) {
				requirements.add(new RelationRequirement(RelationKind.PERIOD_VALUE, List.of()));
			}
			if (amountRequested(normalizedQuestion)) {
				requirements.add(new RelationRequirement(RelationKind.AMOUNT_VALUE, List.of()));
			}
			if (intents.contains("exception_scope") || containsAny(normalizedQuestion, "제외", "예외", "비대상", "면제", "안해도", "불필요")) {
				addLexicalRequirement(requirements, profile,
					List.of("제외", "예외", "비대상", "면제"));
			}
			if (intents.contains("contract_method")) {
				addLexicalRequirement(requirements, profile,
					List.of("수의계약", "계약방법", "계약방식"));
			}
			if (intents.contains("purchase_channel")) {
				addLexicalRequirement(requirements, profile,
					List.of("조달청", "나라장터", "종합쇼핑몰", "구매경로"));
			}
			if (intents.contains("penalty") || containsAny(normalizedQuestion, "금지", "할수없", "불가능", "하면안")) {
				addLexicalRequirement(requirements, profile,
					List.of("불이익", "제재", "처분", "과태료", "금지", "할수없", "불가능"));
			}
			if (intents.contains("review_required")) {
				addLexicalRequirement(requirements, profile,
					List.of("심의", "검토대상", "위원회"));
			}
			if (explicitTargetScope(normalizedQuestion)) {
				addLexicalRequirement(requirements, profile,
					List.of("대상", "범위", "포함", "해당"));
			}
			if (containsAny(normalizedQuestion, "할수있", "가능한가", "가능한지", "허용")) {
				addLexicalRequirement(requirements, profile,
					List.of("할수있", "가능", "허용", "할수없", "불가능", "금지"));
			}
			if (intents.contains("obligation")
				&& !intents.contains("review_required")
				&& containsAny(normalizedQuestion, "의무", "하여야", "해야", "반드시", "필요")) {
				addLexicalRequirement(requirements, profile,
					List.of("의무", "하여야", "해야", "반드시", "필요"));
			}
			if (intents.contains("procedure") && containsAny(normalizedQuestion, "절차", "방법", "어떻게")) {
				addLexicalRequirement(requirements, profile,
					List.of("절차", "방법", "신청", "제출"));
			}
			if (intents.contains("required_documents")) {
				addLexicalRequirement(requirements, profile,
					List.of("제출서류", "필수항목", "기재사항", "요구사항"));
			}
			if (intents.contains("definition")) {
				addLexicalRequirement(requirements, profile,
					List.of("정의", "개념", "의미", "말한다"));
			}
			if (intents.contains("operation_rule") && containsAny(normalizedQuestion, "기준", "요건", "관리", "운영", "준수")) {
				addLexicalRequirement(requirements, profile,
					List.of("기준", "요건", "관리", "운영", "준수"));
			}
			if (requirements.isEmpty() && !profile.intentGroups().isEmpty()) {
				requirements.add(new RelationRequirement(RelationKind.LEXICAL, profile.intentGroups().stream()
					.flatMap(List::stream)
					.map(AnswerQuestionAlignmentVerifier::normalize)
					.filter(value -> value.length() >= 2)
					.distinct()
					.toList()));
			}
			return List.copyOf(requirements);
		}

		private static void addLexicalRequirement(
			List<RelationRequirement> requirements,
			QuestionIntentProfile profile,
			List<String> seeds
		) {
			List<String> normalizedSeeds = normalizedValues(seeds);
			List<String> configured = java.util.stream.Stream.concat(
				profile.intentGroups().stream(),
				profile.directEvidenceGroups().stream()
			)
				.filter(group -> group.stream()
					.map(AnswerQuestionAlignmentVerifier::normalize)
					.anyMatch(term -> normalizedSeeds.stream()
						.anyMatch(seed -> term.contains(seed) || seed.contains(term))))
				.flatMap(List::stream)
				.map(AnswerQuestionAlignmentVerifier::normalize)
				.filter(value -> value.length() >= 2)
				.distinct()
				.toList();
			List<String> alternatives = configured.isEmpty() ? normalizedSeeds : configured;
			String key = String.join("|", alternatives);
			if (!alternatives.isEmpty() && requirements.stream()
				.noneMatch(requirement -> requirement.kind() == RelationKind.LEXICAL
					&& String.join("|", requirement.alternatives()).equals(key))) {
				requirements.add(new RelationRequirement(RelationKind.LEXICAL, alternatives));
			}
		}

		private static boolean explicitTargetScope(String normalizedQuestion) {
			return containsAny(normalizedQuestion, "대상", "범위", "포함", "해당");
		}

		private static boolean amountRequested(String normalizedQuestion) {
			return containsAny(normalizedQuestion, "금액", "비용", "한도", "얼마", "가격", "대가");
		}

		private static List<List<String>> conditionGroups(String question) {
			String[] tokens = String.valueOf(question == null ? "" : question.trim()).split("\\s+");
			List<List<String>> groups = new ArrayList<>();
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
					groups.add(anchors.stream().distinct().toList());
				}
			}
			return distinctGroups(groups);
		}

		private static boolean isConditionTerm(String normalized) {
			return CONDITION_SUFFIXES.stream().anyMatch(normalized::endsWith)
				|| (normalized.length() >= 3 && (normalized.endsWith("하면") || normalized.endsWith("되면")));
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
