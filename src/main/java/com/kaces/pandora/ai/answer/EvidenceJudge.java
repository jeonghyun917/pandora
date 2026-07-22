package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import com.kaces.pandora.common.text.QuestionIntentProfile;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.rag.chunk.RagChunkQualityStatus;
import com.kaces.pandora.rag.common.HwpxTextCleaner;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class EvidenceJudge {

	private static final int MIN_RELEVANT_RESULTS = 2;
	private static final int UI_NAVIGATION_EXCLUSION_WINDOW = 16;
	private static final List<String> UI_NAVIGATION_TERMS = List.of(
		"메뉴", "화면", "클릭", "버튼", "경로", "navigation"
	);
	private static final List<String> UI_NAVIGATION_LOOKUP_TERMS = List.of(
		"메뉴", "화면", "클릭", "버튼", "navigation"
	);
	private static final List<String> UI_NAVIGATION_EXCLUSION_CUES = List.of(
		"말고", "아니라", "제외", "빼고"
	);
	public Result judge(
		String question,
		List<LawSemanticChunkRow> chunks,
		Map<String, Double> baseScoreByChunkId,
		int requestedLimit
	) {
		if (chunks == null || chunks.isEmpty()) {
			return new Result(List.of(), Map.of(), false, false, false, false, 0, 0, 0, "empty");
		}
		List<LawSemanticChunkRow> searchableChunks = chunks.stream()
			.filter(chunk -> chunk != null && RagChunkQualityStatus.from(chunk.qualityStatus()).searchable())
			.toList();
		if (searchableChunks.isEmpty()) {
			return new Result(List.of(), Map.of(), false, false, false, false, 0, 0, 0, "quality_filtered");
		}

		EvidenceQuestionProfile profile = buildQuestionProfile(question);
		List<JudgedChunk> judgedChunks = searchableChunks.stream()
			.map(chunk -> judgeChunk(profile, chunk, baseScoreByChunkId.getOrDefault(scoreKey(chunk), 0.0)))
			.sorted(Comparator.comparingDouble(JudgedChunk::score).reversed())
			.toList();
		List<JudgedChunk> topicAlignedChunks = judgedChunks.stream()
			.filter(JudgedChunk::topicAligned)
			.toList();

		List<JudgedChunk> relevantChunks = topicAlignedChunks.stream()
			.filter(JudgedChunk::relevant)
			.toList();
		List<JudgedChunk> directEvidenceChunks = topicAlignedChunks.stream()
			.filter(JudgedChunk::directEvidence)
			.toList();
		boolean directEvidenceRequired = !profile.directEvidenceGroups().isEmpty();
		List<JudgedChunk> directSupportingChunks = topicAlignedChunks.stream()
			.filter(chunk -> chunk.bodyDirectEvidenceMatches() > 0)
			.toList();
		List<JudgedChunk> exploratoryLookupChunks = isExploratoryLookupQuestion(profile.normalizedQuestion())
			? judgedChunks.stream()
				.filter(chunk -> matchesExploratoryLookup(profile, chunk.chunk()))
				.toList()
			: List.of();
		exploratoryLookupChunks = preferExploratoryTitleAnchors(profile, exploratoryLookupChunks);
		List<JudgedChunk> crossChunkDirectEvidenceChunks = crossChunkDirectEvidenceChunks(
			profile,
			directSupportingChunks,
			requestedLimit
		);
		List<JudgedChunk> directEvidenceWithExploratorySupport = !directEvidenceChunks.isEmpty()
			? withSupportingExploratoryChunks(profile, directEvidenceChunks, judgedChunks, requestedLimit)
			: List.of();
		boolean directEvidenceFound = directEvidenceRequired && (!directEvidenceChunks.isEmpty()
			|| !crossChunkDirectEvidenceChunks.isEmpty());
		boolean conceptEvidenceRequired = !profile.conceptGroups().isEmpty();
		boolean conceptEvidenceFound = !relevantChunks.isEmpty();
		boolean useRelevantOnly = relevantChunks.size() >= Math.min(MIN_RELEVANT_RESULTS, Math.max(1, requestedLimit));
		List<JudgedChunk> selectedChunks = directEvidenceRequired
			? !directEvidenceChunks.isEmpty()
				? directEvidenceWithExploratorySupport
				: directEvidenceFound ? crossChunkDirectEvidenceChunks
				: !exploratoryLookupChunks.isEmpty() ? exploratoryLookupChunks : List.of()
			: !exploratoryLookupChunks.isEmpty() ? exploratoryLookupChunks
			: conceptEvidenceRequired ? relevantChunks
			: useRelevantOnly ? relevantChunks : topicAlignedChunks.isEmpty() ? judgedChunks : topicAlignedChunks;
		selectedChunks = preferSecurityReviewGuideEvidence(profile, selectedChunks);
		selectedChunks = preferCommitteeExpansion(profile, selectedChunks);
		String selectionPolicy = selectionPolicy(
			directEvidenceRequired,
			!directEvidenceChunks.isEmpty(),
			!crossChunkDirectEvidenceChunks.isEmpty(),
			!exploratoryLookupChunks.isEmpty(),
			conceptEvidenceRequired,
			useRelevantOnly,
			topicAlignedChunks.isEmpty()
		);

		Map<String, Double> scoreByChunkId = new HashMap<>(baseScoreByChunkId);
		for (JudgedChunk judgedChunk : judgedChunks) {
			scoreByChunkId.put(scoreKey(judgedChunk.chunk()), judgedChunk.score());
		}

		return new Result(
			selectedChunks.stream().map(JudgedChunk::chunk).toList(),
			scoreByChunkId,
			directEvidenceRequired,
			directEvidenceFound,
			conceptEvidenceRequired,
			conceptEvidenceFound,
			topicAlignedChunks.size(),
			relevantChunks.size(),
			directEvidenceChunks.size(),
			selectionPolicy
		);
	}

	private List<JudgedChunk> withSupportingExploratoryChunks(
		EvidenceQuestionProfile profile,
		List<JudgedChunk> directEvidenceChunks,
		List<JudgedChunk> exploratoryLookupChunks,
		int requestedLimit
	) {
		if (directEvidenceChunks == null || directEvidenceChunks.isEmpty()
			|| exploratoryLookupChunks == null || exploratoryLookupChunks.isEmpty()
			|| !isExploratoryLookupQuestion(profile.normalizedQuestion())) {
			return directEvidenceChunks == null ? List.of() : directEvidenceChunks;
		}
		int limit = Math.max(1, requestedLimit);
		List<JudgedChunk> selected = new ArrayList<>(directEvidenceChunks);
		for (JudgedChunk exploratoryChunk : exploratoryLookupChunks) {
			if (selected.size() >= limit) {
				break;
			}
			if (!selected.contains(exploratoryChunk)
				&& (matchesExploratoryLookup(profile, exploratoryChunk.chunk())
					|| isExploratoryComplementaryEvidence(profile, exploratoryChunk.chunk()))) {
				selected.add(exploratoryChunk);
			}
		}
		return selected;
	}

	private String selectionPolicy(
		boolean directEvidenceRequired,
		boolean directEvidenceAvailable,
		boolean crossChunkDirectEvidenceAvailable,
		boolean exploratoryLookupAvailable,
		boolean conceptEvidenceRequired,
		boolean useRelevantOnly,
		boolean topicAlignedEmpty
	) {
		if (directEvidenceRequired) {
			if (directEvidenceAvailable) {
				return "direct";
			}
			if (crossChunkDirectEvidenceAvailable) {
				return "cross_chunk_direct";
			}
			return exploratoryLookupAvailable ? "exploratory_lookup" : "no_direct_evidence";
		}
		if (exploratoryLookupAvailable) {
			return "exploratory_lookup";
		}
		if (conceptEvidenceRequired) {
			return "concept_relevant";
		}
		if (useRelevantOnly) {
			return "relevant";
		}
		return topicAlignedEmpty ? "fallback_ranked" : "topic_aligned";
	}

	private List<JudgedChunk> crossChunkDirectEvidenceChunks(
		EvidenceQuestionProfile profile,
		List<JudgedChunk> chunks,
		int requestedLimit
	) {
		if (chunks == null || chunks.isEmpty()) {
			return List.of();
		}
		List<JudgedChunk> eligibleChunks = chunks.stream()
			.filter(chunk -> isCrossChunkDirectEvidenceCandidate(profile, chunk))
			.toList();
		if (eligibleChunks.isEmpty()) {
			return List.of();
		}
		if (allowsCrossDocumentFamilyCoverage(profile)) {
			return coveringDirectEvidenceChunks(profile, eligibleChunks, requestedLimit);
		}
		Map<String, List<JudgedChunk>> chunksByDocumentFamily = new LinkedHashMap<>();
		for (JudgedChunk chunk : eligibleChunks) {
			String familyKey = documentFamilyKey(chunk.chunk());
			if (!familyKey.isBlank()) {
				chunksByDocumentFamily.computeIfAbsent(familyKey, ignored -> new ArrayList<>()).add(chunk);
			}
		}
		for (List<JudgedChunk> familyChunks : chunksByDocumentFamily.values()) {
			List<JudgedChunk> coveringChunks = coveringDirectEvidenceChunks(profile, familyChunks, requestedLimit);
			if (!coveringChunks.isEmpty()) {
				return coveringChunks;
			}
		}
		return List.of();
	}

	private List<JudgedChunk> coveringDirectEvidenceChunks(
		EvidenceQuestionProfile profile,
		List<JudgedChunk> chunks,
		int requestedLimit
	) {
		java.util.LinkedHashSet<Integer> coveredGroups = new java.util.LinkedHashSet<>();
		List<JudgedChunk> selected = new ArrayList<>();
		for (JudgedChunk chunk : chunks) {
			List<Integer> matchedGroups = matchedDirectGroupIndexes(profile, chunk.chunk());
			boolean addsCoverage = matchedGroups.stream().anyMatch(group -> !coveredGroups.contains(group));
			if (!addsCoverage) {
				continue;
			}
			selected.add(chunk);
			coveredGroups.addAll(matchedGroups);
			if (coveredGroups.size() >= profile.requiredDirectEvidenceMatches()) {
				break;
			}
		}
		if (coveredGroups.size() < profile.requiredDirectEvidenceMatches()) {
			return List.of();
		}
		int limit = Math.max(1, requestedLimit);
		return selected.size() <= limit ? selected : List.of();
	}

	private boolean isCrossChunkDirectEvidenceCandidate(EvidenceQuestionProfile profile, JudgedChunk chunk) {
		if (chunk.bodyDirectEvidenceMatches() <= 0 || isTableOfContentsLike(chunk.chunk())) {
			return false;
		}
		String body = normalize(chunk.chunk().chunkText());
		String documentTitle = normalize(chunk.chunk().title());
		String chunkHeading = normalize(chunk.chunk().chunkTitle());
		String text = documentTitle + chunkHeading + body;
		if (!containsAll(text, profile.requiredTerms())) {
			return false;
		}
		if (!passesDirectEvidenceHardContextGate(profile, chunk.chunk(), body, documentTitle, chunkHeading)) {
			return false;
		}
		if (profile.conceptGroups().isEmpty()) {
			return true;
		}
		return countMatchedGroups(text, profile.conceptGroups()) > 0
			|| hasGroupNearGroup(body, profile.conceptGroups(), profile.directEvidenceGroups(), 96)
			|| countMatchedGroups(text, profile.intentGroups()) > 0;
	}

	private boolean passesDirectEvidenceHardContextGate(
		EvidenceQuestionProfile profile,
		LawSemanticChunkRow chunk,
		String body,
		String documentTitle,
		String chunkHeading
	) {
		String normalizedQuestion = profile.normalizedQuestion();
		boolean projectReviewRelationQuestion = isProjectReviewPreConsultationRelationQuestion(normalizedQuestion);
		if (isProjectReviewScopeQuestion(normalizedQuestion) || projectReviewRelationQuestion) {
			boolean allowed = isProjectReviewScopeChunk(body, documentTitle, chunkHeading)
				|| (projectReviewRelationQuestion && isPreConsultationContextChunk(body, documentTitle, chunkHeading));
			if (!allowed) {
				return false;
			}
		}
		if (!isAutonomyPreConsultationQuestion(normalizedQuestion)
			&& isPreConsultationTargetQuestion(normalizedQuestion)) {
			if (!isPreConsultationContextChunk(body, documentTitle, chunkHeading)) {
				return false;
			}
			if (!isPreConsultationGeneralScopeChunk(body, chunkHeading)) {
				return false;
			}
			if (isPreConsultationSpecificQaChunk(body, chunkHeading)
				&& !isPreConsultationGeneralScopeChunk(body, chunkHeading)) {
				return false;
			}
		}
		if (isSecurityReviewTargetQuestion(normalizedQuestion)
			&& !isSecurityReviewTargetAnswerChunk(body, documentTitle, chunkHeading)) {
			return false;
		}
		if (profile.trafficCrosswalkStopQuestion()) {
			String title = documentTitle + chunkHeading;
			boolean trafficDriverSubject = body.contains("우회전하는차의운전자는")
				|| body.contains("보행자의횡단을방해");
			boolean trafficDutyAction = body.contains("횡단보도앞")
				|| body.contains("일시정지하여야")
				|| body.contains("정지하거나진행하는보행자")
				|| body.contains("보행자의횡단을방해");
			boolean trafficDriverDutyChunk = title.contains("교차로통행방법")
				|| title.contains("보행자의보호")
				|| (trafficDriverSubject && trafficDutyAction);
			return trafficDriverDutyChunk || ("law".equals(chunk.target()) && documentTitle.contains("도로교통법"));
		}
		return true;
	}

	private boolean isExactLawArticleReferenceEvidence(
		EvidenceQuestionProfile profile,
		LawSemanticChunkRow chunk,
		String body,
		String documentTitle,
		String chunkHeading
	) {
		if (profile == null || chunk == null || profile.normalizedQuestion().isBlank()) {
			return false;
		}
		if (!"law".equals(chunk.target()) && !"admrul".equals(chunk.target())) {
			return false;
		}
		String documentTitleStem = documentTitleStem(documentTitle);
		if (documentTitleStem.length() < 4 || !profile.normalizedQuestion().contains(documentTitleStem)) {
			return false;
		}
		List<String> articleReferences = articleReferences(profile.normalizedQuestion());
		if (articleReferences.isEmpty()) {
			return false;
		}
		String text = String.valueOf(chunkHeading == null ? "" : chunkHeading) + String.valueOf(body == null ? "" : body);
		if (articleReferences.stream().noneMatch(text::contains)) {
			return false;
		}
		boolean asksGround = containsAny(
			profile.normalizedQuestion(),
			List.of("관련조항", "조항근거", "근거", "알려", "찾아", "보여")
		);
		boolean domainTermMatched = profile.terms().stream()
			.filter(term -> term != null && term.length() >= 2)
			.filter(term -> !term.startsWith("제") || !term.contains("조"))
			.anyMatch(term -> text.contains(term) || documentTitleStem.contains(term) || documentTitle.contains(term));
		return asksGround && domainTermMatched;
	}

	private boolean isExactDocumentBodyAnchorEvidence(
		EvidenceQuestionProfile profile,
		LawSemanticChunkRow chunk,
		String body,
		String documentTitle,
		String chunkHeading
	) {
		if (profile == null || chunk == null || profile.normalizedQuestion().isBlank()) {
			return false;
		}
		if (!"law".equals(chunk.target()) && !"admrul".equals(chunk.target())) {
			return false;
		}
		String documentTitleStem = documentTitleStem(documentTitle);
		if (documentTitleStem.length() < 12) {
			return false;
		}
		List<String> meaningfulQuestionTerms = profile.terms().stream()
			.map(EvidenceJudge::normalizeQuestionTerm)
			.filter(term -> term.length() >= 2)
			.filter(term -> !isWeakTerm(term))
			.filter(term -> !isEvidenceAnchorStopTerm(term))
			.distinct()
			.toList();
		int documentTitleTermMatches = countMatchedTerms(documentTitleStem, meaningfulQuestionTerms);
		boolean exactTitleAnchor = profile.normalizedQuestion().contains(documentTitleStem)
			|| documentTitleTermMatches >= Math.min(4, Math.max(3, meaningfulQuestionTerms.size()));
		if (!exactTitleAnchor) {
			return false;
		}
		String question = normalize(profile.normalizedQuestion());
		boolean asksGround = containsAny(
			question,
			List.of("근거", "조항", "본문", "관련", "알려", "찾아", "보여", "확인")
		);
		if (!asksGround && profile.directEvidenceGroups().isEmpty()) {
			return false;
		}
		String searchableBody = normalize(String.valueOf(body) + " " + String.valueOf(chunkHeading));
		if (isSupplementOrRepealNoise(searchableBody, chunkHeading)) {
			return false;
		}
		LinkedHashSet<String> anchorTermSet = new LinkedHashSet<>();
		profile.directEvidenceGroups().stream()
			.flatMap(List::stream)
			.map(EvidenceJudge::normalizeQuestionTerm)
			.filter(term -> term.length() >= 2)
			.filter(term -> !isWeakTerm(term))
			.filter(term -> !isEvidenceAnchorStopTerm(term))
			.forEach(anchorTermSet::add);
		profile.terms().stream()
			.map(EvidenceJudge::normalizeQuestionTerm)
			.filter(term -> term.length() >= 2)
			.filter(term -> !isWeakTerm(term))
			.filter(term -> !isEvidenceAnchorStopTerm(term))
			.forEach(anchorTermSet::add);
		List<String> anchorTerms = anchorTermSet.stream().toList();
		int anchorMatches = countMatchedTerms(searchableBody, anchorTerms);
		if (anchorMatches < Math.min(2, Math.max(1, anchorTerms.size()))) {
			return false;
		}
		List<String> domainTerms = profile.terms().stream()
			.map(EvidenceJudge::normalizeQuestionTerm)
			.filter(term -> term.length() >= 3)
			.filter(term -> !isWeakTerm(term))
			.distinct()
			.toList();
		int domainMatches = countMatchedTerms(searchableBody, domainTerms);
		return domainMatches >= 2 || anchorMatches >= 3;
	}

	private String documentTitleStem(String normalizedDocumentTitle) {
		if (normalizedDocumentTitle == null || normalizedDocumentTitle.isBlank()) {
			return "";
		}
		java.util.regex.Matcher articleMatcher = java.util.regex.Pattern
			.compile("제\\d+조")
			.matcher(normalizedDocumentTitle);
		if (articleMatcher.find() && articleMatcher.start() >= 4) {
			return normalizedDocumentTitle.substring(0, articleMatcher.start());
		}
		java.util.regex.Matcher appendixMatcher = java.util.regex.Pattern
			.compile("(별표|별지|부칙|서식)")
			.matcher(normalizedDocumentTitle);
		if (appendixMatcher.find() && appendixMatcher.start() >= 4) {
			return normalizedDocumentTitle.substring(0, appendixMatcher.start());
		}
		return normalizedDocumentTitle;
	}

	private boolean isSupplementOrRepealNoise(String body, String chunkHeading) {
		String text = normalize(String.valueOf(body) + " " + String.valueOf(chunkHeading));
		boolean supplementHeading = text.contains("부칙") || text.contains("시행일");
		boolean repealOrEffectiveOnly = text.contains("폐지")
			|| text.contains("시행한다")
			|| text.contains("시행한날")
			|| text.contains("발령한날")
			|| text.contains("개정");
		return supplementHeading && repealOrEffectiveOnly;
	}

	private List<String> articleReferences(String normalizedQuestion) {
		if (normalizedQuestion == null || normalizedQuestion.isBlank()) {
			return List.of();
		}
		java.util.regex.Matcher matcher = java.util.regex.Pattern
			.compile("제\\d+조(?:제\\d+항)?")
			.matcher(normalizedQuestion);
		List<String> references = new ArrayList<>();
		while (matcher.find()) {
			references.add(matcher.group());
		}
		return references.stream().distinct().toList();
	}

	private List<Integer> matchedDirectGroupIndexes(EvidenceQuestionProfile profile, LawSemanticChunkRow chunk) {
		String text = normalize(chunk.title() + " " + chunk.parentSectionTitle() + " " + chunk.chunkTitle() + " " + chunk.chunkText());
		List<Integer> indexes = new ArrayList<>();
		for (int i = 0; i < profile.directEvidenceGroups().size(); i++) {
			if (containsAny(text, profile.directEvidenceGroups().get(i))) {
				indexes.add(i);
			}
		}
		return indexes;
	}

	private boolean allowsCrossDocumentFamilyCoverage(EvidenceQuestionProfile profile) {
		return isProjectReviewPreConsultationRelationQuestion(profile.normalizedQuestion())
			|| hasExplicitCrossConceptRelationCue(profile.normalizedQuestion());
	}

	private String documentFamilyKey(LawSemanticChunkRow chunk) {
		String target = String.valueOf(chunk.target() == null ? "" : chunk.target()).trim();
		if (chunk.documentId() > 0) {
			return target + "|documentId:" + chunk.documentId();
		}
		String externalId = String.valueOf(chunk.externalId() == null ? "" : chunk.externalId()).trim();
		return externalId.isBlank() ? "" : target + "|externalId:" + externalId;
	}

	private static boolean hasExplicitCrossConceptRelationCue(String normalizedQuestion) {
		String normalized = normalize(normalizedQuestion);
		String relationCueText = normalized
			.replace("이해관계", "")
			.replace("관계기관", "")
			.replace("관계법령", "")
			.replace("관계부처", "")
			.replace("관계규정", "")
			.replace("관계없이", "");
		return normalized.contains("같이")
			|| normalized.contains("함께")
			|| normalized.contains("둘다")
			|| normalized.contains("동시에")
			|| relationCueText.contains("의관계")
			|| relationCueText.contains("상호관계")
			|| relationCueText.contains("관계를")
			|| relationCueText.contains("관계는")
			|| relationCueText.endsWith("관계")
			|| relationCueText.contains("연관성")
			|| relationCueText.contains("연관관계")
			|| relationCueText.contains("연관을")
			|| relationCueText.contains("연관이")
			|| relationCueText.endsWith("연관");
	}

	// 메소드 설명: judgeChunk 처리 흐름을 수행합니다.
	private JudgedChunk judgeChunk(EvidenceQuestionProfile profile, LawSemanticChunkRow chunk, double baseScore) {
		String body = normalize(chunk.chunkText());
		String documentTitle = normalize(chunk.title());
		String parentSectionTitle = normalize(chunk.parentSectionTitle());
		String chunkHeading = normalize(chunk.chunkTitle());
		String sectionType = normalize(chunk.sectionType());
		String title = documentTitle + parentSectionTitle + chunkHeading;
		String text = title + body;
		boolean requiredTermsMatched = containsAll(text, profile.requiredTerms());

		int conceptMatches = countMatchedGroups(text, profile.conceptGroups());
		int bodyConceptMatches = countMatchedGroups(body, profile.conceptGroups());
		int headingConceptMatches = countMatchedGroups(chunkHeading, profile.conceptGroups());
		int titleConceptMatches = countMatchedGroups(title, profile.conceptGroups());
		int intentMatches = countMatchedGroups(text, profile.intentGroups());
		int bodyIntentMatches = countMatchedGroups(body, profile.intentGroups());
		int headingIntentMatches = countMatchedGroups(chunkHeading, profile.intentGroups());
		int directEvidenceMatches = countMatchedGroups(text, profile.directEvidenceGroups());
		int bodyDirectEvidenceMatches = countMatchedGroups(body, profile.directEvidenceGroups());
		int headingDirectEvidenceMatches = countMatchedGroups(chunkHeading, profile.directEvidenceGroups());
		int bodyDirectEvidenceTermMatches = countMatchedTerms(body, flattenGroups(profile.directEvidenceGroups()));
		List<List<String>> questionAnchoredDirectGroups = questionAnchoredDirectEvidenceGroups(profile);
		int questionAnchoredDirectMatches = countMatchedGroups(text, questionAnchoredDirectGroups);
		int bodyQuestionAnchoredDirectMatches = countMatchedGroups(body, questionAnchoredDirectGroups);
		int structuralDirectEvidenceMatches = bodyDirectEvidenceMatches + headingDirectEvidenceMatches;
		int termMatches = countMatchedTerms(text, profile.terms());
		int titleMatches = countMatchedTerms(title, profile.terms());
		int bodyTermMatches = countMatchedTerms(body, profile.terms());
		boolean tableOfContents = isTableOfContentsLike(chunk);
		boolean suppressEvidenceNoise = EvidenceNoiseClassifier.shouldSuppressAsEvidence(chunk, profile.normalizedQuestion());
		boolean committeeQuestion = profile.committeeQuestion();
		boolean trafficCrosswalkStopQuestion = profile.trafficCrosswalkStopQuestion();
		boolean projectReviewScopeQuestion = isProjectReviewScopeQuestion(profile.normalizedQuestion());
		boolean projectReviewPreConsultationRelationQuestion = isProjectReviewPreConsultationRelationQuestion(profile.normalizedQuestion());
		boolean securityReviewTargetQuestion = isSecurityReviewTargetQuestion(profile.normalizedQuestion());
		boolean exploratoryLookupQuestion = isExploratoryLookupQuestion(profile.normalizedQuestion());
		boolean penaltyConsequenceQuestion = isPenaltyConsequenceQuestion(profile);
		boolean autonomyPreConsultationQuestion = isAutonomyPreConsultationQuestion(profile.normalizedQuestion());
		boolean autonomyPreConsultationProcedureQuestion = isAutonomyPreConsultationProcedureQuestion(profile.normalizedQuestion());
		boolean preConsultationTargetQuestion = !autonomyPreConsultationQuestion
			&& isPreConsultationTargetQuestion(profile.normalizedQuestion());
		boolean targetScopeQuestion = profile.preferredSectionTypes().contains("target_scope")
			&& !autonomyPreConsultationProcedureQuestion;
		boolean criticalConceptMissing = isCriticalConceptMissing(profile.normalizedQuestion(), text);
		boolean projectReviewScopeChunk = isProjectReviewScopeChunk(body, documentTitle, chunkHeading);
		boolean projectReviewAdjacentChunk = isProjectReviewAdjacentChunk(body, documentTitle, chunkHeading);
		boolean preConsultationContextChunk = isPreConsultationContextChunk(body, documentTitle, chunkHeading);
		boolean preConsultationGeneralScopeChunk = isPreConsultationGeneralScopeChunk(body, chunkHeading);
		boolean preConsultationSpecificQaChunk = isPreConsultationSpecificQaChunk(body, chunkHeading);
		boolean securityReviewTargetAnswerChunk = securityReviewTargetQuestion
			&& isSecurityReviewTargetAnswerChunk(body, documentTitle, chunkHeading);
		boolean preferredSection = profile.prefersSection(chunk.sectionType());
		boolean procurementCatalogContractQuestion = KoreanQueryNormalizer.isProcurementCatalogContractQuestion(profile.normalizedQuestion());
		boolean procurementCatalogContractChunk = isProcurementCatalogContractContextChunk(body, documentTitle, chunkHeading);
		boolean procurementCatalogNoiseChunk = isProcurementCatalogNoiseChunk(body, documentTitle, chunkHeading);
		boolean procurementCatalogScopeChunk = isProcurementCatalogScopeChunk(body, chunkHeading);
		boolean procurementContractMethodQuestion = isProcurementContractMethodQuestion(profile.normalizedQuestion());
		boolean procurementContractMethodChunk = isProcurementContractMethodChunk(body, chunkHeading);
		boolean procurementExclusionChunk = isProcurementExclusionChunk(body, chunkHeading);
		boolean projectReviewAllowedChunk = !projectReviewScopeQuestion
			|| projectReviewScopeChunk
			|| (projectReviewPreConsultationRelationQuestion && preConsultationContextChunk);
		boolean trafficDriverSubject = body.contains("우회전하는차의운전자는")
			|| body.contains("보행자의횡단을방해");
		boolean trafficDutyAction = body.contains("횡단보도앞")
			|| body.contains("일시정지하여야")
			|| body.contains("정지하거나진행하는보행자")
			|| body.contains("보행자의횡단을방해");
		boolean trafficDriverDutyChunk = title.contains("교차로통행방법")
			|| title.contains("보행자의보호")
			|| (trafficDriverSubject && trafficDutyAction);
		boolean trafficFacilityGuideChunk = title.contains("설치관리기준")
			|| title.contains("설계지침")
			|| body.contains("설치할수있다")
			|| body.contains("시설물")
			|| body.contains("시거확보");
		boolean trafficLegalAnswerChunk = !trafficCrosswalkStopQuestion
			|| trafficDriverDutyChunk
			|| ("law".equals(chunk.target()) && documentTitle.contains("도로교통법"));
		boolean conceptIntentNear = hasGroupNearGroup(body, profile.conceptGroups(), profile.intentGroups(), 96)
			|| hasGroupNearGroup(body, profile.conceptGroups(), profile.directEvidenceGroups(), 96);
		boolean strongIntentSignal = hasStrongIntentSignal(profile, body, chunkHeading);
		boolean asksBusinessTarget = profile.terms().stream().anyMatch(term -> term.contains("사업"));
		boolean projectTargetPhrase = text.contains("대상사업")
			|| text.contains("적용대상사업")
			|| text.contains("사업의적용범위")
			|| text.contains("추진하는다음각호")
			|| text.contains("다음각호의사업");
		boolean broadTargetPhrase = projectTargetPhrase
			|| text.contains("대상기관")
			|| text.contains("적용대상")
			|| text.contains("적용범위")
			|| text.contains("표준화대상")
			|| text.contains("제공대상");
		boolean directTargetPhrase = asksBusinessTarget ? projectTargetPhrase : broadTargetPhrase;
		boolean targetObligationPhrase = text.contains("신청하여야한다")
			|| text.contains("말한다")
			|| text.contains("포함한다")
			|| text.contains("제출하여야한다");
		boolean formOrChecklistNoise = text.contains("사업대상시스템")
			|| text.contains("제안요청서")
			|| text.contains("작성예시")
			|| text.contains("검토하였는가")
			|| text.contains("체크")
			|| text.contains("항목");
		boolean relationSideEvidence = isRelationSideEvidence(
			projectReviewPreConsultationRelationQuestion,
			projectReviewScopeChunk,
			preConsultationContextChunk,
			conceptMatches
		);
		boolean conceptAnchored = profile.conceptGroups().isEmpty()
			|| bodyConceptMatches >= profile.requiredConceptMatches()
			|| headingConceptMatches >= profile.requiredConceptMatches()
			|| conceptIntentNear
			|| (preferredSection && (conceptMatches > 0 || intentMatches > 0 || termMatches > 0))
			|| (exploratoryLookupQuestion && conceptMatches >= profile.requiredConceptMatches())
			|| (titleConceptMatches >= profile.requiredConceptMatches() && strongIntentSignal)
			|| (procurementCatalogContractQuestion && procurementCatalogContractChunk)
			|| relationSideEvidence;

		boolean conceptOk = profile.conceptGroups().isEmpty()
			|| (conceptMatches >= profile.requiredConceptMatches() && conceptAnchored)
			|| (procurementCatalogContractQuestion && procurementCatalogContractChunk)
			|| relationSideEvidence;
		boolean directEvidence = !profile.directEvidenceGroups().isEmpty()
			&& directEvidenceMatches >= profile.requiredDirectEvidenceMatches()
			&& structuralDirectEvidenceMatches > 0
			&& (questionAnchoredDirectGroups.isEmpty() || bodyQuestionAnchoredDirectMatches > 0 || relationSideEvidence)
			&& (conceptOk || (preferredSection && bodyDirectEvidenceMatches >= profile.requiredDirectEvidenceMatches()))
			&& (!profile.requiresStrongIntentSignal() || strongIntentSignal || bodyDirectEvidenceMatches >= profile.requiredDirectEvidenceMatches())
			&& !tableOfContents;
		boolean preferredStructuralDirectEvidence = !profile.directEvidenceGroups().isEmpty()
			&& preferredSection
			&& requiredTermsMatched
			&& directEvidenceMatches >= profile.requiredDirectEvidenceMatches()
			&& bodyDirectEvidenceMatches >= profile.requiredDirectEvidenceMatches()
			&& !tableOfContents
			&& passesDirectEvidenceHardContextGate(profile, chunk, body, documentTitle, chunkHeading);
		if (preferredStructuralDirectEvidence) {
			directEvidence = true;
		}
		boolean documentAnchoredDirectEvidence = !profile.directEvidenceGroups().isEmpty()
			&& requiredTermsMatched
			&& titleMatches >= Math.min(2, Math.max(1, profile.terms().size()))
			&& bodyDirectEvidenceMatches >= profile.requiredDirectEvidenceMatches()
			&& (questionAnchoredDirectGroups.isEmpty() || bodyQuestionAnchoredDirectMatches > 0)
			&& passesDirectEvidenceHardContextGate(profile, chunk, body, documentTitle, chunkHeading)
			&& !tableOfContents;
		if (documentAnchoredDirectEvidence) {
			directEvidence = true;
		}
		boolean privacyPurposePolicyEvidence = isPrivacyPurposePolicyQuestion(profile.normalizedQuestion())
			&& isPrivacyPurposePolicyEvidence(text);
		boolean publicDataActivationQuestion = isPublicDataActivationQuestion(profile.normalizedQuestion());
		boolean publicDataActivationPrivacyNoise = publicDataActivationQuestion
			&& isPublicDataActivationPrivacyNoise(body, documentTitle, chunkHeading);
		boolean publicDataStandardizationOnlyChunk = isPublicDataStandardizationOnlyChunk(body, documentTitle, chunkHeading);
		boolean publicDataStandardizationQuestion = isPublicDataStandardizationQuestion(profile.normalizedQuestion());
		boolean publicDataCustomSupportQuestion = isPublicDataCustomSupportQuestion(profile.normalizedQuestion());
		boolean publicDataAiManagementQuestion = isPublicDataAiManagementQuestion(profile.normalizedQuestion());
		boolean publicDataQualityDiagnosisOverviewQuestion = isPublicDataQualityDiagnosisOverviewQuestion(profile.normalizedQuestion());
		boolean publicDataQualityDiagnosisOverviewEvidence = isPublicDataQualityDiagnosisOverviewEvidence(text);
		boolean publicDataQualityDiagnosisPreferredOverview = isPublicDataQualityDiagnosisPreferredOverview(text);
		boolean publicDataQualityDiagnosisDetailEvidence = isPublicDataQualityDiagnosisDetailEvidence(text);
		boolean publicDataStandardTermQuestion = isPublicDataStandardTermQuestion(profile.normalizedQuestion());
		boolean publicDataStandardTermEvidence = isPublicDataStandardTermEvidence(text);
		boolean publicDataStandardTermNoise = isPublicDataStandardTermNoise(text);
		boolean exactLawArticleReferenceEvidence = isExactLawArticleReferenceEvidence(
			profile,
			chunk,
			body,
			documentTitle,
			chunkHeading
		);
		boolean exactDocumentBodyAnchorEvidence = isExactDocumentBodyAnchorEvidence(
			profile,
			chunk,
			body,
			documentTitle,
			chunkHeading
		);
		boolean koreanLiteratureExportQuestion = isKoreanLiteratureExportQuestion(profile.normalizedQuestion());
		boolean quantumOecdQuestion = isQuantumOecdQuestion(profile.normalizedQuestion());
		boolean tvingSmishingQuestion = isTvingSmishingQuestion(profile.normalizedQuestion());
		boolean cctvPublicPlaceExceptionQuestion = isCctvPublicPlaceExceptionQuestion(profile.normalizedQuestion());
		boolean cctvInvestigationProvisionQuestion = isCctvInvestigationProvisionQuestion(profile.normalizedQuestion());
		boolean cctvRetentionOrPurposeQuestion = isCctvRetentionOrPurposeQuestion(profile.normalizedQuestion());
		boolean pseudonymAdditionalInfoQuestion = isPseudonymAdditionalInfoQuestion(profile.normalizedQuestion());
		boolean privacyRetentionDestructionQuestion = isPrivacyRetentionDestructionQuestion(profile.normalizedQuestion());
		boolean officialReportBodyQuestion = isOfficialReportBodyQuestion(profile);
		boolean autonomyPreConsultationEvidence = isAutonomyPreConsultationEvidence(text);
		boolean autonomyPreConsultationProcedureEvidence = isAutonomyPreConsultationProcedureEvidence(text);
		boolean effectiveAutonomyPreConsultationEvidence = autonomyPreConsultationProcedureQuestion
			? autonomyPreConsultationProcedureEvidence
			: autonomyPreConsultationEvidence;
		boolean publicDataCustomSupportEvidence = isPublicDataCustomSupportEvidence(text);
		boolean publicDataStandardizationEvidence = isPublicDataStandardizationEvidence(text);
		boolean publicDataAiManagementEvidence = isPublicDataAiManagementEvidence(text);
		boolean pseudonymAdditionalInfoEvidence = isPseudonymAdditionalInfoEvidence(text);
		boolean koreanLiteratureExportEvidence = isKoreanLiteratureExportEvidence(text);
		boolean quantumOecdEvidence = isQuantumOecdEvidence(text);
		boolean tvingSmishingEvidence = isTvingSmishingEvidence(text);
		boolean cctvPublicPlaceExceptionEvidence = isCctvPublicPlaceExceptionEvidence(text);
		boolean cctvInvestigationProvisionEvidence = isCctvInvestigationProvisionEvidence(text);
		boolean cctvRetentionOrPurposeEvidence = isCctvRetentionOrPurposeEvidence(text);
		boolean privacyRetentionDestructionEvidence = isPrivacyRetentionDestructionEvidence(text);
		boolean admrulNoticeExceptionQuestion = isAdmrulNoticeExceptionQuestion(profile.normalizedQuestion());
		boolean admrulNoticeExceptionReviewEvidence = admrulNoticeExceptionQuestion
			&& isAdmrulNoticeExceptionReviewEvidence(body, documentTitle, chunkHeading);
		boolean admrulNoticeExceptionRepealNoise = admrulNoticeExceptionQuestion
			&& isAdmrulNoticeExceptionRepealNoise(body, documentTitle, chunkHeading);
		boolean officialReportBodyEvidence = officialReportBodyQuestion
			&& isOfficialReportBodyEvidence(profile, chunk, body, documentTitle, chunkHeading);
		boolean publicDataSpecialDirectEvidence =
			(publicDataQualityDiagnosisOverviewQuestion && publicDataQualityDiagnosisOverviewEvidence)
				|| (publicDataStandardTermQuestion && publicDataStandardTermEvidence && !publicDataStandardTermNoise);
		boolean effectiveRequiredTermsMatched = requiredTermsMatched
			|| publicDataSpecialDirectEvidence
			|| exactLawArticleReferenceEvidence
			|| exactDocumentBodyAnchorEvidence;
		boolean officialDocumentSpecificEvidence =
			(autonomyPreConsultationQuestion && effectiveAutonomyPreConsultationEvidence)
				|| (publicDataStandardizationQuestion && publicDataStandardizationEvidence)
				|| (publicDataCustomSupportQuestion && publicDataCustomSupportEvidence)
				|| (publicDataAiManagementQuestion && publicDataAiManagementEvidence)
				|| (pseudonymAdditionalInfoQuestion && pseudonymAdditionalInfoEvidence)
				|| (koreanLiteratureExportQuestion && koreanLiteratureExportEvidence)
				|| (quantumOecdQuestion && quantumOecdEvidence)
				|| (tvingSmishingQuestion && tvingSmishingEvidence)
				|| (cctvPublicPlaceExceptionQuestion && cctvPublicPlaceExceptionEvidence)
				|| (cctvInvestigationProvisionQuestion && cctvInvestigationProvisionEvidence)
				|| (cctvRetentionOrPurposeQuestion && cctvRetentionOrPurposeEvidence)
				|| (privacyRetentionDestructionQuestion && privacyRetentionDestructionEvidence);
		if (privacyPurposePolicyEvidence && requiredTermsMatched && !tableOfContents) {
			directEvidence = true;
		}
		if (officialDocumentSpecificEvidence && requiredTermsMatched && !tableOfContents) {
			directEvidence = true;
		}
		if (publicDataSpecialDirectEvidence && !tableOfContents) {
			directEvidence = true;
		}
		if (exactLawArticleReferenceEvidence && !tableOfContents) {
			directEvidence = true;
		}
		if (exactDocumentBodyAnchorEvidence && !tableOfContents) {
			directEvidence = true;
		}
		if (admrulNoticeExceptionReviewEvidence && !tableOfContents) {
			directEvidence = true;
		}
		if (securityReviewTargetAnswerChunk && !tableOfContents) {
			directEvidence = true;
		}
		if (trafficCrosswalkStopQuestion && trafficFacilityGuideChunk && !trafficDriverDutyChunk) {
			directEvidence = false;
		}
		if (trafficCrosswalkStopQuestion && !trafficLegalAnswerChunk) {
			directEvidence = false;
		}
		if (projectReviewScopeQuestion && !projectReviewAllowedChunk) {
			directEvidence = false;
		}
		if (preConsultationTargetQuestion && preConsultationSpecificQaChunk && !preConsultationGeneralScopeChunk) {
			directEvidence = false;
		}
		if (preConsultationTargetQuestion && !preConsultationContextChunk) {
			directEvidence = false;
		}
		if (!effectiveRequiredTermsMatched && !securityReviewTargetAnswerChunk) {
			directEvidence = false;
		}
		if (penaltyConsequenceQuestion && directEvidence && !hasPenaltyConsequenceSignal(body, chunkHeading, parentSectionTitle)) {
			directEvidence = false;
		}
		if (criticalConceptMissing) {
			directEvidence = false;
		}
		boolean intentOk = profile.definitionQuestion()
			|| profile.intentGroups().isEmpty()
			|| (profile.requiresStrongIntentSignal()
				? strongIntentSignal
				: bodyIntentMatches > 0
					|| headingIntentMatches > 0
					|| intentMatches > 0
					|| conceptIntentNear
					|| (!profile.conceptGroups().isEmpty() && termMatches >= Math.min(3, profile.terms().size())));
		boolean hasAnySignal = termMatches > 0
			|| conceptMatches + intentMatches >= 2
			|| (profile.definitionQuestion() && conceptMatches > 0);
		boolean definitionSignal = !profile.definitionQuestion()
			|| directEvidence
			|| hasDefinitionSignal(profile, body, documentTitle, chunkHeading);
		boolean exploratoryRelevant = exploratoryLookupQuestion
			&& requiredTermsMatched
			&& conceptMatches >= profile.requiredConceptMatches()
			&& termMatches >= Math.min(2, Math.max(1, profile.terms().size()));
		boolean relevant = !tableOfContents
			&& (directEvidence || exploratoryRelevant || officialReportBodyEvidence || (conceptOk && intentOk && hasAnySignal && definitionSignal));
		boolean topicAligned = !tableOfContents
			&& effectiveRequiredTermsMatched
			&& (
				directEvidence
					|| exploratoryRelevant
					|| officialReportBodyEvidence
					|| conceptAnchored
					|| conceptMatches >= profile.requiredConceptMatches()
					|| titleConceptMatches >= profile.requiredConceptMatches()
					|| (exploratoryLookupQuestion && titleMatches > 0 && bodyTermMatches > 0)
					|| titleMatches >= Math.min(2, Math.max(1, profile.terms().size()))
					|| termMatches >= Math.min(3, Math.max(1, profile.terms().size()))
			);
		if (trafficCrosswalkStopQuestion && !trafficLegalAnswerChunk) {
			relevant = false;
			topicAligned = false;
		}
		if (projectReviewScopeQuestion && !projectReviewAllowedChunk) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (preConsultationTargetQuestion && preConsultationSpecificQaChunk && !preConsultationGeneralScopeChunk) {
			directEvidence = false;
		}
		if (preConsultationTargetQuestion && !preConsultationContextChunk) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (preConsultationTargetQuestion && !preConsultationGeneralScopeChunk) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (securityReviewTargetQuestion && !securityReviewTargetAnswerChunk) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (!effectiveRequiredTermsMatched && !securityReviewTargetAnswerChunk) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (penaltyConsequenceQuestion && !hasPenaltyConsequenceSignal(body, chunkHeading, parentSectionTitle)) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (criticalConceptMissing) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (procurementCatalogContractQuestion && procurementCatalogNoiseChunk) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (targetScopeQuestion && formOrChecklistNoise && !directTargetPhrase && !securityReviewTargetAnswerChunk && !documentAnchoredDirectEvidence) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (autonomyPreConsultationQuestion && !effectiveAutonomyPreConsultationEvidence) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (publicDataCustomSupportQuestion && !publicDataCustomSupportEvidence) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (publicDataAiManagementQuestion && !publicDataAiManagementEvidence) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (publicDataActivationQuestion && publicDataStandardizationOnlyChunk) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (publicDataActivationPrivacyNoise) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (publicDataQualityDiagnosisOverviewQuestion
			&& publicDataQualityDiagnosisDetailEvidence
			&& !publicDataQualityDiagnosisOverviewEvidence) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (publicDataStandardTermQuestion
			&& (!publicDataStandardTermEvidence || publicDataStandardTermNoise)) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (koreanLiteratureExportQuestion && !koreanLiteratureExportEvidence) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (quantumOecdQuestion && !quantumOecdEvidence) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (tvingSmishingQuestion && !tvingSmishingEvidence) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (cctvPublicPlaceExceptionQuestion && !cctvPublicPlaceExceptionEvidence) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (cctvInvestigationProvisionQuestion && !cctvInvestigationProvisionEvidence) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (privacyRetentionDestructionQuestion && !privacyRetentionDestructionEvidence) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (admrulNoticeExceptionRepealNoise && !admrulNoticeExceptionReviewEvidence) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (suppressEvidenceNoise
			&& !securityReviewTargetAnswerChunk
			&& !publicDataSpecialDirectEvidence
			&& !documentAnchoredDirectEvidence
			&& !exactDocumentBodyAnchorEvidence) {
			directEvidence = false;
			relevant = false;
			topicAligned = false;
		}
		if (publicDataSpecialDirectEvidence && !tableOfContents) {
			directEvidence = true;
			relevant = true;
			topicAligned = true;
		}
		if (exactLawArticleReferenceEvidence && !tableOfContents) {
			directEvidence = true;
			relevant = true;
			topicAligned = true;
		}
		if (exactDocumentBodyAnchorEvidence && !tableOfContents) {
			directEvidence = true;
			relevant = true;
			topicAligned = true;
		}
		if (admrulNoticeExceptionReviewEvidence && !tableOfContents) {
			directEvidence = true;
			relevant = true;
			topicAligned = true;
		}

		double score = baseScore
			+ (directEvidenceMatches * 0.34)
			+ (bodyDirectEvidenceMatches * 0.48)
			+ (headingDirectEvidenceMatches * 0.28)
			+ (bodyDirectEvidenceTermMatches * 0.08)
			+ (conceptMatches * 0.26)
			+ (bodyConceptMatches * 0.12)
			+ (intentMatches * 0.18)
			+ (strongIntentSignal ? 0.2 : 0.0)
			+ (conceptIntentNear ? 0.16 : 0.0)
			+ (termMatches * 0.035)
			+ (titleMatches * 0.025);
		if (!profile.directEvidenceGroups().isEmpty()) {
			int requiredDirectMatches = profile.requiredDirectEvidenceMatches();
			if (bodyDirectEvidenceMatches >= requiredDirectMatches) {
				score += 1.75 + Math.min(0.75, bodyDirectEvidenceMatches * 0.25);
			}
			else if (directEvidenceMatches < requiredDirectMatches) {
				score -= 0.42;
			}
			if (directEvidence && bodyDirectEvidenceMatches >= requiredDirectMatches) {
				score += 0.6;
			}
		}
		if (exactDocumentBodyAnchorEvidence) {
			score += 1.4;
		}
		if (!questionAnchoredDirectGroups.isEmpty()) {
			if (bodyQuestionAnchoredDirectMatches > 0) {
				score += 3.2 + Math.min(0.8, bodyQuestionAnchoredDirectMatches * 0.4);
			}
			else if (questionAnchoredDirectMatches > 0) {
				score += 0.45;
			}
			else {
				score -= 2.4;
			}
		}
		if (preferredSection) {
			score += 0.34;
		}
		if (!profile.preferredSectionTypes().isEmpty()
			&& !sectionType.isBlank()
			&& !preferredSection
			&& profile.requiresStrongIntentSignal()) {
			score -= 0.16;
		}
		if (exploratoryRelevant) {
			score += 0.42;
		}
		if (targetScopeQuestion) {
			if (directTargetPhrase) {
				score += 1.05;
			}
			if (directTargetPhrase && targetObligationPhrase) {
				score += 0.42;
			}
			if (formOrChecklistNoise && !directTargetPhrase && !securityReviewTargetAnswerChunk) {
				score -= 1.55;
			}
			if (chunkHeading.contains("사업대상시스템") && !directTargetPhrase) {
				score -= 0.75;
			}
		}

		if (trafficCrosswalkStopQuestion) {
			if ("law".equals(chunk.target()) && documentTitle.contains("도로교통법")) {
				score += 0.84;
			}
			if (trafficDriverDutyChunk) {
				score += 0.68;
			}
			if (trafficFacilityGuideChunk && !trafficDriverDutyChunk) {
				score -= 1.35;
			}
		}
		if (projectReviewScopeQuestion) {
			if (projectReviewScopeChunk) {
				score += 1.05;
			}
			if (projectReviewPreConsultationRelationQuestion && preConsultationContextChunk) {
				score += 0.72;
			}
			if (projectReviewAdjacentChunk && !projectReviewScopeChunk && !(projectReviewPreConsultationRelationQuestion && preConsultationContextChunk)) {
				score -= 1.35;
			}
		}
		if (preConsultationTargetQuestion) {
			if (!preConsultationContextChunk) {
				score -= 1.45;
			}
			if (preConsultationGeneralScopeChunk) {
				score += 0.82;
			}
			if (preConsultationSpecificQaChunk && !preConsultationGeneralScopeChunk) {
				score -= 1.05;
			}
		}
		if (procurementCatalogContractQuestion) {
			if (procurementCatalogNoiseChunk) {
				score -= 1.35;
			}
			if (procurementCatalogContractChunk) {
				score += 0.92;
				if (procurementCatalogScopeChunk) {
					score += 0.64;
				}
				if (procurementContractMethodQuestion && procurementContractMethodChunk) {
					score += 2.2;
				}
				if (procurementContractMethodQuestion && procurementExclusionChunk) {
					score -= 1.1;
				}
			}
			else if (text.contains("수의계약")) {
				score -= 0.65;
			}
		}
		if (privacyPurposePolicyEvidence) {
			score += 1.15;
		}
		if (autonomyPreConsultationQuestion) {
			score += effectiveAutonomyPreConsultationEvidence ? 3.4 : -2.6;
			if (autonomyPreConsultationProcedureQuestion) {
				score += autonomyPreConsultationProcedureEvidence ? 2.0 : -1.4;
			}
		}
		if (publicDataCustomSupportQuestion) {
			score += publicDataCustomSupportEvidence ? 2.2 : -1.1;
		}
		if (publicDataAiManagementQuestion) {
			score += publicDataAiManagementEvidence ? 1.8 : -1.0;
		}
		if (publicDataQualityDiagnosisOverviewQuestion) {
			if (publicDataQualityDiagnosisPreferredOverview) {
				score += 3.2;
			}
			else if (publicDataQualityDiagnosisOverviewEvidence) {
				score += 1.7;
			}
			if (publicDataQualityDiagnosisDetailEvidence && !publicDataQualityDiagnosisOverviewEvidence) {
				score -= 2.1;
			}
		}
		if (publicDataStandardTermQuestion) {
			if (publicDataStandardTermEvidence) {
				score += 2.7;
			}
			if (publicDataStandardTermNoise) {
				score -= 2.0;
			}
		}
		if (koreanLiteratureExportQuestion) {
			score += koreanLiteratureExportEvidence ? 2.5 : -1.4;
		}
		if (quantumOecdQuestion) {
			score += quantumOecdEvidence ? 3.0 : -1.8;
		}
		if (tvingSmishingQuestion) {
			score += tvingSmishingEvidence ? 3.2 : -1.6;
		}
		if (cctvPublicPlaceExceptionQuestion) {
			score += cctvPublicPlaceExceptionEvidence ? 4.0 : -1.8;
		}
		if (cctvInvestigationProvisionQuestion) {
			score += cctvInvestigationProvisionEvidence ? 4.2 : -2.0;
		}
		if (privacyRetentionDestructionQuestion) {
			score += privacyRetentionDestructionEvidence ? 3.8 : -2.1;
		}
		if (officialReportBodyEvidence) {
			score += 2.1;
		}
		if (profile.conceptGroups().size() > profile.requiredConceptMatches()
			&& conceptMatches > profile.requiredConceptMatches()) {
			score += (conceptMatches - profile.requiredConceptMatches()) * 0.18;
		}
		if (profile.definitionQuestion() && committeeQuestion) {
			if (body.contains("이하위원회라한다") || body.contains("위원회라한다")) {
				score += 0.52;
			}
			if ((body.contains("둔다") || body.contains("설치")) && (body.contains("심의의결") || body.contains("심의조정"))) {
				score += 0.34;
			}
			if (title.contains("지원단") && !body.contains("이하위원회라한다")) {
				score -= 0.28;
			}
		}
		if (!conceptOk && !profile.conceptGroups().isEmpty()) {
			score -= 0.55;
		}
		if (!conceptAnchored && !profile.conceptGroups().isEmpty()) {
			score -= 0.35;
		}
		if (!intentOk && !profile.intentGroups().isEmpty()) {
			score -= 0.25;
		}
		if (profile.requiresStrongIntentSignal() && !strongIntentSignal) {
			score -= 0.25;
		}
		if (!requiredTermsMatched) {
			score -= 2.4;
		}
		if (!definitionSignal) {
			score -= 0.45;
		}
		if (body.length() < 80) {
			score -= 0.04;
		}
		if (tableOfContents) {
			score -= 1.2;
		}
		if (EvidenceNoiseClassifier.shouldDownrankAsContextOnly(chunk)) {
			score -= 0.45;
		}
		if (suppressEvidenceNoise) {
			score -= 1.8;
		}

		return new JudgedChunk(chunk, score, topicAligned, relevant, directEvidence, directEvidenceMatches, bodyDirectEvidenceMatches);
	}

	private List<JudgedChunk> preferCommitteeExpansion(EvidenceQuestionProfile profile, List<JudgedChunk> chunks) {
		List<String> preferredTerms = profile.preferredCommitteeTerms();
		if (preferredTerms.isEmpty() || chunks.size() <= 1) {
			return chunks;
		}
		List<JudgedChunk> preferredChunks = chunks.stream()
			.filter(chunk -> containsAny(normalize(chunk.chunk().title() + " " + chunk.chunk().parentSectionTitle() + " " + chunk.chunk().chunkTitle() + " " + chunk.chunk().chunkText()), preferredTerms))
			.toList();
		return preferredChunks.isEmpty() ? chunks : preferredChunks;
	}

	private List<JudgedChunk> preferSecurityReviewGuideEvidence(EvidenceQuestionProfile profile, List<JudgedChunk> chunks) {
		if (!isSecurityReviewTargetQuestion(profile.normalizedQuestion()) || chunks.size() <= 1) {
			return chunks;
		}
		List<JudgedChunk> guideChunks = chunks.stream()
			.filter(chunk -> isSecurityReviewGuideAnswerChunk(chunk.chunk()))
			.toList();
		if (guideChunks.isEmpty()) {
			return chunks;
		}
		List<JudgedChunk> reorderedChunks = new ArrayList<>(chunks.size());
		reorderedChunks.addAll(guideChunks);
		chunks.stream()
			.filter(chunk -> !guideChunks.contains(chunk))
			.forEach(reorderedChunks::add);
		return reorderedChunks;
	}

	private static boolean isSecurityReviewGuideAnswerChunk(LawSemanticChunkRow chunk) {
		String target = chunk.target() == null ? "" : chunk.target();
		if (!"official_doc".equals(target) && !"internal_doc".equals(target) && !"reference_doc".equals(target)) {
			return false;
		}
		String body = normalize(chunk.chunkText());
		String title = normalize(chunk.title());
		String heading = normalize(chunk.parentSectionTitle() + " " + chunk.chunkTitle());
		String text = title + heading + body;
		boolean guideTitle = title.contains("보안성검토가이드")
			|| title.contains("정보화사업보안성검토")
			|| title.contains("정보화사업보안성검토가이드");
		return guideTitle && isSecurityReviewTargetAnswerChunk(body, title, heading);
	}

	private boolean isExploratoryLookupQuestion(String normalizedQuestion) {
		if (normalizedQuestion == null || normalizedQuestion.isBlank()) {
			return false;
		}
		if (explicitlyExcludesUiNavigation(normalizedQuestion)) {
			return false;
		}
		boolean lookupCue = normalizedQuestion.contains("가이드")
			|| normalizedQuestion.contains("매뉴얼")
			|| normalizedQuestion.contains("안내")
			|| normalizedQuestion.contains("사용법")
			|| normalizedQuestion.contains("권한")
			|| containsAny(normalizedQuestion, UI_NAVIGATION_LOOKUP_TERMS);
		boolean strictDecisionCue = normalizedQuestion.contains("대상")
			|| normalizedQuestion.contains("제외")
			|| normalizedQuestion.contains("포함")
			|| normalizedQuestion.contains("필수")
			|| normalizedQuestion.contains("항목")
			|| normalizedQuestion.contains("예외")
			|| normalizedQuestion.contains("조건")
			|| normalizedQuestion.contains("사유")
			|| normalizedQuestion.contains("가능")
			|| normalizedQuestion.contains("허용")
			|| normalizedQuestion.contains("금지")
			|| normalizedQuestion.contains("해야")
			|| normalizedQuestion.contains("안해도")
			|| normalizedQuestion.contains("되나")
			|| normalizedQuestion.contains("될까");
		return lookupCue && !strictDecisionCue;
	}

	private static boolean explicitlyExcludesUiNavigation(String normalizedQuestion) {
		String question = normalize(normalizedQuestion);
		for (String uiTerm : UI_NAVIGATION_TERMS) {
			int uiStart = question.indexOf(uiTerm);
			while (uiStart >= 0) {
				int afterUi = uiStart + uiTerm.length();
				int markerLimit = Math.min(question.length(), afterUi + UI_NAVIGATION_EXCLUSION_WINDOW);
				for (String exclusionCue : UI_NAVIGATION_EXCLUSION_CUES) {
					int markerStart = question.indexOf(exclusionCue, afterUi);
					while (markerStart >= 0 && markerStart <= markerLimit) {
						int afterMarker = markerStart + exclusionCue.length();
						if (!isAdditiveNotExclusion(question, exclusionCue, markerStart)
							&& !containsUiNavigationTermAfter(question, afterMarker)) {
							return true;
						}
						markerStart = question.indexOf(exclusionCue, afterMarker);
					}
				}
				uiStart = question.indexOf(uiTerm, afterUi);
			}
		}
		return false;
	}

	private static boolean isAdditiveNotExclusion(String question, String exclusionCue, int markerStart) {
		return "아니라".equals(exclusionCue)
			&& markerStart >= 2
			&& question.startsWith("뿐만아니라", markerStart - 2);
	}

	private static boolean containsUiNavigationTermAfter(String question, int start) {
		return UI_NAVIGATION_TERMS.stream().anyMatch(term -> question.indexOf(term, start) >= 0);
	}

	private boolean matchesExploratoryLookup(EvidenceQuestionProfile profile, LawSemanticChunkRow chunk) {
		if (isTableOfContentsLike(chunk)) {
			return false;
		}
		String body = normalize(chunk.chunkText());
		String title = normalize(chunk.title() + " " + chunk.parentSectionTitle() + " " + chunk.chunkTitle());
		String text = title + body;
		List<String> titleAnchors = exploratoryCoreTitleAnchors(profile);
		if (!titleAnchors.isEmpty() && !containsAny(title, titleAnchors)) {
			return false;
		}
		List<String> contentTerms = profile.terms().stream()
			.filter(term -> !profile.requiredTerms().contains(term))
			.filter(term -> !isExploratoryDescriptorTerm(term))
			.toList();
		int termMatches = countMatchedTerms(text, profile.terms());
		int conceptMatches = countMatchedGroups(text, profile.conceptGroups());
		if (termMatches < Math.min(2, Math.max(1, profile.terms().size()))) {
			return false;
		}
		int titleMatches = countMatchedTerms(title, profile.terms());
		int bodyMatches = countMatchedTerms(body, profile.terms());
		boolean splitTitleBodySignal = titleMatches > 0 && bodyMatches > 0;
		boolean contentTermMatched = contentTerms.isEmpty()
			|| contentTerms.stream().anyMatch(text::contains)
			|| splitTitleBodySignal
			|| conceptMatches >= profile.requiredConceptMatches();
		if (!contentTermMatched) {
			return false;
		}
		return splitTitleBodySignal
			|| termMatches >= Math.min(3, profile.terms().size());
	}

	private List<JudgedChunk> preferExploratoryTitleAnchors(EvidenceQuestionProfile profile, List<JudgedChunk> chunks) {
		if (chunks == null || chunks.size() <= 1 || !isExploratoryLookupQuestion(profile.normalizedQuestion())) {
			return chunks == null ? List.of() : chunks;
		}
		List<String> anchors = exploratoryTitleAnchors(profile);
		if (anchors.isEmpty()) {
			return chunks;
		}
		List<JudgedChunk> titleAnchoredChunks = chunks.stream()
			.filter(chunk -> containsAny(normalize(chunk.chunk().title() + " " + chunk.chunk().parentSectionTitle() + " " + chunk.chunk().chunkTitle()), anchors))
			.toList();
		if (titleAnchoredChunks.isEmpty()) {
			return chunks;
		}
		List<JudgedChunk> selected = new ArrayList<>(titleAnchoredChunks);
		chunks.stream()
			.filter(chunk -> !titleAnchoredChunks.contains(chunk))
			.filter(chunk -> isExploratoryComplementaryEvidence(profile, chunk.chunk()))
			.forEach(selected::add);
		return selected;
	}

	private boolean isExploratoryComplementaryEvidence(EvidenceQuestionProfile profile, LawSemanticChunkRow chunk) {
		if (isTableOfContentsLike(chunk)) {
			return false;
		}
		String body = normalize(chunk.chunkText());
		String title = normalize(chunk.title() + " " + chunk.parentSectionTitle() + " " + chunk.chunkTitle());
		String text = title + body;
		List<String> titleTerms = profile.terms().stream()
			.map(EvidenceJudge::normalize)
			.filter(term -> term.length() >= 3)
			.filter(term -> !isExploratoryDescriptorTerm(term))
			.filter(term -> !Set.of("권한", "사용자", "담당자", "메뉴").contains(term))
			.toList();
		boolean titleAnchor = containsAny(title, exploratoryCoreTitleAnchors(profile))
			|| containsAny(title, titleTerms);
		boolean bodyIntent = countMatchedTerms(body, profile.terms()) > 0
			|| countMatchedGroups(body, profile.conceptGroups()) > 0
			|| countMatchedGroups(body, profile.intentGroups()) > 0;
		boolean enoughQuestionOverlap = countMatchedTerms(text, profile.terms()) >= 2
			|| countMatchedGroups(text, profile.conceptGroups()) > 0
			|| countMatchedGroups(text, profile.intentGroups()) > 0;
		return titleAnchor && bodyIntent && enoughQuestionOverlap;
	}

	private List<String> exploratoryTitleAnchors(EvidenceQuestionProfile profile) {
		java.util.LinkedHashSet<String> anchors = new java.util.LinkedHashSet<>();
		anchors.addAll(exploratoryCoreTitleAnchors(profile));
		return anchors.stream().toList();
	}

	private List<String> exploratoryCoreTitleAnchors(EvidenceQuestionProfile profile) {
		java.util.LinkedHashSet<String> anchors = new java.util.LinkedHashSet<>();
		for (String requiredTerm : profile.requiredTerms()) {
			String normalized = normalize(requiredTerm);
			if (isExploratoryCoreTitleAnchor(normalized)) {
				anchors.add(normalized);
			}
		}
		for (String term : profile.terms()) {
			String normalized = normalize(term);
			if (isExploratoryCoreTitleAnchor(normalized)) {
				anchors.add(normalized);
			}
		}
		return anchors.stream().toList();
	}

	private boolean isExploratoryCoreTitleAnchor(String term) {
		String normalized = normalize(term);
		if (normalized.length() < 3 || isExploratoryDescriptorTerm(normalized)) {
			return false;
		}
		return !Set.of("권한", "사용자", "담당자", "메뉴").contains(normalized);
	}

	private boolean isExploratoryDescriptorTerm(String term) {
		String normalized = normalize(term);
		if (normalized.endsWith("뿐만")
			&& UI_NAVIGATION_TERMS.stream().anyMatch(normalized::startsWith)) {
			return true;
		}
		return Set.of(
			"가이드",
			"매뉴얼",
			"안내",
			"사용법",
			"화면",
			"말고",
			"아니라",
			"제외",
			"빼고",
			"뿐만",
			"뿐만아니라",
			"어디야",
			"사용자",
			"담당자",
			"문서",
			"자료"
		).contains(normalized);
	}

	private boolean hasDefinitionSignal(
		EvidenceQuestionProfile profile,
		String body,
		String documentTitle,
		String chunkHeading
	) {
		List<String> conceptTerms = flattenGroups(profile.conceptGroups());
		if (conceptTerms.isEmpty()) {
			conceptTerms = profile.terms();
		}
		if (conceptTerms.isEmpty()) {
			return true;
		}
		if (conceptTerms.stream().anyMatch(term -> normalize(term).contains("보안성검토"))) {
			return hasSecurityReviewDefinitionSignal(body, chunkHeading);
		}
		if (containsAny(chunkHeading, conceptTerms)) {
			return true;
		}
		if (containsAny(documentTitle, conceptTerms)
			&& containsAny(chunkHeading, List.of("개요", "목적", "대상", "정의", "운영계획"))) {
			return true;
		}
		if (profile.committeeQuestion()
			&& (body.contains("위원회라한다") || body.contains("이하위원회라한다") || body.contains("위원회를둔다"))) {
			return true;
		}
		List<String> cues = List.of(
			"정의",
			"개요",
			"목적",
			"의미",
			"말한다",
			"라한다",
			"대상",
			"범위",
			"기준",
			"요건",
			"지표",
			"평가지표",
			"평가항목",
			"설치",
			"둔다",
			"실시",
			"요청",
			"심의",
			"의결",
			"검토대상",
			"검토를거쳐",
			"위하여",
			"하는것",
			"하는업무"
		);
		for (String term : conceptTerms) {
			String normalizedTerm = normalize(term);
			if (normalizedTerm.length() >= 2 && containsNearAny(body, normalizedTerm, cues, 64)) {
				return true;
			}
		}
		return false;
	}

	private boolean hasSecurityReviewDefinitionSignal(String body, String chunkHeading) {
		String text = chunkHeading + body;
		List<String> cues = List.of(
			"추진개요",
			"목적",
			"근거",
			"시기",
			"대상사업",
			"대상시스템",
			"검토대상",
			"검토절차",
			"운영계획",
			"실시",
			"요청할수있다",
			"검토를거쳐",
			"생략대상",
			"절차이행생략대상",
			"제2장제2절보안성검토"
		);
		return containsNearAny(text, "보안성검토", cues, 96);
	}

	private static boolean containsNearAny(String text, String term, List<String> values, int window) {
		int start = text.indexOf(term);
		while (start >= 0) {
			int from = Math.max(0, start - window);
			int to = Math.min(text.length(), start + term.length() + window);
			String nearby = text.substring(from, to);
			for (String value : values) {
				if (nearby.contains(normalize(value))) {
					return true;
				}
			}
			start = text.indexOf(term, start + term.length());
		}
		return false;
	}

	private static boolean hasGroupNearGroup(
		String text,
		List<List<String>> leftGroups,
		List<List<String>> rightGroups,
		int window
	) {
		if (text == null || text.isBlank() || leftGroups.isEmpty() || rightGroups.isEmpty()) {
			return false;
		}
		List<String> leftTerms = flattenGroups(leftGroups).stream()
			.map(EvidenceJudge::normalize)
			.filter(term -> term.length() >= 2)
			.toList();
		List<String> rightTerms = flattenGroups(rightGroups).stream()
			.map(EvidenceJudge::normalize)
			.filter(term -> term.length() >= 2)
			.toList();
		for (String leftTerm : leftTerms) {
			if (containsNearAny(text, leftTerm, rightTerms, window)) {
				return true;
			}
		}
		return false;
	}

	private boolean hasStrongIntentSignal(EvidenceQuestionProfile profile, String body, String chunkHeading) {
		if (!profile.requiresStrongIntentSignal()) {
			return true;
		}
		return containsAny(chunkHeading + body, profile.strongIntentCues());
	}

	private boolean isRelationSideEvidence(
		boolean relationQuestion,
		boolean projectReviewScopeChunk,
		boolean preConsultationContextChunk,
		int conceptMatches
	) {
		return relationQuestion
			&& conceptMatches > 0
			&& (projectReviewScopeChunk || preConsultationContextChunk);
	}

	private static List<String> flattenGroups(List<List<String>> groups) {
		return groups.stream()
			.flatMap(List::stream)
			.distinct()
			.toList();
	}

	private List<List<String>> questionAnchoredDirectEvidenceGroups(EvidenceQuestionProfile profile) {
		if (profile.directEvidenceGroups().isEmpty()) {
			return List.of();
		}
		List<String> anchors = questionDirectAnchorTerms(profile);
		if (anchors.isEmpty()) {
			return List.of();
		}
		boolean strictAnchorMatching = requiresStrictQuestionAnchoredDirectMatching(profile.normalizedQuestion());
		return profile.directEvidenceGroups().stream()
			.map(group -> strictAnchorMatching
				? anchoredDirectEvidenceTerms(group, anchors)
				: (directEvidenceGroupContainsAnchor(group, anchors) ? group : List.<String>of()))
			.filter(group -> !group.isEmpty())
			.toList();
	}

	private boolean requiresStrictQuestionAnchoredDirectMatching(String normalizedQuestion) {
		String question = normalize(normalizedQuestion);
		return containsAny(question, List.of("하드웨어", "hw", "hardware", "appliance"));
	}

	private List<String> questionDirectAnchorTerms(EvidenceQuestionProfile profile) {
		String question = normalize(profile.normalizedQuestion());
		LinkedHashSet<String> anchors = new LinkedHashSet<>();
		for (String term : profile.terms()) {
			String normalized = normalizeQuestionTerm(term);
			if (!isWeakDirectAnchorTerm(normalized)) {
				anchors.add(normalized);
			}
		}
		if (containsAny(question, List.of("하드웨어", "hw", "hardware", "appliance"))) {
			anchors.addAll(List.of("하드웨어", "h/w", "hw", "hardware", "appliance", "단순h/w", "단순hw"));
		}
		if (containsAny(question, List.of("비대상", "제외", "면제", "안해도", "안해", "하지않아도", "아니어도"))) {
			anchors.addAll(List.of("비대상", "제외", "면제", "소프트웨어사업으로볼수없는", "소프트웨어사업으로 볼 수 없는"));
		}
		if (containsAny(question, List.of("수의계약", "계약방식", "계약방법", "계약"))) {
			anchors.addAll(List.of("수의계약", "계약방법", "계약방식", "구매계약"));
		}
		if (containsAny(question, List.of("디지털카탈로그", "디지털카달로그", "디지털서비스몰", "종합쇼핑몰", "나라장터", "조달청"))) {
			anchors.addAll(List.of("디지털서비스몰", "디지털카탈로그", "디지털카달로그", "종합쇼핑몰", "나라장터", "조달청"));
		}
		if (containsAny(question, List.of("보안성검토", "보안성검토대상"))) {
			anchors.addAll(List.of("보안성검토", "보안성검토대상", "정보시스템구축", "주요데이터베이스구축"));
		}
		return anchors.stream()
			.map(EvidenceJudge::normalize)
			.filter(term -> term.length() >= 2)
			.distinct()
			.toList();
	}

	private boolean directEvidenceGroupContainsAnchor(List<String> group, List<String> anchors) {
		return !anchoredDirectEvidenceTerms(group, anchors).isEmpty();
	}

	private List<String> anchoredDirectEvidenceTerms(List<String> group, List<String> anchors) {
		List<String> anchoredTerms = new ArrayList<>();
		for (String groupTerm : group) {
			String normalizedGroupTerm = normalize(groupTerm);
			if (normalizedGroupTerm.isBlank()) {
				continue;
			}
			for (String anchor : anchors) {
				String normalizedAnchor = normalize(anchor);
				if (normalizedAnchor.length() < 2 || isWeakDirectAnchorTerm(normalizedAnchor)) {
					continue;
				}
				if (normalizedGroupTerm.contains(normalizedAnchor) || normalizedAnchor.contains(normalizedGroupTerm)) {
					anchoredTerms.add(groupTerm);
					break;
				}
			}
		}
		return anchoredTerms;
	}

	private static boolean isWeakDirectAnchorTerm(String term) {
		String normalized = normalize(term);
		return normalized.isBlank()
			|| KoreanQueryNormalizer.isWeakQuestionTerm(normalized)
			|| Set.of(
				"사업",
				"대상",
				"대상사업",
				"적용대상",
				"공공sw",
				"sw",
				"소프트웨어",
				"소프트웨어사업",
				"공공소프트웨어",
				"과업심의",
				"사전협의",
				"정보화사업",
				"공공기관",
				"국가기관"
			).contains(normalized);
	}

	private boolean isTableOfContentsLike(LawSemanticChunkRow chunk) {
		String text = HwpxTextCleaner.clean(String.valueOf(chunk.chunkText() == null ? "" : chunk.chunkText()))
			.trim()
			.toLowerCase();
		if (text.isEmpty()) {
			return false;
		}
		String head = text.substring(0, Math.min(260, text.length()))
			.replaceAll("\\s+", " ");
		return head.contains("목 차")
			|| head.contains("목차")
			|| head.contains("contents");
	}

	// 메소드 설명: countCoveredGroups 처리 흐름을 수행합니다.
	private int countCoveredGroups(List<JudgedChunk> chunks, List<List<String>> groups) {
		int count = 0;
		for (List<String> group : groups) {
			boolean covered = chunks.stream()
				.anyMatch(chunk -> containsAny(normalize(chunk.chunk().title() + " " + chunk.chunk().parentSectionTitle() + " " + chunk.chunk().chunkTitle() + " " + chunk.chunk().chunkText()), group));
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

	private boolean containsAll(String text, List<String> values) {
		if (values == null || values.isEmpty()) {
			return true;
		}
		String normalizedText = normalize(text);
		return values.stream()
			.map(EvidenceJudge::normalize)
			.allMatch(value -> matchesRequiredTermOrAlias(normalizedText, value));
	}

	private static boolean matchesRequiredTermOrAlias(String text, String value) {
		String term = normalize(value);
		if (term.isBlank()) {
			return true;
		}
		if (text.contains(term)) {
			return true;
		}
		return switch (term) {
			case "cctv" -> text.contains("영상정보처리기기") || text.contains("고정형영상정보처리기기");
			case "ai" -> text.contains("인공지능");
			case "sw" -> text.contains("소프트웨어");
			case "hw" -> text.contains("하드웨어");
			default -> false;
		};
	}

	private static boolean isOfficialReportBodyQuestion(EvidenceQuestionProfile profile) {
		if (profile == null || !profile.preferredSectionTypes().contains("body")) {
			return false;
		}
		String question = normalize(profile.normalizedQuestion());
		return question.contains("조사")
			|| question.contains("통계")
			|| question.contains("보고서")
			|| question.contains("공식문서")
			|| question.contains("정책")
			|| question.contains("문서");
	}

	private boolean isOfficialReportBodyEvidence(
		EvidenceQuestionProfile profile,
		LawSemanticChunkRow chunk,
		String body,
		String documentTitle,
		String chunkHeading
	) {
		if (profile == null || chunk == null) {
			return false;
		}
		String target = String.valueOf(chunk.target());
		if (!"official_doc".equals(target) && !"internal_doc".equals(target)) {
			return false;
		}
		if (body == null || body.length() < 40) {
			return false;
		}
		String question = normalize(profile.normalizedQuestion());
		String text = documentTitle + chunkHeading + body;
		int conceptMatches = countMatchedGroups(text, profile.conceptGroups());
		int termMatches = countMatchedTerms(text, profile.terms());
		boolean strongAnchor = conceptMatches >= Math.min(2, Math.max(1, profile.conceptGroups().size()))
			|| termMatches >= Math.min(3, Math.max(1, profile.terms().size()));
		if (question.contains("콘텐츠산업")) {
			return text.contains("콘텐츠산업")
				&& (text.contains("콘텐츠산업조사") || text.contains("승인통계") || text.contains("결과보고서"))
				&& containsAny(body, List.of("매출", "사업체", "종사자", "수출", "부가가치", "통계", "조사"));
		}
		if (question.contains("스포츠산업")) {
			return text.contains("스포츠산업")
				&& (text.contains("스포츠산업조사") || text.contains("결과보고서") || text.contains("보고서"))
				&& containsAny(body, List.of("매출", "사업체", "종사자", "시장규모", "조사", "통계"));
		}
		if ((question.contains("과기정통부") || question.contains("과학기술정보통신부"))
			&& (question.contains("인공지능") || question.contains("ai"))) {
			return (text.contains("과학기술정보통신부") || text.contains("과기정통부") || text.contains("인공지능") || text.contains("ai"))
				&& containsAny(body, List.of("정책", "추진", "서비스", "확산", "선도사업", "방향", "계획", "전략"));
		}
		boolean reportCue = text.contains("보고서")
			|| text.contains("조사")
			|| text.contains("통계")
			|| text.contains("정책")
			|| text.contains("계획");
		boolean bodyCue = containsAny(body, List.of("결과", "현황", "통계", "정책", "추진", "관리", "계획", "사업"));
		return strongAnchor && reportCue && bodyCue;
	}

	private static boolean isAutonomyPreConsultationQuestion(String normalizedQuestion) {
		return normalizedQuestion != null
			&& normalizedQuestion.contains("자치분권")
			&& normalizedQuestion.contains("사전협의");
	}

	private static boolean isAutonomyPreConsultationEvidence(String text) {
		return text.contains("자치분권")
			&& text.contains("사전협의")
			&& (text.contains("법령제개정권한") || text.contains("중앙행정기관") || text.contains("대상기관"));
	}

	private static boolean isAutonomyPreConsultationProcedureQuestion(String normalizedQuestion) {
		return isAutonomyPreConsultationQuestion(normalizedQuestion)
			&& (normalizedQuestion.contains("절차")
				|| normalizedQuestion.contains("방법")
				|| normalizedQuestion.contains("어떻게"));
	}

	private static boolean isAutonomyPreConsultationProcedureEvidence(String text) {
		boolean procedureFlow = text.contains("전체흐름도") || text.contains("협의절차전체흐름도");
		boolean requestStep = text.contains("사전협의요청서작성")
			|| text.contains("사전협의요청서작성제출")
			|| text.contains("사전협의요청서");
		boolean localAutonomyReview = text.contains("지방자치관련성검토");
		boolean resultNotice = text.contains("협의결과서통보")
			|| text.contains("결과통보서")
			|| text.contains("검토의견제시")
			|| text.contains("검토의견");
		boolean legalReview = text.contains("법령안검토")
			&& (text.contains("사무배분의적정성") || text.contains("자치권보장") || text.contains("국가관여의적정성"));
		boolean legislativeFlow = text.contains("법령안입안")
			&& text.contains("관계기관협의")
			&& resultNotice;
		return text.contains("자치분권")
			&& text.contains("사전협의")
			&& ((procedureFlow && requestStep && localAutonomyReview)
				|| (requestStep && localAutonomyReview && resultNotice)
				|| (legalReview && resultNotice)
				|| legislativeFlow);
	}

	private static boolean isPublicDataCustomSupportQuestion(String normalizedQuestion) {
		return normalizedQuestion != null
			&& normalizedQuestion.contains("공공데이터")
			&& (
				(normalizedQuestion.contains("활용기업")
					&& normalizedQuestion.contains("맞춤형")
					&& normalizedQuestion.contains("지원"))
				|| (normalizedQuestion.contains("전처리")
					&& (normalizedQuestion.contains("코칭") || normalizedQuestion.contains("절차")))
			);
	}

	private static boolean isPublicDataCustomSupportEvidence(String text) {
		return text.contains("공공데이터")
			&& (
				(text.contains("활용기업")
					&& text.contains("맞춤형")
					&& (text.contains("공공데이터활용역량")
						|| text.contains("수요분석")
						|| text.contains("기업이필요한공공데이터제공")
						|| (text.contains("데이터검색") && text.contains("추천"))))
				|| (text.contains("데이터전처리")
					&& text.contains("오류원인분석")
					&& (text.contains("대상선정") || text.contains("방법결정") || text.contains("삭제")))
			);
	}

	private static boolean isPublicDataStandardizationQuestion(String normalizedQuestion) {
		return normalizedQuestion != null
			&& (normalizedQuestion.contains("공공데이터베이스") || normalizedQuestion.contains("공공데이터포털"))
			&& (normalizedQuestion.contains("표준화")
				|| normalizedQuestion.contains("표준용어")
				|| normalizedQuestion.contains("표준도메인")
				|| normalizedQuestion.contains("데이터표준")
				|| normalizedQuestion.contains("품질관리")
				|| normalizedQuestion.contains("품질진단")
				|| normalizedQuestion.contains("진단항목")
				|| normalizedQuestion.contains("진단영역"));
	}

	private static boolean isPublicDataStandardizationEvidence(String text) {
		boolean standardizationContext = text.contains("공공데이터베이스")
			&& (text.contains("표준화") || text.contains("표준화관리매뉴얼"));
		boolean targetScopeEvidence = (text.contains("표준화대상") || text.contains("적용범위"))
			&& (text.contains("공공기관") || text.contains("적용대상"));
		boolean standardTermEvidence = text.contains("표준용어")
			&& (text.contains("표준도메인") || text.contains("데이터표준") || text.contains("일관성"));
		boolean requirementEvidence = text.contains("요구사항")
			&& (text.contains("데이터표준") || text.contains("표준용어") || text.contains("표준도메인"));
		boolean diagnosisEvidence = text.contains("예방적품질관리")
			&& (text.contains("진단영역") || text.contains("진단항목") || text.contains("진단기준"))
			&& (text.contains("4개영역") || text.contains("9개항목") || text.contains("18개진단기준"));
		return standardizationContext && (targetScopeEvidence || standardTermEvidence || requirementEvidence || diagnosisEvidence);
	}

	private static boolean isPublicDataAiManagementQuestion(String normalizedQuestion) {
		return normalizedQuestion != null
			&& normalizedQuestion.contains("공공데이터")
			&& (normalizedQuestion.contains("ai") || normalizedQuestion.contains("인공지능"))
			&& (normalizedQuestion.contains("학습용") || normalizedQuestion.contains("학습데이터") || normalizedQuestion.contains("친화적관리"));
	}

	private static boolean isPublicDataAiManagementEvidence(String text) {
		return text.contains("공공데이터")
			&& (text.contains("인공지능친화적관리")
				|| (text.contains("학습데이터") && text.contains("참조데이터"))
				|| (text.contains("데이터셋") && text.contains("메타데이터")));
	}

	private static boolean isPseudonymAdditionalInfoQuestion(String normalizedQuestion) {
		return normalizedQuestion != null
			&& (normalizedQuestion.contains("가명정보") || normalizedQuestion.contains("가명처리"))
			&& (normalizedQuestion.contains("추가정보")
				|| normalizedQuestion.contains("분리보관")
				|| normalizedQuestion.contains("분리하여보관")
				|| normalizedQuestion.contains("파기"));
	}

	private static boolean isPseudonymAdditionalInfoEvidence(String text) {
		return text.contains("가명정보")
			&& text.contains("추가정보")
			&& (text.contains("분리보관")
				|| text.contains("분리하여보관")
				|| text.contains("별도보관")
				|| text.contains("파기"));
	}

	private static boolean isKoreanLiteratureExportQuestion(String normalizedQuestion) {
		return normalizedQuestion != null
			&& normalizedQuestion.contains("한국문학")
			&& (normalizedQuestion.contains("해외진출")
				|| (normalizedQuestion.contains("해외") && normalizedQuestion.contains("진출"))
				|| normalizedQuestion.contains("번역"));
	}

	private static boolean isKoreanLiteratureExportEvidence(String text) {
		return text.contains("한국문학")
			&& (text.contains("한국문학번역")
				|| text.contains("해외진출지원")
				|| text.contains("해외출판사")
				|| text.contains("기획번역"));
	}

	private static boolean isQuantumOecdQuestion(String normalizedQuestion) {
		return normalizedQuestion != null
			&& normalizedQuestion.contains("oecd")
			&& (normalizedQuestion.contains("양자") || normalizedQuestion.contains("퀀텀"))
			&& normalizedQuestion.contains("권고문");
	}

	private static boolean isQuantumOecdEvidence(String text) {
		return text.contains("oecd")
			&& text.contains("양자")
			&& (text.contains("권고문")
				|| text.contains("재정적기여")
				|| text.contains("국제연수회")
				|| text.contains("초안작성"));
	}

	private static boolean isTvingSmishingQuestion(String normalizedQuestion) {
		return normalizedQuestion != null
			&& (normalizedQuestion.contains("티빙") || normalizedQuestion.contains("tving"))
			&& normalizedQuestion.contains("스미싱");
	}

	private static boolean isTvingSmishingEvidence(String text) {
		return (text.contains("티빙") || text.contains("tving"))
			&& text.contains("스미싱")
			&& (text.contains("스미싱피해신고")
				|| text.contains("소액결제확인서")
				|| text.contains("경찰서사이버수사대")
				|| text.contains("사건사고사실확인서"));
	}

	private static boolean isCctvPublicPlaceExceptionQuestion(String normalizedQuestion) {
		return normalizedQuestion != null
			&& (normalizedQuestion.contains("cctv") || normalizedQuestion.contains("영상정보처리기기"))
			&& normalizedQuestion.contains("공개된장소")
			&& (normalizedQuestion.contains("예외")
				|| normalizedQuestion.contains("설치할수")
				|| normalizedQuestion.contains("설치가능")
				|| normalizedQuestion.contains("가능한가")
				|| normalizedQuestion.contains("가능"));
	}

	private boolean isAdmrulNoticeExceptionQuestion(String normalizedQuestion) {
		String question = normalize(normalizedQuestion);
		return question.contains("면제예외인정에관한정책지침")
			|| (question.contains("면제") && question.contains("예외") && question.contains("정책지침"));
	}

	private boolean isAdmrulNoticeExceptionReviewEvidence(String body, String documentTitle, String chunkHeading) {
		String title = normalize(documentTitle);
		String heading = normalize(chunkHeading);
		String text = normalize(body);
		boolean titleMatched = title.contains("면제예외인정에관한정책지침")
			|| (title.contains("면제예외") && title.contains("정책지침"));
		boolean reviewSignal = containsAny(text, List.of("위험평가", "안전기준", "충분히검토", "검토하여야", "검토해야", "항공환경"))
			|| (heading.contains("처리기준") && text.contains("검토"));
		return titleMatched && reviewSignal;
	}

	private boolean isAdmrulNoticeExceptionRepealNoise(String body, String documentTitle, String chunkHeading) {
		String title = normalize(documentTitle);
		String heading = normalize(chunkHeading);
		String text = normalize(body);
		boolean titleMatched = title.contains("면제예외인정에관한정책지침")
			|| (title.contains("면제예외") && title.contains("정책지침"));
		boolean repealSignal = text.contains("폐지") || heading.contains("부칙") || text.contains("부칙");
		boolean reviewSignal = containsAny(text, List.of("위험평가", "안전기준", "충분히검토", "검토하여야", "검토해야", "항공환경"));
		return titleMatched && repealSignal && !reviewSignal;
	}

	private static boolean isCctvInvestigationProvisionQuestion(String normalizedQuestion) {
		String question = normalize(normalizedQuestion);
		return !question.isBlank()
			&& (question.contains("cctv") || question.contains("영상정보") || question.contains("개인영상정보"))
			&& List.of("수사기관", "범죄수사", "공소제기", "수사").stream().anyMatch(question::contains)
			&& List.of("제공", "열람", "줄수", "가능").stream().anyMatch(question::contains);
	}

	private static boolean isCctvRetentionOrPurposeQuestion(String normalizedQuestion) {
		return normalizedQuestion != null
			&& (normalizedQuestion.contains("cctv") || normalizedQuestion.contains("영상정보처리기기") || normalizedQuestion.contains("영상정보"))
			&& (normalizedQuestion.contains("보관기간")
				|| normalizedQuestion.contains("30일")
				|| normalizedQuestion.contains("설치목적")
				|| normalizedQuestion.contains("촬영범위")
				|| normalizedQuestion.contains("목적범위"));
	}

	private static boolean isAiCommitteeFunctionQuestion(String normalizedQuestion) {
		return normalizedQuestion != null
			&& (normalizedQuestion.contains("인공지능위원회")
				|| normalizedQuestion.contains("국가인공지능전략위원회")
				|| normalizedQuestion.contains("ai위원회"))
			&& (normalizedQuestion.contains("심의")
				|| normalizedQuestion.contains("의결")
				|| normalizedQuestion.contains("역할")
				|| normalizedQuestion.contains("기능")
				|| normalizedQuestion.contains("어떤일")
				|| normalizedQuestion.contains("무슨일"));
	}

	private static boolean isCctvPublicPlaceExceptionEvidence(String text) {
		boolean publicPlaceCamera = text.contains("공개된장소")
			&& (text.contains("고정형영상정보처리기기") || text.contains("영상정보처리기기") || text.contains("cctv"));
		boolean prohibition = text.contains("원칙적으로금지")
			|| text.contains("원칙적으로설치운영금지")
			|| (text.contains("원칙적으로") && text.contains("금지"));
		boolean exceptionAllowed = text.contains("예외적으로설치")
			|| text.contains("예외적으로설치운영")
			|| (text.contains("예외적으로") && text.contains("허용"))
			|| (text.contains("예외") && text.contains("설치운영") && text.contains("허용"));
		boolean legalBasis = text.contains("법령에서구체적으로허용")
			|| text.contains("법령에서구체적으로")
			|| text.contains("법제25조")
			|| text.contains("제25조제1항");
		return publicPlaceCamera && prohibition && exceptionAllowed && legalBasis;
	}

	private static boolean isCctvInvestigationProvisionEvidence(String text) {
		String normalized = normalize(text);
		boolean cameraContext = List.of("cctv자료", "cctv영상", "개인영상정보", "영상정보처리기기").stream().anyMatch(normalized::contains);
		boolean investigationContext = List.of("경찰이나검찰", "수사기관", "범죄수사", "범죄의수사").stream().anyMatch(normalized::contains);
		boolean provisionContext = List.of("열람", "제공", "제3자제공", "동의없이제공").stream().anyMatch(normalized::contains);
		boolean prosecutionOrBasis = List.of("공소제기", "공소의제기", "공소제기유지", "법제18조제2항", "표준지침제40조").stream().anyMatch(normalized::contains);
		return cameraContext && investigationContext && provisionContext && prosecutionOrBasis;
	}

	private static boolean isCctvRetentionOrPurposeEvidence(String text) {
		boolean cameraContext = text.contains("고정형영상정보처리기기")
			|| text.contains("영상정보처리기기")
			|| text.contains("cctv");
		boolean purposeScope = text.contains("설치목적") && text.contains("촬영범위");
		boolean retention = text.contains("보관기간")
			&& (text.contains("30일이내") || text.contains("최소한의기간") || text.contains("목적달성"));
		return cameraContext && (purposeScope || retention);
	}

	private static boolean isPrivacyRetentionDestructionQuestion(String normalizedQuestion) {
		String question = normalize(normalizedQuestion);
		return question.contains("개인정보")
			&& (
				List.of("보유기간", "보존기간", "기간경과", "기간이지나", "기간이지난").stream().anyMatch(question::contains)
					|| (question.contains("처리목적") && question.contains("달성"))
					|| question.contains("목적달성")
			)
			&& List.of("파기", "어떻게", "해야").stream().anyMatch(question::contains);
	}

	private static boolean isPrivacyRetentionDestructionEvidence(String text) {
		String normalized = normalize(text);
		boolean privacyContext = List.of("개인정보보호법", "개인정보처리통합안내서", "개인정보처리자", "개인정보").stream().anyMatch(normalized::contains);
		boolean retentionExpired = List.of("보유기간의경과", "보유기간경과", "보존기간경과", "개인정보의처리목적달성", "처리목적달성").stream().anyMatch(normalized::contains);
		boolean destructionDuty = List.of("지체없이파기", "지체없이그개인정보를파기", "파기하여야", "파기해야").stream().anyMatch(normalized::contains);
		return privacyContext && retentionExpired && destructionDuty;
	}

	// 메소드 설명: scoreKey 처리 흐름을 수행합니다.
	private static String scoreKey(LawSemanticChunkRow chunk) {
		return chunk.target() + ":" + chunk.chunkId();
	}

	// 메소드 설명: queryTerms 처리 흐름을 수행합니다.
	private static List<String> queryTerms(String question) {
		List<String> terms = new ArrayList<>();
		for (String token : String.valueOf(question).split("\\s+")) {
			String term = normalizeQuestionTerm(token);
			if (term.length() >= 2 && !isWeakTerm(term)) {
				terms.add(term);
			}
		}
		String compact = normalizeQuestionTerm(question);
		if (!terms.isEmpty() && terms.size() <= 1 && compact.length() >= 4 && !terms.contains(compact)) {
			terms.add(compact);
		}
		return terms.stream().distinct().toList();
	}

	private static List<String> requiredExactTerms(String question) {
		List<String> terms = new ArrayList<>();
		for (String token : String.valueOf(question).split("[^A-Za-z0-9]+")) {
			if (isRequiredAcronymToken(token)) {
				String normalized = normalizeQuestionTerm(token);
				if (!normalized.isBlank()) {
					terms.add(normalized);
				}
			}
		}
		return terms.stream().distinct().toList();
	}

	private static List<List<String>> documentEvidenceAnchorDirectGroups(String question) {
		if (question == null || question.isBlank()) {
			return List.of();
		}
		List<String> anchors = documentEvidenceAnchorTerms(question);
		if (anchors.isEmpty()) {
			return List.of();
		}
		List<List<String>> groups = new ArrayList<>();
		for (String anchor : anchors) {
			String normalized = normalizeQuestionTerm(anchor);
			if (normalized.length() >= 2
				&& !isWeakTerm(normalized)
				&& !isEvidenceAnchorStopTerm(normalized)) {
				groups.add(List.of(normalized));
			}
		}
		return groups;
	}

	private static List<String> documentEvidenceAnchorTerms(String question) {
		LinkedHashSet<String> terms = new LinkedHashSet<>();
		for (String marker : List.of(
			"문서에서",
			"자료에서",
			"보고서에서",
			"매뉴얼에서",
			"메뉴얼에서",
			"가이드에서",
			"가이드북에서",
			"가이드라인에서",
			"안내서에서",
			"해설서에서",
			"백서에서",
			"보도자료에서",
			"법에서",
			"법령에서",
			"법률에서",
			"시행령에서",
			"시행규칙에서",
			"훈령에서",
			"규칙에서",
			"지침에서",
			"기준에서",
			"고시에서",
			"예규에서",
			"규정에서",
			"문서에서",
			"자료에서",
			"보고서에서",
			"사례집에서",
			"매뉴얼에서",
			"가이드에서",
			"가이드라인에서",
			"법에서",
			"특별법에서",
			"법률에서",
			"시행령에서",
			"시행규칙에서",
			"세칙에서",
			"시행세칙에서",
			"규칙에서",
			"지침에서",
			"예규에서",
			"규정에서",
			"훈령에서"
		)) {
			int markerIndex = question.indexOf(marker);
			if (markerIndex < 0) {
				continue;
			}
			int start = markerIndex + marker.length();
			if (start >= question.length()) {
				continue;
			}
			String evidence = cleanDocumentEvidenceAnchor(question.substring(start));
			for (String token : evidence.split("\\s+")) {
				String term = normalizeQuestionTerm(stripQuestionIntentSuffix(stripTrailingJosa(stripQuestionIntentSuffix(token))));
				if (term.length() >= 2
					&& !isWeakTerm(term)
					&& !isEvidenceAnchorStopTerm(term)) {
					terms.add(term);
				}
			}
		}
		return terms.stream()
			.distinct()
			.limit(10)
			.toList();
	}

	private static String cleanDocumentEvidenceAnchor(String value) {
		String cleaned = String.valueOf(value)
			.replaceAll("[?？!！.。,:;；]+", " ")
			.replaceAll("\\s+", " ")
			.trim();
		for (String stop : List.of(
			"관련 본문 근거",
			"직접 근거",
			"본문 근거",
			"관련 근거",
			"관련 조항",
			"관련 항목",
			"본문",
			"근거",
			"조항",
			"항목",
			"내용",
			"섹션",
			"찾아줘",
			"알려줘",
			"보여줘",
			"확인해줘",
			"뭐야",
			"무엇",
			"어떤"
		)) {
			cleaned = cleaned.replace(stop, " ");
		}
		return cleaned.replaceAll("\\s+", " ").trim();
	}

	private static boolean isEvidenceAnchorStopTerm(String term) {
		String normalized = normalize(term);
		return Set.of(
			"관련",
			"본문",
			"근거",
			"직접근거",
			"조항",
			"항목",
			"내용",
			"섹션",
			"찾아줘",
			"알려줘",
			"보여줘",
			"확인해줘",
			"무엇",
			"어떤",
			"가능",
			"해야"
		).contains(normalized);
	}

	private static boolean isRequiredAcronymToken(String token) {
		if (token == null || token.isBlank()) {
			return false;
		}
		String value = token.trim();
		if (value.length() < 3 || value.length() > 12) {
			return false;
		}
		if (!value.matches("(?=.*[A-Za-z].*[A-Za-z])[A-Za-z0-9]+")) {
			return false;
		}
		String normalized = normalizeQuestionTerm(value);
		if (Set.of(
			"ai", "api", "csv", "db", "doc", "docx", "hwp", "hwpx", "html",
			"http", "https", "json", "pdf", "ppt", "pptx", "rfp", "sql",
			"sw", "hw", "txt", "uri", "url", "xls", "xlsx", "xml"
		).contains(normalized)) {
			return false;
		}
		return value.length() <= 4 || value.equals(value.toUpperCase(java.util.Locale.ROOT));
	}

	private static String normalizeQuestionTerm(String term) {
		return KoreanQueryNormalizer.normalizeQueryTerm(term);
	}

	// 메소드 설명: isWeakTerm 처리 흐름을 수행합니다.
	private static boolean isWeakTerm(String term) {
		if (isTemporalQuestionTerm(term)) {
			return true;
		}
		return KoreanQueryNormalizer.isWeakQuestionTerm(term) || Set.of(
			"알려줘",
			"알수있어",
			"알수있나요",
			"어떻게",
			"어떤",
			"어디",
			"어디까지",
			"가능",
			"가능해",
			"가능한가",
			"가능하나요",
			"가능한지",
			"무엇",
			"뭐야",
			"이란",
			"정의",
			"질문",
			"유형",
			"새로운",
			"한걸",
			"하는게",
			"확인",
			"확인하는게",
			"확인해",
			"확인하나",
			"확인하나요",
			"확인하는지",
			"여부",
			"있나",
			"있나요",
			"되나요",
			"있어",
			"관련",
			"대한"
		).contains(term);
	}

	// 메소드 설명: stripQuestionIntentSuffix 처리 흐름을 수행합니다.
	private static String stripQuestionIntentSuffix(String term) {
		return KoreanQueryNormalizer.stripQuestionSuffix(term);
	}

	// 메소드 설명: stripTrailingJosa 처리 흐름을 수행합니다.
	private static String stripTrailingJosa(String term) {
		return KoreanQueryNormalizer.stripTrailingJosa(term);
	}

	// 메소드 설명: normalize 처리 흐름을 수행합니다.
	private static boolean isTemporalQuestionTerm(String term) {
		String normalized = normalize(term);
		return normalized.equals("언제")
			|| normalized.startsWith("언제")
			|| normalized.equals("시기")
			|| normalized.equals("일정")
			|| normalized.equals("기한")
			|| normalized.equals("기간")
			|| normalized.equals("마감")
			|| normalized.endsWith("까지");
	}

	private static String normalize(String value) {
		return KoreanQueryNormalizer.normalizeForMatch(HwpxTextCleaner.clean(String.valueOf(value == null ? "" : value)));
	}

	private static boolean isPenaltyConsequenceQuestion(EvidenceQuestionProfile profile) {
		if (profile == null) {
			return false;
		}
		String question = normalize(profile.normalizedQuestion());
		return profile.preferredSectionTypes().contains("penalty")
			|| containsPenaltyConsequenceCue(question);
	}

	private static boolean hasPenaltyConsequenceSignal(String body, String chunkHeading, String parentSectionTitle) {
		String text = normalize(String.join(" ", String.valueOf(body), String.valueOf(chunkHeading), String.valueOf(parentSectionTitle)));
		return containsPenaltyConsequenceCue(text)
			|| text.contains("보완요구")
			|| text.contains("예산조정")
			|| text.contains("검토결과반영")
			|| text.contains("반영되지않")
			|| text.contains("반영하지않")
			|| text.contains("시정명령")
			|| text.contains("입찰참가자격제한");
	}

	private static boolean containsPenaltyConsequenceCue(String normalizedText) {
		String text = normalize(normalizedText);
		return text.contains("불이익")
			|| text.contains("불리한조치")
			|| text.contains("제재")
			|| text.contains("처분")
			|| text.contains("처벌")
			|| text.contains("과태료")
			|| text.contains("벌칙")
			|| text.contains("감점")
			|| text.contains("책임")
			|| text.contains("위약")
			|| text.contains("보완")
			|| text.contains("조치")
			|| text.contains("미준수")
			|| text.contains("위반")
			|| text.contains("불이행")
			|| text.contains("준수하지");
	}

	private static boolean isPublicDataActivationQuestion(String normalizedQuestion) {
		String question = normalize(normalizedQuestion);
		return question.contains("공공데이터")
			&& (question.contains("활성화") || question.contains("활용") || question.contains("개방") || question.contains("방안"))
			&& !question.contains("공공데이터베이스")
			&& !question.contains("표준화");
	}

	private static boolean isPublicDataActivationPrivacyNoise(String body, String documentTitle, String chunkHeading) {
		String text = normalize(String.join(" ", String.valueOf(body), String.valueOf(documentTitle), String.valueOf(chunkHeading)));
		boolean privacyContext = text.contains("개인정보")
			|| text.contains("개인정보보호")
			|| text.contains("비공개정보")
			|| text.contains("목적외이용제한");
		boolean directActivationContext = text.contains("공공데이터이용활성화")
			|| text.contains("이용활성화를촉진")
			|| text.contains("기본목표와추진방향")
			|| text.contains("공공데이터활용지원센터");
		return privacyContext && !directActivationContext;
	}

	private static boolean isPublicDataStandardizationOnlyChunk(String body, String documentTitle, String chunkHeading) {
		String text = normalize(String.join(" ", String.valueOf(body), String.valueOf(documentTitle), String.valueOf(chunkHeading)));
		boolean standardizationContext = text.contains("공공데이터베이스")
			|| text.contains("데이터베이스")
			|| text.contains("표준용어")
			|| text.contains("표준코드")
			|| text.contains("표준화")
			|| text.contains("구축산출물")
			|| text.contains("산출물작성");
		boolean activationAnswerContext = text.contains("기본목표와추진방향")
			|| text.contains("활성화에필요한사업")
			|| text.contains("이용활성화지원사업")
			|| text.contains("지원하는사업")
			|| text.contains("품질진단컨설팅및품질개선");
		return standardizationContext && !activationAnswerContext;
	}

	private boolean isPublicDataQualityDiagnosisOverviewQuestion(String normalizedQuestion) {
		String question = normalize(normalizedQuestion);
		return question.contains("공공데이터")
			&& containsAny(question, List.of("품질관리", "품질진단", "예방적품질관리", "진단"))
			&& containsAny(question, List.of("몇개", "구성", "항목", "영역", "진단기준", "진단체계", "어떻게"));
	}

	private boolean isPublicDataQualityDiagnosisOverviewEvidence(String normalizedText) {
		String text = normalize(normalizedText);
		boolean context = text.contains("공공데이터베이스")
			&& containsAny(text, List.of("예방적품질관리", "품질관리진단", "진단영역", "진단항목", "진단체계"));
		boolean summary = containsAny(text, List.of(
			"4개영역",
			"4개의진단영역",
			"9개항목",
			"총9개",
			"18개진단기준",
			"총18개"
		)) || containsAll(text, List.of("데이터표준", "데이터구조", "데이터값", "데이터관리체계"));
		return context && summary;
	}

	private boolean isPublicDataQualityDiagnosisPreferredOverview(String normalizedText) {
		String text = normalize(normalizedText);
		return isPublicDataQualityDiagnosisOverviewEvidence(text)
			&& (
				containsAny(text, List.of(
					"314진단영역및항목",
					"진단영역및항목",
					"4개의진단영역은세부진단항목으로구성",
					"진단영역4진단항목9진단기준18"
				))
					|| containsAll(text, List.of("데이터표준", "데이터구조", "데이터값", "데이터관리체계"))
			)
			&& !text.contains("321예방적품질관리진단기준");
	}

	private boolean isPublicDataQualityDiagnosisDetailEvidence(String normalizedText) {
		String text = normalize(normalizedText);
		return text.contains("공공데이터베이스")
			&& text.contains("진단기준")
			&& containsAny(text, List.of("기준설명", "사업유형별검토", "컨설팅사업", "구축사업"))
			&& !isPublicDataQualityDiagnosisOverviewEvidence(text);
	}

	private boolean isPublicDataStandardTermQuestion(String normalizedQuestion) {
		String question = normalize(normalizedQuestion);
		return question.contains("공공데이터베이스")
			&& question.contains("표준용어")
			&& containsAny(question, List.of("왜", "필요", "관리", "목적", "일관성"));
	}

	private boolean isPublicDataStandardTermEvidence(String normalizedText) {
		String text = normalize(normalizedText);
		return text.contains("공공데이터베이스")
			&& text.contains("표준용어")
			&& containsAny(text, List.of(
				"일관된기준",
				"같은의미",
				"동일한의미",
				"데이터의용이한식별",
				"융합분석",
				"쉽게식별",
				"관리원칙",
				"용어표준화"
			));
	}

	private boolean isPublicDataStandardTermNoise(String normalizedText) {
		String text = normalize(normalizedText);
		return text.contains("개인정보")
			|| isPublicDataQualityDiagnosisDetailEvidence(text)
			|| (text.contains("진단기준") && text.contains("데이터값"));
	}

	private static boolean isProjectReviewScopeQuestion(String normalizedQuestion) {
		String normalized = normalize(normalizedQuestion);
		if (!normalized.contains("과업심의")) {
			return false;
		}
		return normalized.contains("대상")
			|| normalized.contains("비대상")
			|| normalized.contains("제외")
			|| normalized.contains("포함")
			|| normalized.contains("해당")
			|| normalized.contains("안해도")
			|| normalized.contains("받아야")
			|| normalized.contains("받아야해")
			|| normalized.contains("해야")
			|| normalized.contains("필요")
			|| normalized.contains("면제")
			|| normalized.contains("가능")
			|| normalized.contains("되나")
			|| normalized.contains("될까");
	}

	private static boolean isProjectReviewPreConsultationRelationQuestion(String normalizedQuestion) {
		String normalized = normalize(normalizedQuestion);
		return normalized.contains("과업심의") && normalized.contains("사전협의");
	}

	private static boolean isPreConsultationTargetQuestion(String normalizedQuestion) {
		String normalized = normalize(normalizedQuestion);
		return normalized.contains("사전협의") && normalized.contains("대상");
	}

	private static boolean isSecurityReviewTargetQuestion(String normalizedQuestion) {
		String normalized = normalize(normalizedQuestion);
		return (normalized.contains("보안성검토") || (normalized.contains("보안성") && normalized.contains("검토")))
			&& normalized.contains("대상");
	}

	private static boolean isSecurityReviewTargetAnswerChunk(String body, String documentTitle, String chunkHeading) {
		String text = documentTitle + chunkHeading + body;
		boolean securityReviewContext = text.contains("보안성검토")
			|| text.contains("정보화사업보안성검토")
			|| text.contains("국가정보원검토대상")
			|| text.contains("국가정보보안기본지침");
		boolean explicitTargetCue = text.contains("보안성검토대상")
			|| text.contains("보안성검토대상사업")
			|| text.contains("국가정보원검토대상")
			|| text.contains("국정원검토대상")
			|| text.contains("문체부검토대상")
			|| text.contains("검토대상정보화사업")
			|| text.contains("검토대상사업")
			|| text.contains("검토대상")
			|| text.contains("검토대상은")
			|| text.contains("대상사업및시기")
			|| text.contains("대상시스템");
		boolean concreteSystemScope = text.contains("정보통신망또는정보시스템구축")
			|| text.contains("정보시스템구축")
			|| text.contains("주요데이터베이스구축")
			|| text.contains("주요정보통신기반시설")
			|| text.contains("제어시스템")
			|| text.contains("민감정보")
			|| text.contains("고유식별정보")
			|| text.contains("대외비")
			|| text.contains("비밀");
		boolean adminEntryOnly = text.contains("발주정보등록")
			|| text.contains("입력한다")
			|| text.contains("화면에서")
			|| text.contains("신청서");
		return securityReviewContext && explicitTargetCue && concreteSystemScope && !adminEntryOnly;
	}

	private static boolean isProcurementCatalogContractContextChunk(String body, String documentTitle, String chunkHeading) {
		String text = documentTitle + chunkHeading + body;
		if (isProcurementCatalogNoiseChunk(body, documentTitle, chunkHeading)) {
			return false;
		}
		boolean catalogCue = text.contains("디지털서비스몰")
			|| text.contains("디지털서비스")
			|| text.contains("디지털카탈로그")
			|| text.contains("디지털카달로그")
			|| text.contains("종합쇼핑몰")
			|| text.contains("조달청종합쇼핑몰")
			|| (text.contains("나라장터")
				&& (
					text.contains("디지털서비스")
						|| text.contains("종합쇼핑몰")
						|| text.contains("카탈로그")
						|| text.contains("카달로그")
				));
		boolean purchaseCue = text.contains("상용sw직접구매")
			|| text.contains("상용소프트웨어직접구매")
			|| text.contains("직접구매")
			|| text.contains("구매계약")
			|| text.contains("계약및관리감독")
			|| text.contains("수의계약")
			|| text.contains("계약방법")
			|| text.contains("계약방식")
			|| text.contains("계약제도")
			|| text.contains("카탈로그계약");
		boolean softwareCue = text.contains("상용sw")
			|| text.contains("상용소프트웨어")
			|| text.contains("소프트웨어");
		return catalogCue && (purchaseCue || (softwareCue && text.contains("구매")));
	}

	private static boolean isCriticalConceptMissing(String normalizedQuestion, String normalizedText) {
		String question = normalize(normalizedQuestion);
		String text = normalize(normalizedText);
		if (question.contains("성과측정") && !text.contains("성과측정")) {
			return true;
		}
		return false;
	}

	private static boolean isPrivacyPurposePolicyQuestion(String normalizedQuestion) {
		String question = normalize(normalizedQuestion);
		return question.contains("개인정보")
			&& (question.contains("처리목적") || question.contains("처리방침"));
	}

	private static boolean isPrivacyPurposePolicyEvidence(String normalizedText) {
		String text = normalize(normalizedText);
		boolean policyCue = text.contains("개인정보처리방침")
			|| text.contains("개인정보의처리방침")
			|| text.contains("처리방침");
		boolean purposeCue = text.contains("개인정보의처리목적")
			|| text.contains("개인정보처리목적")
			|| text.contains("처리목적");
		boolean obligationOrDisclosureCue = text.contains("정하여야")
			|| text.contains("포함하여야")
			|| text.contains("작성할때")
			|| text.contains("공개하여야")
			|| text.contains("공개하고")
			|| text.contains("공개");
		boolean sourceNoticeNoise = text.contains("수집출처")
			|| text.contains("출처등통지")
			|| text.contains("처리정지");
		return policyCue && purposeCue && obligationOrDisclosureCue && !sourceNoticeNoise;
	}

	private static boolean isProcurementCatalogScopeChunk(String body, String chunkHeading) {
		String text = chunkHeading + body;
		return text.contains("적용대상")
			|| text.contains("직접구매대상")
			|| text.contains("대상사업")
			|| text.contains("1차조건")
			|| text.contains("2차조건")
			|| text.contains("saas포함")
			|| text.contains("등록소프트웨어");
	}

	private static boolean isProcurementContractMethodQuestion(String normalizedQuestion) {
		return normalizedQuestion.contains("수의계약")
			|| normalizedQuestion.contains("계약방식")
			|| normalizedQuestion.contains("계약방법")
			|| normalizedQuestion.contains("계약인가");
	}

	private static boolean isProcurementContractMethodChunk(String body, String chunkHeading) {
		String text = chunkHeading + body;
		return text.contains("수의계약")
			&& (
				text.contains("계약방식")
					|| text.contains("계약방법")
					|| text.contains("계약제도")
					|| text.contains("카탈로그계약")
			);
	}

	private static boolean isProcurementExclusionChunk(String body, String chunkHeading) {
		String text = chunkHeading + body;
		return text.contains("비대상")
			|| text.contains("제외")
			|| text.contains("제외사유");
	}

	private static boolean isProcurementCatalogNoiseChunk(String body, String documentTitle, String chunkHeading) {
		String text = documentTitle + chunkHeading + body;
		return text.contains("브로커")
			|| text.contains("불공정행위")
			|| text.contains("직제")
			|| text.contains("구매사업국")
			|| text.contains("기술서비스국");
	}

	private static boolean isPreConsultationContextChunk(String body, String documentTitle, String chunkHeading) {
		String text = documentTitle + chunkHeading + body;
		return documentTitle.contains("사전협의")
			|| chunkHeading.contains("사전협의")
			|| text.contains("전자정부사전협의")
			|| text.contains("정보화사업사전협의")
			|| text.contains("사전협의대상")
			|| text.contains("사전협의의대상")
			|| text.contains("사전협의신청")
			|| text.contains("사전협의절차");
	}

	private static boolean isPreConsultationGeneralScopeChunk(String body, String chunkHeading) {
		String text = chunkHeading + body;
		return text.contains("사전협의의대상사업")
			|| text.contains("사전협의대상사업은")
			|| text.contains("예산과목및계약방식과관계없이")
			|| text.contains("대상기관이추진하는모든정보화사업")
			|| text.contains("대상기관이추진하는정보화사업");
	}

	private static boolean isPreConsultationSpecificQaChunk(String body, String chunkHeading) {
		String text = chunkHeading + body;
		return text.contains("대상입니까")
			|| text.contains("대상인가요")
			|| text.contains("대상이아닙니다")
			|| text.contains("대상입니다")
			|| text.contains("문의드립니다");
	}

	private static boolean isProjectReviewScopeChunk(String body, String documentTitle, String chunkHeading) {
		String text = documentTitle + chunkHeading + body;
		String headingAndBody = chunkHeading + body;
		boolean explicitTargetScope = headingAndBody.contains("적용대상사업")
			|| headingAndBody.contains("대상사업국가기관등의장이발주하는소프트웨어사업")
			|| headingAndBody.contains("국가기관등이발주하는모든sw사업")
			|| headingAndBody.contains("국가기관등의장이발주하는소프트웨어사업")
			|| (headingAndBody.contains("대상사업") && (headingAndBody.contains("sw사업") || headingAndBody.contains("소프트웨어사업")));
		boolean conflictOfInterestContext = body.contains("이해관계")
			|| body.contains("위원제척")
			|| body.contains("기피절차")
			|| body.contains("위원의제척")
			|| body.contains("위원의기피");
		boolean softwareDefinitionScope = (body.contains("sw개발제작생산유통운영및유지")
			|| body.contains("소프트웨어와관련된서비스"))
			&& (headingAndBody.contains("적용대상")
				|| headingAndBody.contains("대상사업")
				|| body.contains("모든sw사업")
				|| body.contains("국가기관등의장이발주"));
		boolean targetScope = explicitTargetScope || softwareDefinitionScope;
		boolean exclusionScope = headingAndBody.contains("소프트웨어사업으로볼수없는경우는비대상")
			|| (headingAndBody.contains("소프트웨어사업으로볼수없는") && headingAndBody.contains("비대상"))
			|| (headingAndBody.contains("단순hw") && headingAndBody.contains("비대상"))
			|| (headingAndBody.contains("appliance") && headingAndBody.contains("비대상"));
		boolean projectReviewContext = documentTitle.contains("과업심의")
			|| chunkHeading.contains("과업심의")
			|| body.contains("과업심의대상")
			|| containsNearAny(
				body,
				"과업심의",
				List.of("적용대상", "대상사업", "비대상", "모든sw사업", "국가기관등의장이발주"),
				160
			)
			|| text.contains("공공sw사업")
			|| text.contains("공공소프트웨어사업")
			|| text.contains("소프트웨어사업");
		boolean projectReviewTitled = documentTitle.contains("과업심의") || chunkHeading.contains("과업심의");
		boolean lawComplianceChecklist = !projectReviewTitled
			&& (documentTitle.contains("법령준수")
				|| chunkHeading.contains("법령준수")
				|| chunkHeading.contains("체크리스트")
				|| body.contains("법령준수여부")
				|| body.contains("검토의견")
				|| body.contains("개선권고"));
		boolean otherSoftwareScopeDomain = !projectReviewTitled
			&& !body.contains("과업심의")
			&& (text.contains("sw영향평가")
				|| text.contains("소프트웨어영향평가")
				|| text.contains("정보화사업사전협의")
				|| text.contains("사전협의"));
		return projectReviewContext
			&& (targetScope || exclusionScope)
			&& !conflictOfInterestContext
			&& !lawComplianceChecklist
			&& !otherSoftwareScopeDomain;
	}

	private static boolean isProjectReviewAdjacentChunk(String body, String documentTitle, String chunkHeading) {
		String text = documentTitle + chunkHeading + body;
		boolean simplified = text.contains("간소화과업심의")
			|| text.contains("간소화방식")
			|| text.contains("간소화된방식")
			|| text.contains("간소화심의");
		boolean directPurchaseProcedure = text.contains("상용소프트웨어직접구매계약정보")
			|| text.contains("계약정보를등록")
			|| text.contains("직접구매계약정보")
			|| text.contains("switorkr")
			|| text.contains("wwwswitorkr");
		boolean operationOrMemberRule = text.contains("위원회의회의")
			|| text.contains("재적위원")
			|| text.contains("위원의제척")
			|| text.contains("위원의해촉")
			|| text.contains("대리인이었던")
			|| text.contains("회의를소집");
		boolean reviewItem = text.contains("과업내용의적정성")
			|| text.contains("비용산정의적정성")
			|| text.contains("적정사업기간의산정")
			|| text.contains("sw영향평가의재평가");
		return simplified || directPurchaseProcedure || operationOrMemberRule || reviewItem;
	}

	public record Result(
		List<LawSemanticChunkRow> chunks,
		Map<String, Double> scoreByChunkId,
		boolean directEvidenceRequired,
		boolean directEvidenceFound,
		boolean conceptEvidenceRequired,
		boolean conceptEvidenceFound,
		int topicAlignedCount,
		int relevantCount,
		int directEvidenceCount,
		String selectionPolicy
	) {
	}

	private record JudgedChunk(
		LawSemanticChunkRow chunk,
		double score,
		boolean topicAligned,
		boolean relevant,
		boolean directEvidence,
		int directEvidenceMatches,
		int bodyDirectEvidenceMatches
	) {
	}

	private static EvidenceQuestionProfile buildQuestionProfile(String question) {
		QuestionProfile legacy = QuestionProfile.from(question);
		return new EvidenceQuestionProfile(
			legacy.normalizedQuestion(),
			legacy.terms(),
			legacy.requiredTerms(),
			legacy.conceptGroups(),
			legacy.intentGroups(),
			legacy.directEvidenceGroups(),
			legacy.preferredSectionTypes(),
			legacy.definitionQuestion()
		);
	}

	private record QuestionProfile(
		String normalizedQuestion,
		List<String> terms,
		List<String> requiredTerms,
		List<List<String>> conceptGroups,
		List<List<String>> intentGroups,
		List<List<String>> directEvidenceGroups,
		Set<String> preferredSectionTypes,
		boolean definitionQuestion
	) {

		static QuestionProfile from(String question) {
			String normalized = normalize(question);
			QuestionIntentProfile extracted = QuestionIntentProfile.from(question);
			List<String> terms = extracted.terms().isEmpty() ? queryTerms(question) : extracted.terms();
			boolean definitionQuestion = isDefinitionQuestion(normalized) || isAiCommitteeFunctionQuestion(normalized);
			List<String> requiredTerms = requiredExactTerms(question);
			if (definitionQuestion && hasNonAcronymConceptTerm(terms, requiredTerms)) {
				requiredTerms = List.of();
			}
			List<List<String>> conceptGroups = new ArrayList<>();
			List<List<String>> intentGroups = new ArrayList<>();
			List<List<String>> directEvidenceGroups = new ArrayList<>();
			for (String requiredTerm : acronymOnlyConceptGroups(terms, requiredTerms)) {
				conceptGroups.add(List.of(requiredTerm));
			}
			for (List<String> group : extracted.conceptGroups()) {
				if (!isConceptGroupCovered(conceptGroups, group)) {
					conceptGroups.add(group);
				}
			}
			for (List<String> group : extracted.intentGroups()) {
				if (!isConceptGroupCovered(intentGroups, group)) {
					intentGroups.add(group);
				}
			}
			if (shouldUseExtractedDirectEvidenceGroups(extracted, normalized)) {
				for (List<String> group : extracted.directEvidenceGroups()) {
					if (!isDirectEvidenceGroupCovered(directEvidenceGroups, group)) {
						directEvidenceGroups.add(group);
					}
				}
			}
			for (List<String> group : documentEvidenceAnchorDirectGroups(question)) {
				if (!isDirectEvidenceGroupCovered(directEvidenceGroups, group)) {
					directEvidenceGroups.add(group);
				}
			}
			addSpecificConceptGroups(terms, conceptGroups);

			return new QuestionProfile(
				normalized,
				terms,
				requiredTerms,
				conceptGroups,
				intentGroups,
				directEvidenceGroups,
				extracted.preferredSectionTypes(),
				definitionQuestion
			);
		}

		private static boolean shouldUseExtractedDirectEvidenceGroups(QuestionIntentProfile extracted, String normalized) {
			Set<String> intentTypes = extracted.intentTypes();
			if (extracted.directEvidenceGroups().isEmpty()) {
				return false;
			}
			boolean hasEntityDirectEvidence = extracted.entities().stream()
				.anyMatch(entity -> !entity.directEvidenceGroups().isEmpty());
			if (hasEntityDirectEvidence) {
				return true;
			}
			if (!extracted.matchedPolicyIds().isEmpty()) {
				return true;
			}
			if (isAutonomyPreConsultationQuestion(normalized)
				|| isPublicDataCustomSupportQuestion(normalized)
				|| isPublicDataAiManagementQuestion(normalized)
				|| isKoreanLiteratureExportQuestion(normalized)
				|| isQuantumOecdQuestion(normalized)
				|| isTvingSmishingQuestion(normalized)
				|| isCctvPublicPlaceExceptionQuestion(normalized)
				|| isAiCommitteeFunctionQuestion(normalized)) {
				return true;
			}
			return intentTypes.contains("target_scope")
				|| intentTypes.contains("required_documents")
				|| intentTypes.contains("penalty")
				|| intentTypes.contains("privacy_notice")
				|| intentTypes.contains("pre_consultation_required")
				|| intentTypes.contains("security_review_required")
				|| (intentTypes.contains("exception_scope") && (
					normalized.contains("과업심의")
						|| normalized.contains("사전협의")
						|| normalized.contains("보안성검토")
						|| normalized.contains("소프트웨어사업")
				));
		}

		// 메소드 설명: isSecurityReviewQuestion 처리 흐름을 수행합니다.
		private static boolean isSecurityReviewQuestion(String normalized) {
			return normalized.contains("보안성검토")
				|| (normalized.contains("보안성") && normalized.contains("검토"));
		}

		private static boolean isPublicDataActivationQuestion(String normalized) {
			return normalized.contains("공공데이터")
				&& (normalized.contains("활성화") || normalized.contains("활용") || normalized.contains("개방"))
				&& !normalized.contains("공공데이터베이스");
		}

		private static boolean isTrafficCrosswalkStopQuestion(String normalized) {
			return (normalized.contains("횡단보도") || normalized.contains("보행자"))
				&& (normalized.contains("우회전") || normalized.contains("운전") || normalized.contains("차"))
				&& (containsStopLike(normalized) || normalized.contains("해야") || normalized.contains("하나") || normalized.contains("되나"));
		}

		private static boolean isDefinitionQuestion(String normalized) {
			return normalized.contains("정의")
				|| normalized.contains("무엇")
				|| normalized.contains("무슨")
				|| normalized.contains("뭐야")
				|| normalized.contains("뭔지")
				|| normalized.contains("뭔가")
				|| normalized.contains("이란");
		}

		private static List<String> acronymOnlyConceptGroups(List<String> terms, List<String> requiredTerms) {
			if (requiredTerms.isEmpty() || terms.isEmpty()) {
				return List.of();
			}
			Set<String> required = Set.copyOf(requiredTerms);
			boolean acronymOnly = terms.stream()
				.map(QuestionProfile::stripIntentSuffix)
				.allMatch(required::contains);
			return acronymOnly ? requiredTerms : List.of();
		}

		private static boolean hasNonAcronymConceptTerm(List<String> terms, List<String> requiredTerms) {
			if (terms == null || terms.isEmpty()) {
				return false;
			}
			Set<String> required = Set.copyOf(requiredTerms);
			return terms.stream()
				.map(QuestionProfile::stripIntentSuffix)
				.filter(term -> term.length() >= 3)
				.filter(term -> !required.contains(term))
				.filter(term -> !isShortLatinTerm(term))
				.filter(term -> !isIntentLikeTerm(term))
				.anyMatch(term -> !isWeakTerm(term));
		}

		private static boolean containsStopLike(String normalized) {
			return normalized.contains("멈추")
				|| normalized.contains("멈춰")
				|| normalized.contains("정지")
				|| normalized.contains("일시정지")
				|| normalized.contains("서야")
				|| normalized.contains("세워");
		}

		// 메소드 설명: addSpecificConceptGroups 처리 흐름을 수행합니다.
		private static boolean isTemporalQuestion(String normalized) {
			if (normalized.contains("어디까지")) {
				return false;
			}
			return normalized.contains("언제")
				|| normalized.contains("시기")
				|| normalized.contains("일정")
				|| normalized.contains("기한")
				|| normalized.contains("기간")
				|| normalized.contains("마감")
				|| normalized.contains("몇월")
				|| normalized.contains("몇일")
				|| normalized.contains("며칠")
				|| normalized.contains("까지");
		}

		private static boolean isScopeQuestion(String normalized) {
			return normalized.contains("어디까지")
				|| normalized.contains("범위")
				|| normalized.contains("한도")
				|| normalized.contains("어느정도")
				|| normalized.contains("얼마나")
				|| normalized.contains("가능");
		}

		private static void addSpecificConceptGroups(List<String> terms, List<List<String>> conceptGroups) {
			for (String candidateTerm : terms) {
				String term = stripIntentSuffix(candidateTerm);
				if (term.length() < 3 || isShortLatinTerm(term) || isIntentLikeTerm(term)) {
					continue;
				}
				for (List<String> group : KoreanQueryNormalizer.conceptGroupsForTerm(term)) {
					if (!isConceptGroupCovered(conceptGroups, group)) {
						conceptGroups.add(group);
					}
				}
			}
		}

		private static boolean isConceptGroupCovered(List<List<String>> conceptGroups, List<String> group) {
			return group.stream()
				.map(EvidenceJudge::normalize)
				.anyMatch(term -> conceptGroups.stream()
					.flatMap(List::stream)
					.map(EvidenceJudge::normalize)
					.anyMatch(value -> value.equals(term)));
		}

		private static boolean isDirectEvidenceGroupCovered(List<List<String>> directEvidenceGroups, List<String> group) {
			List<String> candidateTerms = group.stream()
				.map(EvidenceJudge::normalize)
				.filter(term -> !term.isBlank())
				.distinct()
				.toList();
			if (candidateTerms.isEmpty()) {
				return true;
			}
			return directEvidenceGroups.stream()
				.map(existing -> existing.stream()
					.map(EvidenceJudge::normalize)
					.filter(term -> !term.isBlank())
					.distinct()
					.toList())
				.anyMatch(existingTerms -> existingTerms.containsAll(candidateTerms));
		}

		// 메소드 설명: stripIntentSuffix 처리 흐름을 수행합니다.
		private static String stripIntentSuffix(String term) {
			return KoreanQueryNormalizer.stripIntentSuffix(term);
		}

		// 메소드 설명: isShortLatinTerm 처리 흐름을 수행합니다.
		private static boolean isShortLatinTerm(String term) {
			return term.matches("[a-z0-9]+") && term.length() <= 3;
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
				"제출서류",
				"대상",
				"포함",
				"해당",
				"필수",
				"요소",
				"항목",
				"절차",
				"방법",
				"서류",
				"기한",
				"기간",
				"범위",
				"한도",
				"어디",
				"어디까지",
				"가능",
				"가능해",
				"가능한가",
				"가능하나요",
				"가능한지",
				"금액",
				"비용",
				"정의",
				"이란"
			).contains(term);
		}
	}
}
