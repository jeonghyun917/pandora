package com.kaces.pandora.ai.answer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ClaimEvidenceAtomizer {
	private final KoreanEvidenceAtomParser evidenceAtomParser = new KoreanEvidenceAtomParser();

	private static final Pattern STRUCTURAL_BOUNDARY = Pattern.compile(
		"(?<=[!?])\\s+"
			+ "|(?<=[가-힣][.])\\s+"
			+ "|(?<=\\d[.])\\s+"
			+ "(?=[\\p{IsHangul}A-Za-z0-9()·ㆍ/-]{2,}?(?:은|는|이|가)\\s)"
			+ "|[;；]"
			+ "|\\R+"
			+ "|(?=[①-⑳•‣□○※]\\s+)"
			+ "|(?<=\\s)(?=[*∙]\\s*[\\p{IsHangul}A-Za-z0-9])"
			+ "|(?<!\\s)(?<!제)\\s+(?=\\d{1,2}[)]\\s)"
			+ "|(?<!\\s)(?<!제)(?<!\\d[.])\\s+(?=\\d{1,2}[.]\\s)"
			+ "|(?<!\\s)(?<!제)(?<!\\d[.])\\s+(?=\\d{1,2}[.](?=[\\p{IsHangul}A-Za-z(]))"
			+ "|(?<=[\\p{IsHangul}][.])"
			+ "(?=\\d{1,2}[.)]\\s*[\"“‘']?[\\p{IsHangul}A-Za-z(])"
	);
	private static final Pattern COORDINATING_BOUNDARY = Pattern.compile(
		"(?<=하고)[,，]?\\s+"
			+ "|(?<=이며)[,，]?\\s+"
			+ "|(?<=으며)[,，]?\\s+"
			+ "|(?<=이고)[,，]?\\s+"
			+ "|(?<=되며)[,，]?\\s+"
			+ "|(?<=하되)[,，]?\\s+"
			+ "|(?<=지만)[,，]?\\s+"
	);
	private static final Pattern EXPLICIT_EXCEPTION_BOUNDARY = Pattern.compile(
		"(?<![\\p{IsHangul}A-Za-z0-9])(?=(?:다만[,，]?\\s+|예외적으로\\s+))"
	);
	private static final Pattern ATTACHED_EXCEPTION_CONDITION = Pattern.compile(
		"(?:경우|때)(?:에는?|에만|만)?$|한하여$"
	);
	private static final Pattern ASSERTION_COMMA = Pattern.compile("[,，]\\s+");
	private static final Pattern EXPLICIT_SUBJECT = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}A-Za-z0-9()·ㆍ/-]{2,}?)(?:은|는|이|가)(?=\\s)"
	);
	private static final Pattern EXPLICIT_OBJECT = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}A-Za-z0-9()·ㆍ/-]{2,}?)(?:을|를)(?=\\s)"
	);
	private static final Pattern EXPLICIT_RECIPIENT = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}A-Za-z0-9()·ㆍ/-]{2,}?)(?:에게|한테)(?=\\s)"
	);
	private static final Pattern EXPLICIT_PERMISSION_ACTION = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}A-Za-z0-9]{2,}?)(?:을|를)?\\s*"
			+ "할\\s*수\\s*(?:있|없)"
	);
	private static final List<String> ALLOWED_PERMISSION_ENDINGS = List.of(
		"할수있지만", "할수있습니다", "할수있다", "할수있음",
		"가능하지만", "가능합니다", "가능하다", "가능함",
		"허용되지만", "허용됩니다", "허용된다", "허용됨",
		"할수있으며", "가능하며", "허용되며", "허용되어있으며"
	);
	private static final List<String> PROHIBITED_PERMISSION_ENDINGS = List.of(
		"할수없지만", "할수없습니다", "할수없다", "할수없음",
		"불가능하지만", "불가능합니다", "불가능하다", "불가능함",
		"금지되지만", "금지됩니다", "금지된다", "금지됨",
		"할수없으며", "불가능하며", "금지되며", "금지되어있으며"
	);
	private static final Pattern GENERAL_COORDINATED_PREDICATE = Pattern.compile(
		"[\\p{IsHangul}A-Za-z0-9]{2,}(?:하고|하되|되며|이며|으며|이고)$"
	);
	private static final List<String> NON_ATOMIC_PREMISE_STEMS = List.of(
		"이상", "이하", "미만", "초과", "조건", "요건", "경우", "필수", "필요"
	);
	private static final Pattern INDEPENDENT_ASSERTION_ENDING = Pattern.compile(
		"(?:합니다|됩니다|입니다|있습니다|없습니다|아닙니다"
			+ "|한다|된다|이다|있다|없다|아니다|함|됨|임)[.!?\\s]*$"
	);
	private static final Pattern RESTRICTIVE_REMAINDER = Pattern.compile(
		"(?:필요(?:합니다|하다|함)|요건(?:입니다|이다|임)|조건(?:입니다|이다|임)"
			+ "|경우에한(?:합니다|한다|함)|있어야|충족해야|갖추어야"
			+ "|(?:요구|전제)(?:됩니다|된다|합니다|한다|됨|함)"
			+ "|(?:승인|허가|동의|심사|검토|확인).{0,40}"
			+ "(?:받아야|거쳐야|취득해야|얻어야|확보해야|제출해야)"
			+ "(?:합니다|한다|함)?)[.!?\\s]*$"
	);
	private static final Pattern RESTRICTIVE_MODAL_REMAINDER = Pattern.compile(
		"(?:.*(?:해야|하여야|되어야|받아야|있어야|없어야)(?:만)?"
			+ "(?:합니다|한다|함)?"
			+ "|.*(?:요구|전제)(?:됩니다|된다|합니다|한다|됨|함|로합니다|로한다)"
			+ "|.*(?:조건|요건|경우에한함|경우에한합니다))[.!?\\s]*$"
	);
	private static final Pattern EMBEDDED_ATTRIBUTIVE_REMAINDER = Pattern.compile(
		"^\\s+[^.!?;；\\n]{0,100}?"
			+ "[\\p{IsHangul}A-Za-z0-9]{1,40}(?:한|하는|할|된|되는|될|받은|받는|받을|있는|없는|던)\\s+"
			+ "(?:(?:모든|각|해당|관련|주요)\\s+){0,2}"
			+ "[\\p{IsHangul}A-Za-z0-9()·ㆍ/\\-]{2,}?"
			+ "(?:은|는|이|가|을|를|에게|한테|임|입니다|이다)(?=\\s|[.!?]|$)"
	);
	private static final Pattern ATTRIBUTIVE_VERB_ROLE_TOKEN = Pattern.compile(
		"(?:하|되|있|없|받)(?:은|는)$"
	);
	private static final Set<String> SUBJECT_MODIFIER_BOUNDARY_WORDS = Set.of(
		"및", "또는", "혹은", "하고", "등",
		"모든", "전체", "각", "각종",
		"위해", "위하여", "위한", "대한", "관한", "따른", "통해"
	);
	private static final List<String> SUBJECT_MODIFIER_BOUNDARY_SUFFIXES = List.of(
		"에게", "한테", "에서", "으로", "부터", "까지"
	);
	private static final Set<String> SUBJECT_MODIFIER_SINGLE_PARTICLES = Set.of(
		"은", "는", "이", "가", "을", "를", "와", "과"
	);
	private static final Set<String> LEXICAL_UI_ENDINGS = Set.of(
		"협의", "동의", "회의", "정의", "논의"
	);
	private static final Pattern LEADING_LIST_MARKER = Pattern.compile(
		"^(?:[①-⑳•‣□○※](?=\\s)"
			+ "|[*∙]\\s*"
			+ "|\\d{1,2}[.)](?=\\s|[\"“‘'\\p{IsHangul}A-Za-z(]))\\s*"
	);
	private static final Pattern OCR_PAGE_MARKER = Pattern.compile(
		"(?i)(?<![\\p{Alnum}])p[.]\\d{1,4}(?!\\d)"
	);
	private static final Pattern SAFE_OCR_HEADING = Pattern.compile(
		"[\\p{IsHangul}A-Za-z0-9()·ㆍ/\\-]+(?:\\s+[\\p{IsHangul}A-Za-z0-9()·ㆍ/\\-]+){0,5}"
	);

	List<String> atomize(String text) {
		return atomize(text, false);
	}

	List<String> atomizeForAlignment(String text) {
		return atomize(text, true);
	}

	List<EvidenceAtom> parseAtoms(String text) {
		return atomizeForAlignment(text).stream().map(evidenceAtomParser::parse).toList();
	}

	private List<String> atomize(String text, boolean strictCoordinatingBoundaries) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		Set<String> atoms = new LinkedHashSet<>();
		for (String structural : STRUCTURAL_BOUNDARY.split(text.replace('\r', '\n'))) {
			String deheaded = stripRepeatedOcrPageHeading(structural);
			for (String assertion : splitIndependentAssertionCommas(deheaded)) {
				for (String clause : splitConnectives(assertion, strictCoordinatingBoundaries)) {
					String cleaned = LEADING_LIST_MARKER.matcher(clause)
						.replaceFirst("")
						.replaceAll("\\s+", " ")
						.trim();
					if (!cleaned.isBlank()) {
						atoms.add(cleaned);
					}
				}
			}
		}
		return List.copyOf(new ArrayList<>(atoms));
	}

	private String stripRepeatedOcrPageHeading(String text) {
		String source = String.valueOf(text == null ? "" : text).trim();
		Matcher marker = OCR_PAGE_MARKER.matcher(source);
		int markerCount = 0;
		int firstStart = -1;
		int lastEnd = -1;
		while (marker.find()) {
			if (firstStart < 0) {
				firstStart = marker.start();
			}
			lastEnd = marker.end();
			markerCount++;
		}
		if (markerCount < 2 || firstStart <= 0 || lastEnd < 0) {
			return source;
		}
		String heading = source.substring(0, firstStart).replaceAll("\\s+", " ").trim();
		if (heading.length() < 2
			|| heading.length() > 60
			|| !SAFE_OCR_HEADING.matcher(heading).matches()) {
			return source;
		}
		String remainder = source.substring(lastEnd).trim();
		boolean removed = false;
		while (remainder.equals(heading) || remainder.startsWith(heading + " ")) {
			remainder = remainder.substring(heading.length()).trim();
			removed = true;
		}
		return removed && !remainder.isBlank() ? remainder : source;
	}

	List<String> splitCommaJoinedAssertions(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		List<String> assertions = new ArrayList<>();
		for (String assertion : splitIndependentAssertionCommas(text)) {
			String cleaned = assertion.replaceAll("\\s+", " ").trim();
			if (!cleaned.isBlank()) {
				assertions.add(cleaned);
			}
		}
		return List.copyOf(assertions);
	}

	private List<String> splitIndependentAssertionCommas(String text) {
		List<String> clauses = new ArrayList<>();
		Matcher matcher = ASSERTION_COMMA.matcher(String.valueOf(text == null ? "" : text));
		int start = 0;
		while (matcher.find()) {
			String left = text.substring(start, matcher.start()).trim();
			String right = text.substring(matcher.end()).trim();
			if (hasCompleteAssertion(left) && hasCompleteAssertion(right)) {
				clauses.add(left);
				start = matcher.end();
			}
		}
		clauses.add(text.substring(start));
		return clauses;
	}

	private List<String> splitConnectives(String text, boolean strictCoordinatingBoundaries) {
		List<String> clauses = new ArrayList<>();
		for (String exceptionBounded : splitExplicitExceptions(text)) {
			MatcherState state = new MatcherState(exceptionBounded);
			Matcher matcher = COORDINATING_BOUNDARY.matcher(exceptionBounded);
			while (matcher.find()) {
				String leftClause = state.currentClause(matcher.start());
				String rightClause = rightClauseBeforeNextBoundary(exceptionBounded, matcher.end());
				if (!isAtomicClauseBoundary(
					leftClause,
					rightClause,
					strictCoordinatingBoundaries
				)) {
					continue;
				}
				state.addBoundary(
					clauses,
					matcher.start(),
					matcher.end(),
					sharedMatrixSubjectPrefix(leftClause, rightClause)
				);
			}
			state.addRemainder(clauses);
		}
		return clauses;
	}

	private List<String> splitExplicitExceptions(String text) {
		String source = String.valueOf(text == null ? "" : text);
		List<String> clauses = new ArrayList<>();
		Matcher matcher = EXPLICIT_EXCEPTION_BOUNDARY.matcher(source);
		int start = 0;
		while (matcher.find()) {
			if (hasAttachedExceptionCondition(source, matcher.start())) {
				continue;
			}
			String clause = source.substring(start, matcher.start());
			if (!clause.isBlank()) {
				clauses.add(clause);
			}
			start = matcher.start();
		}
		String remainder = source.substring(start);
		if (!remainder.isBlank()) {
			clauses.add(remainder);
		}
		return clauses;
	}

	private boolean hasAttachedExceptionCondition(String source, int exceptionStart) {
		String prefix = source.substring(0, Math.max(0, exceptionStart))
			.replaceAll("[\\s,，]+", "");
		return ATTACHED_EXCEPTION_CONDITION.matcher(prefix).find();
	}

	private String rightClauseBeforeNextBoundary(String text, int start) {
		Matcher nextBoundary = COORDINATING_BOUNDARY.matcher(text);
		if (nextBoundary.find(start)) {
			return text.substring(start, nextBoundary.start());
		}
		return text.substring(start);
	}

	private boolean isAtomicClauseBoundary(
		String leftClause,
		String rightClause,
		boolean strictCoordinatingBoundaries
	) {
		String normalized = leftClause.replaceAll("[\\s,，]+", "");
		if (!hasIndependentAssertion(rightClause)) {
			return false;
		}
		Set<String> leftSubjects = explicitSubjects(leftClause, false);
		Set<String> rightSubjects = explicitSubjects(rightClause, !leftSubjects.isEmpty());
		if (strictCoordinatingBoundaries
			&& !rightSubjects.isEmpty()
			&& GENERAL_COORDINATED_PREDICATE.matcher(normalized).find()
			&& !isNonAtomicPremise(normalized)) {
			return true;
		}
		if (isRestrictiveRemainder(rightClause)
			&& (!distinctNonEmpty(leftSubjects, rightSubjects)
				|| !hasIndependentClassification(rightClause))) {
			return false;
		}
		boolean distinctObjects = distinctNonEmpty(
			explicitObjects(leftClause),
			explicitObjects(rightClause)
		);
		boolean distinctRecipients = distinctNonEmpty(
			explicitRecipients(leftClause),
			explicitRecipients(rightClause)
		);
		if (GENERAL_COORDINATED_PREDICATE.matcher(normalized).find()
			&& !isNonAtomicPremise(normalized)
			&& oppositePermissionPolarity(leftClause, rightClause)) {
			return true;
		}
		if (normalized.endsWith("지만")) {
			Set<String> leftObjects = explicitObjects(leftClause);
			Set<String> rightObjects = explicitObjects(rightClause);
			if (!leftObjects.isEmpty() && !rightObjects.isEmpty()
				&& (!leftObjects.equals(rightObjects)
					|| oppositePermissionPolarity(leftClause, rightClause))) {
				return true;
			}
			if (distinctRecipients) {
				return true;
			}
			if (distinctNonEmpty(
				explicitPermissionActions(leftClause),
				explicitPermissionActions(rightClause)
			)) {
				return true;
			}
			return !leftSubjects.isEmpty() && !rightSubjects.isEmpty();
		}
		if (!leftSubjects.isEmpty()
			&& rightSubjects.isEmpty()
			&& (distinctObjects || distinctRecipients)
			&& GENERAL_COORDINATED_PREDICATE.matcher(normalized).find()
			&& !isNonAtomicPremise(normalized)) {
			return true;
		}
		if (!leftSubjects.isEmpty()
			&& !rightSubjects.isEmpty()
			&& GENERAL_COORDINATED_PREDICATE.matcher(normalized).find()
			&& !isNonAtomicPremise(normalized)) {
			return true;
		}
		return !leftSubjects.isEmpty()
			&& !rightSubjects.isEmpty()
			&& List.of(
			"대상이며", "비대상이며", "대상이고", "비대상이고",
			"제외되며", "면제되며", "금지되며", "허용되며",
			"필수이며", "의무이며", "가능하며", "불가능하며",
			"할수있으며", "할수없으며", "하지않아도되며", "생략할수있으며"
		).stream().anyMatch(normalized::endsWith);
	}

	private boolean distinctNonEmpty(Set<String> left, Set<String> right) {
		return !left.isEmpty() && !right.isEmpty() && !left.equals(right);
	}

	private String sharedMatrixSubjectPrefix(String leftClause, String rightClause) {
		if (explicitSubjects(leftClause, false).isEmpty()
			|| !explicitSubjects(rightClause, true).isEmpty()
			|| (!distinctNonEmpty(explicitObjects(leftClause), explicitObjects(rightClause))
				&& !distinctNonEmpty(
					explicitRecipients(leftClause),
					explicitRecipients(rightClause)
				))) {
			return "";
		}
		String source = String.valueOf(leftClause == null ? "" : leftClause);
		Matcher matcher = EXPLICIT_SUBJECT.matcher(source);
		String prefix = "";
		int matrixSubjects = 0;
		while (matcher.find()) {
			if (isEmbeddedAttributiveSubject(source, matcher, false)) {
				continue;
			}
			matrixSubjects++;
			int boundary = lastStructuralBoundary(source, matcher.start());
			prefix = source.substring(boundary + 1, matcher.end()).trim();
		}
		return matrixSubjects == 1 ? prefix : "";
	}

	private int lastStructuralBoundary(String source, int before) {
		int boundary = -1;
		for (char delimiter : new char[] {'.', '!', '?', ';', '；', ',', '，', ':', '：', '\n', '\r'}) {
			boundary = Math.max(boundary, source.lastIndexOf(delimiter, Math.max(0, before - 1)));
		}
		return boundary;
	}

	private boolean hasIndependentAssertion(String clause) {
		String source = String.valueOf(clause == null ? "" : clause).trim();
		return INDEPENDENT_ASSERTION_ENDING.matcher(source).find()
			|| GENERAL_COORDINATED_PREDICATE.matcher(
				source.replaceAll("[\\s,，]+", "")
			).find();
	}

	private boolean hasCompleteAssertion(String clause) {
		return INDEPENDENT_ASSERTION_ENDING.matcher(
			String.valueOf(clause == null ? "" : clause).trim()
		).find();
	}

	private boolean isRestrictiveRemainder(String clause) {
		String normalized = String.valueOf(clause == null ? "" : clause)
			.replaceAll("[\\s,，]+", "");
		return RESTRICTIVE_REMAINDER.matcher(normalized).find()
			|| RESTRICTIVE_MODAL_REMAINDER.matcher(normalized).find();
	}

	private boolean hasIndependentClassification(String clause) {
		String normalized = String.valueOf(clause == null ? "" : clause)
			.replaceAll("[\\s,，]+", "");
		return List.of(
			"대상", "비대상", "제외", "면제", "금지", "허용", "가능", "불가능"
		).stream().anyMatch(normalized::contains);
	}

	private boolean isNonAtomicPremise(String normalizedLeft) {
		String stem = normalizedLeft.replaceFirst("(?:하고|하되|되며|이며|으며|이고|지만)$", "");
		return NON_ATOMIC_PREMISE_STEMS.stream().anyMatch(stem::endsWith);
	}

	private Set<String> explicitSubjects(String clause, boolean suppressStandaloneAttributive) {
		Set<String> subjects = new LinkedHashSet<>();
		Matcher matcher = EXPLICIT_SUBJECT.matcher(String.valueOf(clause == null ? "" : clause));
		while (matcher.find()) {
			if (isEmbeddedAttributiveSubject(clause, matcher, suppressStandaloneAttributive)) {
				continue;
			}
			String head = normalizeIdentity(matcher.group(1));
			if (head.length() >= 2) {
				subjects.add(head);
			}
			String qualified = qualifiedSubjectIdentity(
				clause,
				matcher.start(1),
				matcher.group(1)
			);
			if (qualified.length() >= 2) {
				subjects.add(qualified);
			}
		}
		return Set.copyOf(subjects);
	}

	private boolean isEmbeddedAttributiveSubject(
		String clause,
		Matcher subjectMatcher,
		boolean suppressStandaloneAttributive
	) {
		String source = String.valueOf(clause == null ? "" : clause);
		String matchedToken = source.substring(subjectMatcher.start(1), subjectMatcher.end());
		if (ATTRIBUTIVE_VERB_ROLE_TOKEN.matcher(matchedToken).find()) {
			return true;
		}
		if (!looksLikeAttributiveSubject(source, subjectMatcher)) {
			return false;
		}
		Matcher otherSubject = EXPLICIT_SUBJECT.matcher(source);
		while (otherSubject.find()) {
			if (otherSubject.start() == subjectMatcher.start()
				&& otherSubject.end() == subjectMatcher.end()) {
				continue;
			}
			char otherParticle = source.charAt(otherSubject.end() - 1);
			if (otherParticle == '은'
				|| otherParticle == '는'
				|| !looksLikeAttributiveSubject(source, otherSubject)) {
				return true;
			}
		}
		return suppressStandaloneAttributive;
	}

	private boolean looksLikeAttributiveSubject(String source, Matcher subjectMatcher) {
		if (subjectMatcher.end() <= 0) {
			return false;
		}
		char particle = source.charAt(subjectMatcher.end() - 1);
		if (particle != '이' && particle != '가') {
			return false;
		}
		return EMBEDDED_ATTRIBUTIVE_REMAINDER.matcher(
			source.substring(subjectMatcher.end())
		).find();
	}

	private String qualifiedSubjectIdentity(String clause, int subjectStart, String rawHead) {
		String head = normalizeIdentity(rawHead);
		String source = String.valueOf(clause == null ? "" : clause);
		String prefix = source.substring(0, Math.max(0, Math.min(subjectStart, source.length())));
		int boundary = -1;
		for (char delimiter : new char[] {'.', '!', '?', ';', '；', ',', '，', ':', '：', '\n', '\r'}) {
			boundary = Math.max(boundary, prefix.lastIndexOf(delimiter));
		}
		String[] candidates = prefix.substring(boundary + 1).trim().split("\\s+");
		List<String> modifiers = new ArrayList<>();
		for (int index = candidates.length - 1; index >= 0; index--) {
			String cleaned = candidates[index]
				.replaceAll("^[^\\p{IsHangul}A-Za-z0-9()·ㆍ/\\-]+", "")
				.replaceAll("[^\\p{IsHangul}A-Za-z0-9()·ㆍ/\\-]+$", "");
			String normalized = normalizedSubjectModifier(cleaned);
			if (normalized.length() < 2 || isSubjectModifierBoundary(cleaned, normalized)) {
				break;
			}
			modifiers.add(0, normalized);
		}
		if (modifiers.isEmpty()) {
			return head;
		}
		return normalizeIdentity(String.join("", modifiers) + head);
	}

	private String normalizedSubjectModifier(String rawToken) {
		String normalized = normalizeIdentity(rawToken);
		if (rawToken.endsWith("의")
			&& rawToken.length() >= 3
			&& LEXICAL_UI_ENDINGS.stream().noneMatch(normalized::endsWith)) {
			return normalizeIdentity(rawToken.substring(0, rawToken.length() - 1));
		}
		return normalized;
	}

	private boolean isSubjectModifierBoundary(String rawToken, String normalizedToken) {
		return SUBJECT_MODIFIER_BOUNDARY_WORDS.contains(normalizedToken)
			|| SUBJECT_MODIFIER_BOUNDARY_SUFFIXES.stream().anyMatch(rawToken::endsWith)
			|| SUBJECT_MODIFIER_SINGLE_PARTICLES.stream().anyMatch(particle ->
				hasSyntacticallyCompatibleTrailingParticle(rawToken, particle)
			);
	}

	private boolean hasSyntacticallyCompatibleTrailingParticle(
		String rawToken,
		String particle
	) {
		if (rawToken.length() < 3 || !rawToken.endsWith(particle)) {
			return false;
		}
		char stemEnding = rawToken.charAt(rawToken.length() - particle.length() - 1);
		if (stemEnding < 0xAC00 || stemEnding > 0xD7A3) {
			return true;
		}
		boolean hasFinalConsonant = (stemEnding - 0xAC00) % 28 != 0;
		return Set.of("은", "이", "을", "과").contains(particle)
			? hasFinalConsonant
			: !hasFinalConsonant;
	}

	private String normalizeIdentity(String value) {
		return String.valueOf(value == null ? "" : value)
			.replaceAll("[^\\p{IsHangul}A-Za-z0-9]", "")
			.toLowerCase();
	}

	private Set<String> explicitObjects(String clause) {
		Set<String> objects = new LinkedHashSet<>();
		String source = String.valueOf(clause == null ? "" : clause);
		Matcher matcher = EXPLICIT_OBJECT.matcher(source);
		while (matcher.find()) {
			addQualifiedRoleIdentity(objects, source, matcher);
		}
		return Set.copyOf(objects);
	}

	private Set<String> explicitRecipients(String clause) {
		Set<String> recipients = new LinkedHashSet<>();
		Matcher matcher = EXPLICIT_RECIPIENT.matcher(
			String.valueOf(clause == null ? "" : clause)
		);
		while (matcher.find()) {
			addQualifiedRoleIdentity(recipients, clause, matcher);
		}
		return Set.copyOf(recipients);
	}

	private Set<String> explicitPermissionActions(String clause) {
		Set<String> actions = new LinkedHashSet<>();
		Matcher matcher = EXPLICIT_PERMISSION_ACTION.matcher(
			String.valueOf(clause == null ? "" : clause)
		);
		while (matcher.find()) {
			String action = normalizeIdentity(matcher.group(1));
			if (action.length() >= 2) {
				actions.add(action);
			}
		}
		return Set.copyOf(actions);
	}

	private void addQualifiedRoleIdentity(Set<String> roles, String clause, Matcher matcher) {
		String head = normalizeIdentity(matcher.group(1));
		if (head.length() >= 2) {
			roles.add(head);
		}
		String qualified = qualifiedSubjectIdentity(
			clause,
			matcher.start(1),
			matcher.group(1)
		);
		if (qualified.length() >= 2) {
			roles.add(qualified);
		}
	}

	private boolean oppositePermissionPolarity(String leftClause, String rightClause) {
		PermissionPolarity left = permissionPolarity(leftClause);
		PermissionPolarity right = permissionPolarity(rightClause);
		return left != PermissionPolarity.UNSPECIFIED
			&& right != PermissionPolarity.UNSPECIFIED
			&& left != right;
	}

	private PermissionPolarity permissionPolarity(String clause) {
		String normalized = String.valueOf(clause == null ? "" : clause)
			.replaceAll("[\\s,.!?;；，]+", "");
		if (PROHIBITED_PERMISSION_ENDINGS.stream().anyMatch(normalized::endsWith)) {
			return PermissionPolarity.PROHIBITED;
		}
		if (ALLOWED_PERMISSION_ENDINGS.stream().anyMatch(normalized::endsWith)) {
			return PermissionPolarity.ALLOWED;
		}
		return PermissionPolarity.UNSPECIFIED;
	}

	private enum PermissionPolarity {
		ALLOWED,
		PROHIBITED,
		UNSPECIFIED
	}

	private static final class MatcherState {
		private final String text;
		private int start;
		private String prefix = "";

		private MatcherState(String text) {
			this.text = text;
		}

		private String currentClause(int end) {
			return (prefix + " " + text.substring(start, end)).trim();
		}

		private void addBoundary(
			List<String> clauses,
			int boundaryStart,
			int nextStart,
			String nextPrefix
		) {
			clauses.add(currentClause(boundaryStart));
			start = nextStart;
			prefix = String.valueOf(nextPrefix == null ? "" : nextPrefix).trim();
		}

		private void addRemainder(List<String> clauses) {
			clauses.add(currentClause(text.length()));
		}
	}
}
