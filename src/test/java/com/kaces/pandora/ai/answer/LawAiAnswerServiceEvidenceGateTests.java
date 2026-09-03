package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.common.text.QuestionSearchPlan;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.search.RagChunkSearchIndexService;
import com.kaces.pandora.semantic.config.LawAiLexicalVariantProperties;
import com.kaces.pandora.semantic.config.LawAiProperties;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LawAiAnswerServiceEvidenceGateTests {

	@Test
	void directPreservationPolicyOverridesEarlierConceptRelevantClassification() {
		assertThat(LawAiAnswerService.evidenceRoleForSelectionPolicy("concept_relevant"))
			.isEqualTo("related_definition");
		assertThat(LawAiAnswerService.evidenceRoleForSelectionPolicy(
			"concept_relevant+complete_procedure_preserve"
		)).isEqualTo("direct");
		assertThat(LawAiAnswerService.evidenceRoleForSelectionPolicy(
			"concept_relevant+intent_direct_preserve"
		)).isEqualTo("direct");
		assertThat(LawAiAnswerService.evidenceRoleForSelectionPolicy(
			"concept_relevant+semantic_direct_preserve"
		)).isEqualTo("direct");
	}

	@Test
	void authoritativeLexicalVariantPreservesCompleteProcedureGround() throws Exception {
		LawSemanticChunkRow partial = chunk(
			101L,
			"official_doc",
			"정보화사업 보안성 검토 안내서",
			"절차",
			"요청기관은 보안성 검토를 요청하고 검토기관은 검토한다."
		);
		LawSemanticChunkRow complete = chunk(
			102L,
			"official_doc",
			"정보화사업 보안성 검토 안내서",
			"절차",
			"요청기관이 보안성 검토를 요청하면 검토기관이 검토한 뒤 결과를 통보한다."
		);
		EvidenceJudge.Result judged = result(
			List.of(partial), false, true, false, true, 1, 1, 1, "judge"
		);
		LawAiAnswerService service = service();
		try {
			service.configureLexicalVariantProperties(new LawAiLexicalVariantProperties(true, true, 4, 60.0));

			EvidenceJudge.Result preserved = preserveCompleteProcedureEvidenceChunks(
				service,
				judged,
				List.of(partial, complete),
				"보안성검토 절차는 어떻게 돼?"
			);

			assertThat(preserved.chunks()).extracting(LawSemanticChunkRow::chunkId).containsExactly(102L, 101L);
			assertThat(preserved.selectionPolicy()).endsWith("+complete_procedure_preserve");
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void authoritativeAlreadyCompleteProcedureMarksEarlierConceptSelectionAsDirect() throws Exception {
		LawSemanticChunkRow complete = chunk(
			103L,
			"official_doc",
			"정보화사업 보안성 검토 안내서",
			"절차",
			"요청기관이 보안성 검토를 요청하면 검토기관이 검토한 뒤 결과를 통보한다."
		);
		EvidenceJudge.Result judged = result(
			List.of(complete), false, true, false, true, 1, 1, 1, "concept_relevant"
		);
		LawAiAnswerService service = service();
		try {
			service.configureLexicalVariantProperties(new LawAiLexicalVariantProperties(true, true, 4, 60.0));

			EvidenceJudge.Result preserved = preserveCompleteProcedureEvidenceChunks(
				service,
				judged,
				List.of(complete),
				"보안성검토 절차는 어떻게 돼?"
			);

			assertThat(preserved.selectionPolicy()).endsWith("+complete_procedure_preserve");
			assertThat(LawAiAnswerService.evidenceRoleForSelectionPolicy(preserved.selectionPolicy()))
				.isEqualTo("direct");
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void controlLexicalVariantDoesNotChangeProcedureGrounds() throws Exception {
		LawSemanticChunkRow partial = chunk(
			201L, "official_doc", "정보화사업 보안성 검토 안내서", "절차", "검토를 요청하고 수행한다."
		);
		LawSemanticChunkRow complete = chunk(
			202L, "official_doc", "정보화사업 보안성 검토 안내서", "절차", "검토를 요청하고 수행한 뒤 결과를 통보한다."
		);
		EvidenceJudge.Result judged = result(
			List.of(partial), false, true, false, true, 1, 1, 1, "judge"
		);
		LawAiAnswerService service = service();
		try {
			service.configureLexicalVariantProperties(new LawAiLexicalVariantProperties(true, false, 4, 60.0));

			EvidenceJudge.Result preserved = preserveCompleteProcedureEvidenceChunks(
				service,
				judged,
				List.of(partial, complete),
				"보안성검토 절차는 어떻게 돼?"
			);

			assertThat(preserved).isSameAs(judged);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void authoritativeLexicalVariantPromotesCompleteProcedureGroundIntoFinalAnswerContext() throws Exception {
		List<LawSemanticChunkRow> displayChunks = new java.util.ArrayList<>();
		for (long chunkId = 301L; chunkId <= 308L; chunkId++) {
			displayChunks.add(chunk(
				chunkId,
				"official_doc",
				"정보화사업 보안성 검토 안내서",
				"절차",
				"보안성 검토 요청과 관련된 일부 안내다."
			));
		}
		LawSemanticChunkRow complete = chunk(
			309L,
			"official_doc",
			"정보화사업 보안성 검토 안내서",
			"추진절차",
			"보안성 검토를 요청하고 검토기관이 총괄 검토한 뒤 검토 결과를 통보한다."
		);
		displayChunks.add(complete);
		LawAiAnswerService service = service();
		try {
			service.configureLexicalVariantProperties(new LawAiLexicalVariantProperties(true, true, 4, 60.0));

			List<LawSemanticChunkRow> selected = selectAnswerContextChunks(
				service,
				displayChunks,
				"보안성검토 절차는 어떻게 돼?"
			);

			assertThat(selected).hasSize(8);
			assertThat(selected).extracting(LawSemanticChunkRow::chunkId)
				.containsExactly(309L, 301L, 302L, 303L, 304L, 305L, 306L, 307L);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void completeNumberedProcedureSnippetStartsAtTheProcedureFlow() throws Exception {
		String prefix = ("보안성 검토 대상 사업 식별과 담당기관 역할을 설명한다. ").repeat(30);
		LawSemanticChunkRow complete = chunk(
			310L,
			"official_doc",
			"정보화사업 보안성 검토 안내서",
			"추진절차",
			prefix
				+ "② 보안성 검토요청: 자체 보안대책과 사업계획서를 첨부하여 검토를 요청한다. "
				+ "③ 보안성 검토 수행: 검토기관이 보안대책의 적절성을 검토한다. "
				+ "④ 검토결과 통보: 검토기관이 요청기관에 검토결과를 통보한다."
		);
		LawAiAnswerService service = service();
		try {
			String snippet = snippet(service, complete, "보안성검토 절차는 어떻게 돼?");

			assertThat(snippet)
				.contains("② 보안성 검토요청")
				.contains("③ 보안성 검토 수행")
				.contains("④ 검토결과 통보");
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void completeNumberedProcedureAnswerContextStartsAtTheProcedureFlow() throws Exception {
		String prefix = ("보안성 검토 대상 사업 식별과 담당기관 역할을 설명한다. ").repeat(30);
		LawSemanticChunkRow complete = chunk(
			311L,
			"official_doc",
			"정보화사업 보안성 검토 안내서",
			"추진절차",
			prefix
				+ "② 보안성 검토요청: 자체 보안대책과 사업계획서를 첨부하여 검토를 요청한다. "
				+ "③ 보안성 검토 수행: 검토기관이 보안대책의 적절성을 검토한다. "
				+ "④ 검토결과 통보: 검토기관이 요청기관에 검토결과를 통보한다."
		);
		LawAiAnswerService service = service();
		try {
			String context = contextSnippet(service, complete, "보안성검토 절차는 어떻게 돼?", 820);

			assertThat(context)
				.contains("② 보안성 검토요청")
				.contains("③ 보안성 검토 수행")
				.contains("④ 검토결과 통보");
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void shadowModePreservesTheExistingControlCandidateOrder() throws Exception {
		LawSemanticChunkRow first = chunk(10L, "law", "첫째", "제1조", "첫째 본문");
		LawSemanticChunkRow second = chunk(20L, "law", "둘째", "제2조", "둘째 본문");
		Method method = LawAiAnswerService.class.getDeclaredMethod(
			"selectCandidateOrder",
			List.class,
			List.class,
			boolean.class
		);
		method.setAccessible(true);

		@SuppressWarnings("unchecked")
		List<LawSemanticChunkRow> selected = (List<LawSemanticChunkRow>) method.invoke(
			null,
			List.of(first, second),
			List.of(second, first),
			false
		);

		assertThat(selected).containsExactly(first, second);
	}

	@Test
	void authoritativeModeCanPassTheFusedCandidateOrderForward() throws Exception {
		LawSemanticChunkRow first = chunk(10L, "law", "첫째", "제1조", "첫째 본문");
		LawSemanticChunkRow second = chunk(20L, "law", "둘째", "제2조", "둘째 본문");
		Method method = LawAiAnswerService.class.getDeclaredMethod(
			"selectCandidateOrder",
			List.class,
			List.class,
			boolean.class
		);
		method.setAccessible(true);

		@SuppressWarnings("unchecked")
		List<LawSemanticChunkRow> selected = (List<LawSemanticChunkRow>) method.invoke(
			null,
			List.of(first, second),
			List.of(second, first),
			true
		);

		assertThat(selected).containsExactly(second, first);
	}

	@Test
	void runtimeInfoUsesArtifactIdentityCapturedByTheService() throws Exception {
		LawAiAnswerService service = service();
		try {
			java.lang.reflect.Field field = LawAiAnswerService.class.getDeclaredField("runtimeArtifactIdentity");
			field.setAccessible(true);
			RuntimeArtifactIdentity snapshot = new RuntimeArtifactIdentity(
				"jar", "startup-sha256", 123L, "C:/runtime/pandora.jar", "2026-07-31T00:00:00Z"
			);
			field.set(service, snapshot);

			LawAiRuntimeInfo runtimeInfo = service.runtimeInfo();

			assertThat(runtimeInfo.runtimeArtifactKind()).isEqualTo("jar");
			assertThat(runtimeInfo.runtimeArtifactSha256()).isEqualTo("startup-sha256");
			assertThat(runtimeInfo.runtimeArtifactSize()).isEqualTo(123L);
			assertThat(runtimeInfo.runtimeArtifactPath()).isEqualTo("C:/runtime/pandora.jar");
			assertThat(runtimeInfo.runtimeArtifactModifiedAt()).isEqualTo("2026-07-31T00:00:00Z");
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void rejectsTopicAlignedPolicyForCarefulQuestion() throws Exception {
		String reason = rejectionReason(
			"과업심의 대상은?",
			result(false, false, false, false, 5, 2, 0, "topic_aligned")
		);

		assertThat(reason).contains("직접근거로 보기 어렵습니다");
	}

	@Test
	void rejectsRelevantPolicyWithoutDirectEvidenceForCarefulQuestion() throws Exception {
		String reason = rejectionReason(
			"디지털카탈로그에서 구매하면 수의계약인가?",
			result(false, false, true, true, 8, 4, 0, "relevant")
		);

		assertThat(reason).contains("직접 답하는 근거가 없어");
	}

	@Test
	void rejectsCrossChunkPolicyWhenNoSingleDirectGroundWasConfirmed() throws Exception {
		String reason = rejectionReason(
			"이메일만으로 개인정보라고 볼 수 있나?",
			result(true, true, true, true, 8, 4, 0, "cross_chunk_direct")
		);

		assertThat(reason).contains("단일 직접근거");
	}

	@Test
	void rejectsExploratoryLookupPolicyForCarefulQuestion() throws Exception {
		String reason = rejectionReason(
			"디지털카탈로그에서 구매하면 수의계약인가?",
			result(false, false, true, true, 8, 4, 0, "exploratory_lookup")
		);

		assertThat(reason).contains("탐색용 근거");
	}

	@Test
	void allowsDirectEvidencePolicyForCarefulQuestion() throws Exception {
		String reason = rejectionReason(
			"보안성검토 대상 시스템은?",
			result(true, true, true, true, 8, 4, 1, "direct")
		);

		assertThat(reason).isNull();
	}

	@Test
	void securityReviewTargetPreferenceDoesNotMutateImmutableJudgeScores() {
		LawSemanticChunkRow existing = chunk(
			401L,
			"law",
			"정보통신기반 보호법",
			"대상",
			"정보시스템 보호에 관한 일반 규정입니다."
		);
		LawSemanticChunkRow officialGuide = chunk(
			402L,
			"official_doc",
			"정보화사업 보안성검토 가이드",
			"보안성 검토 대상",
			"민감정보 및 고유식별정보를 처리하는 정보시스템은 보안성 검토 대상입니다."
		);
		Map<String, Double> immutableJudgeScores = Map.of("law:401", 1.0);
		LawAiAnswerService service = service();
		try {
			LawAiAnswerService.SecurityReviewEvidencePreference preferred =
				service.preferOfficialSecurityReviewTargetEvidence(
					"민감정보를 처리하는 시스템이면 보안성검토 대상이야?",
					List.of(existing),
					List.of(existing, officialGuide),
					immutableJudgeScores,
					Map.of("official_doc:402", 0.8)
				);

			assertThat(preferred.evidenceChunks()).containsExactly(officialGuide, existing);
			assertThat(preferred.finalScoreByChunkId()).containsEntry("official_doc:402", 4.0);
			assertThat(immutableJudgeScores).containsExactlyEntriesOf(Map.of("law:401", 1.0));
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void securityReviewExceptionPrefersOfficialSystemAccessCondition() {
		LawSemanticChunkRow genericDefinition = chunk(
			403L,
			"admrul",
			"정보보안 기본지침",
			"정의",
			"정보보안은 정보시스템을 보호하는 일체의 활동입니다."
		);
		LawSemanticChunkRow officialAccessCondition = chunk(
			404L,
			"official_doc",
			"2025년 문화정보화 수준평가 매뉴얼",
			"보안성 검토 대상",
			"DB 구축, 콘텐츠 제작 등 용역사업 참여인력이 시스템에 접근하지 않는 사업은 보안성 검토 대상에서 제외됩니다. "
				+ "다만 데이터 입력과 가공을 위해 시스템에 접근하는 사업은 보안성 검토 대상입니다."
		);
		Map<String, Double> immutableJudgeScores = Map.of("admrul:403", 1.0);
		LawAiAnswerService service = service();
		try {
			LawAiAnswerService.SecurityReviewEvidencePreference preferred =
				service.preferOfficialSecurityReviewTargetEvidence(
					"보안성검토 생략 가능한 경우는?",
					List.of(genericDefinition),
					List.of(genericDefinition, officialAccessCondition),
					immutableJudgeScores,
					Map.of("official_doc:404", 0.8)
				);

			assertThat(preferred.evidenceChunks()).containsExactly(officialAccessCondition, genericDefinition);
			assertThat(preferred.finalScoreByChunkId()).containsEntry("official_doc:404", 4.0);
			assertThat(immutableJudgeScores).containsExactlyEntriesOf(Map.of("admrul:403", 1.0));
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void answerFocusSeparatesProjectReviewFromPreConsultation() throws Exception {
		LawAiAnswerService service = service();
		try {
			String focus = answerFocusInstruction(service, "과업심의 한 사업은 사전협의도 꼭 해야돼?");

			assertThat(focus)
				.contains("별도 제도")
				.contains("각 제도의 대상사업 여부를 각각 확인")
				.contains("자동으로 충족하거나 면제");
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void answerFocusKeepsPreConsultationExceptionAndNewProjectConditionTogether() throws Exception {
		LawAiAnswerService service = service();
		try {
			String focus = answerFocusInstruction(service, "정보화사업 사전협의 제외 대상은?");

			assertThat(focus)
				.contains("기관별 기준금액")
				.contains("신규 사업")
				.contains("함께 답하세요");
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void answerFocusKeepsSecurityReviewAccessExceptionTwoSided() throws Exception {
		LawAiAnswerService service = service();
		try {
			String focus = answerFocusInstruction(service, "보안성검토 생략 가능한 경우는?");

			assertThat(focus)
				.contains("시스템 접근 여부")
				.contains("접근하지 않는 조건")
				.contains("접근하는 경우");
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void rejectsDirectEvidenceThatMissesConfiguredEntityAnchor() throws Exception {
		String question = "전자정부 성과관리 실행계획의 예비검토는 어떤 사업을 대상으로 하는거야?";
		LawSemanticChunkRow unrelatedSecurityReview = chunk(
			1L,
			"official_doc",
			"2026년 정보화사업 보안성 검토 가이드",
			"보안성 검토 대상",
			"중앙행정기관이 추진하는 정보화사업은 보안성 검토 대상일 수 있습니다."
		);

		String reason = rejectionReason(
			question,
			result(List.of(unrelatedSecurityReview), true, true, true, true, 8, 4, 1, "direct")
		);

		assertThat(reason).contains("configured anchor");
	}

	@Test
	void allowsDirectEvidenceThatCoversConfiguredEntityAnchor() throws Exception {
		String question = "전자정부 성과관리 실행계획의 예비검토는 어떤 사업을 대상으로 하는거야?";
		LawSemanticChunkRow directPreliminaryReview = chunk(
			1L,
			"official_doc",
			"전자정부 성과관리 지침",
			"예비검토 대상 사업",
			"예비검토는 중앙행정기관이 추진하는 정보화사업에 적용됩니다."
		);

		String reason = rejectionReason(
			question,
			result(List.of(directPreliminaryReview), true, true, true, true, 8, 4, 1, "direct")
		);

		assertThat(reason).isNull();
	}

	@Test
	void filtersUnanchoredCandidatesWhenConfiguredEntityAnchorIsAvailable() throws Exception {
		String question = "전자정부 성과관리 실행계획의 예비검토는 어떤 사업을 대상으로 하는거야?";
		LawSemanticChunkRow unrelatedSecurityReview = chunk(
			1L,
			"official_doc",
			"2026년 정보화사업 보안성 검토 가이드",
			"보안성 검토 대상",
			"중앙행정기관이 추진하는 정보화사업은 보안성 검토 대상일 수 있습니다."
		);
		LawSemanticChunkRow preliminaryReview = chunk(
			2L,
			"official_doc",
			"전자정부 성과관리 지침",
			"예비검토 대상 사업",
			"예비검토는 중앙행정기관이 추진하는 정보화사업에 적용됩니다."
		);

		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> filtered = filterByQuestionIntent(
				service,
				List.of(unrelatedSecurityReview, preliminaryReview),
				question
			);

			assertThat(filtered).containsExactly(preliminaryReview);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void intentDirectEvidencePreserveRequiresEveryConfiguredAnchorMentionedInQuestion() throws Exception {
		String question = "전자정부 성과관리 실행계획의 예비검토는 어떤 사업을 대상으로 하는거야?";
		LawSemanticChunkRow wrongPreConsultation = chunk(
			5L,
			"official_doc",
			"전자정부 성과관리 지침",
			"사전협의 대상사업",
			"사전협의의 대상사업은 중앙행정기관의 장이 추진하는 모든 정보화사업입니다."
		);
		LawSemanticChunkRow correctPreliminaryReview = chunk(
			6L,
			"official_doc",
			"전자정부 성과관리 지침",
			"예비검토 대상 사업",
			"예비검토는 중앙행정기관의 장이 다음 해에 추진하는 정보화사업을 대상으로 합니다."
		);

		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> preserved = intentDirectEvidenceChunks(
				service,
				List.of(wrongPreConsultation, correctPreliminaryReview),
				question
			);

			assertThat(preserved).containsExactly(correctPreliminaryReview);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void allowsWeakPolicyForPureDefinitionQuestion() throws Exception {
		String reason = rejectionReason(
			"정의란 무엇?",
			result(false, false, true, true, 2, 1, 0, "topic_aligned")
		);

		assertThat(reason).isNull();
	}

	@Test
	void boostsExplicitLawTitleOverAdministrativeGuidelines() throws Exception {
		String question = "개인정보 보호법상 개인정보 처리 목적은 어떻게 알려야 해?";
		LawSemanticChunkRow law = chunk(1L, "law", "개인정보 보호법", "제30조", "개인정보의 처리 목적을 공개하여야 한다.");
		LawSemanticChunkRow guideline = chunk(2L, "admrul", "문화체육관광부 개인정보 보호지침", "제4조", "개인정보 처리 목적을 명확하게 하여야 한다.");
		Map<String, Double> baseScores = Map.of(
			"law:1", 1.0,
			"admrul:2", 1.0
		);

		LawAiAnswerService service = service();
		try {
			double lawScore = adjustedScore(service, law, question, baseScores);
			double guidelineScore = adjustedScore(service, guideline, question, baseScores);

			assertThat(lawScore).isGreaterThan(guidelineScore + 2.0);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void relaxesScenarioAcronymForReviewScopeDecisionQuestions() throws Exception {
		LawAiAnswerService service = service();
		try {
			List<String> snsTerms = queryTerms(service, "SNS운영 사업도 과업심의 받아야해?");
			List<String> snsRequiredTerms = requiredExactTermsForQuery(
				service,
				"SNS운영 사업도 과업심의 받아야해?",
				snsTerms
			);
			List<String> oecdTerms = queryTerms(service, "OECD 양자 기술 권고문 내용 알려줘");
			List<String> oecdRequiredTerms = requiredExactTermsForQuery(
				service,
				"OECD 양자 기술 권고문 내용 알려줘",
				oecdTerms
			);

			assertThat(snsTerms).contains("sns운영", "과업심의");
			assertThat(snsRequiredTerms).isEmpty();
			assertThat(oecdRequiredTerms).contains("oecd");
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void prefersTaskReviewGuideScopePageForGenericProjectReviewQuestion() throws Exception {
		String question = "SNS운영 사업도 과업심의 받아야해?";
		LawSemanticChunkRow taskReviewGuide = chunk(
			1L,
			"official_doc",
			"공공소프트웨어사업 과업심의 가이드(2022. 12.)",
			"p.5 적용 대상 사업",
			"적용 대상 사업. 국가기관 등이 발주하는 모든 SW사업(상용SW포함). 소프트웨어의 개발, 제작, 생산, 유통, 운영 및 유지·관리 등 소프트웨어와 관련된 서비스. 단순 H/W 도입·설치는 비대상",
			5
		);
		LawSemanticChunkRow managementGuide = chunk(
			2L,
			"official_doc",
			"공공SW사업 법제도 관리감독 및 지원 가이드(2024. 12.)",
			"p.6 대상사업 사례",
			"대상 SW사업 예시. 온라인홍보 및 방송콘텐츠서비스, 온라인홍보 및 방송콘텐츠 개발, SW구매 등 대상사업 사례",
			6
		);
		Map<String, Double> baseScores = Map.of("official_doc:1", 0.2, "official_doc:2", 2.0);
		LawAiAnswerService service = service();
		try {
			double taskReviewScore = adjustedScore(service, taskReviewGuide, question, baseScores);
			double managementScore = adjustedScore(service, managementGuide, question, baseScores);

			assertThat(taskReviewScore).isGreaterThan(managementScore);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void filtersInformationSystemCompliancePenaltyToDirectConsequenceEvidence() throws Exception {
		String question = "정보화시스템 법제도 준수안하면 어떤 불이익?";
		LawSemanticChunkRow broadPurpose = chunk(
			1L,
			"official_doc",
			"전자정부 성과관리 지침(2024. 2.)",
			"p.1 신청",
			"정보화사업의 효율적인 추진과 성과관리를 위한 신청 절차와 관리 목적을 설명한다.",
			1
		);
		LawSemanticChunkRow securityPenalty = chunk(
			2L,
			"official_doc",
			"(붙임2) 2026년 정보화사업 보안성 검토 가이드",
			"p.5 작성방법",
			"누출금지 대상정보, 부정당업자 제재조치, 기밀유지 의무 및 위반 시 불이익을 명시한다. 국가계약법 시행령에 따라 입찰 참가자격 제한을 받는 등의 불이익과 사업자 보안위규 처리기준, 보안 위약금 부과 기준을 제시한다.",
			5
		);
		LawSemanticChunkRow managementGuide = chunk(
			3L,
			"official_doc",
			"공공SW사업 법제도 관리감독 및 지원 가이드(2024. 12.)",
			"p.7 점검시점 및 절차",
			"법제도 준수여부를 점검하고 미준수 사항이 있을 경우 개선권고 검토의견을 제시하며 발주기관은 제안요청서를 보완한다.",
			7
		);
		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> filtered = filterByQuestionIntent(
				service,
				List.of(broadPurpose, managementGuide, securityPenalty),
				question
			);

			assertThat(filtered).containsExactly(securityPenalty, managementGuide);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void prefersPublicDataDiagnosisFullCountPageOverAdjacentOverview() throws Exception {
		String question = "공공데이터포털 표준화 매뉴얼의 예방적 품질관리 진단 항목은 어떻게 구성돼?";
		LawSemanticChunkRow overview = chunk(
			1L,
			"official_doc",
			"1. 공공데이터베이스 표준화 관리 매뉴얼(2026. 4.)",
			"p.39 3.1.4 진단 영역 및 항목",
			"예방적 품질관리 진단영역은 데이터 표준, 데이터 구조, 데이터 값, 데이터 관리체계 4개 영역으로 구성된다.",
			39
		);
		LawSemanticChunkRow fullCount = chunk(
			2L,
			"official_doc",
			"1. 공공데이터베이스 표준화 관리 매뉴얼(2026. 4.)",
			"p.40 1.1 데이터 표준화 및",
			"4개의 진단영역은 세부 진단항목으로 구성되며, 현재 적용 중인 진단항목은 총 9개로 항목별 2개의 진단기준으로 구성되어 총 18개의 진단기준을 제시하고 있다.",
			40
		);
		Map<String, Double> baseScores = Map.of("official_doc:1", 1.2, "official_doc:2", 0.1);
		LawAiAnswerService service = service();
		try {
			double overviewScore = adjustedScore(service, overview, question, baseScores);
			double fullCountScore = adjustedScore(service, fullCount, question, baseScores);

			assertThat(fullCountScore).isGreaterThan(overviewScore);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void prefersCctvPublicPlaceExceptionPageOverAdjacentProvisionPages() throws Exception {
		String question = "개인정보보호위원회 CCTV 안내서에서 공개된 장소에 CCTV를 설치할 수 있는 예외는?";
		LawSemanticChunkRow exceptionPage = chunk(
			1L,
			"official_doc",
			"★고정형 영상정보처리기기 설치 운영 안내서(2024.12)",
			"p.15 1. 법령에서 구체적으로 허용하고 있는 경우",
			"누구든지 공개된 장소에 고정형 영상정보처리기기를 설치·운영하는 것은 원칙적으로 금지되며 예외적으로 법 제25조에서 정하는 사유에 해당하는 경우에만 설치·운영할 수 있다. 관련조항 1. 법령에서 구체적으로 허용하고 있는 경우",
			15
		);
		LawSemanticChunkRow adjacentProvision = chunk(
			2L,
			"official_doc",
			"공공기관 고정형 영상정보처리기기 설치·운영 가이드라인 5차 개정(2024.1)",
			"p.21 개인영상정보 보호 계획의 수립 및 시행",
			"공개된 장소에서의 고정형 영상정보처리기기 설치는 원칙적으로 금지되지만 예외적으로 법 제25조에서 정하는 사유에 해당하는 경우 설치·운영할 수 있으며 목적 범위 내로 이용할 수 있다.",
			21
		);
		Map<String, Double> baseScores = Map.of("official_doc:1", 0.1, "official_doc:2", 1.8);
		LawAiAnswerService service = service();
		try {
			double exceptionScore = adjustedScore(service, exceptionPage, question, baseScores);
			double adjacentScore = adjustedScore(service, adjacentProvision, question, baseScores);

			assertThat(exceptionScore).isGreaterThan(adjacentScore);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void prefersPublicDataCustomSupportDetailOverCoverPage() throws Exception {
		String question = "공공데이터 활용기업 맞춤형지원은 뭘 지원해?";
		LawSemanticChunkRow coverPage = chunk(
			1L,
			"official_doc",
			"2025년 공공데이터 활용 기업 맞춤형 지원사업 우수 사례집_F",
			"표지",
			"공공데이터·AI 활용기업",
			1
		);
		LawSemanticChunkRow supportDetail = chunk(
			2L,
			"official_doc",
			"2022년 공공데이터 활용기업 맞춤형지원 활용사례",
			"지원 내용",
			"공공데이터 활용역량 및 수요 분석을 통해 기업이 필요한 공공데이터 제공, 데이터 검색, 추천, 지원 프로그램을 제공한다.",
			4
		);
		Map<String, Double> baseScores = Map.of("official_doc:1", 2.0, "official_doc:2", 0.1);
		LawAiAnswerService service = service();
		try {
			double coverScore = adjustedScore(service, coverPage, question, baseScores);
			double detailScore = adjustedScore(service, supportDetail, question, baseScores);

			assertThat(detailScore).isGreaterThan(coverScore);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void filtersMachineReadableFormatNoiseWhenDirectEvidenceExists() throws Exception {
		String question = "공공데이터는 기계판독 가능한 오픈포맷으로 제공해야 해?";
		LawSemanticChunkRow privacyNoise = chunk(
			1L,
			"official_doc",
			"공공데이터의 인공지능 친화적 관리 가이드라인",
			"개인정보 처리",
			"개인정보보호법과 개인정보 처리 관련 유의사항을 설명한다.",
			10
		);
		LawSemanticChunkRow directEvidence = chunk(
			2L,
			"law",
			"공공데이터의 제공 및 이용 활성화에 관한 법률",
			"제24조",
			"공공데이터는 기계판독이 가능한 형태인 오픈 포맷으로 제공해야 한다.",
			null
		);
		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> filtered = filterByQuestionIntent(
				service,
				List.of(privacyNoise, directEvidence),
				question
			);

			assertThat(filtered).containsExactly(directEvidence);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void filtersCctvRetentionToOfficialGuideRetentionEvidence() throws Exception {
		String question = "영상정보 보관기간은 무조건 30일로 해야 해?";
		LawSemanticChunkRow overviewNoise = chunk(
			1L,
			"official_doc",
			"민간분야 고정형 영상정보처리기기 설치·운영 가이드라인 5차 개정(2024.1)",
			"p.8 개요",
			"공개된 장소와 비공개 장소의 고정형 영상정보처리기기 설치 개요를 설명한다.",
			8
		);
		LawSemanticChunkRow templateNoise = chunk(
			2L,
			"official_doc",
			"1. 공인중개사 개인정보 처리방침 표준(안)",
			"작성 예시",
			"작성 예시. 촬영시간 24시간, 보관기간 촬영일로부터 30일, 보관장소를 적는다.",
			25
		);
		LawSemanticChunkRow retentionEvidence = chunk(
			3L,
			"official_doc",
			"공공기관 고정형 영상정보처리기기 설치·운영 가이드라인 5차 개정(2024.1)",
			"p.38 보관기간",
			"개인영상정보의 보관기간은 설치 목적 달성을 위한 최소한의 기간으로 정하되, 표준지침에 따라 30일 이내로 한다.",
			38
		);
		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> filtered = filterByQuestionIntent(
				service,
				List.of(overviewNoise, templateNoise, retentionEvidence),
				question
			);

			assertThat(filtered).containsExactly(retentionEvidence);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void filtersPublicDataMetadataQuestionToManualMetadataManagementEvidence() throws Exception {
		String question = "공공데이터베이스 표준화에서 메타데이터도 관리해야 해?";
		LawSemanticChunkRow lawFormNoise = chunk(
			1L,
			"law",
			"공공데이터의 제공 및 이용 활성화에 관한 법률 시행규칙",
			"공공데이터 제공 변경ㆍ중단 통보서",
			"공공데이터 제공 변경 또는 중단 통보서 서식의 기관명, 전화번호, 전자우편주소 항목을 설명한다.",
			null
		);
		LawSemanticChunkRow manualEvidence = chunk(
			2L,
			"official_doc",
			"1. 공공데이터베이스 표준화 관리 매뉴얼(2026. 4.)",
			"p.5 산출물 관리",
			"공공데이터베이스 산출물이 새롭게 생성되거나 변경될 경우 기관 메타데이터 관리시스템에 메타정보를 등록하여야 하고 최신성을 유지하여야 한다.",
			5
		);
		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> filtered = filterByQuestionIntent(
				service,
				List.of(lawFormNoise, manualEvidence),
				question
			);

			assertThat(filtered).containsExactly(manualEvidence);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void filtersPublicDataLawUsePromotionToExactLawEvidence() throws Exception {
		String question = "공공데이터법에서 이용 활성화는 어떤 방향이야?";
		LawSemanticChunkRow agencyDirective = chunk(
			1L,
			"admrul",
			"법무부 공공데이터 관리지침",
			"제1조",
			"이 지침은 공공데이터법에 따라 법무부 공공데이터 관리원칙과 기준을 정함을 목적으로 한다.",
			null
		);
		LawSemanticChunkRow privacyGuideNoise = chunk(
			2L,
			"official_doc",
			"공공데이터의 인공지능 친화적 관리 가이드라인",
			"p.56 법률 간 충돌 조정 체계",
			"개인정보 관련 사항과 공공데이터 제공 여부의 이해관계 충돌을 설명한다.",
			56
		);
		LawSemanticChunkRow exactLawEvidence = chunk(
			3L,
			"law",
			"공공데이터의 제공 및 이용 활성화에 관한 법률",
			"제14조",
			"정부는 공공데이터 이용에 대한 국민의 인식을 높이고 이용 활성화를 촉진하기 위하여 공공데이터 이용의 성공사례 발굴ㆍ포상 및 홍보, 포럼 및 세미나 개최 등 필요한 사업을 추진할 수 있다.",
			null
		);
		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> filtered = filterByQuestionIntent(
				service,
				List.of(agencyDirective, privacyGuideNoise, exactLawEvidence),
				question
			);

			assertThat(filtered).containsExactly(exactLawEvidence);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void filtersPublicDataManagementDirectiveToExactManagementSystemArticle() throws Exception {
		String question = "공공데이터 관리지침에서 관리주체와 관리체계는 어디를 봐야 해?";
		LawSemanticChunkRow agencyDirective = chunk(
			1L,
			"admrul",
			"법무부 공공데이터 관리지침",
			"제1조(목적)",
			"이 지침은 공공데이터 관리지침과 공공기관의 데이터베이스 표준화 지침에 따라 법무부 공공데이터 관리원칙을 정한다.",
			null
		);
		LawSemanticChunkRow exactDirective = chunk(
			2L,
			"admrul",
			"공공데이터 관리지침",
			"제5조(관리체계)",
			"제5조 관리체계. 공공기관의 장은 공공데이터제공책임관 및 실무담당자를 임명하고, 업무부서와 담당자는 관리주체로서 각 호의 사항을 수행한다.",
			null
		);
		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> filtered = filterByQuestionIntent(
				service,
				List.of(agencyDirective, exactDirective),
				question
			);

			assertThat(filtered).containsExactly(exactDirective);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void boostsExplicitArticleReferenceEvidenceAboveLooseSameDocumentChunks() throws Exception {
		String question = "감사원 징계 규칙에서 징계위원회 제8조제2항 관련 조항 근거를 알려줘";
		LawSemanticChunkRow looseCommittee = chunk(
			1L,
			"law",
			"감사원 징계 규칙",
			"제11조(심문과 진술권) 등",
			"징계위원회는 출석한 징계등 혐의자에게 심문하고 진술 기회를 주어야 한다.",
			null
		);
		LawSemanticChunkRow articleReference = chunk(
			2L,
			"law",
			"감사원 징계 규칙",
			"제9조(징계의결등의 기한) 등",
			"징계위원회는 제8조제2항에 따른 징계의결등 요구서를 접수한 날부터 30일 이내에 징계의결등을 하여야 한다.",
			null
		);
		Map<String, Double> baseScores = Map.of("law:1", 1.2, "law:2", 0.2);
		LawAiAnswerService service = service();
		try {
			double looseScore = adjustedScore(service, looseCommittee, question, baseScores);
			double articleScore = adjustedScore(service, articleReference, question, baseScores);

			assertThat(articleScore).isGreaterThan(looseScore);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void filtersProjectReviewPreConsultationRelationToBothDirectEvidenceTypes() throws Exception {
		String question = "SNS운영 사업도 과업심의랑 사전협의를 둘 다 받아야 해?";
		LawSemanticChunkRow projectReview = chunk(
			1L,
			"official_doc",
			"공공소프트웨어사업 과업심의 가이드(2022. 12.)",
			"p.5 적용 대상 사업",
			"적용 대상 사업. 국가기관 등이 발주하는 모든 SW사업에 적용하며 소프트웨어의 운영 및 유지관리 등 소프트웨어와 관련된 서비스도 포함한다.",
			5
		);
		LawSemanticChunkRow preConsultation = chunk(
			2L,
			"official_doc",
			"정보화사업 사전협의 안내서",
			"사전협의의 대상사업",
			"사전협의의 대상사업은 대상기관이 추진하는 모든 정보화사업이며 예산과목 및 계약방식과 관계없이 적용한다.",
			10
		);
		LawSemanticChunkRow exampleNoise = chunk(
			3L,
			"official_doc",
			"정보화사업 사전협의 안내서",
			"작성 예시",
			"제안서 평가방법과 기술평가방법 작성 예시를 안내한다.",
			42
		);
		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> filtered = filterByQuestionIntent(
				service,
				List.of(exampleNoise, projectReview, preConsultation),
				question
			);

			assertThat(filtered).contains(projectReview, preConsultation);
			assertThat(filtered).doesNotContain(exampleNoise);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void filtersAutonomyPreConsultationAwayFromInformationSystemNoise() throws Exception {
		String question = "자치분권 사전협의 대상기관은 어디야?";
		LawSemanticChunkRow informationSystemNoise = chunk(
			1L,
			"official_doc",
			"정보화사업 사전협의 안내서",
			"대상사업",
			"정보화사업 사전협의는 대상기관이 추진하는 모든 정보화사업을 대상으로 한다.",
			6
		);
		LawSemanticChunkRow autonomyEvidence = chunk(
			2L,
			"official_doc",
			"자치분권 사전협의 지침(2024년판)",
			"p.8 대상 기관",
			"자치분권 사전협의 대상기관은 법령 제개정 권한이 있는 중앙행정기관이다.",
			8
		);
		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> filtered = filterByQuestionIntent(
				service,
				List.of(informationSystemNoise, autonomyEvidence),
				question
			);

			assertThat(filtered).containsExactly(autonomyEvidence);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void ranksAutonomyPreConsultationTargetBodyBeforeLooseExamplePage() throws Exception {
		String question = "자치분권 사전협의 대상기관은 어디야?";
		LawSemanticChunkRow looseExample = chunk(
			1L,
			"official_doc",
			"자치분권 사전협의 지침(2022년판)",
			"p.7 생 략",
			"자치분권 사전협의 예시. 생략된 설명과 사전협의 대상기관이라는 단어만 포함한다.",
			7
		);
		LawSemanticChunkRow targetBody = chunk(
			2L,
			"official_doc",
			"자치분권 사전협의 지침(2024년판)",
			"p.8 대상 기관",
			"자치분권 사전협의 대상기관은 법령 제개정 권한이 있는 중앙행정기관이며 모든 제개정 법령안을 대상으로 한다.",
			8
		);
		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> filtered = filterByQuestionIntent(
				service,
				List.of(looseExample, targetBody),
				question
			);

			assertThat(filtered).containsExactly(targetBody);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void filtersAutonomyPreConsultationProcedureTocAwayFromProcedureBody() throws Exception {
		String question = "자치분권 사전협의 절차는 어떻게 돼?";
		LawSemanticChunkRow toc = chunk(
			1L,
			"official_doc",
			"자치분권 사전협의 지침(2024년판)",
			"p.2 자치분권 사전협의 지침",
			"목차 Contents. Ⅱ 협의절차 및 검토항목. 1. 법적 근거. 2. 협의절차 및 내용.",
			2
		);
		LawSemanticChunkRow procedureBody = chunk(
			2L,
			"official_doc",
			"자치분권 사전협의 지침(2024년판)",
			"p.19 Ⅱ. 협의절차 및 검토항목",
			"협의절차 전체 흐름도. 사전협의 요청서 작성·제출, 지방자치 관련성 검토, 법령안 검토, 협의결과서 통보 순서로 진행한다.",
			19
		);
		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> filtered = filterByQuestionIntent(
				service,
				List.of(toc, procedureBody),
				question
			);

			assertThat(filtered).containsExactly(procedureBody);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void filtersInformationSystemPreConsultationTargetAwayFromProjectReviewNoise() throws Exception {
		String question = "공공기관 정보화사업도 사전협의를 받아야 하나?";
		LawSemanticChunkRow projectReviewNoise = chunk(
			1L,
			"official_doc",
			"공공소프트웨어사업 과업심의 가이드(2022. 12.)",
			"p.5 적용 대상 사업",
			"국가기관 등이 발주하는 모든 SW사업은 과업심의 대상이 될 수 있다.",
			5
		);
		LawSemanticChunkRow preConsultationTarget = chunk(
			2L,
			"official_doc",
			"2025년 문화체육관광부 정보화사업 사전협의 안내서",
			"p.2 사전협의 대상사업은",
			"사전협의 대상사업은 예산과목 및 계약방식과 관계없이 대상기관이 추진하는 모든 정보화사업에 해당한다.",
			2
		);
		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> filtered = filterByQuestionIntent(
				service,
				List.of(projectReviewNoise, preConsultationTarget),
				question
			);

			assertThat(filtered).containsExactly(preConsultationTarget);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void prefersOfficialPreConsultationGuideOverAgencyRulesForGeneralTargetQuestion() throws Exception {
		String question = "공공기관 정보화사업도 사전협의를 받아야 하나?";
		LawSemanticChunkRow agencyRule = chunk(
			1L,
			"admrul",
			"농촌진흥청 정보화업무처리에 관한 훈령",
			"제10조",
			"대상사업은 예산과목 및 계약방식과 관계없이 추진하는 모든 정보화사업으로 하며 사전협의 대상 사업을 정한다.",
			null
		);
		LawSemanticChunkRow officialGuide = chunk(
			2L,
			"official_doc",
			"2024년 정보화사업 사전협의 안내자료(배포용)",
			"p.28 대상 사업",
			"사전협의의 대상사업은 예산과목 및 계약방식과 관계없이 대상기관이 추진하는 모든 정보화사업이다. 중앙·공공기관 정보화사업도 대상 범위에 포함된다.",
			28
		);
		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> filtered = filterByQuestionIntent(
				service,
				List.of(agencyRule, officialGuide),
				question
			);

			assertThat(filtered).containsExactly(officialGuide);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void prefersNoticeExceptionBodyOverRepealSupplement() throws Exception {
		String question = "면제/예외 인정에 관한 정책지침에서 예외 인정은 무엇을 검토해야 해?";
		LawSemanticChunkRow repealSupplement = chunk(
			1L,
			"admrul",
			"면제/예외 인정에 관한 정책지침",
			"부칙",
			"부칙. 기존 훈령의 폐지. 면제/예외 인정에 관한 정책지침은 이를 폐지한다.",
			null
		);
		LawSemanticChunkRow body = chunk(
			2L,
			"admrul",
			"면제/예외 인정에 관한 정책지침",
			"제4조(처리기준)",
			"소관 과장은 면제/예외 인정을 함에 있어 안전기준, 위험평가, 항공환경적인 상황 등 다음 각 호의 사항을 충분히 검토하여야 한다.",
			null
		);
		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> filtered = filterByQuestionIntent(
				service,
				List.of(repealSupplement, body),
				question
			);
			double repealScore = adjustedScore(service, repealSupplement, question, Map.of("admrul:1", 2.0, "admrul:2", 0.2));
			double bodyScore = adjustedScore(service, body, question, Map.of("admrul:1", 2.0, "admrul:2", 0.2));

			assertThat(filtered).containsExactly(body);
			assertThat(bodyScore).isGreaterThan(repealScore);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void filtersNoticeExceptionRepealSupplementEvenWhenQuestionDoesNotSayGrounds() throws Exception {
		String question = "면제/예외 인정에 관한 정책지침에서 예외 인정은 무엇을 검토해야 해?";
		LawSemanticChunkRow repealSupplement = chunk(
			1L,
			"admrul",
			"면제/예외 인정에 관한 정책지침",
			"부칙",
			"부칙. 제2조(기존 훈령의 폐지) 면제/예외 인정에 관한 정책지침은 이를 폐지한다. 항공안전 의무보고 운영에 관한 규정 등 일괄개정은 발령한 날부터 시행한다.",
			null
		);
		LawSemanticChunkRow body = chunk(
			2L,
			"admrul",
			"면제/예외 인정에 관한 정책지침",
			"제4조(처리기준)",
			"소관 과장은 면제/예외 인정을 함에 있어 다음 각 호의 사항을 충분히 검토하여야 한다. 안전기준, 위험평가, 항공환경적인 상황을 검토한다.",
			null
		);
		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> filtered = filterByQuestionIntent(
				service,
				List.of(repealSupplement, body),
				question
			);

			assertThat(isStrictDocumentEvidenceAnchorQuestion(service, question)).isTrue();
			assertThat(filtered).containsExactly(body);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void boostsExplicitRuleTitleAnchorForLawArticleQuestions() throws Exception {
		String question = "중앙행정기관 및 지방자치단체 자체감사기준에서 해당하여 감사수행 관련 조항 근거를 알려줘";
		LawSemanticChunkRow genericAudit = chunk(
			1L,
			"law",
			"공공감사기준",
			"제1장 총칙",
			"지방자치단체의 자체감사와 감사수행에 관한 일반 기준을 설명한다.",
			null
		);
		LawSemanticChunkRow exactRuleArticle = chunk(
			2L,
			"law",
			"중앙행정기관 및 지방자치단체 자체감사기준",
			"제6조(감사담당자등의 회피 등)",
			"감사담당자등은 다음 각 호의 어느 하나에 해당하여 감사수행의 독립성을 유지하기 어렵다고 판단될 때에는 지체 없이 보고하여야 한다.",
			null
		);
		LawAiAnswerService service = service();
		try {
			double genericScore = adjustedScore(service, genericAudit, question, Map.of("law:1", 2.0, "law:2", 0.1));
			double exactScore = adjustedScore(service, exactRuleArticle, question, Map.of("law:1", 2.0, "law:2", 0.1));

			assertThat(exactScore).isGreaterThan(genericScore);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void filtersRfpRequirementNoiseWhenDirectRequirementEvidenceExists() throws Exception {
		String question = "제안요청서 작성할 때 요구사항 평가요소랑 평가방법도 넣어야 해?";
		LawSemanticChunkRow formNoise = chunk(
			1L,
			"admrul",
			"소프트웨어사업 계약 및 관리감독에 관한 지침 별지",
			"신청자 현황",
			"신청자 현황, 첨부서류, 최근 3개 사업연도 매출액을 작성한다.",
			null
		);
		LawSemanticChunkRow rfpEvidence = chunk(
			2L,
			"official_doc",
			"공공SW사업 제안요청서 작성 가이드",
			"제안요청서 기재사항",
			"제안요청서에는 다음 각 호의 사항을 포함해야 하며 과업내용, 요구사항, 계약조건, 평가요소와 평가방법, 제안서의 규격 및 제출방법을 기재한다.",
			14
		);
		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> filtered = filterByQuestionIntent(
				service,
				List.of(formNoise, rfpEvidence),
				question
			);

			assertThat(filtered).containsExactly(rfpEvidence);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void filtersAiCommitteeNoiseWhenDirectLawEvidenceExists() throws Exception {
		String question = "인공지능위원회는 어떤 기능을 심의 의결해?";
		LawSemanticChunkRow informationCommitteeNoise = chunk(
			1L,
			"admrul",
			"정보화업무 운영 지침",
			"정보화심의위원회",
			"정보화심의위원회는 정보화사업 추진 사항을 심의한다.",
			null
		);
		LawSemanticChunkRow aiCommittee = chunk(
			2L,
			"law",
			"인공지능 발전과 신뢰 기반 조성 등에 관한 기본법",
			"제7조",
			"대통령 소속 국가인공지능전략위원회는 인공지능 기본계획과 주요 정책에 관한 사항을 심의ㆍ의결한다.",
			null
		);
		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> filtered = filterByQuestionIntent(
				service,
				List.of(informationCommitteeNoise, aiCommittee),
				question
			);

			assertThat(filtered).containsExactly(aiCommittee);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void filtersTrafficFacilityNoiseWhenDriverDutyEvidenceExists() throws Exception {
		String question = "우회전할 때 횡단보도 보행자가 있으면 일시정지해야 해?";
		LawSemanticChunkRow facilityNoise = chunk(
			1L,
			"admrul",
			"도로안전시설 설치 및 관리지침",
			"횡단보도 설치기준",
			"횡단보도 표지와 시설물 설치기준, 보도폭 및 시거확보 기준을 정한다.",
			null
		);
		LawSemanticChunkRow driverDuty = chunk(
			2L,
			"law",
			"도로교통법",
			"제27조",
			"모든 차의 운전자는 횡단보도에서 보행자가 횡단하고 있거나 횡단하려고 하는 때에는 횡단보도 앞에서 일시정지하여야 한다.",
			null
		);
		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> filtered = filterByQuestionIntent(
				service,
				List.of(facilityNoise, driverDuty),
				question
			);

			assertThat(filtered).containsExactly(driverDuty);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void filtersPublicDataStandardizationScopeToScopePage() throws Exception {
		String question = "공공데이터포털 표준화 매뉴얼의 표준화 대상과 적용 범위는?";
		LawSemanticChunkRow diagnosisNoise = chunk(
			1L,
			"official_doc",
			"1. 공공데이터베이스 표준화 관리 매뉴얼(2026. 4.)",
			"p.40 진단 항목",
			"예방적 품질관리 진단영역은 4개 영역과 총 9개의 진단항목으로 구성된다.",
			40
		);
		LawSemanticChunkRow scopePage = chunk(
			2L,
			"official_doc",
			"1. 공공데이터베이스 표준화 관리 매뉴얼(2026. 4.)",
			"p.12 표준화 대상 및 적용 범위",
			"표준화 대상 및 적용 범위. 공공기관이 법령 등에서 정하는 목적을 위해 생성 또는 취득하여 관리하는 모든 데이터베이스가 표준화 대상이다. 공공데이터베이스 구축·운영, 메타데이터 등록·관리 등 표준화 업무에 적용한다.",
			12
		);
		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> filtered = filterByQuestionIntent(
				service,
				List.of(diagnosisNoise, scopePage),
				question
			);

			assertThat(filtered).containsExactly(scopePage);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void judgesIntentSpecificEvidenceQuestionsWithExactCandidateText() throws Exception {
		LawAiAnswerService service = service();
		try {
			assertThat(shouldJudgeExactCandidateText(
				service,
				"공공데이터베이스 표준화 매뉴얼의 예방 품질관리 진단 항목은 어떻게 구성돼?"
			)).isTrue();
			assertThat(shouldJudgeExactCandidateText(
				service,
				"개인정보보호위원회 안내서에서 공개된 장소 CCTV 설치 예외는?"
			)).isTrue();
			assertThat(shouldJudgeExactCandidateText(
				service,
				"공공데이터 활용기업 맞춤형지원은 뭘 지원해?"
			)).isTrue();
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void extractsGuideTitleBeforeTopicParticle() throws Exception {
		LawAiAnswerService service = service();
		try {
			assertThat(documentTitleAnchorKeywords(
				service,
				"개인정보 처리 통합 안내서는 왜 만든거야?"
			)).contains("개인정보 처리 통합 안내서");
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void extractsGuidebookCoreTitleAndEvidenceFromSharedReference() throws Exception {
		String query = "행정안전부 재난현장 수습활동 가이드북에서 통합지원본부는 어떤 역할을 해?";
		LawAiAnswerService service = service();
		try {
			assertThat(documentTitleAnchorKeywords(service, query))
				.contains(
					"행정안전부 재난현장 수습활동 가이드북",
					"재난현장 수습활동 가이드북"
				);
			assertThat(documentEvidenceAnchorKeywords(service, query))
				.anyMatch(anchor -> normalize(anchor).contains("통합지원본부"));
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void stripsPossessiveAgencyPrefixFromGuideTitleCore() throws Exception {
		LawAiAnswerService service = service();
		try {
			assertThat(documentTitleAnchorKeywords(
				service,
				"개인정보위의 생성형 AI 개인정보 처리 안내서는 어떤 문서야?"
			)).contains(
				"개인정보위의 생성형 AI 개인정보 처리 안내서",
				"생성형 AI 개인정보 처리 안내서"
			);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void stripsAgencyAndYearTitlePrefixesRegardlessOfOrder() throws Exception {
		String core = "재난현장 수습활동 가이드북";
		LawAiAnswerService service = service();
		try {
			assertThat(documentTitleAnchorKeywords(
				service,
				"행정안전부 2025년 재난현장 수습활동 가이드북에서 역할을 알려줘"
			)).contains(core);
			assertThat(documentTitleAnchorKeywords(
				service,
				"2025년 행정안전부 재난현장 수습활동 가이드북에서 역할을 알려줘"
			)).contains(core);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void stripsShortGovernmentAgencyPrefixFromGuideTitleCore() throws Exception {
		LawAiAnswerService service = service();
		try {
			assertThat(documentTitleAnchorKeywords(
				service,
				"환경부 화학물질 관리 안내서에서 적용 기준을 알려줘"
			)).contains(
				"환경부 화학물질 관리 안내서",
				"화학물질 관리 안내서"
			);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void organizationLikeCommonPrefixDoesNotCreateBroaderTitleCore() throws Exception {
		LawAiAnswerService service = service();
		try {
			assertThat(documentTitleAnchorKeywords(
				service,
				"거래처 개인정보 처리 안내서에서 적용 기준을 알려줘"
			)).doesNotContain("개인정보 처리 안내서");
			assertThat(documentTitleAnchorKeywords(
				service,
				"사업부 개인정보 처리 안내서에서 적용 기준을 알려줘"
			)).doesNotContain("개인정보 처리 안내서");
			assertThat(documentTitleAnchorKeywords(
				service,
				"변경신청 개인정보 처리 안내서에서 적용 기준을 알려줘"
			)).doesNotContain("개인정보 처리 안내서");
			assertThat(documentTitleAnchorKeywords(
				service,
				"데이터센터 개인정보 처리 안내서에서 적용 기준을 알려줘"
			)).doesNotContain("개인정보 처리 안내서");
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void nonAgencyWordEndingInWiDoesNotCreateBroaderTitleCore() throws Exception {
		LawAiAnswerService service = service();
		try {
			assertThat(documentTitleAnchorKeywords(
				service,
				"적용범위 상세 운영 안내서는 무엇이야?"
			))
				.contains("적용범위 상세 운영 안내서")
				.doesNotContain("상세 운영 안내서");
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void ordinaryTopicParticleDoesNotCreateDocumentTitleAnchor() throws Exception {
		LawAiAnswerService service = service();
		try {
			assertThat(documentTitleAnchorKeywords(
				service,
				"개인정보 처리방법은 무엇이야?"
			)).isEmpty();
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void explicitGuideTitleUsesBoundedTitleLookupBeforeBroadText() throws Exception {
		String query = "개인정보 처리 통합 안내서는 왜 만든거야?";
		LawSemanticChunkRow titleHit = chunk(
			2001L,
			"official_doc",
			"★개인정보 처리 통합 안내서(2025.7.)",
			"p.2 발간 목적",
			"개인정보 처리 현장에서 준수해야 하는 사항을 이해하기 쉽도록 안내하기 위해 발간하였다.",
			2
		);
		LawSemanticChunkRow broadNoise = chunk(
			2002L,
			"official_doc",
			"공공소프트웨어사업 안내",
			"개인정보 처리 예시",
			"통합 안내서 작성 예시를 설명한다.",
			9
		);
		java.util.ArrayList<String> calls = new java.util.ArrayList<>();
		java.util.concurrent.atomic.AtomicReference<List<String>> capturedTitles = new java.util.concurrent.atomic.AtomicReference<>();
		RagDocumentMapper mapper = (RagDocumentMapper) java.lang.reflect.Proxy.newProxyInstance(
			RagDocumentMapper.class.getClassLoader(),
			new Class<?>[] {RagDocumentMapper.class},
			(proxy, method, arguments) -> {
				calls.add(method.getName());
				if (List.of(
					"findSemanticChunksByDocumentTitleAndTextScoped",
					"findSemanticChunksByDocumentTitleWithTextHints",
					"findSemanticChunksByDocumentTitleScoped"
				).contains(method.getName())) {
					List<String> titleKeywords = List.copyOf((List<String>) arguments[1]);
					capturedTitles.set(titleKeywords);
					return titleKeywords.contains("개인정보 처리 통합 안내서") ? List.of(titleHit) : List.of();
				}
				if ("findSemanticChunksByText".equals(method.getName())) {
					return List.of(broadNoise);
				}
				return List.of();
			}
		);
		LawAiAnswerService service = service(null, mapper);
		try {
			List<LawSemanticChunkRow> chunks = findLexicalChunks(
				service,
				QuestionSearchPlan.from(query),
				List.of("official_doc"),
				false
			);

			assertThat(capturedTitles.get()).contains("개인정보 처리 통합 안내서");
			assertThat(calls).doesNotContain("findSemanticChunksByText");
			assertThat(chunks).contains(titleHit).doesNotContain(broadNoise);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void intentSpecificTitleLookupPreservesStrictEvidenceAndContinuesScopedSearch() throws Exception {
		String query = "개인정보 처리 통합 안내서에서 개인정보 보유기간이 지나면 언제 파기해야 해?";
		LawSemanticChunkRow strictDirect = chunk(
			2101L,
			"official_doc",
			"★개인정보 처리 통합 안내서(2025.7.)",
			"p.34 보유기간 경과 후 파기",
			"개인정보의 보유기간이 경과하거나 처리 목적이 달성된 경우 지체 없이 그 개인정보를 파기하여야 한다.",
			34
		);
		LawSemanticChunkRow boundedOverview = chunk(
			2102L,
			"official_doc",
			"★개인정보 처리 통합 안내서(2025.7.)",
			"목차",
			"개인정보 처리 통합 안내서 목차",
			1
		);
		java.util.ArrayList<String> calls = new java.util.ArrayList<>();
		java.util.concurrent.atomic.AtomicInteger strictScopedCalls = new java.util.concurrent.atomic.AtomicInteger();
		RagDocumentMapper mapper = (RagDocumentMapper) java.lang.reflect.Proxy.newProxyInstance(
			RagDocumentMapper.class.getClassLoader(),
			new Class<?>[] {RagDocumentMapper.class},
			(proxy, method, arguments) -> {
				calls.add(method.getName());
				if ("findSemanticChunksByDocumentTitleAndTextScoped".equals(method.getName())) {
					return strictScopedCalls.incrementAndGet() == 1 ? List.of(strictDirect) : List.of();
				}
				if ("findSemanticChunksByDocumentTitleWithTextHints".equals(method.getName())) {
					return List.of(boundedOverview);
				}
				if ("findSemanticChunksByDocumentTitleScoped".equals(method.getName())) {
					return List.of();
				}
				if ("findSemanticChunksByText".equals(method.getName())) {
					return List.of();
				}
				return List.of();
			}
		);
		LawAiAnswerService service = service(null, mapper);
		try {
			List<LawSemanticChunkRow> chunks = findLexicalChunks(
				service,
				QuestionSearchPlan.from(query),
				List.of("official_doc"),
				false
			);

			assertThat(calls).contains("findSemanticChunksByDocumentTitleScoped");
			assertThat(chunks).contains(strictDirect, boundedOverview);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void lawTitleOnlyHitDoesNotSuppressLawEvidenceTextFallback() throws Exception {
		String query = "근로기준법에서 연차휴가 부여 의무를 설명해줘";
		LawSemanticChunkRow titleOverview = chunk(
			2201L,
			"law",
			"근로기준법",
			"제1장 총칙",
			"이 법은 근로조건의 기준을 정함을 목적으로 한다.",
			null
		);
		LawSemanticChunkRow directArticle = chunk(
			2202L,
			"law",
			"근로기준법",
			"제60조 연차 유급휴가",
			"사용자는 근로자에게 법정 요건에 따른 연차 유급휴가를 주어야 한다.",
			null
		);
		java.util.ArrayList<String> calls = new java.util.ArrayList<>();
		LawChunkMapper mapper = (LawChunkMapper) java.lang.reflect.Proxy.newProxyInstance(
			LawChunkMapper.class.getClassLoader(),
			new Class<?>[] {LawChunkMapper.class},
			(proxy, method, arguments) -> {
				calls.add(method.getName());
				if ("findSemanticChunksByDocumentTitleAndText".equals(method.getName())) {
					return List.of();
				}
				if ("findSemanticChunksByDocumentTitle".equals(method.getName())) {
					return List.of(titleOverview);
				}
				if ("findSemanticChunksByText".equals(method.getName())) {
					return List.of(directArticle);
				}
				return List.of();
			}
		);
		LawAiAnswerService service = service(mapper);
		try {
			List<LawSemanticChunkRow> chunks = findLexicalChunks(
				service,
				QuestionSearchPlan.from(query),
				List.of("law"),
				false
			);

			assertThat(calls).contains(
				"findSemanticChunksByDocumentTitleAndText",
				"findSemanticChunksByDocumentTitle",
				"findSemanticChunksByText"
			);
			assertThat(chunks).contains(titleOverview, directArticle);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void documentDiscoveryUsesEntityAliasesForBoundedLawHeadingRecall() throws Exception {
		String query = "CCTV 관련 법령";
		LawSemanticChunkRow privacyLaw = chunk(
			2251L,
			"law",
			"개인정보 보호법",
			"제25조 고정형 영상정보처리기기의 설치·운영 제한",
			"누구든지 공개된 장소에 고정형 영상정보처리기기를 설치·운영하여서는 아니 된다.",
			null
		);
		java.util.concurrent.atomic.AtomicReference<List<String>> capturedKeywords =
			new java.util.concurrent.atomic.AtomicReference<>(List.of());
		java.util.ArrayList<String> calls = new java.util.ArrayList<>();
		LawChunkMapper mapper = (LawChunkMapper) java.lang.reflect.Proxy.newProxyInstance(
			LawChunkMapper.class.getClassLoader(),
			new Class<?>[] {LawChunkMapper.class},
			(proxy, method, arguments) -> {
				calls.add(method.getName());
				if ("findSemanticChunksByHeadingOrDocumentTitle".equals(method.getName())) {
					List<String> keywords = List.copyOf((List<String>) arguments[1]);
					capturedKeywords.set(keywords);
					return keywords.stream().anyMatch(keyword -> keyword.contains("고정형 영상정보처리기기"))
						? List.of(privacyLaw)
						: List.of();
				}
				return List.of();
			}
		);
		LawAiAnswerService service = service(mapper);
		try {
			List<LawSemanticChunkRow> chunks = findLexicalChunks(
				service,
				QuestionSearchPlan.from(query),
				List.of("law"),
				false
			);

			assertThat(capturedKeywords.get()).anyMatch(keyword -> keyword.contains("고정형 영상정보처리기기"));
			assertThat(capturedKeywords.get()).hasSizeLessThanOrEqualTo(3);
			assertThat(calls).contains("findSemanticChunksByHeadingOrDocumentTitle");
			assertThat(calls).doesNotContain("findSemanticChunksByText");
			assertThat(chunks).contains(privacyLaw);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void documentDiscoveryWaitsForItsBoundedLexicalHeadingLookup() throws Exception {
		LawAiAnswerService service = service();
		try {
			Method method = LawAiAnswerService.class.getDeclaredMethod(
				"shouldWaitForFocusedLexicalSearch",
				String.class
			);
			method.setAccessible(true);

			assertThat(method.invoke(service, "CCTV 관련 법령")).isEqualTo(true);
			assertThat(method.invoke(service, "근로기준법상 연차휴가 일수는?")).isEqualTo(false);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void strictLawTitleOnlyHitDoesNotSkipEvidenceTextFallback() throws Exception {
		String query = "근로기준법에서 연차휴가 산정일수의 근거 조항을 찾아줘";
		LawSemanticChunkRow titleOverview = chunk(
			2301L,
			"law",
			"근로기준법",
			"제1장 총칙",
			"이 법은 근로조건의 기준을 정함을 목적으로 한다.",
			null
		);
		LawSemanticChunkRow directArticle = chunk(
			2302L,
			"law",
			"근로기준법",
			"제60조 연차 유급휴가",
			"연차 유급휴가의 산정일수와 부여 기준은 제60조에서 정한다.",
			null
		);
		java.util.ArrayList<String> calls = new java.util.ArrayList<>();
		LawChunkMapper mapper = (LawChunkMapper) java.lang.reflect.Proxy.newProxyInstance(
			LawChunkMapper.class.getClassLoader(),
			new Class<?>[] {LawChunkMapper.class},
			(proxy, method, arguments) -> {
				calls.add(method.getName());
				if ("findSemanticChunksByDocumentTitleAndText".equals(method.getName())) {
					return List.of();
				}
				if ("findSemanticChunksByDocumentTitle".equals(method.getName())) {
					return List.of(titleOverview);
				}
				if ("findSemanticChunksByText".equals(method.getName())) {
					return List.of(directArticle);
				}
				return List.of();
			}
		);
		LawAiAnswerService service = service(mapper);
		try {
			List<LawSemanticChunkRow> chunks = findLexicalChunks(
				service,
				QuestionSearchPlan.from(query),
				List.of("law"),
				false
			);

			assertThat(isStrictDocumentEvidenceAnchorQuestion(service, query)).isTrue();
			assertThat(calls).contains("findSemanticChunksByText");
			assertThat(chunks).contains(titleOverview, directArticle);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void ragKeywordPreparationRemovesInternalPeriodSectionType() throws Exception {
		QuestionSearchPlan plan = QuestionSearchPlan.from("공익신고자 보호는 어디까지 가능해?");
		java.util.ArrayList<String> keywords = new java.util.ArrayList<>(plan.focusedKeywords());
		keywords.addAll(plan.lexicalKeywords());
		LawAiAnswerService service = service();
		try {
			List<String> prepared = prepareRagKeywordBatches(service, keywords);
			List<String> temporalPrepared = prepareRagKeywordBatches(
				service,
				List.of("period", "procedure", "평가기간", "보관기간")
			);

			assertThat(plan.profile().preferredSectionTypes()).contains("period");
			assertThat(prepared).doesNotContain("period", "procedure", "target_scope");
			assertThat(prepared).anyMatch(keyword -> keyword.contains("공익신고자") || keyword.contains("신고자 보호"));
			assertThat(temporalPrepared).containsExactly("보관기간", "평가기간");
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void directEvidenceFallbackDoesNotSendInternalSectionTypesToLawSql() throws Exception {
		java.util.concurrent.atomic.AtomicReference<List<String>> captured = new java.util.concurrent.atomic.AtomicReference<>();
		LawChunkMapper mapper = (LawChunkMapper) java.lang.reflect.Proxy.newProxyInstance(
			LawChunkMapper.class.getClassLoader(),
			new Class<?>[] {LawChunkMapper.class},
			(proxy, method, arguments) -> {
				if ("findSemanticChunksByText".equals(method.getName())) {
					captured.set(List.copyOf((List<String>) arguments[1]));
					return List.of();
				}
				throw new UnsupportedOperationException(method.getName());
			}
		);
		LawAiAnswerService service = service(mapper);
		try {
			findDirectEvidenceFallbackChunks(
				service,
				QuestionSearchPlan.from("공익신고자 보호는 어디까지 가능해?"),
				List.of("law"),
				false
			);

			assertThat(captured.get()).isNotNull();
			assertThat(captured.get()).doesNotContain("period", "procedure", "target_scope");
			assertThat(captured.get()).anyMatch(keyword -> keyword.contains("공익신고자") || keyword.contains("신고자 보호"));
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void contractCompletionFallbackUsesMaintainedProcedureConcepts() throws Exception {
		LawSemanticChunkRow inspection = chunk(
			5101L,
			"law",
			"\uAD6D\uAC00\uB97C \uB2F9\uC0AC\uC790\uB85C \uD558\uB294 \uACC4\uC57D\uC5D0 \uAD00\uD55C \uBC95\uB960 \uC2DC\uD589\uB839",
			"\uC81C55\uC870(\uAC80\uC0AC)",
			"\uACC4\uC57D\uC0C1\uB300\uC790\uAC00 \uACC4\uC57D\uC758 \uC774\uD589\uC744 \uC644\uB8CC\uD55C \uD6C4 \uC644\uB8CC\uD1B5\uC9C0\uB97C \uD558\uBA74 \uACC4\uC57D\uB2F4\uB2F9\uACF5\uBB34\uC6D0\uC740 \uAC80\uC0AC\uD558\uC5EC\uC57C \uD55C\uB2E4.",
			null
		);
		LawSemanticChunkRow payment = chunk(
			5102L,
			"admrul",
			"(\uACC4\uC57D\uC608\uADDC) \uC6A9\uC5ED\uACC4\uC57D\uC77C\uBC18\uC870\uAC74",
			"\uC81C27\uC870(\uB300\uAC00\uC758 \uC9C0\uAE09)",
			"\uACC4\uC57D\uC0C1\uB300\uC790\uB294 \uC6A9\uC5ED\uC744 \uC644\uB8CC\uD558\uACE0 \uAC80\uC0AC\uC5D0 \uD569\uACA9\uD55C \uD6C4 \uB300\uAC00\uC9C0\uAE09\uC744 \uCCAD\uAD6C\uD560 \uC218 \uC788\uB2E4.",
			null
		);
		java.util.concurrent.atomic.AtomicReference<List<String>> captured = new java.util.concurrent.atomic.AtomicReference<>();
		LawChunkMapper mapper = (LawChunkMapper) java.lang.reflect.Proxy.newProxyInstance(
			LawChunkMapper.class.getClassLoader(),
			new Class<?>[] {LawChunkMapper.class},
			(proxy, method, arguments) -> {
				if ("findSemanticChunksByText".equals(method.getName())) {
					List<String> keywords = List.copyOf((List<String>) arguments[1]);
					captured.set(keywords);
					if (keywords.contains("\uC644\uB8CC\uD1B5\uC9C0")
						&& keywords.contains("\uAC80\uC0AC")
						&& keywords.contains("\uB300\uAC00\uC758 \uC9C0\uAE09")) {
						return List.of(inspection, payment);
					}
					return List.of();
				}
				throw new UnsupportedOperationException(method.getName());
			}
		);
		LawAiAnswerService service = service(mapper);
		try {
			List<LawSemanticChunkRow> recovered = findDirectEvidenceFallbackChunks(
				service,
				QuestionSearchPlan.from(
					"\uACFC\uC5C5\uC9C0\uC2DC\uC11C \uC6A9\uC5ED\uAE30\uAC04\uC774 \uC548 \uB05D\uB0AC\uB294\uB370 \uACB0\uACFC\uBCF4\uACE0\uD574\uB3C4 \uB418\uB098?"
				),
				List.of("law", "admrul"),
				false
			);

			assertThat(captured.get()).contains(
				"\uC6A9\uC5ED\uACC4\uC57D\uC77C\uBC18\uC870\uAC74",
				"\uC644\uB8CC\uD1B5\uC9C0",
				"\uAC80\uC0AC",
				"\uB300\uAC00\uC758 \uC9C0\uAE09"
			);
			assertThat(recovered).contains(inspection, payment);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void contractCompletionFocusedTermsSearchLawTitlesAndTextBeforeJudge() throws Exception {
		java.util.LinkedHashSet<String> titleKeywords = new java.util.LinkedHashSet<>();
		java.util.LinkedHashSet<String> textKeywords = new java.util.LinkedHashSet<>();
		LawChunkMapper mapper = (LawChunkMapper) java.lang.reflect.Proxy.newProxyInstance(
			LawChunkMapper.class.getClassLoader(),
			new Class<?>[] {LawChunkMapper.class},
			(proxy, method, arguments) -> {
				if ("findSemanticChunksByDocumentTitleAndText".equals(method.getName())) {
					titleKeywords.addAll((List<String>) arguments[1]);
					textKeywords.addAll((List<String>) arguments[2]);
					return List.of();
				}
				return List.of();
			}
		);
		LawAiAnswerService service = service(mapper);
		try {
			findLexicalChunks(
				service,
				QuestionSearchPlan.from(
					"\uACFC\uC5C5\uC9C0\uC2DC\uC11C \uC6A9\uC5ED\uAE30\uAC04\uC774 \uC548 \uB05D\uB0AC\uB294\uB370 \uACB0\uACFC\uBCF4\uACE0\uD574\uB3C4 \uB418\uB098?"
				),
				List.of("law", "admrul"),
				false
			);

			assertThat(titleKeywords).contains(
				"\uC6A9\uC5ED\uACC4\uC57D\uC77C\uBC18\uC870\uAC74",
				"\uAD6D\uAC00\uB97C \uB2F9\uC0AC\uC790\uB85C \uD558\uB294 \uACC4\uC57D\uC5D0 \uAD00\uD55C \uBC95\uB960 \uC2DC\uD589\uB839"
			);
			assertThat(titleKeywords).doesNotContain(
				"\uC6A9\uC5ED\uACC4\uC57D",
				"\uACC4\uC57D\uC0C1\uB300\uC790",
				"\uAC80\uC0AC",
				"\uC9C0\uCCB4\uC0C1\uAE08"
			);
			assertThat(textKeywords).contains(
				"\uC644\uB8CC\uD1B5\uC9C0",
				"\uAC80\uC0AC",
				"\uB300\uAC00\uC758 \uC9C0\uAE09"
			);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void contractCompletionLawLookupPrecedesUnrelatedFastRagMatches() throws Exception {
		LawSemanticChunkRow inspection = chunk(
			5201L,
			"law",
			"\uAD6D\uAC00\uB97C \uB2F9\uC0AC\uC790\uB85C \uD558\uB294 \uACC4\uC57D\uC5D0 \uAD00\uD55C \uBC95\uB960 \uC2DC\uD589\uB839",
			"\uC81C55\uC870(\uAC80\uC0AC)",
			"\uACC4\uC57D\uC0C1\uB300\uC790\uAC00 \uACC4\uC57D\uC758 \uC774\uD589\uC744 \uC644\uB8CC\uD558\uBA74 \uAC80\uC0AC\uB97C \uD558\uC5EC\uC57C \uD55C\uB2E4.",
			null
		);
		LawSemanticChunkRow unrelatedGuide = chunk(
			5202L,
			"official_doc",
			"\uD589\uC815\uC5C5\uBB34\uC6B4\uC601 \uD3B8\uB78C",
			"\uC77C\uBC18 \uACC4\uC57D \uC808\uCC28",
			"\uC77C\uBC18\uC801\uC778 \uACC4\uC57D \uC808\uCC28\uB97C \uC124\uBA85\uD55C\uB2E4.",
			null
		);
		LawChunkMapper lawMapper = org.mockito.Mockito.mock(LawChunkMapper.class);
		RagDocumentMapper ragMapper = org.mockito.Mockito.mock(RagDocumentMapper.class);
		org.mockito.Mockito.when(lawMapper.findSemanticChunksByDocumentTitleAndText(
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyBoolean(),
			org.mockito.ArgumentMatchers.anyInt()
		)).thenReturn(List.of(inspection));
		org.mockito.Mockito.when(ragMapper.findSemanticChunksByText(
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyInt()
		)).thenReturn(List.of(unrelatedGuide));
		LawAiAnswerService service = service(lawMapper, ragMapper);
		try {
			List<LawSemanticChunkRow> recovered = findLexicalChunks(
				service,
				QuestionSearchPlan.from(
					"\uACFC\uC5C5\uC9C0\uC2DC\uC11C \uC6A9\uC5ED\uAE30\uAC04\uC774 \uC548 \uB05D\uB0AC\uB294\uB370 \uACB0\uACFC\uBCF4\uACE0\uD574\uB3C4 \uB418\uB098?"
				),
				List.of("law", "admrul", "official_doc", "internal_doc"),
				false
			);

			assertThat(recovered).contains(inspection);
			org.mockito.Mockito.verify(lawMapper).findSemanticChunksByDocumentTitleAndText(
				org.mockito.ArgumentMatchers.anyList(),
				org.mockito.ArgumentMatchers.anyList(),
				org.mockito.ArgumentMatchers.anyList(),
				org.mockito.ArgumentMatchers.eq(false),
				org.mockito.ArgumentMatchers.anyInt()
			);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void mixedTargetPolicyLawLookupPrecedesUnrelatedFastRagMatches() throws Exception {
		String query = "\uC9C0\uB2A5\uC815\uBCF4\uC0AC\uD68C \uC2E4\uD589\uACC4\uD68D\uC758 \uC608\uBE44\uAC80\uD1A0\uB294 \uC5B4\uB5A4 \uC0AC\uC5C5\uC744 \uB300\uC0C1\uC73C\uB85C \uD558\uB294\uAC70\uC57C?";
		LawSemanticChunkRow directArticle = chunk(
			5251L,
			"admrul",
			"\uC804\uC790\uC815\uBD80 \uC131\uACFC\uAD00\uB9AC \uC9C0\uCE68",
			"\uC81C12\uC870(\uC608\uBE44\uAC80\uD1A0)",
			"\uB2E4\uC74C \uD574\uC5D0 \uC815\uBCF4\uD654\uC0AC\uC5C5\uC744 \uCD94\uC9C4\uD558\uACE0\uC790 \uD558\uB294 \uC911\uC559\uD589\uC815\uAE30\uAD00\uC758 \uC7A5, \uC2DC\uB3C4\uC9C0\uC0AC \uBC0F \uC2DC\uB3C4 \uAD50\uC721\uAC10\uC740 \uC608\uBE44\uAC80\uD1A0\uB97C \uC2E0\uCCAD\uD558\uC5EC\uC57C \uD55C\uB2E4.",
			null
		);
		LawSemanticChunkRow unrelatedGuide = chunk(
			5252L,
			"official_doc",
			"\uC815\uBCF4\uD654\uC0AC\uC5C5 \uC6B4\uC601 \uD3B8\uB78C",
			"\uC2E4\uD589\uACC4\uD68D \uAC1C\uC694",
			"\uC2E4\uD589\uACC4\uD68D\uC758 \uC77C\uBC18\uC801\uC778 \uC791\uC131 \uBC29\uBC95\uC744 \uC124\uBA85\uD55C\uB2E4.",
			null
		);
		LawChunkMapper lawMapper = org.mockito.Mockito.mock(LawChunkMapper.class);
		RagDocumentMapper ragMapper = org.mockito.Mockito.mock(RagDocumentMapper.class);
		org.mockito.Mockito.when(lawMapper.findSemanticChunksByDocumentTitleAndText(
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyBoolean(),
			org.mockito.ArgumentMatchers.anyInt()
		)).thenReturn(List.of(directArticle));
		org.mockito.Mockito.when(ragMapper.findSemanticChunksByText(
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyInt()
		)).thenReturn(List.of(unrelatedGuide));
		LawAiAnswerService service = service(lawMapper, ragMapper);
		try {
			List<LawSemanticChunkRow> recovered = findLexicalChunks(
				service,
				QuestionSearchPlan.from(query),
				List.of("admrul", "official_doc", "internal_doc", "law"),
				false
			);

			assertThat(recovered).contains(directArticle);
			org.mockito.Mockito.verify(lawMapper).findSemanticChunksByDocumentTitleAndText(
				org.mockito.ArgumentMatchers.anyList(),
				org.mockito.ArgumentMatchers.argThat(
					titles -> titles.contains("\uC804\uC790\uC815\uBD80 \uC131\uACFC\uAD00\uB9AC \uC9C0\uCE68")
				),
				org.mockito.ArgumentMatchers.argThat(
					keywords -> keywords.contains("\uB2E4\uC74C \uD574\uC5D0 \uC815\uBCF4\uD654\uC0AC\uC5C5\uC744 \uCD94\uC9C4")
						|| keywords.contains("\uC911\uC559\uD589\uC815\uAE30\uAD00\uC758 \uC7A5")
				),
				org.mockito.ArgumentMatchers.eq(false),
				org.mockito.ArgumentMatchers.anyInt()
			);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void contractCompletionPreservesDirectArticlesFromConfiguredLawDocuments() throws Exception {
		LawSemanticChunkRow inspection = chunk(
			5301L,
			"law",
			"\uAD6D\uAC00\uB97C \uB2F9\uC0AC\uC790\uB85C \uD558\uB294 \uACC4\uC57D\uC5D0 \uAD00\uD55C \uBC95\uB960 \uC2DC\uD589\uB839",
			"\uC81C55\uC870(\uAC80\uC0AC)",
			"\uACC4\uC57D\uC0C1\uB300\uC790\uB85C\uBD80\uD130 \uACC4\uC57D\uC758 \uC774\uD589\uC744 \uC644\uB8CC\uD55C \uC0AC\uC2E4\uC744 \uD1B5\uC9C0\uBC1B\uC740 \uB0A0\uBD80\uD130 \uAC80\uC0AC\uD55C\uB2E4."
		);
		LawSemanticChunkRow payment = chunk(
			5302L,
			"admrul",
			"(\uACC4\uC57D\uC608\uADDC) \uC6A9\uC5ED\uACC4\uC57D\uC77C\uBC18\uC870\uAC74",
			"\uC81C27\uC870(\uB300\uAC00\uC758 \uC9C0\uAE09)",
			"\uACC4\uC57D\uC0C1\uB300\uC790\uB294 \uC6A9\uC5ED\uC744 \uC644\uC131\uD55C \uD6C4 \uAC80\uC0AC\uC5D0 \uD569\uACA9\uD558\uBA74 \uB300\uAC00\uC758 \uC9C0\uAE09\uC744 \uCCAD\uAD6C\uD560 \uC218 \uC788\uB2E4."
		);
		LawSemanticChunkRow unrelated = chunk(
			5303L,
			"official_doc",
			"\uD589\uC815\uC5C5\uBB34\uC6B4\uC601 \uD3B8\uB78C",
			"\uC77C\uBC18 \uACC4\uC57D \uC808\uCC28",
			"\uACC4\uC57D\uC5C5\uBB34\uC758 \uC77C\uBC18\uC801\uC778 \uC808\uCC28\uB97C \uC124\uBA85\uD55C\uB2E4."
		);
		LawAiAnswerService service = service();
		try {
			List<LawSemanticChunkRow> direct = intentDirectEvidenceChunks(
				service,
				List.of(unrelated, inspection, payment),
				"\uACFC\uC5C5\uC9C0\uC2DC\uC11C \uC6A9\uC5ED\uAE30\uAC04\uC774 \uC548 \uB05D\uB0AC\uB294\uB370 \uACB0\uACFC\uBCF4\uACE0\uD574\uB3C4 \uB418\uB098?"
			);

			assertThat(direct).contains(inspection, payment);
			assertThat(direct).doesNotContain(unrelated);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void contractCompletionPreserveUsesScoresWithinConfiguredDocuments() throws Exception {
		String decree = "\uAD6D\uAC00\uB97C \uB2F9\uC0AC\uC790\uB85C \uD558\uB294 \uACC4\uC57D\uC5D0 \uAD00\uD55C \uBC95\uB960 \uC2DC\uD589\uB839";
		String conditions = "(\uACC4\uC57D\uC608\uADDC) \uC6A9\uC5ED\uACC4\uC57D\uC77C\uBC18\uC870\uAC74";
		LawSemanticChunkRow lowerProgress = chunk(
			5401L,
			"admrul",
			conditions,
			"\uC81C26\uC870(\uAE30\uC131\uB300\uAC00\uC758 \uC9C0\uAE09)",
			"\uACC4\uC57D\uC0C1\uB300\uC790\uB294 \uAC80\uC0AC\uC5D0 \uD569\uACA9\uD55C \uAE30\uC131\uBD80\uBD84\uC758 \uB300\uAC00\uB97C \uCCAD\uAD6C\uD560 \uC218 \uC788\uB2E4."
		);
		LawSemanticChunkRow lowerPayment = chunk(
			5402L,
			"law",
			decree,
			"\uC81C58\uC870(\uB300\uAC00\uC758 \uC9C0\uAE09)",
			"\uACC4\uC57D\uC0C1\uB300\uC790\uAC00 \uAC80\uC0AC\uC5D0 \uD569\uACA9\uD55C \uD6C4 \uB300\uAC00\uB97C \uC9C0\uAE09\uD55C\uB2E4."
		);
		LawSemanticChunkRow inspection = chunk(
			5404L,
			"law",
			decree,
			"\uC81C55\uC870(\uAC80\uC0AC)",
			"\uACC4\uC57D\uC0C1\uB300\uC790\uB85C\uBD80\uD130 \uACC4\uC57D\uC758 \uC774\uD589\uC744 \uC644\uB8CC\uD55C \uC0AC\uC2E4\uC744 \uD1B5\uC9C0\uBC1B\uC740 \uB0A0\uBD80\uD130 \uAC80\uC0AC\uD55C\uB2E4."
		);
		LawSemanticChunkRow payment = chunk(
			5405L,
			"admrul",
			conditions,
			"\uC81C27\uC870(\uB300\uAC00\uC758 \uC9C0\uAE09)",
			"\uACC4\uC57D\uC0C1\uB300\uC790\uB294 \uC6A9\uC5ED\uC744 \uC644\uC131\uD55C \uD6C4 \uAC80\uC0AC\uC5D0 \uD569\uACA9\uD558\uBA74 \uB300\uAC00\uC758 \uC9C0\uAE09\uC744 \uCCAD\uAD6C\uD560 \uC218 \uC788\uB2E4."
		);
		LawSemanticChunkRow scatteredTerms = chunk(
			5406L,
			"admrul",
			conditions,
			"\uC81C61\uC870(\uACC4\uC57D\uC815\uBCF4 \uACF5\uAC1C)",
			"\uACC4\uC57D\uB2F4\uB2F9\uACF5\uBB34\uC6D0\uC740 \uACC4\uC57D\uC0C1\uB300\uC790\uC758 \uC774\uD589\uC644\uB8CC \uBC0F \uB300\uAC00\uC758 \uC9C0\uAE09 \uC815\uBCF4\uB97C \uD648\uD398\uC774\uC9C0\uC5D0 \uACF5\uAC1C\uD55C\uB2E4."
		);
		LawSemanticChunkRow paymentDelay = chunk(
			5407L,
			"law",
			decree,
			"\uC81C59\uC870(\uB300\uAC00\uC9C0\uAE09\uC9C0\uC5F0\uC5D0 \uB300\uD55C \uC774\uC790)",
			"\uACC4\uC57D\uB2F4\uB2F9\uACF5\uBB34\uC6D0\uC740 \uB300\uAC00\uC9C0\uAE09\uAE30\uD55C\uAE4C\uC9C0 \uB300\uAC00\uB97C \uC9C0\uAE09\uD558\uC9C0 \uBABB\uD558\uBA74 \uC9C0\uC5F0\uC774\uC790\uB97C \uC9C0\uAE09\uD574\uC57C \uD55C\uB2E4."
		);
		LawSemanticChunkRow unrelated = chunk(
			5403L,
			"official_doc",
			"\uD589\uC815\uC5C5\uBB34\uC6B4\uC601 \uD3B8\uB78C",
			"\uC77C\uBC18 \uACC4\uC57D \uC808\uCC28",
			"\uACC4\uC57D\uC5C5\uBB34\uC758 \uC77C\uBC18\uC801\uC778 \uC808\uCC28\uB97C \uC124\uBA85\uD55C\uB2E4."
		);
		EvidenceJudge.Result initiallyJudged = result(
			List.of(scatteredTerms, unrelated),
			true,
			true,
			true,
			true,
			1,
			1,
			1,
			"direct"
		);
		Map<String, Double> combinedScores = Map.of(
			"admrul:5401", 5.0,
			"law:5402", 4.0,
			"law:5404", 10.0,
			"admrul:5405", 9.0,
			"admrul:5406", 100.0,
			"law:5407", 200.0
		);
		LawAiAnswerService service = service();
		try {
			EvidenceJudge.Result preserved = preserveIntentDirectEvidenceChunks(
				service,
				initiallyJudged,
				List.of(lowerProgress, lowerPayment, unrelated, inspection, payment, scatteredTerms, paymentDelay),
				"\uACFC\uC5C5\uC9C0\uC2DC\uC11C \uC6A9\uC5ED\uAE30\uAC04\uC774 \uC548 \uB05D\uB0AC\uB294\uB370 \uACB0\uACFC\uBCF4\uACE0\uD574\uB3C4 \uB418\uB098?",
				combinedScores
			);

			assertThat(preserved.chunks()).contains(inspection, payment);
			assertThat(preserved.chunks()).startsWith(inspection, payment);
			assertThat(preserved.chunks()).doesNotContain(lowerProgress, lowerPayment);
			assertThat(preserved.chunks()).doesNotContain(scatteredTerms);
			assertThat(preserved.chunks()).doesNotContain(paymentDelay);
			assertThat(preserved.chunks()).doesNotContain(unrelated);
		} finally {
			service.shutdownExecutors();
		}
	}

	private String rejectionReason(String question, EvidenceJudge.Result result) throws Exception {
		LawAiAnswerService service = service();
		try {
			return service.weakEvidenceRejectionReason(QuestionSearchPlan.from(question), result);
		} finally {
			service.shutdownExecutors();
		}
	}

	@SuppressWarnings("unchecked")
	private List<String> queryTerms(LawAiAnswerService service, String query) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod("queryTerms", String.class);
		method.setAccessible(true);
		return (List<String>) method.invoke(service, query);
	}

	@SuppressWarnings("unchecked")
	private List<String> prepareRagKeywordBatches(LawAiAnswerService service, List<String> keywords) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod("prepareRagKeywordBatches", List.class);
		method.setAccessible(true);
		return (List<String>) method.invoke(service, keywords);
	}

	@SuppressWarnings("unchecked")
	private List<String> documentTitleAnchorKeywords(LawAiAnswerService service, String query) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod("documentTitleAnchorKeywords", String.class);
		method.setAccessible(true);
		return (List<String>) method.invoke(service, query);
	}

	@SuppressWarnings("unchecked")
	private List<String> documentEvidenceAnchorKeywords(LawAiAnswerService service, String query) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod("documentEvidenceAnchorKeywords", String.class);
		method.setAccessible(true);
		return (List<String>) method.invoke(service, query);
	}

	@SuppressWarnings("unchecked")
	private List<LawSemanticChunkRow> findLexicalChunks(
		LawAiAnswerService service,
		QuestionSearchPlan plan,
		List<String> targets,
		boolean includeFuture
	) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod(
			"findLexicalChunks",
			QuestionSearchPlan.class,
			List.class,
			boolean.class
		);
		method.setAccessible(true);
		return (List<LawSemanticChunkRow>) method.invoke(service, plan, targets, includeFuture);
	}

	@SuppressWarnings("unchecked")
	private List<LawSemanticChunkRow> findDirectEvidenceFallbackChunks(
		LawAiAnswerService service,
		QuestionSearchPlan plan,
		List<String> targets,
		boolean includeFuture
	) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod(
			"findDirectEvidenceFallbackChunks",
			QuestionSearchPlan.class,
			List.class,
			boolean.class
		);
		method.setAccessible(true);
		return (List<LawSemanticChunkRow>) method.invoke(service, plan, targets, includeFuture);
	}

	@SuppressWarnings("unchecked")
	private List<LawSemanticChunkRow> intentDirectEvidenceChunks(
		LawAiAnswerService service,
		List<LawSemanticChunkRow> chunks,
		String query
	) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod(
			"intentDirectEvidenceChunks",
			List.class,
			String.class
		);
		method.setAccessible(true);
		return (List<LawSemanticChunkRow>) method.invoke(service, chunks, query);
	}

	@SuppressWarnings("unchecked")
	private List<String> requiredExactTermsForQuery(
		LawAiAnswerService service,
		String query,
		List<String> terms
	) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod(
			"requiredExactTermsForQuery",
			String.class,
			List.class
		);
		method.setAccessible(true);
		return (List<String>) method.invoke(service, query, terms);
	}

	private LawAiAnswerService service() {
		return service(null);
	}

	private LawAiAnswerService service(LawChunkMapper lawChunkMapper) {
		return service(lawChunkMapper, null);
	}

	private LawAiAnswerService service(LawChunkMapper lawChunkMapper, RagDocumentMapper ragDocumentMapper) {
		return new LawAiAnswerService(
			lawChunkMapper,
			ragDocumentMapper,
			null,
			null,
			null,
			new EvidenceJudge(),
			new AnswerGuard(),
			new ClaimVerifier(),
			new AnswerVerificationService(new AnswerGuard(), new ClaimVerifier()),
			new ParentContextAssembler(),
			new EvidenceCandidateDiversifier(),
			new FailureLoggingService(null),
			null,
			new LawAiProperties(null, null, null, null)
		);
	}

	@Test
	void lexicalSearchMergesExactAndLegacyResultsUntilTheExactIndexIsReady() throws Exception {
		RagDocumentMapper mapper = org.mockito.Mockito.mock(RagDocumentMapper.class);
		RagChunkSearchIndexService indexService = org.mockito.Mockito.mock(RagChunkSearchIndexService.class);
		org.mockito.Mockito.when(indexService.isReady()).thenReturn(false, true);
		LawSemanticChunkRow exact = chunk(1L, "official_doc", "정확 인덱스", "정의", "개인정보 정의");
		LawSemanticChunkRow legacy = chunk(2L, "official_doc", "기존 검색", "사례", "이메일 사례");
		org.mockito.Mockito.when(mapper.findSemanticChunksByText(
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyInt()
		)).thenReturn(List.of(exact));
		org.mockito.Mockito.when(mapper.findSemanticChunksByLegacyText(
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyInt()
		)).thenReturn(List.of(legacy));
		LawAiAnswerService service = service(mapper, indexService);
		try {
			List<LawSemanticChunkRow> building = queryRagLexicalChunks(
				service,
				List.of("official_doc"),
				List.of("privacy"),
				10
			);
			List<LawSemanticChunkRow> ready = queryRagLexicalChunks(
				service,
				List.of("official_doc"),
				List.of("privacy"),
				10
			);

			org.mockito.Mockito.verify(mapper).findSemanticChunksByLegacyText(
				List.of("official_doc"),
				List.of("privacy"),
				10
			);
			org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(2)).findSemanticChunksByText(
				List.of("official_doc"),
				List.of("privacy"),
				10
			);
			assertThat(building).containsExactly(exact, legacy);
			assertThat(ready).containsExactly(exact);
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void lexicalFailureRetriesEveryPreparedCoreTermSeparately() throws Exception {
		RagDocumentMapper mapper = org.mockito.Mockito.mock(RagDocumentMapper.class);
		RagChunkSearchIndexService indexService = org.mockito.Mockito.mock(RagChunkSearchIndexService.class);
		org.mockito.Mockito.when(indexService.isReady()).thenReturn(true);
		org.mockito.Mockito.when(mapper.findSemanticChunksByHeadingText(
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyInt()
		)).thenReturn(List.of());
		org.mockito.Mockito.when(mapper.findSemanticChunksByText(
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyInt()
		)).thenThrow(new IllegalStateException("timeout")).thenReturn(List.of());
		LawAiAnswerService service = service(mapper, indexService);
		try {
			findRagChunksByText(service, List.of("official_doc"), List.of("email", "privacy"), 20);

			@SuppressWarnings("unchecked")
			ArgumentCaptor<List<String>> keywords = ArgumentCaptor.forClass(List.class);
			org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(3)).findSemanticChunksByText(
				org.mockito.ArgumentMatchers.anyList(),
				keywords.capture(),
				org.mockito.ArgumentMatchers.anyInt()
			);
			assertThat(keywords.getAllValues())
				.containsExactly(List.of("privacy", "email"), List.of("privacy"), List.of("email"));
		} finally {
			service.shutdownExecutors();
		}
	}

	@Test
	void directRequiredResultWithNoDirectEvidenceCannotBePromotedByIntentHeuristics() throws Exception {
		LawSemanticChunkRow conceptOnly = chunk(
			901L,
			"official_doc",
			"개인정보 정의 안내서",
			"개인정보의 정의",
			"개인정보는 살아 있는 개인에 관한 정보입니다."
		);
		EvidenceJudge.Result judged = result(
			List.of(conceptOnly),
			true,
			false,
			true,
			true,
			1,
			1,
			0,
			"concept_relevant"
		);
		LawAiAnswerService service = service();
		try {
			EvidenceJudge.Result preserved = preserveIntentDirectEvidenceChunks(
				service,
				judged,
				List.of(conceptOnly),
				"이메일 만으로도 개인정보라고 볼 수 있나?"
			);

			assertThat(preserved).isSameAs(judged);
			assertThat(preserved.directEvidenceCount()).isZero();
			assertThat(preserved.selectionPolicy()).isEqualTo("concept_relevant");
		} finally {
			service.shutdownExecutors();
		}
	}

	@SuppressWarnings("unchecked")
	private List<LawSemanticChunkRow> queryRagLexicalChunks(
		LawAiAnswerService service,
		List<String> targets,
		List<String> keywords,
		int limit
	) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod(
			"queryRagLexicalChunks",
			List.class,
			List.class,
			int.class
		);
		method.setAccessible(true);
		return (List<LawSemanticChunkRow>) method.invoke(service, targets, keywords, limit);
	}

	@SuppressWarnings("unchecked")
	private List<LawSemanticChunkRow> findRagChunksByText(
		LawAiAnswerService service,
		List<String> targets,
		List<String> keywords,
		int limit
	) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod(
			"findRagChunksByText",
			List.class,
			List.class,
			int.class
		);
		method.setAccessible(true);
		return (List<LawSemanticChunkRow>) method.invoke(service, targets, keywords, limit);
	}

	private EvidenceJudge.Result preserveIntentDirectEvidenceChunks(
		LawAiAnswerService service,
		EvidenceJudge.Result judged,
		List<LawSemanticChunkRow> chunks,
		String query
	) throws Exception {
		return preserveIntentDirectEvidenceChunks(service, judged, chunks, query, Map.of());
	}

	private EvidenceJudge.Result preserveCompleteProcedureEvidenceChunks(
		LawAiAnswerService service,
		EvidenceJudge.Result judged,
		List<LawSemanticChunkRow> chunks,
		String query
	) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod(
			"preserveCompleteProcedureEvidenceChunks",
			EvidenceJudge.Result.class,
			List.class,
			String.class,
			Map.class
		);
		method.setAccessible(true);
		return (EvidenceJudge.Result) method.invoke(service, judged, chunks, query, Map.of());
	}

	private EvidenceJudge.Result preserveIntentDirectEvidenceChunks(
		LawAiAnswerService service,
		EvidenceJudge.Result judged,
		List<LawSemanticChunkRow> chunks,
		String query,
		Map<String, Double> combinedScores
	) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod(
			"preserveIntentDirectEvidenceChunks",
			EvidenceJudge.Result.class,
			List.class,
			String.class,
			Map.class,
			Map.class
		);
		method.setAccessible(true);
		return (EvidenceJudge.Result) method.invoke(
			service,
			judged,
			chunks,
			query,
			Map.of(),
			combinedScores
		);
	}

	private LawAiAnswerService service(
		RagDocumentMapper ragDocumentMapper,
		RagChunkSearchIndexService indexService
	) {
		return new LawAiAnswerService(
			null,
			ragDocumentMapper,
			null,
			null,
			null,
			new EvidenceJudge(),
			new AnswerGuard(),
			new ClaimVerifier(),
			new AnswerVerificationService(new AnswerGuard(), new ClaimVerifier()),
			new ParentContextAssembler(),
			new EvidenceCandidateDiversifier(),
			new FailureLoggingService(null),
			null,
			new LawAiProperties(null, null, null, null),
			null,
			indexService
		);
	}

	private double adjustedScore(
		LawAiAnswerService service,
		LawSemanticChunkRow chunk,
		String question,
		Map<String, Double> baseScores
	) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod(
			"adjustedScore",
			LawSemanticChunkRow.class,
			String.class,
			List.class,
			Map.class
		);
		method.setAccessible(true);
		return (double) method.invoke(
			service,
			chunk,
			question,
			QuestionSearchPlan.from(question).lexicalKeywords(),
			baseScores
		);
	}

	@SuppressWarnings("unchecked")
	private List<LawSemanticChunkRow> filterByQuestionIntent(
		LawAiAnswerService service,
		List<LawSemanticChunkRow> chunks,
		String query
	) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod(
			"filterByQuestionIntent",
			List.class,
			String.class
		);
		method.setAccessible(true);
		return (List<LawSemanticChunkRow>) method.invoke(service, chunks, query);
	}

	@SuppressWarnings("unchecked")
	private List<LawSemanticChunkRow> selectAnswerContextChunks(
		LawAiAnswerService service,
		List<LawSemanticChunkRow> chunks,
		String query
	) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod(
			"selectAnswerContextChunks",
			List.class,
			String.class
		);
		method.setAccessible(true);
		return (List<LawSemanticChunkRow>) method.invoke(service, chunks, query);
	}

	private String snippet(LawAiAnswerService service, LawSemanticChunkRow chunk, String query) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod(
			"snippet",
			LawSemanticChunkRow.class,
			String.class
		);
		method.setAccessible(true);
		return (String) method.invoke(service, chunk, query);
	}

	private String contextSnippet(
		LawAiAnswerService service,
		LawSemanticChunkRow chunk,
		String query,
		int limit
	) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod(
			"contextSnippet",
			LawSemanticChunkRow.class,
			String.class,
			int.class
		);
		method.setAccessible(true);
		return (String) method.invoke(service, chunk, query, limit);
	}

	private String answerFocusInstruction(LawAiAnswerService service, String query) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod("answerFocusInstruction", String.class);
		method.setAccessible(true);
		return (String) method.invoke(service, query);
	}

	private boolean shouldJudgeExactCandidateText(LawAiAnswerService service, String query) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod("shouldJudgeExactCandidateText", String.class);
		method.setAccessible(true);
		return (boolean) method.invoke(service, query);
	}

	private boolean isStrictDocumentEvidenceAnchorQuestion(LawAiAnswerService service, String query) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod(
			"isStrictDocumentEvidenceAnchorQuestion",
			String.class,
			String.class
		);
		method.setAccessible(true);
		return (boolean) method.invoke(service, query, normalize(query));
	}

	private String normalize(String value) {
		if (value == null) {
			return "";
		}
		return value
			.toLowerCase(java.util.Locale.ROOT)
			.replaceAll("[^\\p{IsHangul}a-z0-9]", "");
	}

	private LawSemanticChunkRow chunk(long chunkId, String target, String title, String chunkNo, String text) {
		return chunk(chunkId, target, title, chunkNo, text, null);
	}

	private LawSemanticChunkRow chunk(long chunkId, String target, String title, String chunkNo, String text, Integer pageNo) {
		return new LawSemanticChunkRow(
			chunkId,
			chunkId,
			target,
			String.valueOf(chunkId),
			title,
			"",
			"",
			"20260101",
			"CURRENT",
			chunkNo,
			chunkNo,
			text,
			pageNo,
			"",
			"",
			1,
			"hash" + chunkId,
			chunkNo,
			"requirement"
		);
	}

	private EvidenceJudge.Result result(
		boolean directEvidenceRequired,
		boolean directEvidenceFound,
		boolean conceptEvidenceRequired,
		boolean conceptEvidenceFound,
		int topicAlignedCount,
		int relevantCount,
		int directEvidenceCount,
		String selectionPolicy
	) {
		return result(
			List.of(),
			directEvidenceRequired,
			directEvidenceFound,
			conceptEvidenceRequired,
			conceptEvidenceFound,
			topicAlignedCount,
			relevantCount,
			directEvidenceCount,
			selectionPolicy
		);
	}

	private EvidenceJudge.Result result(
		List<LawSemanticChunkRow> chunks,
		boolean directEvidenceRequired,
		boolean directEvidenceFound,
		boolean conceptEvidenceRequired,
		boolean conceptEvidenceFound,
		int topicAlignedCount,
		int relevantCount,
		int directEvidenceCount,
		String selectionPolicy
	) {
		return new EvidenceJudge.Result(
			chunks,
			Map.of(),
			directEvidenceRequired,
			directEvidenceFound,
			conceptEvidenceRequired,
			conceptEvidenceFound,
			topicAlignedCount,
			relevantCount,
			directEvidenceCount,
			selectionPolicy
		);
	}
}
