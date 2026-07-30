package com.kaces.pandora.common.text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record QuestionIntentProfile(
	String normalizedQuestion,
	List<String> terms,
	List<String> lexicalKeywords,
	List<String> focusedKeywords,
	Set<String> intentTypes,
	List<QuestionEntity> entities,
	List<List<String>> synonymGroups,
	List<String> preferredTargets,
	List<List<String>> conceptGroups,
	List<List<String>> intentGroups,
	List<List<String>> directEvidenceGroups,
	Set<String> preferredSectionTypes,
	boolean focusedLexicalSearch,
	List<String> policySearchKeywords,
	Set<String> matchedPolicyIds
) {
	private static final List<String> TARGET_SCOPE_CUES = List.of(
		"대상", "대상사업", "대상기관", "대상시스템", "적용대상", "범위", "포함", "해당", "비대상", "제외",
		"받아야", "받아야해", "받아야하나", "필요", "해야"
	);
	private static final List<String> REQUIREMENT_CUES = List.of(
		"필수", "필수요소", "필수항목", "항목", "요소", "기재", "작성", "명시", "제출서류", "서류", "요구사항", "평가요소", "평가방법"
	);
	private static final List<String> PROCEDURE_CUES = List.of(
		"절차", "방법", "신청", "제출", "등록", "조회", "처리", "통보", "언제", "시기", "기한", "기간"
	);
	private static final List<String> EXPLICIT_PROCEDURE_CUES = List.of(
		"절차", "방법", "어떻게", "언제", "시기", "기한", "기간"
	);
	private static final List<String> CONTRACT_CUES = List.of(
		"수의계약", "계약방법", "계약방식", "계약인가", "계약가능", "구매계약", "카탈로그계약"
	);
	private static final List<String> DOCUMENT_NOUN_CUES = List.of(
		"문서", "매뉴얼", "가이드", "가이드라인", "안내서", "해설서", "보고서", "보도자료", "사례집", "백서"
	);
	private static final List<String> DOCUMENT_PURPOSE_CONTEXT_CUES = List.of(
		"목적", "취지", "발간 이유", "제작 이유", "만든 이유", "마련 이유", "왜 만든", "왜 발간", "왜 제작"
	);
	private static final List<String> DOCUMENT_IDENTITY_CUES = List.of(
		"어떤문서야", "무슨문서야", "어느문서야",
		"어떤문서인지", "무슨문서인지", "어느문서인지",
		"어떤문서인가", "무슨문서인가", "어느문서인가",
		"뭐야", "뭐지", "뭔지", "무엇인지", "무엇인가"
	);
	private static final Set<String> DOCUMENT_IDENTITY_RESPONSE_SUFFIXES = Set.of(
		"", "알려줘", "알려주세요", "설명해줘", "설명해주세요", "말해줘", "말해주세요", "답해줘", "답해주세요"
	);
	private static final Set<String> DOCUMENT_LOOKUP_RESPONSE_SUFFIXES = Set.of(
		"찾아줘", "찾아주세요", "찾아", "찾아줄래", "찾아줄래요"
	);
	private static final List<String> DOCUMENT_DISCOVERY_NOUN_CUES = List.of(
		"가이드라인", "시행규칙", "행정규칙", "시행령",
		"법령", "법률", "조례", "규정",
		"가이드", "안내서", "해설서", "매뉴얼", "자료", "문서"
	);
	private static final Set<String> DOCUMENT_DISCOVERY_RESPONSE_SUFFIXES = Set.of(
		"",
		"찾아", "찾아줘", "찾아주세요", "찾아줄래", "찾아줄래요",
		"알려줘", "알려주세요", "보여줘", "보여주세요"
	);
	private static final List<String> DOCUMENT_DISCOVERY_TOPIC_SUFFIXES = List.of(
		"관련", "관한", "대한", "적용되는", "적용할", "참고할"
	);

	public static QuestionIntentProfile from(String question) {
		String normalized = KoreanQueryNormalizer.normalizeForMatch(question);
		LinkedHashSet<String> matchedPolicyIds = new LinkedHashSet<>(
			QuestionIntentDictionary.matchedPolicyIds(question, normalized)
		);
		LinkedHashSet<String> suppressedIntentIds = new LinkedHashSet<>();
		matchedPolicyIds.forEach(policyId -> suppressedIntentIds.addAll(
			QuestionIntentDictionary.values("policy." + policyId + ".suppress_intents", List.of())
		));
		boolean documentIdentityQuestion = isDocumentIdentityQuestion(normalized);
		boolean documentDiscoveryQuestion = !documentIdentityQuestion
			&& isDocumentDiscoveryQuestion(normalized);
		boolean documentPurposeQuestion = matchedPolicyIds.contains("document_purpose");
		boolean documentPurposeContextQuestion = documentPurposeQuestion
			|| (containsAny(normalized, DOCUMENT_NOUN_CUES)
				&& containsAny(normalized, DOCUMENT_PURPOSE_CONTEXT_CUES));
		boolean suppressProcedureIntent = documentIdentityQuestion
			|| documentDiscoveryQuestion
			|| (documentPurposeContextQuestion && !containsAny(normalized, EXPLICIT_PROCEDURE_CUES));
		List<String> terms = queryTerms(question);
		LinkedHashSet<String> lexical = new LinkedHashSet<>();
		LinkedHashSet<String> focused = new LinkedHashSet<>();
		LinkedHashSet<String> intentTypes = new LinkedHashSet<>();
		LinkedHashSet<String> preferredTargets = new LinkedHashSet<>();
		List<List<String>> conceptGroups = new ArrayList<>();
		List<List<String>> intentGroups = new ArrayList<>();
		List<List<String>> directEvidenceGroups = new ArrayList<>();
		LinkedHashSet<String> sectionTypes = new LinkedHashSet<>();
		LinkedHashSet<String> policySearchKeywords = new LinkedHashSet<>();
		List<QuestionEntity> entities = QuestionIntentDictionary.matchedEntities(normalized);
		List<List<String>> synonymGroups = QuestionIntentDictionary.matchedSynonymGroups(question);

		for (String term : terms) {
			lexical.add(term);
			lexical.addAll(KoreanQueryNormalizer.expandSearchKeywords(term));
		}
		addEntities(entities, conceptGroups, lexical, focused, sectionTypes, preferredTargets, directEvidenceGroups);
		addSynonymGroups(synonymGroups, conceptGroups, lexical);
		addCommonIntent(
			normalized,
			suppressProcedureIntent,
			suppressedIntentIds,
			intentTypes,
			intentGroups,
			directEvidenceGroups,
			sectionTypes
		);
		addConfiguredIntents(
			question,
			normalized,
			suppressProcedureIntent,
			suppressedIntentIds,
			intentTypes,
			intentGroups,
			directEvidenceGroups,
			sectionTypes
		);
		addConfiguredPolicies(
			matchedPolicyIds,
			intentTypes,
			conceptGroups,
			directEvidenceGroups,
			lexical,
			focused,
			policySearchKeywords,
			sectionTypes,
			preferredTargets
		);
		addDomainConcepts(
			normalized,
			conceptGroups,
			intentGroups,
			directEvidenceGroups,
			lexical,
			focused,
			sectionTypes,
			suppressedIntentIds
		);
		if (suppressProcedureIntent) {
			intentTypes.remove("procedure");
			sectionTypes.remove("procedure");
		}
		if (documentDiscoveryQuestion) {
			intentTypes.clear();
			intentTypes.add("document_discovery");
			intentGroups.clear();
			directEvidenceGroups.clear();
			sectionTypes.clear();
		}

		boolean focusedSearch = !documentDiscoveryQuestion && (!focused.isEmpty()
			|| !directEvidenceGroups.isEmpty()
			|| sectionTypes.contains("target_scope")
			|| sectionTypes.contains("requirement"));
		return new QuestionIntentProfile(
			normalized,
			terms,
			lexical.stream().filter(value -> value.length() >= 2).distinct().limit(24).toList(),
			focused.stream().filter(value -> value.length() >= 2).distinct().limit(10).toList(),
			Set.copyOf(intentTypes),
			entities,
			distinctGroups(synonymGroups),
			preferredTargets.stream().distinct().toList(),
			distinctGroups(conceptGroups),
			distinctGroups(intentGroups),
			distinctGroups(directEvidenceGroups),
			Set.copyOf(sectionTypes),
			focusedSearch,
			policySearchKeywords.stream().filter(value -> value.length() >= 2).distinct().limit(24).toList(),
			Set.copyOf(matchedPolicyIds)
		);
	}

	public boolean prefersSection(String sectionType) {
		return sectionType != null && preferredSectionTypes.contains(sectionType);
	}

	/**
	 * Returns only the entity anchors explicitly configured for final-evidence
	 * validation. Broad aliases remain available for recall, while these anchors
	 * prevent a similarly worded but different domain from becoming a direct answer.
	 */
	public List<List<String>> configuredEntityAnchorGroups() {
		return distinctGroups(entities.stream()
			.map(QuestionEntity::answerAnchors)
			.filter(anchors -> anchors != null && !anchors.isEmpty())
			.toList());
	}

	/**
	 * Preserves the dictionary provenance needed by final-answer validation.
	 * Retrieval uses the flattened groups above; answer alignment must evaluate
	 * each requested intent independently so one broad group cannot hide another.
	 */
	public List<ConfiguredIntent> configuredIntents() {
		return intentTypes.stream()
			.sorted()
			.map(intentId -> new ConfiguredIntent(
				intentId,
				QuestionIntentDictionary.values("intent." + intentId + ".terms", List.of()),
				QuestionIntentDictionary.groups("intent." + intentId + ".direct", List.of())
			))
			.filter(intent -> !intent.terms().isEmpty() || !intent.directEvidenceGroups().isEmpty())
			.toList();
	}

	/**
	 * Returns policy-configured proposition groups that a final answer must cover
	 * collectively. This is intentionally separate from direct-evidence groups:
	 * retrieval may satisfy a broad alternative group with one chunk, while a
	 * procedural answer can require several grounded stages across multiple chunks.
	 */
	public List<List<String>> configuredAnswerCoverageGroups() {
		if (documentDiscoveryQuestion()) {
			return List.of();
		}
		return distinctGroups(matchedPolicyIds.stream()
			.flatMap(policyId -> QuestionIntentDictionary.groups(
				"policy." + policyId + ".answer_required",
				List.of()
			).stream())
			.toList());
	}

	public record ConfiguredIntent(
		String id,
		List<String> terms,
		List<List<String>> directEvidenceGroups
	) {
	}

	private static void addCommonIntent(
		String normalized,
		boolean suppressProcedureIntent,
		Set<String> suppressedIntentIds,
		Set<String> intentTypes,
		List<List<String>> intentGroups,
		List<List<String>> directEvidenceGroups,
		Set<String> sectionTypes
	) {
		if (!suppressedIntentIds.contains("target_scope") && containsAny(normalized, TARGET_SCOPE_CUES)) {
			intentTypes.add("target_scope");
			sectionTypes.add("target_scope");
			intentGroups.add(List.of("대상", "대상사업", "대상기관", "대상시스템", "적용대상", "범위", "포함", "해당", "비대상", "제외"));
		}
		if (!suppressedIntentIds.contains("required_documents") && containsAny(normalized, REQUIREMENT_CUES)) {
			intentTypes.add("required_documents");
			sectionTypes.add("requirement");
			intentGroups.add(List.of("필수", "필수항목", "기재사항", "명시하여야", "요구사항", "제출서류", "평가요소", "평가방법"));
		}
		if (!suppressProcedureIntent
			&& !suppressedIntentIds.contains("procedure")
			&& containsAny(normalized, PROCEDURE_CUES)) {
			intentTypes.add("procedure");
			sectionTypes.add("procedure");
			intentGroups.add(List.of("절차", "방법", "신청", "제출", "등록", "조회", "처리", "통보", "시기", "기간"));
		}
		if (!suppressedIntentIds.contains("exception_scope")
			&& containsAny(normalized, List.of("제외", "비대상", "면제", "생략"))) {
			intentTypes.add("exception_scope");
			sectionTypes.add("exception");
			intentGroups.add(List.of("제외", "비대상", "면제", "생략", "예외"));
		}
		if (!suppressedIntentIds.contains("contract_method") && containsAny(normalized, CONTRACT_CUES)) {
			intentTypes.add("contract_method");
			sectionTypes.add("procedure");
			intentGroups.add(List.of("수의계약", "계약방법", "계약방식", "구매계약", "카탈로그계약"));
		}
	}

	private static void addConfiguredIntents(
		String question,
		String normalized,
		boolean suppressProcedureIntent,
		Set<String> suppressedIntentIds,
		Set<String> intentTypes,
		List<List<String>> intentGroups,
		List<List<String>> directEvidenceGroups,
		Set<String> sectionTypes
	) {
		for (String intentId : QuestionIntentDictionary.keys("intents")) {
			if (suppressedIntentIds.contains(intentId)
				|| (suppressProcedureIntent && "procedure".equals(intentId))) {
				continue;
			}
			List<String> cues = QuestionIntentDictionary.values("intent." + intentId + ".cues", List.of());
			if (!QuestionIntentDictionary.matchesAny(question, normalized, cues)) {
				continue;
			}
			intentTypes.add(intentId);
			sectionTypes.addAll(QuestionIntentDictionary.values("intent." + intentId + ".section_types", List.of()));
			List<String> terms = QuestionIntentDictionary.values("intent." + intentId + ".terms", cues);
			if (!terms.isEmpty()) {
				intentGroups.add(terms);
			}
			directEvidenceGroups.addAll(QuestionIntentDictionary.groups("intent." + intentId + ".direct", List.of()));
		}
	}

	private static boolean isDocumentIdentityQuestion(String normalized) {
		if (!containsAny(normalized, DOCUMENT_NOUN_CUES)) {
			return false;
		}
		for (String cue : DOCUMENT_IDENTITY_CUES) {
			int cueIndex = normalized.lastIndexOf(cue);
			if (cueIndex < 0) {
				continue;
			}
			String trailingText = normalized.substring(cueIndex + cue.length());
			if (DOCUMENT_IDENTITY_RESPONSE_SUFFIXES.contains(trailingText)) {
				return true;
			}
		}
		for (String documentNoun : DOCUMENT_NOUN_CUES) {
			String normalizedNoun = KoreanQueryNormalizer.normalizeForMatch(documentNoun);
			int nounIndex = normalized.lastIndexOf(normalizedNoun);
			if (nounIndex < 0) {
				continue;
			}
			String trailingText = normalized.substring(nounIndex + normalizedNoun.length());
			if (DOCUMENT_LOOKUP_RESPONSE_SUFFIXES.contains(trailingText)) {
				return true;
			}
		}
		return false;
	}

	public boolean documentIdentityQuestion() {
		return isDocumentIdentityQuestion(normalizedQuestion);
	}

	public boolean documentDiscoveryQuestion() {
		return !documentIdentityQuestion() && isDocumentDiscoveryQuestion(normalizedQuestion);
	}

	private static boolean isDocumentDiscoveryQuestion(String normalized) {
		if (normalized == null || normalized.isBlank()) {
			return false;
		}
		for (String noun : DOCUMENT_DISCOVERY_NOUN_CUES) {
			String normalizedNoun = KoreanQueryNormalizer.normalizeForMatch(noun);
			int nounIndex = normalized.lastIndexOf(normalizedNoun);
			if (nounIndex < 0) {
				continue;
			}
			String trailingText = normalized.substring(nounIndex + normalizedNoun.length());
			if (!DOCUMENT_DISCOVERY_RESPONSE_SUFFIXES.contains(trailingText)) {
				continue;
			}
			String topic = normalized.substring(0, nounIndex);
			for (String topicSuffix : DOCUMENT_DISCOVERY_TOPIC_SUFFIXES) {
				String normalizedSuffix = KoreanQueryNormalizer.normalizeForMatch(topicSuffix);
				if (topic.endsWith(normalizedSuffix)) {
					topic = topic.substring(0, topic.length() - normalizedSuffix.length());
					break;
				}
			}
			if (topic.length() >= 2) {
				return true;
			}
		}
		return false;
	}

	private static void addConfiguredPolicies(
		Set<String> matchedPolicyIds,
		Set<String> intentTypes,
		List<List<String>> conceptGroups,
		List<List<String>> directEvidenceGroups,
		Set<String> lexical,
		Set<String> focused,
		Set<String> policySearchKeywords,
		Set<String> sectionTypes,
		Set<String> preferredTargets
	) {
		for (String policyId : matchedPolicyIds) {
			policySearchKeywords.addAll(QuestionIntentDictionary.values(
				"policy." + policyId + ".search",
				List.of()
			));
		}
		for (String policyId : matchedPolicyIds) {
			intentTypes.addAll(QuestionIntentDictionary.values("policy." + policyId + ".intent_types", List.of()));
			List<List<String>> configuredConcepts = QuestionIntentDictionary.groups(
				"policy." + policyId + ".concepts",
				List.of()
			);
			conceptGroups.addAll(configuredConcepts);
			configuredConcepts.stream().flatMap(List::stream).forEach(value -> {
				lexical.add(value);
				policySearchKeywords.add(value);
			});
			List<List<String>> configuredDirectEvidence = QuestionIntentDictionary.groups(
				"policy." + policyId + ".direct",
				List.of()
			);
			directEvidenceGroups.addAll(configuredDirectEvidence);
			configuredDirectEvidence.stream().flatMap(List::stream).forEach(policySearchKeywords::add);
			List<String> configuredFocused = QuestionIntentDictionary.values(
				"policy." + policyId + ".focused",
				List.of()
			);
			focused.addAll(configuredFocused);
			policySearchKeywords.addAll(configuredFocused);
			sectionTypes.addAll(QuestionIntentDictionary.values("policy." + policyId + ".section_types", List.of()));
			preferredTargets.addAll(QuestionIntentDictionary.values("policy." + policyId + ".targets", List.of()));
		}
	}

	private static void addEntities(
		List<QuestionEntity> entities,
		List<List<String>> conceptGroups,
		Set<String> lexical,
		Set<String> focused,
		Set<String> sectionTypes,
		Set<String> preferredTargets,
		List<List<String>> directEvidenceGroups
	) {
		for (QuestionEntity entity : entities) {
			if (!entity.aliases().isEmpty()) {
				conceptGroups.add(entity.aliases());
				lexical.addAll(entity.aliases());
			}
			focused.addAll(entity.focusedKeywords());
			sectionTypes.addAll(entity.sectionTypes());
			preferredTargets.addAll(entity.preferredTargets());
			directEvidenceGroups.addAll(entity.directEvidenceGroups());
		}
	}

	private static void addSynonymGroups(
		List<List<String>> synonymGroups,
		List<List<String>> conceptGroups,
		Set<String> lexical
	) {
		for (List<String> group : synonymGroups) {
			conceptGroups.add(group);
			lexical.addAll(group);
		}
	}

	private static void addDomainConcepts(
		String normalized,
		List<List<String>> conceptGroups,
		List<List<String>> intentGroups,
		List<List<String>> directEvidenceGroups,
		Set<String> lexical,
		Set<String> focused,
		Set<String> sectionTypes,
		Set<String> suppressedIntentIds
	) {
		if (normalized.contains("과업심의")) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"project_review.concepts",
				List.of("과업심의", "과업내용", "과업범위", "공공소프트웨어사업", "소프트웨어사업", "sw사업")
			));
			if (sectionTypes.contains("target_scope")) {
				focused.addAll(QuestionIntentDictionary.values(
					"project_review.focused_target",
					List.of("적용 대상 사업", "국가기관등이 발주하는 모든 SW사업", "소프트웨어와 관련된 서비스", "비대상")
				));
				directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
					"project_review.direct_target",
					List.of(
						List.of("적용 대상 사업", "적용대상사업", "대상사업", "과업심의 대상", "과업심의대상"),
						List.of("국가기관등이 발주하는 모든 SW사업", "국가기관등의 장이 발주하는 소프트웨어사업", "소프트웨어와 관련된 서비스"),
						List.of(
							"단순 H/W",
							"단순HW",
							"H/W",
							"HW",
							"Appliance",
							"하드웨어",
							"단순 하드웨어",
							"소프트웨어사업으로 볼 수 없는 경우는 비대상",
							"소프트웨어사업으로 볼 수 없는",
							"비대상"
						)
					)
				));
			}
		}
		if (normalized.contains("사전협의")) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"pre_consultation.concepts",
				List.of("사전협의", "전자정부사전협의", "정보화사업사전협의")
			));
			if (sectionTypes.contains("target_scope")) {
				focused.addAll(QuestionIntentDictionary.values(
					"pre_consultation.focused_target",
					List.of("사전협의의 대상사업", "대상기관이 추진하는 모든 정보화사업", "예산과목 및 계약방식과 관계없이")
				));
				directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
					"pre_consultation.direct_target",
					List.of(
						List.of("사전협의의 대상사업", "사전협의 대상사업", "예산과목 및 계약방식과 관계없이"),
						List.of("대상기관이 추진하는 모든 정보화사업", "중앙공공기관", "공공기관")
					)
				));
			}
		}
		if (isAutonomyPreConsultationQuestion(normalized)) {
			boolean procedureQuestion = normalized.contains("절차")
				|| normalized.contains("방법")
				|| normalized.contains("어떻게");
			conceptGroups.add(QuestionIntentDictionary.values(
				"autonomy_pre_consultation.concepts",
				List.of("자치분권 사전협의", "자치분권사전협의", "자치분권")
			));
			if (procedureQuestion) {
				sectionTypes.add("procedure");
			}
			if (!procedureQuestion || normalized.contains("대상") || normalized.contains("기관") || normalized.contains("같은")) {
				sectionTypes.add("target_scope");
			}
			focused.addAll(QuestionIntentDictionary.values(
				"autonomy_pre_consultation.focused_target",
				List.of("자치분권 사전협의 지침", "대상기관", "법령 제·개정 권한", "중앙행정기관")
			));
			if (procedureQuestion) {
				focused.addAll(QuestionIntentDictionary.values(
					"autonomy_pre_consultation.focused_procedure",
					List.of(
						"협의절차 및 내용",
						"협의절차 전체 흐름도",
						"사전협의 요청서 작성·제출",
						"지방자치 관련성 검토",
						"협의 결과서 통보"
					)
				));
				directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
					"autonomy_pre_consultation.direct_procedure",
					List.of(
						List.of("협의절차", "협의절차 및 내용", "전체 흐름도"),
						List.of("사전협의 요청서 작성", "지방자치 관련성 검토"),
						List.of("법령안 검토", "검토의견", "협의 결과서 통보")
					)
				));
			}
			directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
				"autonomy_pre_consultation.direct_target",
				List.of(
					List.of("자치분권 사전협의", "자치분권사전협의"),
					List.of("대상기관", "법령 제·개정 권한", "중앙행정기관"),
					List.of("자치분권 사전협의 요청", "조문별 제·개정이유서")
				)
			));
		}
		if (isEgovPreliminaryReviewQuestion(normalized)) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"egov_preliminary_review.concepts",
				List.of("예비검토", "지능정보사회 실행계획", "전자정부 성과관리 지침", "정보화사업", "성과관리 대상사업")
			));
			if (sectionTypes.contains("target_scope")) {
				focused.addAll(QuestionIntentDictionary.values(
					"egov_preliminary_review.focused_target",
					List.of("예비검토 신청", "다음 해에 정보화사업을 추진", "중앙행정기관의 장", "시도지사", "시도 교육감", "공공애플리케이션")
				));
				directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
					"egov_preliminary_review.direct_target",
					List.of(
						List.of("예비검토", "예비검토를 신청", "예비검토 신청"),
						List.of("정보화사업", "성과관리 대상사업", "공공애플리케이션"),
						List.of("중앙행정기관의 장", "시도지사", "시도 교육감", "시장군수", "자치구의 구청장")
					)
				));
			}
		}
		if (normalized.contains("보안성검토")) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"security_review.concepts",
				List.of("보안성검토", "정보화사업보안성검토", "국가정보보안기본지침")
			));
			if (sectionTypes.contains("target_scope")) {
				focused.addAll(QuestionIntentDictionary.values(
					"security_review.focused_target",
					List.of("보안성 검토 대상", "대상 사업 및 시기", "정보통신망 또는 정보시스템 구축")
				));
				directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
					"security_review.direct_target",
					List.of(
						List.of("보안성검토대상", "대상사업및시기", "국가정보원검토대상"),
						List.of("정보통신망또는정보시스템구축", "주요데이터베이스구축", "주요정보통신기반시설")
					)
				));
			}
		}
		if (normalized.contains("제안요청서") || normalized.contains("rfp")) {
			conceptGroups.add(QuestionIntentDictionary.values("rfp.concepts", List.of("제안요청서", "rfp")));
			if (sectionTypes.contains("requirement")) {
				focused.addAll(QuestionIntentDictionary.values(
					"rfp.focused_requirement",
					List.of("제안요청서에는 다음 각 호의 사항", "과업내용, 요구사항", "평가요소, 평가방법")
				));
				directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
					"rfp.direct_requirement",
					List.of(
						List.of("제안요청서에는 다음 각 호의 사항", "제안요청서 기재사항", "제안요청서에는"),
						List.of("제안요청서에는 과업내용", "과업내용, 요구사항", "평가요소, 평가방법")
					)
				));
			}
		}
		if (normalized.contains("공공데이터")) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"public_data.concepts",
				List.of("공공데이터", "공공데이터의제공및이용활성화", "공공데이터이용활성화")
			));
			if (normalized.contains("활성화") || normalized.contains("방안")) {
				conceptGroups.add(QuestionIntentDictionary.values(
					"public_data.activation_concepts",
					List.of("활성화", "이용활성화", "개방", "활용")
				));
				focused.addAll(QuestionIntentDictionary.values(
					"public_data.focused_activation",
					List.of("공공데이터 이용 활성화", "활성화에 필요한 사업", "기본목표와 추진방향")
				));
				directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
					"public_data.direct_activation",
					List.of(
						List.of("공공데이터 이용 활성화", "공공데이터 제공 및 이용 활성화", "이용 활성화 지원사업"),
						List.of("활성화에 필요한 사업", "기본목표와 추진방향", "개방전략 수립", "지원하는 사업")
					)
				));
			}
		}
		if (isPublicDataCustomSupportQuestion(normalized)) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"public_data.custom_support_concepts",
				List.of("공공데이터 활용기업 맞춤형지원", "공공데이터 활용기업", "맞춤형 지원")
			));
			sectionTypes.add("target_scope");
			focused.addAll(QuestionIntentDictionary.values(
				"public_data.custom_support_focused",
				List.of("공공데이터 활용기업 맞춤형지원 활용사례", "공공데이터 활용역량 및 수요 분석", "공공데이터 제공", "데이터 검색", "추천")
			));
			directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
				"public_data.custom_support_direct",
				List.of(
					List.of("공공데이터 활용역량", "수요 분석"),
					List.of("공공데이터 제공", "기업이 필요한 공공데이터 제공"),
					List.of("데이터 검색", "추천")
				)
			));
		}
		if (isPublicDataAiManagementQuestion(normalized)) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"public_data.ai_management_concepts",
				List.of("공공데이터 인공지능 친화적 관리", "인공지능 친화적 관리", "AI 학습용 데이터", "학습 데이터", "참조 데이터")
			));
			sectionTypes.add("requirement");
			focused.addAll(QuestionIntentDictionary.values(
				"public_data.ai_management_focused",
				List.of("공공데이터의 인공지능 친화적 관리 가이드라인", "학습 데이터와 참조 데이터", "데이터셋의 목적·구성·품질·한계", "메타데이터")
			));
			directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
				"public_data.ai_management_direct",
				List.of(
					List.of("학습 데이터", "참조 데이터"),
					List.of("공공데이터의 인공지능 친화적 관리", "인공지능 친화적 관리"),
					List.of("데이터셋의 목적·구성·품질·한계", "메타데이터")
				)
			));
		}
		if (normalized.contains("업무성과계획")) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"performance_plan.concepts",
				List.of("업무성과계획", "업무성과계획수립")
			));
			focused.addAll(QuestionIntentDictionary.values(
				"performance_plan.focused",
				List.of("업무성과계획 수립 대상", "업무성과계획 수립 대상 제외")
			));
		}
		if (normalized.contains("성과측정")) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"performance_measure.concepts",
				List.of("성과측정", "성과측정완료", "성과측정기간")
			));
			focused.addAll(QuestionIntentDictionary.values(
				"performance_measure.focused",
				List.of("성과측정 완료 여부", "성과측정 기간", "측정을 완료")
			));
			if (containsAny(normalized, List.of("언제", "시기", "기한", "기간", "마감", "까지"))) {
				focused.addAll(QuestionIntentDictionary.values(
					"performance_measure.period_focused",
					List.of("평가기간", "평가기준", "월말", "기간", "성과측정 완료 여부")
				));
				directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
					"performance_measure.period_direct",
					List.of(List.of("평가기간", "기간", "월말", "성과측정 완료", "평가기준"))
				));
			}
		}
		if (normalized.contains("충실성")) {
			focused.addAll(QuestionIntentDictionary.values(
				"irm.faithfulness_focused",
				List.of("정보자원 등록 충실성", "정보자원 등록요청", "기한 내 등록", "정보등록 품질", "평가방법", "평가기준")
			));
			directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
				"irm.faithfulness_direct",
				List.of(
					List.of("충실성", "정보자원 등록 충실성"),
					List.of("정보자원 등록요청", "기한 내", "등록 여부"),
					List.of("평가방법", "평가기준")
				)
			));
		}
		if (!suppressedIntentIds.contains("privacy_notice")
			&& normalized.contains("개인정보")
			&& containsAny(normalized, List.of("처리목적", "처리방침", "고지", "통지", "알려야"))) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"privacy_notice.concepts",
				List.of("개인정보", "개인정보 보호법", "개인정보처리자", "정보주체", "개인정보 처리방침")
			));
			focused.addAll(QuestionIntentDictionary.values(
				"privacy_notice.focused",
				List.of("개인정보의 처리 목적", "개인정보 처리방침", "정보주체가 쉽게 확인", "공개하여야")
			));
			directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
				"privacy_notice.direct",
				List.of(
					List.of("개인정보의 처리 목적", "처리목적"),
					List.of("개인정보 처리방침", "처리방침"),
					List.of("공개", "알려야", "고지", "통지")
				)
			));
		}
		if (isPrivacyMinimumCollectionQuestion(normalized)) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"privacy_minimum_collection.concepts",
				List.of("개인정보", "개인정보 보호법", "필요한 최소한의 개인정보", "개인정보의 수집")
			));
			sectionTypes.add("requirement");
			focused.addAll(QuestionIntentDictionary.values(
				"privacy_minimum_collection.focused",
				List.of("필요한 최소한의 개인정보", "개인정보를 수집하여야 한다", "처리 목적을 명확하게", "필요한 범위")
			));
			directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
				"privacy_minimum_collection.direct",
				List.of(
					List.of("필요한 최소한의 개인정보", "최소한의 개인정보"),
					List.of("처리 목적을 명확하게", "처리 목적"),
					List.of("개인정보를 수집", "개인정보의 수집")
				)
			));
		}
		if (isCctvPublicPlaceExceptionQuestion(normalized)) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"cctv.public_place_exception_concepts",
				List.of("고정형 영상정보처리기기", "영상정보처리기기", "CCTV", "공개된 장소")
			));
			sectionTypes.add("exception");
			focused.addAll(QuestionIntentDictionary.values(
				"cctv.public_place_exception_focused",
				List.of("공개된 장소", "원칙적으로 금지", "예외적으로 설치", "법령에서 구체적으로 허용", "법 제25조")
			));
			directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
				"cctv.public_place_exception_direct",
				List.of(
					List.of("공개된 장소", "고정형 영상정보처리기기"),
					List.of("원칙적으로 금지", "예외적으로 설치"),
					List.of("법령에서 구체적으로 허용", "법 제25조")
				)
			));
		}
		if (isAiCommitteeFunctionQuestion(normalized)) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"ai_committee.concepts",
				List.of("인공지능위원회", "국가인공지능전략위원회", "인공지능 전략위원회", "AI위원회")
			));
			sectionTypes.add("definition");
			focused.addAll(QuestionIntentDictionary.values(
				"ai_committee.focused_function",
				List.of("국가인공지능전략위원회", "인공지능위원회", "심의·의결", "심의 의결", "인공지능 기본계획")
			));
			directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
				"ai_committee.direct_function",
				List.of(
					List.of("국가인공지능전략위원회", "인공지능위원회"),
					List.of("심의·의결", "심의 의결", "심의", "의결"),
					List.of("인공지능 기본계획", "정책", "전략")
				)
			));
		}
		if (isAiDataAdministrationLawQuestion(normalized)) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"ai_data_administration_law.concepts",
				List.of("인공지능 및 데이터 기반 행정 활성화에 관한 법률", "인공지능기반행정", "데이터기반행정")
			));
			focused.addAll(QuestionIntentDictionary.values(
				"ai_data_administration_law.focused",
				List.of("인공지능 및 데이터 기반 행정 활성화에 관한 법률", "데이터기반행정 활성화 기본계획", "시행계획", "시행일", "시행예정")
			));
			directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
				"ai_data_administration_law.direct",
				List.of(
					List.of("인공지능 및 데이터 기반 행정 활성화에 관한 법률", "인공지능기반행정", "데이터기반행정"),
					List.of("데이터기반행정 활성화 기본계획", "기본계획"),
					List.of("시행일", "시행예정", "미래")
				)
			));
		}
		if (isMcstContentStatisticsQuestion(normalized)) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"official_doc.mcst_content_statistics_concepts",
				List.of("콘텐츠산업조사", "콘텐츠산업", "승인통계", "매출액", "사업체")
			));
			sectionTypes.add("body");
			focused.addAll(QuestionIntentDictionary.values(
				"official_doc.mcst_content_statistics_focused",
				List.of("콘텐츠산업조사 결과보고서", "콘텐츠산업조사 승인통계", "콘텐츠산업 매출", "사업체 수", "종사자 수")
			));
			directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
				"official_doc.mcst_content_statistics_direct",
				List.of(
					List.of("콘텐츠산업조사", "콘텐츠산업"),
					List.of("승인통계", "통계", "조사"),
					List.of("매출", "사업체", "종사자")
				)
			));
		}
		if (isMcstSportsIndustryQuestion(normalized)) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"official_doc.mcst_sports_industry_concepts",
				List.of("스포츠산업조사", "스포츠산업", "사업체", "매출", "종사자")
			));
			sectionTypes.add("body");
			focused.addAll(QuestionIntentDictionary.values(
				"official_doc.mcst_sports_industry_focused",
				List.of("스포츠산업조사 결과 보고서", "스포츠산업 사업체", "스포츠산업 매출", "종사자")
			));
		}
		if (isMsitAiPolicyQuestion(normalized)) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"official_doc.msit_ai_policy_concepts",
				List.of("과학기술정보통신부", "과기정통부", "Ministry of Science and ICT", "인공지능", "AI 정책")
			));
			sectionTypes.add("body");
			focused.addAll(QuestionIntentDictionary.values(
				"official_doc.msit_ai_policy_focused",
				List.of("과기정통부 인공지능 정책", "국민 체감 AI 서비스", "K-AI", "피지컬 AI", "AI 정책 방향")
			));
			directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
				"official_doc.msit_ai_policy_direct",
				List.of(
					List.of("과학기술정보통신부", "과기정통부"),
					List.of("인공지능", "AI", "K-AI"),
					List.of("정책", "서비스", "확산", "선도사업")
				)
			));
		}
		if (isKoreanLiteratureExportQuestion(normalized)) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"official_doc.korean_literature_export_concepts",
				List.of("한국문학", "한국문학 번역", "한국문학 해외 진출", "해외 출판사")
			));
			sectionTypes.add("body");
			focused.addAll(QuestionIntentDictionary.values(
				"official_doc.korean_literature_export_focused",
				List.of("한국문학 번역과 해외 진출 지원", "해외 출판사의 한국문학 번역·출판 지원", "관련 예산을 늘린다", "기획 번역")
			));
			directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
				"official_doc.korean_literature_export_direct",
				List.of(
					List.of("한국문학 번역", "해외 진출 지원"),
					List.of("해외 출판사의 한국문학 번역·출판 지원", "예산을 늘린다"),
					List.of("한국고전과 근현대 걸작 기획 번역", "기획 번역")
				)
			));
		}
		if (isQuantumOecdQuestion(normalized)) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"official_doc.quantum_oecd_concepts",
				List.of("OECD", "양자 기술", "OECD 권고문", "퀀텀")
			));
			sectionTypes.add("body");
			focused.addAll(QuestionIntentDictionary.values(
				"official_doc.quantum_oecd_focused",
				List.of("양자 기술에 관한 OECD 권고문", "재정적 기여", "국제 연수회", "초안 작성", "대한민국이 수행해 온 역할")
			));
			directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
				"official_doc.quantum_oecd_direct",
				List.of(
					List.of("양자 기술에 관한 OECD 권고문", "OECD 권고문"),
					List.of("재정적 기여", "국제 연수회", "초안 작성"),
					List.of("대한민국이 수행해 온 역할", "역할과 기여")
				)
			));
		}
		if (isTvingSmishingQuestion(normalized)) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"official_doc.tving_smishing_concepts",
				List.of("티빙", "TVING", "침해사고", "스미싱")
			));
			sectionTypes.add("procedure");
			focused.addAll(QuestionIntentDictionary.values(
				"official_doc.tving_smishing_focused",
				List.of("스미싱 피해 신고", "소액결제확인서", "경찰서 사이버수사대", "사건사고 사실 확인서")
			));
			directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
				"official_doc.tving_smishing_direct",
				List.of(
					List.of("스미싱 피해 신고", "소액결제확인서"),
					List.of("경찰서 사이버수사대", "민원실"),
					List.of("사건사고 사실 확인서", "피해보상 요구")
				)
			));
		}
		if (normalized.contains("우회전") || normalized.contains("횡단보도")) {
			conceptGroups.add(QuestionIntentDictionary.values(
				"traffic.crosswalk_concepts",
				List.of("우회전", "횡단보도", "보행자보호의무", "도로교통법")
			));
			focused.addAll(QuestionIntentDictionary.values(
				"traffic.crosswalk_focused",
				List.of("횡단보도 앞에서 일시정지", "교차로 통행방법", "보행자의 보호")
			));
			directEvidenceGroups.addAll(QuestionIntentDictionary.groups(
				"traffic.crosswalk_direct",
				List.of(
					List.of("횡단보도", "보행자보호의무", "보행자"),
					List.of("일시정지", "정지하여야", "정지"),
					List.of("우회전", "교차로통행방법")
				)
			));
		}
		if (KoreanQueryNormalizer.isProcurementCatalogContractQuestion(normalized)) {
			for (List<String> group : KoreanQueryNormalizer.procurementCatalogConceptGroups(normalized)) {
				conceptGroups.add(group);
			}
			lexical.addAll(KoreanQueryNormalizer.procurementCatalogKeywords(normalized));
			focused.addAll(KoreanQueryNormalizer.procurementCatalogFocusedKeywords(normalized));
		}
	}

	private static boolean isEgovPreliminaryReviewQuestion(String normalized) {
		return normalized.contains("예비검토")
			&& (normalized.contains("지능정보사회")
				|| normalized.contains("지능정보화")
				|| normalized.contains("전자정부성과관리")
				|| normalized.contains("정보화사업"));
	}

	private static boolean isAutonomyPreConsultationQuestion(String normalized) {
		return normalized.contains("자치분권") && normalized.contains("사전협의");
	}

	private static boolean isPublicDataCustomSupportQuestion(String normalized) {
		return normalized.contains("공공데이터")
			&& normalized.contains("활용기업")
			&& normalized.contains("맞춤형")
			&& normalized.contains("지원");
	}

	private static boolean isPublicDataAiManagementQuestion(String normalized) {
		return normalized.contains("공공데이터")
			&& (normalized.contains("ai") || normalized.contains("인공지능"))
			&& (normalized.contains("학습용") || normalized.contains("학습데이터") || normalized.contains("친화적관리"));
	}

	private static boolean isKoreanLiteratureExportQuestion(String normalized) {
		return normalized.contains("한국문학")
			&& (normalized.contains("해외진출") || (normalized.contains("해외") && normalized.contains("진출")) || normalized.contains("번역"));
	}

	private static boolean isQuantumOecdQuestion(String normalized) {
		return normalized.contains("oecd")
			&& (normalized.contains("양자") || normalized.contains("퀀텀"))
			&& normalized.contains("권고문");
	}

	private static boolean isTvingSmishingQuestion(String normalized) {
		return (normalized.contains("티빙") || normalized.contains("tving"))
			&& normalized.contains("스미싱");
	}

	private static boolean isCctvPublicPlaceExceptionQuestion(String normalized) {
		return (normalized.contains("cctv") || normalized.contains("영상정보처리기기"))
			&& normalized.contains("공개된장소")
			&& (normalized.contains("예외")
				|| normalized.contains("설치할수")
				|| normalized.contains("설치가능")
				|| normalized.contains("가능한가")
				|| normalized.contains("가능"));
	}

	private static boolean isAiCommitteeFunctionQuestion(String normalized) {
		return (normalized.contains("인공지능위원회")
				|| normalized.contains("국가인공지능전략위원회")
				|| normalized.contains("ai위원회"))
			&& (normalized.contains("심의")
				|| normalized.contains("의결")
				|| normalized.contains("역할")
				|| normalized.contains("기능")
				|| normalized.contains("어떤일")
				|| normalized.contains("무슨일"));
	}

	private static boolean isPrivacyMinimumCollectionQuestion(String normalized) {
		return normalized.contains("개인정보")
			&& normalized.contains("수집")
			&& (normalized.contains("필요한")
				|| normalized.contains("최소")
				|| normalized.contains("만큼")
				|| normalized.contains("범위"));
	}

	private static boolean isAiDataAdministrationLawQuestion(String normalized) {
		return normalized.contains("인공지능")
			&& (normalized.contains("데이터기반행정")
				|| normalized.contains("데이터기반")
				|| normalized.contains("데이터기반행정활성화")
				|| normalized.contains("데이터기반행정활성화에관한법률"));
	}

	private static boolean isMcstContentStatisticsQuestion(String normalized) {
		return normalized.contains("콘텐츠산업")
			&& (normalized.contains("조사") || normalized.contains("통계") || normalized.contains("매출"));
	}

	private static boolean isMcstSportsIndustryQuestion(String normalized) {
		return normalized.contains("스포츠산업")
			&& (normalized.contains("조사") || normalized.contains("보고서") || normalized.contains("매출") || normalized.contains("사업체"));
	}

	private static boolean isMsitAiPolicyQuestion(String normalized) {
		return (normalized.contains("과기정통부") || normalized.contains("과학기술정보통신부"))
			&& (normalized.contains("인공지능") || normalized.contains("ai"))
			&& (normalized.contains("정책") || normalized.contains("방향") || normalized.contains("공식문서") || normalized.contains("확인"));
	}

	private static List<String> queryTerms(String question) {
		List<String> terms = new ArrayList<>();
		for (String token : String.valueOf(question).split("\\s+")) {
			String term = KoreanQueryNormalizer.normalizeQueryTerm(token);
			if (term.length() >= 2 && !KoreanQueryNormalizer.isWeakQuestionTerm(term)) {
				terms.add(term);
			}
		}
		String compact = KoreanQueryNormalizer.normalizeQueryTerm(question);
		if (!terms.isEmpty() && terms.size() <= 1 && compact.length() >= 4 && !terms.contains(compact)) {
			terms.add(compact);
		}
		return terms.stream().distinct().toList();
	}

	private static boolean containsAny(String normalized, List<String> terms) {
		if (normalized == null || normalized.isBlank()) {
			return false;
		}
		for (String term : terms) {
			if (normalized.contains(KoreanQueryNormalizer.normalizeForMatch(term))) {
				return true;
			}
		}
		return false;
	}

	private static List<List<String>> distinctGroups(List<List<String>> groups) {
		List<List<String>> result = new ArrayList<>();
		Set<String> keys = new LinkedHashSet<>();
		for (List<String> group : groups) {
			List<String> cleaned = group.stream()
				.filter(value -> value != null && !value.isBlank())
				.distinct()
				.toList();
			if (cleaned.isEmpty()) {
				continue;
			}
			String key = String.join("|", cleaned.stream().map(KoreanQueryNormalizer::normalizeForMatch).toList());
			if (keys.add(key)) {
				result.add(cleaned);
			}
		}
		return result;
	}
}
