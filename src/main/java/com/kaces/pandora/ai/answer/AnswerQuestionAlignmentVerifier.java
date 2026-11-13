package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import com.kaces.pandora.common.text.QuestionEntity;
import com.kaces.pandora.common.text.QuestionIntentProfile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
	private static final String ANSWER_COVERAGE = "ANSWER_COVERAGE";
	private static final Pattern IRM_DEFINITION_REQUEST = Pattern.compile(
		"^(?:irm|정보자원관리시스템)(?:에서|의)?정보자원등록충실성(?:지표)?(?:의)?"
			+ "(?:의미(?:를설명해주세요|는무엇인가요|인가요)|이란무엇인가요)$");
	private static final Pattern NUMBERED_IRM_CRITERION = Pattern.compile(
		"(?m)^([①-⑳])\\h+(정보자원관리시스템\\(IRM\\)[^\\r\\n]{1,80})\\h*\\R"
			+ "\\h*\\1-\\d{1,2}\\h*\\R\\h*([^\\r\\n]{1,180})$");
	private static final Pattern TERMINAL_REQUEST = Pattern.compile("(?U)\\s+(알려주세요|있습니까|무엇입니까)[.!?。？]*\\s*\\z");
	private static final Pattern SPOKEN_APPLICATION_DUTY = Pattern.compile(
		"(?U)(?<=\\s)([가-힣]{2,25})[을를]\\s+적용해야\\s+하나요[?？]\\s*\\z");
	private static final Pattern REPORT_DUTY_QUESTION = Pattern.compile(
		"^((?:단계별)?[가-힣A-Za-z0-9]{1,35}결과보고서)에따른시정조치의무$");
	private static final Pattern NOMINAL_SUBMISSION_LIST = Pattern.compile(
		"(?U)\\s+제출\\s+(문서|서류|자료)[.?？]*\\s*\\z");
	private static final Pattern NOMINAL_SUBMISSION_TRIGGER = Pattern.compile(
		"(?U)\\s([가-힣]{2,20})\\s+시\\s+제출\\s+(?:문서|서류|자료)[.?？]*\\s*\\z");
	private static final Pattern CONTRACT_STANDARD_AUTHORITY = Pattern.compile(
		"(?U)^\\s*([가-힣A-Za-z0-9][가-힣A-Za-z0-9 \\t]{2,88}?계약[ \\t]*체결[ \\t]*기준)"
			+ "[ \\t]*(?:에[ \\t]*(?:따라|따른)|에서)[ \\t]+(.+)$");
	private static final Pattern DIRECT_CONCLUSION_PATTERN = Pattern.compile(
		"(?:입니다|합니다|한다|된다|됩니다|아니다|없습니다|있습니다|"
			+ "하여야\s*한다|해야\s*한다|할\s*수\s*있다|할\s*수\s*없다|"
			+ "하지\s*않는다|금지된다|제외된다|비대상이다)(?:[.?!]|$)"
	);
	private static final List<MetaPredicateFrame> NON_CONCLUSION_META_FRAMES = List.of(
		new MetaPredicateFrame("확인", List.of("확인합니다", "확인한다")),
		new MetaPredicateFrame("검토", List.of("검토합니다", "검토한다")),
		new MetaPredicateFrame("문의", List.of("문의합니다", "문의한다")),
		new MetaPredicateFrame("질문", List.of("질문합니다", "질문한다")),
		new MetaPredicateFrame("알아보", List.of("알아봅니다", "알아본다")),
		new MetaPredicateFrame("파악", List.of("파악합니다", "파악한다")),
		new MetaPredicateFrame("조사", List.of("조사합니다", "조사한다")),
		new MetaPredicateFrame("검증", List.of("검증합니다", "검증한다")),
		new MetaPredicateFrame("살펴보", List.of("살펴봅니다", "살펴본다"))
	);
	private static final List<String> REQUESTED_META_PREDICATE_ENDINGS = List.of(
		"하나요", "하나", "합니까",
		"하는가요", "하는가", "하는지",
		"할까요", "할까", "해요", "해",
		"나요", "는가요", "는가", "는지"
	);
	private static final List<String> NON_CONCLUSION_INTERROGATIVE_ENDINGS = List.of(
		"인가요", "인가",
		"인지요", "인지",
		"일까요", "일까",
		"할까요", "할까",
		"하나요", "하나",
		"여부"
	);
	private static final Pattern NUMERIC_AMOUNT_PATTERN = Pattern.compile(
		"(?<!\\d)\\d+(?:,\\d{3})*(?:\\.\\d+)?\\s*(?:조원|억원|만원|원|%|퍼센트)"
			+ "(?=(?:은|는|이|가|을|를|으로|에서|부터|까지|입니다|이다|이고|이며|[\\s,.!?]|$))"
	);
	private static final Pattern PERIOD_VALUE_PATTERN = Pattern.compile(
		"(?:(?<![\\p{Alnum}제])\\d{4}(?:\\s*[./-]\\s*\\d{1,2}){1,2}|"
			+ "(?<![\\p{Alnum}제])\\d+(?:년|개월|월|일|주|시간|분|초))"
			+ "(?=(?:은|는|이|가|을|를|부터|까지|입니다|이다|이고|이며|[\\s,.!?]|$))|"
			+ "(?:(?:[\\p{IsHangul}A-Za-z0-9]{2,20}\\s+){0,2}"
			+ "(?:확정|계약|체결|신청|접수|제출|공고|시행|선정|승인|허가|등록|발주|착수|완료|종료|개시|도입|구축|운영|발생|통지|수립|"
			+ "월말|연말|분기말|마감일|시행일|공고일|신청일|접수일|제출일|선정일|계약일|착수일|완료일|종료일)"
			+ "(?:\\s*(?:일|시점))?\\s*(?:이전에|이전|전에는|전에|전|이후에|이후|후에는|후에|후|부터|까지))"
			+ "(?=[\\s,.!?]|$)|"
			+ "(?<![가-힣])[가-힣]{2,20}하기\\s+전까지(?=[\\s,.!?]|$)"
	);
	private static final Pattern PROPOSITION_BOUNDARY = Pattern.compile(
		"(?:별개로|무관하게|관계없이|반면(?:에)?|한편|다만|그러나|하지만)[,，]?\\s*"
	);
	private static final Pattern CONFIGURED_POLICY_CONDITION_FRAME = Pattern.compile(
		"[\\p{IsHangul}]+(?:으면|면|는데|인데)(?=\\s|[,.!?？]|$)"
	);
	private static final List<Pattern> CLASSIFICATION_RELATION_PATTERNS = List.of(
		Pattern.compile("(?<![\\p{IsHangul}\\p{Alnum}])([\\p{IsHangul}\\p{Alnum}]{2,})(?:이라고|라고)\\s*볼\\s*수\\s*(?:있|없)"),
		Pattern.compile("(?<![\\p{IsHangul}\\p{Alnum}])([\\p{IsHangul}\\p{Alnum}]{2,})(?:으로|로)\\s*볼\\s*수\\s*(?:있|없)"),
		Pattern.compile("(?<![\\p{IsHangul}\\p{Alnum}])([\\p{IsHangul}\\p{Alnum}]{2,})에\\s*해당하"),
		Pattern.compile("(?<![\\p{IsHangul}\\p{Alnum}])([\\p{IsHangul}\\p{Alnum}]{2,})(?:인가요|인가|인지요|인지)(?:[?？]|\\s|$)")
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
	private final ClaimEvidenceAtomizer atomizer = new ClaimEvidenceAtomizer();

	public AlignmentResult verify(String question, ClaimVerifier.VerificationResult claimResult) {
		return verify(question, claimResult, List.of());
	}

	public AlignmentResult verify(
		String question,
		ClaimVerifier.VerificationResult claimResult,
		List<LawAiAnswerGround> grounds
	) {
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
		String questionScope = normalize(question);
		if (questionScope.contains("개인정보") && questionScope.contains("동의")
			&& thirdPartyProvision(questionScope)
			&& AlignmentProfile.containsAny(questionScope, "고지", "알려", "안내")
			&& supportedLinks.stream()
				.flatMap(link -> atomizer.atomizeForAlignment(link.claim()).stream())
				.map(this::directProposition)
				.map(AnswerQuestionAlignmentVerifier::normalize)
				.noneMatch(claim -> thirdPartyProvision(claim)
					&& claim.contains("동의") && AlignmentProfile.containsAny(claim, "고지", "알려", "안내"))
			&& !hasOwnedProvisionNoticeReference(supportedLinks, grounds)) {
			return AlignmentResult.failed("MISSING_CONDITION", List.of(CONDITION));
		}
		if (!hasAnswerClaimCoverage(supportedLinks, alignmentProfile)) {
			return AlignmentResult.failed("MISSING_ANSWER_COVERAGE", List.of(ANSWER_COVERAGE));
		}
		boolean completeAnswerCoverage = hasCompleteAnswerCoverage(supportedLinks, alignmentProfile);
		Map<Integer, String> subjectContextByGround = subjectContextByGround(grounds);
		List<SourceOwnedConditionalScope.Rule> scopeRules=SourceOwnedConditionalScope.index(grounds);
		List<CandidateResult> candidates = new ArrayList<>(supportedLinks.stream()
			.flatMap(link -> assess(
				link,
				alignmentProfile,
				subjectContextByGround.getOrDefault(link.groundNumber(), ""),
				completeAnswerCoverage,
                scopeRules.stream().filter(rule->rule.owner()==link.groundNumber()).toList()
			).stream())
			.toList());
		if (completeAnswerCoverage && !claimResult.insufficientEvidence()
			&& (claimResult.strongClaimCount() == 2 || claimResult.strongClaimCount() == 3)
			&& claimResult.unsupportedClaims().isEmpty() && claimResult.unsupportedNumericClaims().isEmpty()
			&& claimResult.contradictedClaims().isEmpty()) {
			if (claimResult.strongClaimCount() == 2) {
				addLiteralNumberedDutyCandidate(question, grounds, supportedLinks, alignmentProfile, subjectContextByGround,
					scopeRules, candidates);
				addOwnedIrmDefinition(questionScope, grounds, supportedLinks, candidates);
			}
			if (claimResult.strongClaimCount() == supportedLinks.size()
				&& candidates.stream().noneMatch(candidate -> candidate.missingGroups().isEmpty())) {
				addLiteralNumberedProvisoCandidate(grounds, supportedLinks, alignmentProfile, scopeRules, candidates);
			}
		}
		for (CandidateResult candidate : candidates) {
			if (candidate.missingGroups().isEmpty()) {
				if (!completeAnswerCoverage) {
					return AlignmentResult.failed(
						"MISSING_ANSWER_COVERAGE",
						List.of(ANSWER_COVERAGE)
					);
				}
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

	private void addOwnedIrmDefinition(String question, List<LawAiAnswerGround> grounds,
		List<ClaimVerifier.ClaimEvidenceLink> links, List<CandidateResult> candidates) {
		if (!IRM_DEFINITION_REQUEST.matcher(question).matches() || grounds == null || links.size() != 2) return;
		var heading = links.get(0);
		var criterion = links.get(1);
		if (heading.groundNumber() <= 0 || heading.groundNumber() != criterion.groundNumber()) return;
		var owners = grounds.stream().filter(g -> g != null && g.number() == heading.groundNumber()).toList();
		if (owners.size() != 1) return;
		var owner = owners.get(0);
		if (!"official_doc".equals(owner.target()) || !"CURRENT".equals(owner.effectiveStatus())
			|| owner.matchedChildText() == null) return;
		var match = NUMBERED_IRM_CRITERION.matcher(owner.matchedChildText());
		if (!match.find()) return;
		String originalHeading = foldedWhitespace(match.group(2));
		String originalCriterion = foldedWhitespace(match.group(3));
		if (match.find() || !EvidenceJudge.hasIrmRegistrationCriterion(originalCriterion)
			|| !originalHeading.equals(foldedWhitespace(heading.claim()))
			|| !originalCriterion.equals(foldedWhitespace(criterion.claim()))) return;
		candidates.add(new CandidateResult(originalHeading + "\n" + originalCriterion, List.of()));
	}

	private static boolean thirdPartyProvision(String normalized) {
		return AlignmentProfile.containsAny(normalized, "제3자", "제삼자") && normalized.contains("제공");
	}

	private static final String PROVISION_NOTICE_HEADER =
		"개인정보처리자는제1항제1호에따른동의를받을때에는다음각호의사항을정보주체에게알려야한다";

	private static boolean hasOwnedProvisionNoticeReference(
		List<ClaimVerifier.ClaimEvidenceLink> links, List<LawAiAnswerGround> grounds
	) {
		if (grounds == null) return false;
		for (var link : links) {
			if (!PROVISION_NOTICE_HEADER.equals(normalize(link.claim()))
				|| !PROVISION_NOTICE_HEADER.equals(normalize(link.evidenceSentence()))) continue;
			var owners = grounds.stream().filter(g -> g != null && g.number() == link.groundNumber()).toList();
			if (owners.size() != 1) continue;
			var owner = owners.get(0);
			String child = owner.matchedChildText();
			if (!"law".equals(owner.target()) || !"CURRENT".equals(owner.effectiveStatus())
				|| !"direct".equals(owner.evidenceRole()) || owner.documentId() <= 0 || owner.chunkId() <= 0
				|| child == null || child.length() > 4000) continue;
			var lines = child.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
			int notice = -1;
			for (int i = 0; i < lines.size(); i++) {
				if (!lines.get(i).startsWith("②")) continue;
				String firstSentence = lines.get(i).substring(1).split("\\.", 2)[0];
				if (!PROVISION_NOTICE_HEADER.equals(normalize(firstSentence))) continue;
				if (notice >= 0) { notice = -1; break; }
				notice = i;
			}
			if (notice < 3) continue;
			String opening = lines.get(notice - 3);
			if (!opening.startsWith("①") || !normalize(opening.replaceFirst("\\s*<[^<>]*>\\s*$", "")).matches(
				"개인정보처리자는다음각호의어느하나에해당(?:되는|하는)경우(?:에는)?"
					+ "정보주체의개인정보를제3자에게제공(?:공유를포함한다이하같다)?할수있다")
				|| !lines.get(notice - 2).matches("1\\.\\s*정보주체의 동의를 받은 경우")
				|| !lines.get(notice - 1).matches("2\\.\\s*[^①②③]+")) continue;
			return true;
		}
		return false;
	}

	private boolean hasAnswerClaimCoverage(List<ClaimVerifier.ClaimEvidenceLink> links, AlignmentProfile profile) {
		return profile.answerClaimGroups().isEmpty() || links.stream()
			.flatMap(link -> atomizer.atomizeForAlignment(link.claim()).stream())
			.map(AnswerQuestionAlignmentVerifier::normalize)
			.anyMatch(atom -> profile.answerClaimGroups().stream()
				.allMatch(group -> group.stream().anyMatch(atom::contains)));
	}

	private void addLiteralNumberedDutyCandidate(String question, List<LawAiAnswerGround> grounds,
		List<ClaimVerifier.ClaimEvidenceLink> links, AlignmentProfile profile,
		Map<Integer, String> subjectContext, List<SourceOwnedConditionalScope.Rule> scopeRules,
		List<CandidateResult> candidates) {
		if (grounds == null || links.size() != 2) return;
		var header = links.get(0);
		var items = links.get(1);
		if (header.groundNumber() <= 0 || header.groundNumber() != items.groundNumber()) return;
		var owners = grounds.stream().filter(g -> g != null && g.number() == header.groundNumber()).toList();
		if (owners.size() != 1) return;
		var owner = owners.get(0);
		if (!("law".equals(owner.target()) || "admrul".equals(owner.target()))) return;
		var parts = ParentContextAssembler.exactNumberedDutyParts(owner.matchedChildText());
		if (parts.size() != 2 || !parts.get(0).equals(header.claim()) || !parts.get(0).equals(header.evidenceSentence())
			|| !parts.get(1).equals(items.claim()) || !parts.get(1).equals(items.evidenceSentence())) return;
		var authority = CONTRACT_STANDARD_AUTHORITY.matcher(question == null ? "" : question);
		if (authority.matches()) {
			if (!"admrul".equals(owner.target()) || !"CURRENT".equals(owner.effectiveStatus())
				|| owner.title() == null
				|| !normalize(authority.group(1)).equals(normalize(owner.title().replaceFirst("^\\s*\\(계약예규\\)\\s*", "")))) return;
			// The exact source title satisfies the authority reference, not an operative predicate.
			profile = AlignmentProfile.from(authority.group(2));
			if (!profile.usable() || !hasAnswerClaimCoverage(links, profile) || !hasCompleteAnswerCoverage(links, profile)) return;
		}
		// Re-evaluate the proven original paragraph using the same subject, relation and condition checks.
		String paragraph = owner.matchedChildText();
		candidates.add(assessAtom(paragraph, paragraph, profile, subjectContext.getOrDefault(owner.number(), ""), true,
			scopeRules.stream().filter(rule -> rule.owner() == owner.number()).toList()));
	}

	private void addLiteralNumberedProvisoCandidate(List<LawAiAnswerGround> grounds,
		List<ClaimVerifier.ClaimEvidenceLink> links, AlignmentProfile profile,
		List<SourceOwnedConditionalScope.Rule> scopeRules, List<CandidateResult> candidates) {
		if (grounds == null || (links.size() != 2 && links.size() != 3)) return;
		var main = links.get(links.size() - 2);
		var exception = links.get(links.size() - 1);
		if (main.groundNumber() <= 0 || main.groundNumber() != exception.groundNumber()) return;
		String mainText = foldedWhitespace(main.claim());
		String exceptionText = foldedWhitespace(exception.claim());
		if (!mainText.endsWith(".") || !exceptionText.startsWith("다만, ") || !exceptionText.endsWith(".")
			|| !mainText.equals(foldedWhitespace(main.evidenceSentence()))
			|| !exceptionText.equals(foldedWhitespace(exception.evidenceSentence()))) return;
		var owners = grounds.stream().filter(ground -> ground != null && ground.number() == main.groundNumber()).toList();
		if (owners.size() != 1 || owners.get(0).matchedChildText() == null) return;
		String item = mainText + " " + exceptionText;
		if (item.length() > 1200) return;
		String exact = java.util.Arrays.stream(item.split(" ")).map(Pattern::quote)
			.collect(java.util.stream.Collectors.joining("\\s+"));
		var located = Pattern.compile("(?m)^[ \\t]*[1-9][0-9]?\\.[ \\t]+" + exact + "[ \\t]*(?=\\R|$)")
			.matcher(owners.get(0).matchedChildText());
		if (!located.find()) return;
		int start = located.start(), end = located.end();
		if (located.find()) return;
		String actor = "";
		if (links.size() == 3) {
			var header = links.get(0);
			if (header.groundNumber() != main.groundNumber()) return;
			actor = verifiedParagraphActor(owners.get(0).matchedChildText(), start, end, header, exceptionText);
			if (actor.isEmpty()) return;
		}
		// Only the subject check receives the exact owning item. The proviso still owns the predicate and conditions.
		candidates.add(assessAtom(exceptionText, exceptionText, profile, normalize(actor + " " + item), true,
			scopeRules.stream().filter(rule -> rule.owner() == main.groundNumber()).toList()));
	}

	private static final Pattern CLOSED_FIRST_DUTY_PARAGRAPH = Pattern.compile(
		"(?m)(?:^|(?<=\\)))[ \\t]*①[ \\t]+([^①-⑳※*]{2,480}?)\\R"
			+ "([ \\t]*1\\.[ \\t]+[^①-⑳※*]{2,1400}?)(?=^[ \\t]*②[ \\t]+)");

	private String verifiedParagraphActor(String child, int itemStart, int itemEnd,
		ClaimVerifier.ClaimEvidenceLink header, String proviso) {
		var paragraphs = CLOSED_FIRST_DUTY_PARAGRAPH.matcher(child);
		while (paragraphs.find()) {
			if (itemStart < paragraphs.start(2) || itemEnd > paragraphs.end(2)) continue;
			String literal = foldedWhitespace(paragraphs.group(1))
				.replaceFirst(" <개정 [0-9., ]+>$", "");
			String key = literal.replaceAll("\\s+", "");
			if (!literal.endsWith("한다.") || literal.indexOf('.') != literal.length() - 1
				|| !literal.contains("다음 각 호의 ")
				|| !key.equals(foldedWhitespace(header.claim()).replaceAll("\\s+", ""))
				|| !key.equals(foldedWhitespace(header.evidenceSentence()).replaceAll("\\s+", ""))
				|| !atomizer.hasConsecutiveDutyItems(paragraphs.group(2))) return "";
			int conditionEnd = proviso.lastIndexOf("경우에는 ");
			if (conditionEnd < 0) return "";
			var parser = new KoreanEvidenceAtomParser();
			// A quoted local definition is not a second actor. The full literal was already checked above.
			var owner = parser.parse(literal.replaceAll(
				"\\(이하\\s*이\\s*조에서\\s*[“\"][가-힣A-Za-z0-9 ]{1,40}[”\"](?:이)?라\\s*한다\\)", ""));
			var consequence = parser.parse(proviso.substring(conditionEnd + "경우에는 ".length()));
			if (owner.parseStatus() != EvidenceAtom.ParseStatus.COMPLETE || owner.subjects().size() != 1
				|| owner.modality() != EvidenceAtom.Modality.REQUIRED
				|| consequence.modality() != EvidenceAtom.Modality.REQUIRED || !consequence.subjects().isEmpty()) return "";
			return owner.subjects().iterator().next();
		}
		return "";
	}

	private static String foldedWhitespace(String text) {
		return text == null ? "" : text.strip().replaceAll("\\s+", " ");
	}

	private Map<Integer, String> subjectContextByGround(List<LawAiAnswerGround> grounds) {
		if (grounds == null || grounds.isEmpty()) {
			return Map.of();
		}
		Map<Integer, String> contexts = new LinkedHashMap<>();
		for (LawAiAnswerGround ground : grounds) {
			if (ground == null) {
				continue;
			}
			String context = normalize(String.join(
				" ",
				String.valueOf(ground.title()),
				String.valueOf(ground.chunkTitle()),
				String.valueOf(ground.categoryName())
			));
			if (!context.isBlank()) {
				contexts.putIfAbsent(ground.number(), context);
			}
		}
		return Map.copyOf(contexts);
	}

	private boolean hasCompleteAnswerCoverage(
		List<ClaimVerifier.ClaimEvidenceLink> supportedLinks,
		AlignmentProfile profile
	) {
		if (profile.answerCoverageGroups().isEmpty()) {
			return true;
		}
		String supportedAnswer = normalize(supportedLinks.stream()
			.map(ClaimVerifier.ClaimEvidenceLink::claim)
			.reduce("", (left, right) -> left + " " + String.valueOf(right)));
		return profile.answerCoverageGroups().stream()
			.allMatch(group -> group.stream().anyMatch(supportedAnswer::contains));
	}

	private List<CandidateResult> assess(
		ClaimVerifier.ClaimEvidenceLink link,
		AlignmentProfile profile,
		String subjectContext,
		boolean completeAnswerCoverage,
        List<SourceOwnedConditionalScope.Rule> scopeRules
	) {
		List<String> claimAtoms = atomizer.atomizeForAlignment(link.claim());
		if (claimAtoms.isEmpty()) {
			claimAtoms = List.of(String.valueOf(link.claim() == null ? "" : link.claim()));
		}
		String evidenceProposition = directProposition(link.evidenceSentence());
		return claimAtoms.stream()
			.map(claimAtom -> assessAtom(
				claimAtom,
				evidenceProposition,
				profile,
				subjectContext,
				completeAnswerCoverage,scopeRules
			))
			.toList();
	}

	private CandidateResult assessAtom(
		String claimAtom,
		String evidenceProposition,
		AlignmentProfile profile,
		String subjectContext,
		boolean completeAnswerCoverage,
        List<SourceOwnedConditionalScope.Rule> scopeRules
	) {
		String claimProposition = directProposition(claimAtom);
		String normalizedClaimProposition = normalize(claimProposition);
		boolean configuredAnswerCoverageBridgeMatched = completeAnswerCoverage
			&& profile.answerCoverageGroups().stream()
				.anyMatch(group -> group.stream().anyMatch(normalizedClaimProposition::contains));
		boolean configuredPolicyBridgeMatched = matchesAllGroups(
			normalizedClaimProposition,
			profile.configuredPolicyBridgeGroups()
		);
		boolean documentIdentityBridgeMatched = profile.documentIdentityQuestion()
			&& DocumentIdentityAnswerComposer.titleMatchesAnchors(
				evidenceProposition,
				profile.documentIdentityTitleAnchors()
			)
			&& ClaimEvidenceMatcher.isDocumentIdentityClaim(
				claimProposition,
				evidenceProposition,
				subjectContext
			);
		LinkedHashSet<String> missing = new LinkedHashSet<>();
		boolean subjectMatched = matchesAllGroups(
			normalizedClaimProposition,
			profile.subjectGroups()
		) || matchesAllGroups(subjectContext, profile.subjectGroups())
			|| documentIdentityBridgeMatched;
		if (!configuredPolicyBridgeMatched
			&& !configuredAnswerCoverageBridgeMatched
			&& !subjectMatched) {
			missing.add(SUBJECT);
		}
		boolean claimHasRelation = documentIdentityBridgeMatched
			|| configuredPolicyBridgeMatched
			|| configuredAnswerCoverageBridgeMatched
			|| profile.relationRequirements().stream()
				.allMatch(requirement -> requirement.matches(claimProposition));
		if (!claimHasRelation) {
			missing.add(RELATION);
			if (profile.relationRequirements().stream()
				.allMatch(requirement -> requirement.matches(evidenceProposition))) {
				missing.add(DIRECT_CONCLUSION);
			}
		}
		if (!configuredPolicyBridgeMatched
			&& !configuredAnswerCoverageBridgeMatched
			&& !profile.conditionGroups().stream()
			.allMatch(group -> matchesCondition(claimProposition, normalizedClaimProposition, group, scopeRules))) {
			missing.add(CONDITION);
		}
		if (claimHasRelation
			&& !hasDirectConclusion(claimProposition, profile.relationRequirements())
			&& (!configuredAnswerCoverageBridgeMatched
				|| isExplicitlyNonConclusive(claimProposition, profile.relationRequirements()))) {
			missing.add(DIRECT_CONCLUSION);
		}
		return new CandidateResult(claimAtom, List.copyOf(missing));
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
		String normalized = normalize(trimmed);
		if (isExplicitlyNonConclusive(trimmed, relationRequirements)) {
			return false;
		}
		if (DIRECT_CONCLUSION_PATTERN.matcher(trimmed).find()) {
			return true;
		}
		boolean hasRequestedValue = relationRequirements.stream()
			.anyMatch(requirement ->
				requirement.kind() == RelationKind.PERIOD_VALUE
					|| requirement.kind() == RelationKind.AMOUNT_VALUE
			);
		if (hasRequestedValue
			&& relationRequirements.stream().allMatch(requirement -> requirement.matches(trimmed))) {
			return true;
		}
		return relationRequirements.stream().allMatch(requirement -> requirement.matches(normalized))
			&& List.of("대상", "비대상", "제외", "면제", "금지", "허용", "가능", "불가능")
				.stream()
				.anyMatch(normalized::endsWith);
	}

	private boolean isExplicitlyNonConclusive(
		String proposition,
		List<RelationRequirement> relationRequirements
	) {
		String trimmed = String.valueOf(proposition == null ? "" : proposition).trim();
		String normalized = normalize(trimmed);
		String terminalMetaPredicateStem = terminalMetaPredicateStem(normalized);
		return trimmed.endsWith("?")
			|| trimmed.endsWith("？")
			|| (terminalMetaPredicateStem != null
				&& !requestsLexicalPredicate(terminalMetaPredicateStem, relationRequirements))
			|| NON_CONCLUSION_INTERROGATIVE_ENDINGS.stream().anyMatch(normalized::endsWith);
	}

	private static String terminalMetaPredicateStem(String normalizedProposition) {
		return NON_CONCLUSION_META_FRAMES.stream()
			.filter(frame -> frame.endings().stream().anyMatch(normalizedProposition::endsWith))
			.map(MetaPredicateFrame::stem)
			.findFirst()
			.orElse(null);
	}

	private static boolean requestsLexicalPredicate(
		String predicateStem,
		List<RelationRequirement> relationRequirements
	) {
		return !relationRequirements.isEmpty()
			&& relationRequirements.stream()
				.anyMatch(requirement -> requirement.requestsLexicalPredicate(predicateStem));
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

	private static boolean matchesCondition(String claim, String normalizedClaim, List<String> group,
		List<SourceOwnedConditionalScope.Rule> scopeRules) {
		if (group.size() >= 2 && group.get(group.size() - 1).equals("전까지")) {
			String event = String.join("", group.subList(0, group.size() - 1));
			return normalizedClaim.contains(event + "전까지") || normalizedClaim.contains(event + "하기전까지");
		}
		if (group.size() == 2 && group.get(0).matches("[가-힣]{2,30}[을를]")
			&& group.get(1).matches("[가-힣]{2,20}할")) {
			String object = group.get(0).substring(0, group.get(0).length() - 1);
			String action = group.get(1).substring(0, group.get(1).length() - 1);
			String trigger = "\\s+" + Pattern.quote(action) + "\\s+업무\\s+추진\\s+시(?=\\s|[,，])";
			if (Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(object) + trigger).matcher(claim).find()
				|| (object.startsWith("공공") && object.length() >= 7
					&& normalizedClaim.contains(object + "는")
					&& Pattern.compile("(?:는|하며[,，])\\s+(?:[가-힣]{2,20}\\s+등\\s+)?"
						+ Pattern.quote(object.substring(2)) + trigger).matcher(claim).find())) {
				return true;
			}
		}
		if (group.size() == 1 && group.get(0).endsWith("시")) {
			String nominal = group.get(0);
			String event = nominal.substring(0, nominal.length() - 1);
			return List.of(nominal, event + "하는경우", event + "할경우", event + "할때", event + "하면")
				.stream().anyMatch(normalizedClaim::contains);
		}
		if (group.size() == 3 && group.get(2).startsWith("경우")
			&& (group.get(1).equals("있는") || group.get(1).equals("없는"))) {
			// Do not satisfy the polarity of this premise with a word from a different premise.
			return normalizedClaim.contains(String.join("", group));
		}
		return group.stream().allMatch(normalizedClaim::contains)
			|| SourceOwnedConditionalScope.provesLegalCondition(claim, group, scopeRules);
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

	private record MetaPredicateFrame(String stem, List<String> endings) {
	}

	private enum RelationKind {
		LEXICAL,
		PERIOD_VALUE,
		AMOUNT_VALUE
	}

	private record RelationRequirement(
		RelationKind kind,
		List<String> alternatives,
		boolean requestedPredicate
	) {
		private RelationRequirement(RelationKind kind, List<String> alternatives) {
			this(kind, alternatives, false);
		}

		private boolean matches(String proposition) {
			String source = String.valueOf(proposition == null ? "" : proposition);
			String normalized = normalize(source);
			return switch (kind) {
				case PERIOD_VALUE -> PERIOD_VALUE_PATTERN.matcher(source).find();
				case AMOUNT_VALUE -> NUMERIC_AMOUNT_PATTERN.matcher(source).find();
				case LEXICAL -> alternatives.stream().anyMatch(normalized::contains);
			};
		}

		private boolean requestsLexicalPredicate(String predicateStem) {
			if (kind != RelationKind.LEXICAL || !requestedPredicate) {
				return false;
			}
			String normalizedStem = normalize(predicateStem);
			return alternatives.stream()
				.map(KoreanQueryNormalizer::normalizeQueryTerm)
				.anyMatch(normalizedStem::equals);
		}
	}

	private record AlignmentProfile(
		List<List<String>> subjectGroups,
		List<RelationRequirement> relationRequirements,
		List<List<String>> conditionGroups,
		List<List<String>> configuredPolicyBridgeGroups,
		List<List<String>> answerCoverageGroups,
		List<List<String>> answerClaimGroups,
		List<String> documentIdentityTitleAnchors,
		boolean documentIdentityQuestion
	) {
		private static AlignmentProfile from(String question) {
			if (question != null) {
				question = SPOKEN_APPLICATION_DUTY.matcher(question).replaceFirst("$1 적용 의무가 있습니까?");
			}
			QuestionIntentProfile questionProfile = QuestionIntentProfile.from(question);
			List<List<String>> conditionGroups = conditionGroups(question);
			ClassificationFrame classificationFrame = ClassificationFrame.from(question);
			List<RelationRequirement> relationRequirements = relationRequirements(
				questionProfile,
				question,
				conditionGroups,
				classificationFrame
			);
			List<List<String>> subjectGroups = subjectGroups(
				questionProfile,
				question,
				conditionGroups,
				relationRequirements.size() == 1
					&& relationRequirements.get(0).kind() == RelationKind.LEXICAL
					&& relationRequirements.get(0).alternatives().equals(classificationFrame.relationTerms())
					? classificationFrame
					: ClassificationFrame.empty()
			);
			var reportDuty = REPORT_DUTY_QUESTION.matcher(questionProfile.normalizedQuestion());
			if (reportDuty.matches()) {
				subjectGroups = subjectGroups.stream().filter(group -> !group.equals(List.of("따른"))).toList();
				relationRequirements = new ArrayList<>(relationRequirements);
				relationRequirements.add(new RelationRequirement(RelationKind.LEXICAL,
					List.of(reportDuty.group(1) + "에따라시정조치")));
			}
			List<List<String>> policyBridgeGroups = configuredPolicyBridgeGroups(
				questionProfile,
				question,
				conditionGroups
			);
			return new AlignmentProfile(
				subjectGroups,
				relationRequirements,
				conditionGroups,
				policyBridgeGroups,
				questionProfile.configuredAnswerCoverageGroups().stream()
					.map(AlignmentProfile::normalizedValues)
					.filter(group -> !group.isEmpty())
					.toList(),
				questionProfile.configuredAnswerClaimGroups().stream()
					.map(AlignmentProfile::normalizedValues)
					.filter(group -> !group.isEmpty())
					.toList(),
				DocumentIdentityAnswerComposer.titleAnchors(questionProfile),
				questionProfile.documentIdentityQuestion()
			);
		}

		private static List<List<String>> configuredPolicyBridgeGroups(
			QuestionIntentProfile profile,
			String question,
			List<List<String>> conditionGroups
		) {
			String normalizedQuestion = normalize(question);
			boolean asksPermissionOrWhether = containsAny(
				normalizedQuestion,
				"되나",
				"되는지",
				"가능",
				"해도",
				"할수"
			);
			boolean asksExplicitValue = containsAny(
				normalizedQuestion,
				"언제",
				"며칠",
				"몇일",
				"얼마동안",
				"기간은",
				"기한은"
			);
			boolean hasMappedConditionFrame = !conditionGroups.isEmpty()
				|| CONFIGURED_POLICY_CONDITION_FRAME.matcher(
					String.valueOf(question == null ? "" : question)
				).find();
			if (profile.matchedPolicyIds().isEmpty()
				|| profile.preferredTargets().isEmpty()
				|| !profile.preferredTargets().stream().allMatch(AlignmentProfile::isLawTarget)
				|| profile.directEvidenceGroups().size() < 2
				|| !hasMappedConditionFrame
				|| !asksPermissionOrWhether
				|| asksExplicitValue) {
				return List.of();
			}
			return profile.directEvidenceGroups().stream()
				.map(AlignmentProfile::normalizedValues)
				.filter(group -> !group.isEmpty())
				.toList();
		}

		private static boolean isLawTarget(String target) {
			return "law".equals(target) || "admrul".equals(target);
		}

		private boolean usable() {
			return (!subjectGroups.isEmpty() && !relationRequirements.isEmpty())
				|| !answerCoverageGroups.isEmpty();
		}

		private static List<List<String>> subjectGroups(
			QuestionIntentProfile profile,
			String rawQuestion,
			List<List<String>> conditionGroups,
			ClassificationFrame classificationFrame
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
					List<String> normalizedAliases = normalizedValues(entity.aliases()).stream()
						.filter(alias -> alias.length() >= 2 && question.contains(alias))
						.filter(alias -> !isSubjectStopTerm(alias))
						.toList();
					List<String> explicitAliases = minimalAliases(normalizedAliases);
					List<List<String>> configuredSubjectGroups = entity.directEvidenceGroups().stream()
						.map(AlignmentProfile::normalizedValues)
						.filter(group -> group.stream().anyMatch(value -> explicitAliases.stream()
							.anyMatch(alias -> sameConcept(value, alias))))
						.toList();
					if (!configuredSubjectGroups.isEmpty()) {
						groups.addAll(configuredSubjectGroups);
					}
					else if (!explicitAliases.isEmpty()) {
						List<String> equivalentAliases = minimalAliases(normalizedValues(entity.aliases()).stream()
							.filter(alias -> alias.length() >= 2)
							.filter(alias -> !isSubjectStopTerm(alias))
							.toList());
						groups.add(equivalentAliases.isEmpty() ? explicitAliases : equivalentAliases);
					}
				}
			}
			var terminal = TERMINAL_REQUEST.matcher(rawQuestion == null ? "" : rawQuestion);
			String requestMarker = terminal.find()
				&& (terminal.group(1).equals("알려주세요")
					|| (terminal.group(1).equals("있습니까")
						&& normalize(rawQuestion.substring(0, terminal.start())).endsWith("의무가"))
					|| (terminal.group(1).equals("무엇입니까")
						&& normalize(rawQuestion.substring(0, terminal.start())).endsWith("문서는")
						&& profile.intentTypes().contains("required_documents")))
				&& !normalize(rawQuestion.substring(0, terminal.start())).contains(terminal.group(1))
				? terminal.group(1) : "";
			var nominalSubmission = NOMINAL_SUBMISSION_LIST.matcher(rawQuestion == null ? "" : rawQuestion);
			String submissionObject = nominalSubmission.find()
				&& !normalize(rawQuestion.substring(0, nominalSubmission.start())).contains(nominalSubmission.group(1))
				? nominalSubmission.group(1) : "";
			for (String term : profile.terms()) {
				String normalized = normalize(term);
				if (normalized.length() < 2
					|| normalized.equals(requestMarker)
					|| normalized.equals(submissionObject)
					|| (Set.of("효력", "법은", "법률은").contains(normalized) && profile.normalizedQuestion().contains("언제부터효력")
						&& OfficialCommencementEvidence.isCurrentLookup(profile.normalizedQuestion()))
					|| classificationFrame.matchesRelationTerm(normalized)
					|| isSubjectStopTerm(normalized)
					|| coveredByConditions(normalized, conditionGroups)
					|| coveredBySubjectGroups(normalized, groups)
					|| coveredByIntent(normalized, profile.intentGroups())
					|| coveredByDirectEvidence(normalized, profile.directEvidenceGroups())) {
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

		private static boolean sameConcept(String left, String right) {
			return left.equals(right) || left.contains(right) || right.contains(left);
		}

		private static List<String> minimalAliases(List<String> aliases) {
			return aliases.stream()
				.filter(alias -> aliases.stream()
					.noneMatch(other -> !other.equals(alias) && alias.contains(other)))
				.toList();
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

		private static boolean coveredByDirectEvidence(
			String term,
			List<List<String>> directEvidenceGroups
		) {
			return directEvidenceGroups.stream()
				.flatMap(List::stream)
				.map(AnswerQuestionAlignmentVerifier::normalize)
				.anyMatch(anchor ->
					anchor.equals(term) || anchor.contains(term) || term.contains(anchor)
				);
		}

		private static boolean isSubjectStopTerm(String term) {
			return SUBJECT_STOP_TERMS.contains(term)
				|| term.startsWith("대상")
				|| term.startsWith("금액")
				|| term.startsWith("비용")
				|| term.startsWith("기간")
				|| term.startsWith("시기")
				|| term.startsWith("언제")
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
			String question,
			List<List<String>> conditionGroups,
			ClassificationFrame classificationFrame
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
			addConfiguredIntentRequirements(
				requirements,
				profile,
				normalizedQuestion,
				conditionGroups
			);
			addRequestedMetaPredicateRequirement(requirements, question);
			var applicationDuty = Pattern.compile("(?U)\\s([가-힣]{2,25})\\s+적용\\s+의무가\\s+있습니까[?？]*\\s*\\z")
				.matcher(question == null ? "" : question);
			if (applicationDuty.find()) {
				String object = applicationDuty.group(1);
				addLexicalRequirement(requirements, List.of(
					object + "을적용하여야한다", object + "를적용하여야한다",
					object + "을적용해야한다", object + "를적용해야한다"));
			}
			if (NOMINAL_SUBMISSION_LIST.matcher(question == null ? "" : question).find()) {
				addLexicalRequirement(requirements, List.of(
					"문서를제출하여야한다", "문서를제출해야한다",
					"서류를제출하여야한다", "서류를제출해야한다",
					"자료를제출하여야한다", "자료를제출해야한다"));
			}
			if (containsAny(normalizedQuestion, "할수있", "가능한가", "가능한지", "허용")) {
				addLexicalRequirement(requirements,
					List.of("할수있", "가능", "허용", "할수없", "불가능", "금지"));
			}
			if (requirements.isEmpty() && !profile.intentGroups().isEmpty()) {
				requirements.add(new RelationRequirement(RelationKind.LEXICAL, profile.intentGroups().stream()
					.flatMap(List::stream)
					.map(AnswerQuestionAlignmentVerifier::normalize)
					.filter(value -> value.length() >= 2)
					.distinct()
					.toList()));
			}
			if (requirements.isEmpty() && !classificationFrame.relationTerms().isEmpty()) {
				addLexicalRequirement(requirements, classificationFrame.relationTerms());
			}
			return List.copyOf(requirements);
		}

		private static void addRequestedMetaPredicateRequirement(
			List<RelationRequirement> requirements,
			String question
		) {
			String normalizedQuestion = normalize(question);
			NON_CONCLUSION_META_FRAMES.stream()
				.filter(frame -> REQUESTED_META_PREDICATE_ENDINGS.stream()
					.anyMatch(ending -> normalizedQuestion.endsWith(frame.stem() + ending)))
				.map(MetaPredicateFrame::stem)
				.findFirst()
				.ifPresent(stem -> requirements.add(
					new RelationRequirement(RelationKind.LEXICAL, List.of(stem), true)
				));
		}

		private static void addConfiguredIntentRequirements(
			List<RelationRequirement> requirements,
			QuestionIntentProfile profile,
			String normalizedQuestion,
			List<List<String>> conditionGroups
		) {
			for (QuestionIntentProfile.ConfiguredIntent intent : profile.configuredIntents()) {
				if ("amount".equals(intent.id()) || "period".equals(intent.id())) {
					continue;
				}
				List<List<String>> anchoredDirectGroups = intent.directEvidenceGroups().stream()
					.map(AlignmentProfile::normalizedValues)
					.filter(group -> group.stream().anyMatch(term ->
						normalizedQuestion.contains(term) && !coveredByConditions(term, conditionGroups)))
					.toList();
				if (!anchoredDirectGroups.isEmpty()) {
					anchoredDirectGroups.forEach(group -> addLexicalRequirement(requirements, group));
					continue;
				}
				List<String> terms = normalizedValues(intent.terms());
				if (terms.stream().anyMatch(term ->
					normalizedQuestion.contains(term) && !coveredByConditions(term, conditionGroups))) {
					addLexicalRequirement(requirements, terms);
				}
			}
		}

		private static void addLexicalRequirement(
			List<RelationRequirement> requirements,
			List<String> alternatives
		) {
			List<String> normalizedAlternatives = alternatives.stream()
				.map(AnswerQuestionAlignmentVerifier::normalize)
				.filter(value -> value.length() >= 2)
				.distinct()
				.toList();
			String key = String.join("|", normalizedAlternatives);
			if (!normalizedAlternatives.isEmpty() && requirements.stream()
				.noneMatch(requirement -> requirement.kind() == RelationKind.LEXICAL
					&& String.join("|", requirement.alternatives()).equals(key))) {
				requirements.add(new RelationRequirement(RelationKind.LEXICAL, normalizedAlternatives));
			}
		}

		private static boolean amountRequested(String normalizedQuestion) {
			return containsAny(normalizedQuestion, "금액", "비용", "한도", "얼마", "가격", "대가");
		}

		private static List<List<String>> conditionGroups(String question) {
			String[] tokens = String.valueOf(question == null ? "" : question.trim()).split("\\s+");
			List<List<String>> groups = new ArrayList<>();
			var submissionTrigger = NOMINAL_SUBMISSION_TRIGGER.matcher(question == null ? "" : question);
			if (submissionTrigger.find()) {
				groups.add(List.of(submissionTrigger.group(1) + "시"));
			}
			for (int index = 0; index < tokens.length; index++) {
				String token = normalize(tokens[index]);
				// A separated nominal condition is a premise, not a requested conclusion.
				if (index >= 2 && "시".equals(token) && "미준수".equals(normalize(tokens[index - 1]))) {
					String object = normalize(tokens[index - 2]);
					if (object.length() >= 2) {
						groups.add(List.of(object, "미준수시"));
					}
					continue;
				}
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
				|| (normalized.length() >= 3 && (normalized.endsWith("하면") || normalized.endsWith("되면")
					|| normalized.endsWith("않으면")));
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

		private record ClassificationFrame(List<String> relationTerms) {
			private boolean matchesRelationTerm(String term) {
				return relationTerms.stream().anyMatch(relation ->
					term.equals(relation)
						|| term.equals(relation + "로")
						|| term.equals(relation + "으로")
						|| term.equals(relation + "라고")
						|| term.equals(relation + "이라고")
				);
			}

			private static ClassificationFrame from(String question) {
				String source = String.valueOf(question == null ? "" : question);
				for (Pattern pattern : CLASSIFICATION_RELATION_PATTERNS) {
					java.util.regex.Matcher matcher = pattern.matcher(source);
					if (!matcher.find()) {
						continue;
					}
					String relationTerm = KoreanQueryNormalizer.normalizeQueryTerm(matcher.group(1));
					if (relationTerm.length() >= 2 && !KoreanQueryNormalizer.isWeakQuestionTerm(relationTerm)) {
						return new ClassificationFrame(List.of(relationTerm));
					}
				}
				return empty();
			}

			private static ClassificationFrame empty() {
				return new ClassificationFrame(List.of());
			}
		}
	}
}
