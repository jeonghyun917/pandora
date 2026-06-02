package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceJudgeTests {

	// 메소드 설명: EvidenceJudge 처리 흐름을 수행합니다.
	private final EvidenceJudge judge = new EvidenceJudge();

	@Test
	// 메소드 설명: promotesDirectHardwareExclusionEvidenceOverLooseSoftwareMatches 처리 흐름을 수행합니다.
	void promotesDirectHardwareExclusionEvidenceOverLooseSoftwareMatches() {
		LawSemanticChunkRow looseMatch = chunk(
			1,
			"상용소프트웨어 직접구매 가이드",
			"상용소프트웨어 직접구매 대상 소프트웨어와 구매 절차를 설명한다."
		);
		LawSemanticChunkRow directEvidence = chunk(
			2,
			"공공소프트웨어사업 과업심의 가이드",
			"단순 H/W(Appliance 포함) 도입 설치, 네트워크 등 인프라 수수료와 같이 소프트웨어사업으로 볼 수 없는 경우는 비대상이다."
		);
		LawSemanticChunkRow supportingEvidence = chunk(
			3,
			"공공SW사업 법제도 관리감독 및 지원 가이드",
			"국가기관등의 장이 발주하는 소프트웨어사업 중 단순 H/W 도입 설치는 소프트웨어사업으로 볼 수 없는 경우로 비대상이다."
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		EvidenceJudge.Result result = judge.judge(
			"공공소프트웨어사업에서 단순 하드웨어 구매는 소프트웨어사업에 포함되나요?",
			List.of(looseMatch, directEvidence, supportingEvidence),
			Map.of("official_doc:1", 0.9, "official_doc:2", 0.3, "official_doc:3", 0.4),
			8
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(result.chunks().get(0)).isIn(directEvidence, supportingEvidence);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(result.chunks()).doesNotContain(looseMatch);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(result.scoreByChunkId().get("official_doc:2"))
			.isGreaterThan(result.scoreByChunkId().get("official_doc:1"));
	}

	@Test
	// 메소드 설명: promotesRfpRequiredItemsEvidence 처리 흐름을 수행합니다.
	void promotesRfpRequiredItemsEvidence() {
		LawSemanticChunkRow requiredItems = chunk(
			1,
			"공공정보화사업 유형별 제안요청서 작성 가이드",
			"제안요청서에는 다음 각 호의 사항을 명시하여야 한다. 1. 과업내용, 요구사항 2. 계약조건 3. 평가요소, 평가방법"
		);
		LawSemanticChunkRow submissionSchedule = chunk(
			2,
			"공공정보화사업 유형별 제안요청서 작성 가이드",
			"입찰에 참여하는 제안사가 제출하여야 하는 각종 서류와 제출일정 및 제출방법을 기재한다."
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		EvidenceJudge.Result result = judge.judge(
			"공공기관 제안요청서 작성할때 필수요소가 있나?",
			List.of(submissionSchedule, requiredItems),
			Map.of("official_doc:1", 0.4, "official_doc:2", 0.7),
			8
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(result.chunks()).first().isEqualTo(requiredItems);
	}

	@Test
	// 메소드 설명: keepsOnlyDirectTargetEvidenceWhenTargetQuestionHasAnswerLikeChunks 처리 흐름을 수행합니다.
	void keepsOnlyDirectTargetEvidenceWhenTargetQuestionHasAnswerLikeChunks() {
		LawSemanticChunkRow directTarget = chunk(
			1,
			"공공소프트웨어사업 과업심의 가이드",
			"적용 대상 사업은 국가기관 등이 발주하는 모든 SW사업이다."
		);
		LawSemanticChunkRow adjacentPage = chunk(
			2,
			"공공소프트웨어사업 과업심의 가이드",
			"위원의 직접 이해관계 해석범위와 대상 사업 예시를 설명한다."
		);
		LawSemanticChunkRow wrongConcept = chunk(
			3,
			"공공SW사업 법제도 관리감독 및 지원 가이드",
			"SW영향평가 적용 대상 사업과 적용 제외 사업을 설명한다."
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		EvidenceJudge.Result result = judge.judge(
			"과업심의 대상은?",
			List.of(adjacentPage, wrongConcept, directTarget),
			Map.of("official_doc:1", 0.4, "official_doc:2", 0.9, "official_doc:3", 0.95),
			8
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(result.chunks()).containsExactly(directTarget);
	}

	@Test
	// 메소드 설명: targetQuestionPrefersScopeEvidenceOverReviewItemEvidence 처리 흐름을 수행합니다.
	void targetQuestionPrefersScopeEvidenceOverReviewItemEvidence() {
		LawSemanticChunkRow directTarget = chunk(
			1,
			"공공SW사업 법제도 관리감독 및 지원 가이드",
			"대상사업 : 국가기관등의 장이 발주하는 소프트웨어사업. 대상사업은 SW개발, 제작, 생산, 유통, 운영 및 유지관리 등과 소프트웨어와 관련된 서비스를 포함한다."
		);
		LawSemanticChunkRow reviewItems = chunk(
			2,
			"공공소프트웨어사업 과업심의 가이드",
			"과업내용의 적정성 검토, 과업내용과 비용 산정의 적정성, 적정사업기간의 산정, SW영향평가의 재평가를 심의한다."
		);
		LawSemanticChunkRow exceptionOnly = chunk(
			3,
			"공공소프트웨어사업 과업심의 가이드",
			"과업심의 대상 - 총 사업금액 1억 이하, 상용SW 또는 타기관 표준 정보시스템 구매사업, 기존 과업심의를 거쳤던 사업."
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		EvidenceJudge.Result result = judge.judge(
			"과업심의 대상은?",
			List.of(reviewItems, exceptionOnly, directTarget),
			Map.of("official_doc:1", 0.4, "official_doc:2", 0.95, "official_doc:3", 0.9),
			8
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(result.chunks()).containsExactly(directTarget);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(result.chunks()).doesNotContain(reviewItems, exceptionOnly);
	}

	@Test
	// 메소드 설명: keepsOnlyDirectPreConsultationTargetEvidence 처리 흐름을 수행합니다.
	void keepsOnlyDirectPreConsultationTargetEvidence() {
		LawSemanticChunkRow directTarget = chunk(
			1,
			"2024년 정보화사업 사전협의 안내자료",
			"사전협의의 대상사업은 예산과목 및 계약방식과 관계없이 대상기관이 추진하는 모든 정보화사업이다."
		);
		LawSemanticChunkRow adjacentPage = chunk(
			2,
			"사전협의 사전협의 개요",
			"대상시스템을 연속성 있게 성과관리하기 위하여 사업명칭 부여 기준을 제시한다."
		);
		LawSemanticChunkRow procedurePage = chunk(
			3,
			"사전협의 사전협의 개요",
			"유사 시스템 식별 단계에서 대상사업의 행정서비스 업무기능과 데이터 수요자 측면을 검색한다."
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		EvidenceJudge.Result result = judge.judge(
			"기타공공기관 사전협의 대상 알려줘",
			List.of(adjacentPage, procedurePage, directTarget),
			Map.of("official_doc:1", 0.4, "official_doc:2", 0.9, "official_doc:3", 0.8),
			8
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(result.chunks()).containsExactly(directTarget);
	}

	@Test
	// 메소드 설명: securityReviewTargetQuestionRejectsGenericSystemTargetEvidence 처리 흐름을 수행합니다.
	void securityReviewTargetQuestionRejectsGenericSystemTargetEvidence() {
		LawSemanticChunkRow directTarget = chunk(
			1,
			"(붙임2) 2026년 정보화사업 보안성 검토 가이드",
			"대상 사업 및 시기. 국가정보원 검토 대상은 비밀 대외비를 유통 관리하기 위한 정보통신망 또는 정보시스템 구축, 주요 데이터베이스 구축 등이다."
		);
		LawSemanticChunkRow genericDbManual = chunk(
			2,
			"공공데이터베이스 표준화 관리 매뉴얼",
			"관리대상 DB와 폐기 대상 DB, 정보시스템 DB 적용 제외 기준을 설명한다."
		);
		LawSemanticChunkRow securityItemOnly = chunk(
			3,
			"(붙임2) 2026년 정보화사업 보안성 검토 가이드",
			"AI모델 대상 적대적 모의공격 수행, AI시스템 통신구간 보호 등 보안위협 예시와 대책을 설명한다."
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		EvidenceJudge.Result result = judge.judge(
			"보안성검토 대상 시스템은?",
			List.of(genericDbManual, securityItemOnly, directTarget),
			Map.of("official_doc:1", 0.4, "official_doc:2", 0.95, "official_doc:3", 0.9),
			8
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(result.chunks()).containsExactly(directTarget);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(result.chunks()).doesNotContain(genericDbManual, securityItemOnly);
	}

	@Test
	// 메소드 설명: securityReviewTargetQuestionRejectsAdministrativeEntryInstructions 처리 흐름을 수행합니다.
	void securityReviewTargetQuestionRejectsAdministrativeEntryInstructions() {
		LawSemanticChunkRow directTarget = chunk(
			1,
			"국가정보보안기본지침",
			"국가정보원 검토 대상은 비밀ㆍ대외비를 유통ㆍ관리하기 위한 정보통신망 또는 정보시스템 구축, 주요 데이터베이스 구축 등이다."
		);
		LawSemanticChunkRow entryInstruction = chunk(
			2,
			"2025년 정보화사업 사전협의 안내서",
			"발주정보 등록 화면에서 사업명, 예산, 사전협의 대상, 보안성검토 대상 등을 입력한다."
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		EvidenceJudge.Result result = judge.judge(
			"보안성검토 대상 시스템은?",
			List.of(entryInstruction, directTarget),
			Map.of("official_doc:1", 0.4, "official_doc:2", 0.95),
			8
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(result.chunks()).containsExactly(directTarget);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(result.chunks()).doesNotContain(entryInstruction);
	}

	@Test
	// 메소드 설명: conceptQuestionDoesNotFallbackToUnrelatedCandidatesWhenOnlyOneDirectMatchExists 처리 흐름을 수행합니다.
	void conceptQuestionDoesNotFallbackToUnrelatedCandidatesWhenOnlyOneDirectMatchExists() {
		LawSemanticChunkRow directTarget = chunk(
			1,
			"데이터표준화 지침",
			"데이터표준화 대상은 기관이 구축ㆍ운영하는 주요 데이터와 관련 시스템이다."
		);
		LawSemanticChunkRow genericTarget = chunk(
			2,
			"정보화사업 보안성 검토 가이드",
			"보안성 검토 대상 사업은 정보통신망 또는 정보시스템 구축 사업이다."
		);
		LawSemanticChunkRow genericSystem = chunk(
			3,
			"공공데이터베이스 표준화 관리 매뉴얼",
			"관리 대상 시스템과 제외 대상을 설명한다."
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		EvidenceJudge.Result result = judge.judge(
			"데이터표준화 대상은?",
			List.of(genericTarget, genericSystem, directTarget),
			Map.of("official_doc:1", 0.4, "official_doc:2", 0.95, "official_doc:3", 0.9),
			8
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(result.chunks()).containsExactly(directTarget);
	}

	@Test
	// 메소드 설명: definitionQuestionRequiresSpecificConceptTerm 처리 흐름을 수행합니다.
	void definitionQuestionRequiresSpecificConceptTerm() {
		LawSemanticChunkRow irmOnly = chunk(
			1,
			"IRM 정보자원 등록관리 업무 담당자 기본 교육자료",
			"IRM 사업관리 메뉴에서 정보화사업을 등록하고 발주정보를 선택하는 절차를 설명한다."
		);
		LawSemanticChunkRow conceptEvidence = chunk(
			2,
			"전자정부 성과관리 기관 설명회 발표자료",
			"정보자원 관리수준 지표는 정보자원 등록 충실성, 정보자원 품질 정합성, 정보등록 품질 최신성으로 구성된다."
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		EvidenceJudge.Result result = judge.judge(
			"IRM 충실성 이란?",
			List.of(irmOnly, conceptEvidence),
			Map.of("official_doc:1", 0.95, "official_doc:2", 0.4),
			8
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(result.chunks()).containsExactly(conceptEvidence);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(result.scoreByChunkId().get("official_doc:2"))
			.isGreaterThan(result.scoreByChunkId().get("official_doc:1"));
	}

	@Test
	void tableOfContentsChunkIsNotUsedAsDirectEvidence() {
		LawSemanticChunkRow toc = chunk(
			1,
			"정보화사업 보안성 검토 가이드",
			"2 목 차 Ⅰ. 보안성 검토 개요 1 󰊲 대상 사업 및 시기 1 Ⅱ. 추진체계 및 역할 4"
		);
		LawSemanticChunkRow directTarget = chunk(
			2,
			"정보화사업 보안성 검토 가이드",
			"보안성 검토 대상은 비밀ㆍ대외비를 유통ㆍ관리하기 위한 정보통신망 또는 정보시스템 구축 사업이다."
		);

		EvidenceJudge.Result result = judge.judge(
			"보안성검토 대상 시스템은?",
			List.of(toc, directTarget),
			Map.of("official_doc:1", 0.95, "official_doc:2", 0.4),
			8
		);

		assertThat(result.chunks()).containsExactly(directTarget);
		assertThat(result.scoreByChunkId().get("official_doc:2"))
			.isGreaterThan(result.scoreByChunkId().get("official_doc:1"));
	}

	@Test
	void relationQuestionStripsNaturalKoreanQuestionEndings() {
		LawSemanticChunkRow genericIrmPlan = chunk(
			1,
			"업무성과계획관리 IRM 사용매뉴얼",
			"정보자원관리시스템 IRM에서 업무성과계획을 등록하고 제출하는 화면 사용 방법을 설명한다."
		);
		LawSemanticChunkRow directRelation = chunk(
			2,
			"2026년 전자정부 성과관리 기관 설명회 발표자료",
			"평가방법은 정보자원관리시스템 IRM 내 정보시스템별 업무성과계획 제출여부를 확인하고, 업무성과계획을 수립하여 기간 내 제출하였는지 확인하는 것이다. 차년도 성과측정 시 실적을 제출한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"irm 업무성과계획을 수립 한걸 확인하는게 성과측정인가?",
			List.of(genericIrmPlan, directRelation),
			Map.of("official_doc:1", 0.95, "official_doc:2", 0.4),
			8
		);

		assertThat(result.chunks()).containsExactly(directRelation);
		assertThat(result.conceptEvidenceRequired()).isTrue();
		assertThat(result.conceptEvidenceFound()).isTrue();
	}

	@Test
	void workPerformancePlanExclusionQuestionRequiresExclusionEvidence() {
		LawSemanticChunkRow genericPlan = chunk(
			1,
			"2026년 전자정부 성과관리 기관 설명회 발표자료",
			"평가방법은 정보자원관리시스템 내 정보시스템별 업무성과계획 제출여부를 확인하고 업무성과계획을 수립하여 기간 내 제출하였는지 확인하는 것이다."
		);
		LawSemanticChunkRow exclusion = chunk(
			2,
			"업무성과계획관리 IRM 사용매뉴얼",
			"업무성과계획 수립 대상 제외 기준을 설명한다. 일부 정보시스템은 업무성과계획 수립 대상에서 제외될 수 있다."
		);

		EvidenceJudge.Result result = judge.judge(
			"업무성과계획 수립 대상 제외는 뭐야?",
			List.of(genericPlan, exclusion),
			Map.of("official_doc:1", 0.95, "official_doc:2", 0.4),
			8
		);

		assertThat(result.chunks()).containsExactly(exclusion);
		assertThat(result.directEvidenceRequired()).isTrue();
		assertThat(result.directEvidenceFound()).isTrue();
	}

	@Test
	void completionStatusQuestionIgnoresConfirmationFillerWords() {
		LawSemanticChunkRow unrelatedConfirmation = chunk(
			1,
			"유해특성을 확인해야하는 폐기물의 종류 및 발생업종에 관한 규정 고시",
			"재활용 대상폐기물의 종류와 발생업종, 유해특성 해당여부를 확인하여야 한다."
		);
		LawSemanticChunkRow completionEvidence = chunk(
			2,
			"2026년 전자정부 성과관리 기관 설명회 발표자료",
			"평가방법은 정보자원관리시스템 내 정보시스템 운영 성과측정 완료 여부를 확인하는 것이다. 성과측정을 완료하고 제공한 데이터와 증빙자료가 일치하는 경우 충족으로 판단한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"성과측정 완료 여부는 어떻게 확인해?",
			List.of(unrelatedConfirmation, completionEvidence),
			Map.of("law:1", 0.95, "official_doc:2", 0.4),
			8
		);

		assertThat(result.chunks()).containsExactly(completionEvidence);
		assertThat(result.directEvidenceRequired()).isTrue();
		assertThat(result.directEvidenceFound()).isTrue();
	}

	@Test
	void projectReviewQuestionKeepsFullProtectedConceptTerm() {
		LawSemanticChunkRow genericProjectReview = chunk(
			1,
			"공공소프트웨어사업 과업심의 가이드",
			"과업심의위원회 구성 및 운영 방법을 설명한다."
		);
		LawSemanticChunkRow directTarget = chunk(
			2,
			"공공소프트웨어사업 과업심의 가이드",
			"적용 대상 사업은 국가기관 등이 발주하는 모든 SW사업이며 소프트웨어와 관련된 서비스를 포함한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"과업심의 대상은?",
			List.of(genericProjectReview, directTarget),
			Map.of("official_doc:1", 0.95, "official_doc:2", 0.4),
			8
		);

		assertThat(result.chunks()).containsExactly(directTarget);
		assertThat(result.directEvidenceRequired()).isTrue();
		assertThat(result.directEvidenceFound()).isTrue();
	}

	@Test
	void conceptQuestionDoesNotFallbackToSystemNameOnlyCandidate() {
		LawSemanticChunkRow irmOnly = chunk(
			1,
			"IRM 정보자원 등록관리 업무 담당자 기본 교육자료",
			"IRM 사업관리 메뉴에서 정보화사업을 등록하고 발주정보를 선택하는 절차를 설명한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"IRM 충실성 이란?",
			List.of(irmOnly),
			Map.of("official_doc:1", 0.95),
			8
		);

		assertThat(result.chunks()).isEmpty();
		assertThat(result.conceptEvidenceRequired()).isTrue();
		assertThat(result.conceptEvidenceFound()).isFalse();
	}

	@Test
	void conceptQuestionRequiresCoreConceptInsteadOfGenericSubjectOnly() {
		LawSemanticChunkRow genericSubject = chunk(
			1,
			"전자문서 업무 가이드",
			"전자문서를 등록하고 조회하는 화면 사용 방법을 설명한다."
		);
		LawSemanticChunkRow directConcept = chunk(
			2,
			"전자문서 보존 지침",
			"전자문서 보존 기준과 보존기간은 기록물 유형에 따라 산정한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"전자문서 보존기간은?",
			List.of(genericSubject, directConcept),
			Map.of("official_doc:1", 0.95, "official_doc:2", 0.4),
			8
		);

		assertThat(result.chunks()).containsExactly(directConcept);
		assertThat(result.scoreByChunkId().get("official_doc:2")).isGreaterThan(0.9);
	}

	@Test
	// 메소드 설명: keepsCandidatesWhenNoReliableJudgmentExists 처리 흐름을 수행합니다.
	void keepsCandidatesWhenNoReliableJudgmentExists() {
		LawSemanticChunkRow candidate = chunk(
			1,
			"임의 문서",
			"질문과 일부 관련될 수 있는 일반 설명 문장이다."
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		EvidenceJudge.Result result = judge.judge(
			"새로운 유형의 질문",
			List.of(candidate),
			Map.of("official_doc:1", 0.5),
			8
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(result.chunks()).containsExactly(candidate);
	}

	@Test
	void protectionScopeQuestionKeepsConfidentialityAndProtectionEvidence() {
		LawSemanticChunkRow unrelated = chunk(
			1,
			"공익신고 접수 통계",
			"연도별 접수 건수와 처리 현황을 집계한 일반 통계 자료이다."
		);
		LawSemanticChunkRow purpose = chunk(
			2,
			"공익신고자 보호사무 운영지침",
			"이 예규는 공익신고자등에 대한 보호 업무 절차를 정함으로써 보호와 지원 업무를 원활히 수행하게 하는 것을 목적으로 한다."
		);
		LawSemanticChunkRow confidentiality = chunk(
			3,
			"공익신고자 보호사무 운영지침",
			"공익신고자등의 비밀보장: 동의 없이 공익신고자등의 인적사항이나 공익신고자등임을 미루어 알 수 있는 사항을 공개 또는 보도해서는 아니 된다."
		);
		LawSemanticChunkRow protectionAction = chunk(
			4,
			"공익신고자 보호사무 운영지침",
			"공익신고자등은 신변보호조치의 요청 및 보호조치 신청을 통하여 불이익조치에 대한 보호를 받을 수 있다."
		);

		EvidenceJudge.Result result = judge.judge(
			"공익신고자 보호는 어디까지 가능해?",
			List.of(unrelated, purpose, confidentiality, protectionAction),
			Map.of("official_doc:1", 0.8, "official_doc:2", 0.5, "official_doc:3", 0.6, "official_doc:4", 0.7),
			8
		);

		assertThat(result.chunks()).contains(confidentiality, protectionAction);
		assertThat(result.chunks()).doesNotContain(unrelated);
	}

	@Test
	void colloquialCommitteeDefinitionQuestionMatchesExpandedCommitteeName() {
		LawSemanticChunkRow unrelated = chunk(
			1,
			"인공지능 활용 서비스 목록 관리",
			"공공기관의 장은 인공지능 활용 서비스의 유형과 데이터 현황을 관리하여야 한다."
		);
		LawSemanticChunkRow committee = chunk(
			2,
			"인공지능 발전과 신뢰 기반 조성 등에 관한 기본법",
			"국가인공지능전략위원회는 인공지능 발전 및 신뢰 기반 조성에 관한 주요 정책과 계획을 심의ㆍ조정하기 위하여 설치되는 위원회이다."
		);

		EvidenceJudge.Result result = judge.judge(
			"인공지능위원회라는건 뭐야?",
			List.of(unrelated, committee),
			Map.of("official_doc:1", 0.9, "official_doc:2", 0.5),
			8
		);

		assertThat(result.chunks()).containsExactly(committee);
	}

	@Test
	void definitionQuestionRejectsLooseMentionWithoutExplanationSignal() {
		LawSemanticChunkRow looseMention = chunk(
			1,
			"2025년 정보화사업 사전협의 안내서",
			"교육내용은 정보화사업 발주 관련 법과 제도, 사전협의 절차, 보안성 검토 등 관련 분야 교육 추진으로 구성된다."
		);
		LawSemanticChunkRow explanatoryEvidence = chunk(
			2,
			"국가정보보안기본지침",
			"보안성 검토를 거쳐 완료한 정보화사업에 대하여 정보통신망 구성을 변경하지 아니하는 범위의 후속 운영과 유지보수는 생략할 수 있다."
		);

		EvidenceJudge.Result result = judge.judge(
			"보안성검토라는게 뭐야?",
			List.of(looseMention, explanatoryEvidence),
			Map.of("official_doc:1", 0.95, "official_doc:2", 0.4),
			8
		);

		assertThat(result.chunks()).containsExactly(explanatoryEvidence);
		assertThat(result.chunks()).doesNotContain(looseMention);
	}

	@Test
	void definitionQuestionDoesNotAcceptDocumentTitleOnlyMatch() {
		LawSemanticChunkRow loosePage = chunk(
			1,
			"(붙임2) 2026년 정보화사업 보안성 검토 가이드",
			"p.5 서비스 공급업체는 기관 VPC 영역에서 보안관제를 수행할 수 있는 기반 제공",
			"AI 정보화 사업은 보안성 검토에 장시간 소요될 수 있어 사전 협의 시 신속하게 진행할 수 있다는 안내이다."
		);
		LawSemanticChunkRow overviewPage = chunk(
			2,
			"(붙임1) 2026년 정보화사업 보안성 검토 운영계획",
			"추진개요. 보안성 검토의 목적은 정보화 사업 계획 단계에서 보안대책의 적정성을 사전에 검토하여 정보보안을 강화하는 것이다."
		);

		EvidenceJudge.Result result = judge.judge(
			"보안성검토라는게 뭐야?",
			List.of(loosePage, overviewPage),
			Map.of("official_doc:1", 0.95, "official_doc:2", 0.4),
			8
		);

		assertThat(result.chunks()).containsExactly(overviewPage);
		assertThat(result.chunks()).doesNotContain(loosePage);
	}

	// 메소드 설명: chunk 처리 흐름을 수행합니다.
	// 메소드 설명: chunk 처리 흐름을 수행합니다.
	private LawSemanticChunkRow chunk(long id, String title, String text) {
		return chunk(id, title, "p." + id, text);
	}

	private LawSemanticChunkRow chunk(long id, String title, String chunkTitle, String text) {
		return new LawSemanticChunkRow(
			id,
			1,
			"official_doc",
			String.valueOf(id),
			title,
			"기관",
			"공식 가이드 문서",
			null,
			"page " + id,
			chunkTitle,
			text,
			(int) id,
			null,
			null,
			(int) id,
			"hash-" + id
		);
	}
}
