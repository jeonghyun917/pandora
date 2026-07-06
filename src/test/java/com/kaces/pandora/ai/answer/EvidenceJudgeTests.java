package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.common.text.QuestionIntentProfile;
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
	void ranksQuestionAnchoredHardwareExclusionBeforeGeneralTargetScope() {
		LawSemanticChunkRow generalTargetScope = chunk(
			1,
			"official_doc",
			"공공SW사업 법제도 관리감독 및 지원 가이드(2024. 12.)",
			"p.5 대상 사업",
			"대상사업 : 국가기관등의 장이 발주하는 소프트웨어사업. 국가기관, 지방자치단체, 공공기관 등 국가기관등의 범위를 설명한다.",
			"대상 사업",
			"target_scope"
		);
		LawSemanticChunkRow directHardwareExclusion = chunk(
			2,
			"official_doc",
			"공공소프트웨어사업 과업심의 가이드(2022. 12.)",
			"p.5 적용 대상 사업",
			"적용 대상 사업. 국가기관 등이 발주하는 모든 SW사업(상용SW포함). ※ 단순 H/W(Appliance 포함) 도입·설치, 단순 동영상 제작, 네트워크 등 인프라 수수료와 같이 소프트웨어사업으로 볼 수 없는 경우는 비대상",
			"적용 대상 사업",
			"target_scope"
		);

		EvidenceJudge.Result result = judge.judge(
			"하드웨어만 사는 사업도 공공SW 과업심의를 해야 해?",
			List.of(generalTargetScope, directHardwareExclusion),
			Map.of("official_doc:1", 0.95, "official_doc:2", 0.2),
			8
		);

		assertThat(result.directEvidenceFound()).isTrue();
		assertThat(result.chunks()).first().isEqualTo(directHardwareExclusion);
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
	void mapsEverydayStopQuestionToTrafficLawEvidence() {
		LawSemanticChunkRow unrelatedCrosswalk = chunk(
			1,
			"건축물관리법",
			"해당 건축물 주변에 버스 정류장, 지하도, 횡단보도 등이 있는 경우 안전조치를 검토한다."
		);
		LawSemanticChunkRow rightTurnStop = chunk(
			2,
			"law",
			"도로교통법",
			"제25조(교차로 통행방법)",
			"제25조 교차로 통행방법. 우회전하는 차의 운전자는 신호에 따라 정지하거나 진행하는 보행자 또는 자전거등에 주의하여야 한다."
		);
		LawSemanticChunkRow crosswalkStop = chunk(
			3,
			"law",
			"도로교통법",
			"제27조(보행자의 보호)",
			"제27조 보행자의 보호. 모든 차의 운전자는 보행자가 횡단보도를 통행하고 있거나 통행하려고 하는 때에는 횡단보도 앞에서 일시정지하여야 한다."
		);
		LawSemanticChunkRow facilityGuide = chunk(
			4,
			"admrul",
			"단지내도로 교통안전시설의 설치·관리기준",
			"문단 1",
			"교차로는 보행자와 자동차 간 상충이 많으므로 시거확보를 고려하고, 우회전 차량과 횡단보도 보행자 안전을 위한 시설물을 설치할 수 있다."
		);

		EvidenceJudge.Result result = judge.judge(
			"운전중 우회전할때 횡단보도에서 멈춰야 하나?",
			List.of(unrelatedCrosswalk, facilityGuide, rightTurnStop, crosswalkStop),
			Map.of("official_doc:1", 0.9, "admrul:4", 0.95, "law:2", 0.5, "law:3", 0.6),
			8
		);

		assertThat(result.directEvidenceFound()).isTrue();
		assertThat(result.chunks()).containsExactly(rightTurnStop, crosswalkStop);
		assertThat(result.chunks()).doesNotContain(unrelatedCrosswalk);
		assertThat(result.chunks()).doesNotContain(facilityGuide);
	}

	@Test
	void acceptsInformationSystemComplianceConsequenceEvidence() {
		LawSemanticChunkRow ruleOnly = chunk(
			91,
			"정보화업무 기본지침",
			"정보화시스템 운영 및 관리 기준은 법제도 준수 여부를 정기적으로 확인하고 관리하여야 한다."
		);
		LawSemanticChunkRow consequence = chunk(
			92,
			"공공SW사업 법제도 관리감독 및 지원 가이드",
			"법제도 준수 확인",
			"정보화사업 법제도 준수 여부를 검토하고, 검토결과가 반영되지 않으면 보완 요구 또는 예산 조정 등의 조치를 받을 수 있다."
		);

		EvidenceJudge.Result result = judge.judge(
			"정보화시스템 법제도 준수안하면 어떤 불이익?",
			List.of(ruleOnly, consequence),
			Map.of("official_doc:91", 0.7, "official_doc:92", 0.4),
			8
		);

		assertThat(result.directEvidenceFound()).isTrue();
		assertThat(result.chunks()).contains(consequence);
		assertThat(result.chunks()).doesNotContain(ruleOnly);
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
	void targetQuestionCanUseSplitScopeEvidenceFromSameDocument() {
		LawSemanticChunkRow targetHeading = chunk(
			1,
			"공공소프트웨어사업 과업심의 가이드",
			"p.5 적용 대상 사업",
			"적용 대상 사업."
		);
		LawSemanticChunkRow targetBody = chunk(
			2,
			"공공소프트웨어사업 과업심의 가이드",
			"p.5 상세 설명",
			"국가기관 등이 발주하는 모든 SW사업이다. 소프트웨어의 개발, 제작, 생산, 유통, 운영 및 유지관리와 관련 서비스를 포함한다."
		);
		LawSemanticChunkRow adjacentOperation = chunk(
			3,
			"공공소프트웨어사업 과업심의 가이드",
			"p.6 과업심의위원회 운영",
			"위원장은 회의를 소집하고 위원의 제척ㆍ기피 절차를 운영한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"과업심의 대상은?",
			List.of(adjacentOperation, targetHeading, targetBody),
			Map.of("official_doc:1", 0.42, "official_doc:2", 0.4, "official_doc:3", 0.98),
			8
		);

		assertThat(result.chunks()).containsExactly(targetHeading, targetBody);
		assertThat(result.chunks()).doesNotContain(adjacentOperation);
		assertThat(result.directEvidenceFound()).isTrue();
	}

	@Test
	void targetQuestionDoesNotMergeSplitEvidenceFromDifferentDocumentsWithoutRelationCue() {
		LawSemanticChunkRow targetLabelOnly = chunk(
			1,
			"국가정보보안기본지침",
			"보안성 검토 대상",
			"국가정보원 검토 대상과 부처 검토 대상 구분을 설명한다."
		);
		LawSemanticChunkRow systemScopeOnly = chunk(
			2,
			"정보화사업 보안성 검토 운영계획",
			"검토 시스템",
			"정보통신망 또는 정보시스템 구축, 주요 데이터베이스 구축 등 시스템 유형을 설명한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"보안성검토 대상 시스템은?",
			List.of(targetLabelOnly, systemScopeOnly),
			Map.of("official_doc:1", 0.5, "official_doc:2", 0.48),
			8
		);

		assertThat(result.chunks()).isEmpty();
		assertThat(result.directEvidenceRequired()).isTrue();
		assertThat(result.directEvidenceFound()).isFalse();
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
	void preConsultationTargetQuestionPrefersGeneralScopeOverQaException() {
		LawSemanticChunkRow generalScopeLead = chunk(
			1,
			"2024년 정보화사업 사전협의 안내자료",
			"p.28 대상 사업",
			"사전협의의 대상사업은 예산과목 및 계약방식과 관계없이 적용한다."
		);
		LawSemanticChunkRow generalScopeBody = chunk(
			2,
			"2024년 정보화사업 사전협의 안내자료",
			"p.28 대상 기관",
			"대상기관이 추진하는 모든 정보화사업에 해당한다."
		);
		LawSemanticChunkRow qaException = chunk(
			3,
			"2024년 정보화사업 사전협의 안내자료",
			"Q. 기관 자체 예산으로 수행하는 정보화사업도 사전협의의 대상입니까?",
			"기관 자체 수입으로 추진하는 정보화사업은 사전협의의 대상이 아닙니다. 출연금, 보조금 등을 사용하지 않는 공공기관 정보화사업은 대상사업이 아닙니다."
		);
		LawSemanticChunkRow securityReview = chunk(
			4,
			"(붙임2) 2026년 정보화사업 보안성 검토 가이드",
			"p.2 검토 대상",
			"국정원 검토 대상 정보화사업인 경우 사전에 본부 보안성 검토 담당자와 협의 후 국가정보원 의뢰를 추진한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"기타공공기관 사전협의 대상 알려줘",
			List.of(qaException, securityReview, generalScopeLead, generalScopeBody),
			Map.of("official_doc:1", 0.4, "official_doc:2", 0.35, "official_doc:3", 0.95, "official_doc:4", 1.0),
			8
		);

		assertThat(result.chunks()).contains(generalScopeLead, generalScopeBody);
		assertThat(result.chunks()).doesNotContain(qaException);
		assertThat(result.chunks()).doesNotContain(securityReview);
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
	void securityReviewTargetQuestionPrefersOfficialGuideOverGenericRegulationDefinition() {
		LawSemanticChunkRow genericDefinition = chunk(
			1,
			"admrul",
			"산업통상자원부 정보보안 세부지침",
			"제2조(정의)",
			"정보화사업 보안성검토 대상은 정보통신망 또는 정보시스템 구축, 주요정보통신기반시설, 민감정보 및 고유식별정보 처리 시스템 등을 포함할 수 있다."
		);
		LawSemanticChunkRow guideTarget = chunk(
			2,
			"official_doc",
			"(붙임2) 2026년 정보화사업 보안성 검토 가이드",
			"p.2 국정원 검토 대상 사업",
			"국정원 검토 대상 사업은 비밀ㆍ대외비를 유통ㆍ관리하기 위한 정보통신망 또는 정보시스템 구축, 주요 데이터베이스 구축, 주요정보통신기반시설 및 제어시스템 구축 사업이다."
		);

		EvidenceJudge.Result result = judge.judge(
			"보안성검토 대상 시스템은?",
			List.of(genericDefinition, guideTarget),
			Map.of("admrul:1", 0.95, "official_doc:2", 0.4),
			8
		);

		assertThat(result.directEvidenceFound()).isTrue();
		assertThat(result.chunks()).first().isEqualTo(guideTarget);
		assertThat(result.chunks()).contains(genericDefinition);
	}

	@Test
	void securityReviewTargetQuestionAcceptsGuideChunkWithCompactReviewTargetHeading() {
		LawSemanticChunkRow guideTarget = chunk(
			1,
			"official_doc",
			"(붙임2) 2026년 정보화사업 보안성 검토 가이드",
			"p.2 검토 대상",
			"국정원 검토 대상 정보화사업은 비밀ㆍ대외비를 유통ㆍ관리하기 위한 정보통신망 또는 정보시스템 구축, 주요정보통신기반시설, 제어시스템, 민감정보 및 고유식별정보 처리 정보시스템 구축을 포함한다.",
			"검토 대상",
			"target_scope"
		);
		LawSemanticChunkRow genericDefinition = chunk(
			2,
			"admrul",
			"산업통상자원부 정보보안 세부지침",
			"제2조(정의)",
			"정보화사업 보안성검토 대상은 정보통신망 또는 정보시스템 구축과 주요정보통신기반시설 구축을 포함한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"보안성검토 대상 시스템은?",
			List.of(genericDefinition, guideTarget),
			Map.of("official_doc:1", 0.4, "admrul:2", 0.95),
			8
		);

		assertThat(result.directEvidenceFound()).isTrue();
		assertThat(result.chunks()).first().isEqualTo(guideTarget);
	}

	@Test
	void securityReviewTargetQuestionKeepsGuideTargetScopeDespiteProcedureAndChecklistTerms() {
		LawSemanticChunkRow guideTarget = chunk(
			1,
			"official_doc",
			"(붙임2) 2026년 정보화사업 보안성 검토 가이드",
			"p.2 검토 대상",
			"국정원 검토 대상 정보화사업은 비밀ㆍ대외비를 유통ㆍ관리하기 위한 정보통신망 또는 정보시스템 구축, 주요정보통신기반시설 및 제어시스템 구축 사업이다. 제안요청서의 보안 검토 항목과 체크 절차는 별도로 확인한다.",
			"검토 대상",
			"target_scope"
		);
		LawSemanticChunkRow genericDefinition = chunk(
			2,
			"admrul",
			"산업통상자원부 정보보안 세부지침",
			"제2조(정의)",
			"정보화사업 보안성검토 대상은 정보통신망 또는 정보시스템 구축과 주요정보통신기반시설 구축을 포함한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"보안성검토 대상 시스템은?",
			List.of(genericDefinition, guideTarget),
			Map.of("official_doc:1", 0.4, "admrul:2", 0.95),
			8
		);

		assertThat(result.directEvidenceFound()).isTrue();
		assertThat(result.chunks()).first().isEqualTo(guideTarget);
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
	void targetQuestionRejectsTitleConceptWithLooseIntentWords() {
		LawSemanticChunkRow looseUiInstruction = chunk(
			1,
			"데이터표준화 지침",
			"관리 화면에서 대상 시스템을 선택하고 조회 버튼을 누르는 방법을 설명한다."
		);
		LawSemanticChunkRow directTarget = chunk(
			2,
			"데이터표준화 지침",
			"데이터표준화 대상은 기관이 구축ㆍ운영하는 주요 데이터와 관련 데이터베이스이다."
		);

		EvidenceJudge.Result result = judge.judge(
			"데이터표준화 대상은?",
			List.of(looseUiInstruction, directTarget),
			Map.of("official_doc:1", 0.95, "official_doc:2", 0.4),
			8
		);

		assertThat(result.chunks()).containsExactly(directTarget);
		assertThat(result.chunks()).doesNotContain(looseUiInstruction);
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
	void projectReviewExemptionQuestionPrefersApplicableScopePageOverAdjacentPages() {
		LawSemanticChunkRow scopePage = chunk(
			1,
			"공공소프트웨어사업 과업심의 가이드",
			"p.5 적용 대상 사업",
			"적용 대상 사업은 국가기관 등이 발주하는 모든 SW사업(상용SW포함)이다. "
				+ "소프트웨어의 개발, 제작, 생산, 유통, 운영 및 유지관리 등과 그 밖에 소프트웨어와 관련된 서비스를 포함한다. "
				+ "단순 H/W(Appliance 포함) 도입ㆍ설치, 네트워크 등 인프라 수수료와 같이 소프트웨어사업으로 볼 수 없는 경우는 비대상이다."
		);
		LawSemanticChunkRow committeeOperation = chunk(
			2,
			"공공소프트웨어사업 과업심의 가이드",
			"p.6 과업심의위원회 운영",
			"위원장은 과업심의위원회의 회의를 소집하고 재적위원 과반수의 출석과 출석위원 과반수의 찬성으로 의결한다. 위원의 제척ㆍ해촉 기준을 설명한다."
		);
		LawSemanticChunkRow directPurchaseRegistration = chunk(
			3,
			"상용소프트웨어 직접구매 가이드",
			"p.12 계약정보 등록",
			"상용소프트웨어 직접구매 계약정보 등록. 상용소프트웨어 계약체결 또는 계약변경 후 30일 이내에 계약정보를 등록한다."
		);
		LawSemanticChunkRow simplifiedReview = chunk(
			4,
			"공공소프트웨어사업 과업심의 가이드",
			"p.7 간소화 과업심의",
			"상용소프트웨어 구매사업은 간소화 방식으로 심의할 수 있다. 총 사업금액 1억원 이하 사업도 간소화 과업심의 대상이다."
		);
		LawSemanticChunkRow complianceChecklist = chunk(
			5,
			"소프트웨어사업관련 법령준수",
			"p.2 법령준수 체크리스트",
			"o 대상사업: SW개발, 제작, 생산, 유통, 운영 및 유지관리 등과 이에 관련된 서비스. "
				+ "제안요청서와 과업내용, 사업기간, 예산 산정 자료, 계약조건, 품질관리, 산출물 활용, 하도급 제한, 사업정보 제출 여부를 점검한다. "
				+ "첨부 SW사업 관련 법령: 1. 과업심의위원회 2. 상용SW 직접구매 및 품질성능 평가시험."
		);

		EvidenceJudge.Result result = judge.judge(
			"단순 소프트웨어 구매면 과업심의 안해도됨?",
			List.of(committeeOperation, directPurchaseRegistration, simplifiedReview, complianceChecklist, scopePage),
			Map.of(
				"official_doc:1", 0.3,
				"official_doc:2", 0.96,
				"official_doc:3", 0.94,
				"official_doc:4", 0.92,
				"official_doc:5", 0.91
			),
			8
		);

		assertThat(result.chunks()).containsExactly(scopePage);
		assertThat(result.chunks()).doesNotContain(
			committeeOperation,
			directPurchaseRegistration,
			simplifiedReview,
			complianceChecklist
		);
		assertThat(result.directEvidenceRequired()).isTrue();
		assertThat(result.directEvidenceFound()).isTrue();
	}

	@Test
	void projectReviewPreConsultationRelationQuestionKeepsEvidenceFromBothSystems() {
		LawSemanticChunkRow projectReviewScope = chunk(
			1,
			"공공소프트웨어사업 과업심의 가이드",
			"p.5 적용 대상 사업",
			"적용 대상 사업 국가기관 등이 발주하는 모든 SW사업(상용SW포함)이다. "
				+ "소프트웨어의 개발, 제작, 생산, 유통, 운영 및 유지관리 등과 소프트웨어와 관련된 서비스를 포함한다. "
				+ "단순 H/W(Appliance 포함) 도입ㆍ설치처럼 소프트웨어사업으로 볼 수 없는 경우는 비대상이다."
		);
		LawSemanticChunkRow preConsultationScope = chunk(
			2,
			"2025년 문화체육관광부 정보화사업 사전협의 안내서",
			"p.2 ㅇ 사전협의 대상사업은",
			"사전협의 대상사업은 예산과목 및 계약방식과 관계없이 대상기관이 추진하는 모든 정보화사업에 해당한다."
		);
		LawSemanticChunkRow projectReviewOperation = chunk(
			3,
			"공공소프트웨어사업 과업심의 가이드",
			"p.6 과업심의위원회 운영",
			"위원장은 과업심의위원회의 회의를 소집하고 위원 제척 및 기피 절차를 운영한다."
		);
		LawSemanticChunkRow unrelatedProcedure = chunk(
			4,
			"전기용품 및 생활용품 안전관리법 시행규칙",
			"별표내용",
			"신청서 작성, 접수, 심사, 지정여부 결정 및 결과 회신 절차를 설명한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"과업심의 한 사업은 사전협의 꼭 해야됨",
			List.of(projectReviewOperation, unrelatedProcedure, preConsultationScope, projectReviewScope),
			Map.of("official_doc:1", 0.4, "official_doc:2", 0.5, "official_doc:3", 0.98, "law:4", 0.96),
			8
		);

		assertThat(result.chunks()).contains(projectReviewScope, preConsultationScope);
		assertThat(result.chunks()).doesNotContain(projectReviewOperation, unrelatedProcedure);
		assertThat(result.directEvidenceRequired()).isTrue();
		assertThat(result.directEvidenceFound()).isTrue();
	}

	@Test
	void projectReviewPreConsultationRelationQuestionKeepsRealIndexedScopeChunks() {
		LawSemanticChunkRow projectReviewScope = chunk(
			26369,
			"공공소프트웨어사업 과업심의 가이드(2022. 12.)",
			"p.5 적용 대상 사업",
			"적용 대상 사업 국가기관 등이 발주하는 모든 SW사업(상용SW포함) - 소프트웨어의 개발, 제작, 생산, 유통, 운영 및 유지·관리 등과 그 밖에 소프트웨어와 관련된 서비스를 제공하는 산업과 관련된 경제활동(‘소프트웨어 진흥법’제2조) ※ 단순 H/W(Appliance 포함) 도입·설치, 단순 동영상 제작, 네트워크 등 인프라 수수료와 같이 소프트웨어사업으로 볼 수 없는 경우는 비대상"
		);
		LawSemanticChunkRow preConsultationScope = chunk(
			26469,
			"2025년+문화체육관광부+정보화사업+사전협의+안내서_250723",
			"p.2 ㅇ 사전협의 대상사업은",
			"대상사업은 예산과목 및 계약방식과 관계없이 * 대상기관이 추진하는 모든 정보화사업에 해당 * 디지털서비스 전문계약제도 이용 계약, 공모, R&D, 민간투자형 소프트웨어사업 등 예산과목 및 계약방식에 관계 없음 ㅇ"
		);
		LawSemanticChunkRow adjacentOperation = chunk(
			26371,
			"공공소프트웨어사업 과업심의 가이드(2022. 12.)",
			"p.6 06 ┃ 공공소프트웨어사업 과업심의 가이드",
			"대상 사업과 직접적으로 이해관계가 존재하는 경우 위원 제척 및 기피 절차를 설명한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"과업심의 한 사업은 사전협의 꼭 해야됨",
			List.of(adjacentOperation, projectReviewScope, preConsultationScope),
			Map.of("official_doc:26369", 9.7, "official_doc:26469", 5.5, "official_doc:26371", 7.1),
			8
		);

		assertThat(result.chunks()).contains(projectReviewScope, preConsultationScope);
		assertThat(result.chunks()).doesNotContain(adjacentOperation);
		assertThat(result.directEvidenceFound()).isTrue();
	}

	@Test
	void projectReviewScopeQuestionUsesStructuralHeadingWhenBodyStartsAtContinuation() {
		LawSemanticChunkRow continuationScope = chunk(
			1,
			"공공소프트웨어사업 과업심의 가이드(2022. 12.)",
			"p.5 적용 대상 사업",
			"- 소프트웨어의 개발, 제작, 생산, 유통, 운영 및 유지관리 등과 그 밖에 소프트웨어와 관련된 서비스를 포함한다. "
				+ "※ 단순 H/W(Appliance 포함) 도입ㆍ설치, 네트워크 등 인프라 수수료와 같이 소프트웨어사업으로 볼 수 없는 경우는 비대상이다."
		);
		LawSemanticChunkRow adjacentOperation = chunk(
			2,
			"공공소프트웨어사업 과업심의 가이드(2022. 12.)",
			"p.6 과업심의위원회 운영",
			"위원장은 과업심의위원회의 회의를 소집하고 재적위원 과반수 출석으로 운영한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"단순 소프트웨어 구매면 과업심의 안해도됨?",
			List.of(adjacentOperation, continuationScope),
			Map.of("official_doc:1", 0.4, "official_doc:2", 0.95),
			8
		);

		assertThat(result.chunks()).containsExactly(continuationScope);
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
	void acronymQuestionRequiresTheSameAcronymInEvidence() {
		LawSemanticChunkRow unrelatedPermissionGuide = chunk(
			1,
			"(붙임2) 2026년 정보화사업 보안성 검토 가이드",
			"p.5 장비(CMOS) 사용자 권한",
			"관리자용 CMOS 비밀번호를 설정하고 일반 사용자는 관리자 권한을 부여하지 않으며 단말 점검과 NAC 연동으로 비준수 단말의 네트워크 접근을 제한한다."
		);
		LawSemanticChunkRow irmPermissionGuide = chunk(
			2,
			"(26년 기초교육자료) IRM 정보자원 등록관리 업무 담당자 기본 교육자료",
			"p.24 IRM 사용자 권한",
			"IRM 정보자원관리시스템에서 사용자 권한은 사업관리, 정보화사업 등록, 성과관리 업무에 따라 부여되며 발주정보 조회와 중복확인 기능 사용 권한을 구분한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"IRM 사용자 권한 가이드",
			List.of(unrelatedPermissionGuide, irmPermissionGuide),
			Map.of("official_doc:1", 0.98, "official_doc:2", 0.42),
			8
		);

		assertThat(result.chunks()).containsExactly(irmPermissionGuide);
		assertThat(result.chunks()).doesNotContain(unrelatedPermissionGuide);
		assertThat(result.conceptEvidenceRequired()).isTrue();
		assertThat(result.conceptEvidenceFound()).isTrue();
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
	void colloquialCommitteeQuestionDoesNotExpandToEveryCommitteeType() {
		LawSemanticChunkRow ethicsCommittee = chunk(
			1,
			"인공지능 발전과 신뢰 기반 조성 등에 관한 기본법",
			"민간자율인공지능윤리위원회는 인공지능윤리위원회의 설치와 운영에 관한 사항을 정한다."
		);
		LawSemanticChunkRow strategyCommittee = chunk(
			2,
			"인공지능 발전과 신뢰 기반 조성 등에 관한 기본법",
			"국가인공지능전략위원회는 인공지능 발전 및 신뢰 기반 조성에 관한 주요 정책과 계획을 심의ㆍ조정하기 위하여 설치되는 위원회이다."
		);

		EvidenceJudge.Result result = judge.judge(
			"인공지능위원회라는건 뭐야?",
			List.of(ethicsCommittee, strategyCommittee),
			Map.of("official_doc:1", 0.95, "law:2", 0.4),
			8
		);

		assertThat(result.chunks()).containsExactly(strategyCommittee);
		assertThat(result.chunks()).doesNotContain(ethicsCommittee);
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

	@Test
	void publicDataActivationQuestionRejectsDatabaseStandardizationOnlyEvidence() {
		LawSemanticChunkRow activationSupport = chunk(
			1,
			"공공데이터 이용활성화 지원 사업 관리지침",
			"공공데이터의 품질을 진단ㆍ개선하여 최신성ㆍ정확성을 확보하기 위한 품질진단 컨설팅 및 품질개선 등을 지원하는 사업"
		);
		LawSemanticChunkRow activationDirection = chunk(
			2,
			"공공데이터의 제공 및 이용 활성화에 관한 법률",
			"공공데이터 제공 및 이용 활성화의 기본목표와 추진방향을 기본계획에 포함하여야 한다."
		);
		LawSemanticChunkRow databaseStandardization = chunk(
			3,
			"공공데이터베이스_표준화_관리_매뉴얼_23년_4월",
			"공공기관의 장은 공공데이터베이스 설계ㆍ구축ㆍ운영 등 관리 시 표준용어와 표준코드를 준수하여 데이터베이스 구축 산출물을 작성하여야 한다. "
				+ "공공데이터의 제공 및 이용 활성화에 관한 법률에 따른 품질 진단ㆍ평가와 개선지원 등 필요한 시책을 언급한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"공공데이터 활성화를 위한 방안",
			List.of(databaseStandardization, activationSupport, activationDirection),
			Map.of("official_doc:1", 0.4, "official_doc:2", 0.5, "official_doc:3", 0.95),
			8
		);

		assertThat(result.chunks()).containsExactly(activationDirection, activationSupport);
		assertThat(result.chunks()).doesNotContain(databaseStandardization);
	}

	@Test
	void publicDataActivationQuestionRejectsPrivacyConflictGuideEvidence() {
		LawSemanticChunkRow privacyConflictGuide = chunk(
			1,
			"official_doc",
			"공공데이터의 인공지능 친화적 관리 가이드라인",
			"p.56 법률 간 충돌 조정 체계",
			"개인정보 관련 사항과 비공개정보를 설명하고 공공데이터 제공 여부의 이해관계 충돌을 설명한다."
		);
		LawSemanticChunkRow activationDirection = chunk(
			2,
			"law",
			"공공데이터의 제공 및 이용 활성화에 관한 법률",
			"제14조(공공데이터 이용 활성화)",
			"정부는 공공데이터 이용에 대한 국민의 인식을 높이고 이용 활성화를 촉진하기 위하여 성공사례 발굴ㆍ포상 및 홍보, 포럼 및 세미나 개최 등 필요한 사업을 추진할 수 있다."
		);

		EvidenceJudge.Result result = judge.judge(
			"공공데이터법에서 이용 활성화는 어떤 방향이야?",
			List.of(privacyConflictGuide, activationDirection),
			Map.of("official_doc:1", 1.0, "law:2", 0.2),
			8
		);

		assertThat(result.directEvidenceFound()).isTrue();
		assertThat(result.chunks()).containsExactly(activationDirection);
	}

	@Test
	void exploratoryGuideQuestionKeepsEvidenceWhenConceptsAreSplitBetweenTitleAndBody() {
		LawSemanticChunkRow unrelatedSecurityGuide = chunk(
			1,
			"(붙임2) 2026년 정보화사업 보안성 검토 가이드",
			"정보시스템 등급별 IRM 기준을 적용하고 개별사용자의 계정ㆍ비밀번호 등 정보시스템 접근권한 정보를 누출금지 대상으로 관리한다."
		);
		LawSemanticChunkRow irmPermissionGuide = chunk(
			2,
			"정보자원관리시스템(IRM) 클라우드컴퓨팅서비스 이용정보입력방법",
			"클라우드이용정보 관련 메뉴를 이용하려면 권한이 필요하며, 개별기관 관리자와 정보등록 담당자가 권한 신청 및 승인 후 입력을 진행한다."
		);
		LawSemanticChunkRow irmTraining = chunk(
			3,
			"(26년 기초교육자료) IRM 정보자원 등록관리 업무 담당자 기본 교육자료",
			"사업자 업무(권한) 및 권한 부여날짜 설정, 권한 만료 시 자동 회수 절차를 안내한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"IRM 사용자 권한 가이드",
			List.of(unrelatedSecurityGuide, irmPermissionGuide, irmTraining),
			Map.of("official_doc:1", 0.95, "official_doc:2", 0.45, "official_doc:3", 0.4),
			8
		);

		assertThat(result.chunks()).contains(irmPermissionGuide, irmTraining);
		assertThat(result.chunks()).doesNotContain(unrelatedSecurityGuide);
	}

	@Test
	void procurementCatalogContractQuestionKeepsDirectPurchaseContextAndRejectsLoosePrivateContractLaw() {
		LawSemanticChunkRow loosePrivateContractLaw = chunk(
			1,
			"law",
			"지방자치단체를 당사자로 하는 계약에 관한 법률 시행령",
			"제25조 수의계약",
			"지방자치단체의 장 또는 계약담당자는 추정가격이 일정 금액 이하인 경우 수의계약을 할 수 있다."
		);
		LawSemanticChunkRow digitalServiceMallDirectPurchase = chunk(
			2,
			"official_doc",
			"공공SW사업 법제도 관리감독 및 지원 가이드",
			"o 적용 대상",
			"상용SW 직접구매 적용 대상에는 조달청 종합쇼핑몰 또는 디지털서비스몰에 등록된 SaaS가 포함된다. 1차 조건과 2차 조건을 충족하면 직접구매 대상 사업으로 본다."
		);
		LawSemanticChunkRow brokerRule = chunk(
			3,
			"admrul",
			"디지털서비스 카탈로그계약 특수조건",
			"제20조 브로커의 불공정행위 방지",
			"브로커가 디지털서비스 카탈로그계약의 가격협의, 수의시담, 계약체결, 구매계약과정에 부당하게 개입한 경우를 금지한다."
		);
		LawSemanticChunkRow procurementOfficeRule = chunk(
			4,
			"law",
			"조달청과 그 소속기관 직제 시행규칙",
			"제7조 구매사업국",
			"조달청장이 지정한 국내물품의 구매계약 및 관리, 소프트웨어 분야 물품의 구매계약을 담당한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"조달청 디지털카달로그에서 구매하면 수의계약 인가?",
			List.of(loosePrivateContractLaw, digitalServiceMallDirectPurchase, brokerRule, procurementOfficeRule),
			Map.of("law:1", 0.95, "official_doc:2", 0.45, "admrul:3", 0.92, "law:4", 0.9),
			8
		);

		assertThat(result.chunks()).containsExactly(digitalServiceMallDirectPurchase);
		assertThat(result.chunks()).doesNotContain(loosePrivateContractLaw);
		assertThat(result.chunks()).doesNotContain(brokerRule, procurementOfficeRule);
		assertThat(result.conceptEvidenceFound()).isTrue();
	}

	@Test
	void sectionMetadataHelpsJudgePreferMatchingIntentSection() {
		LawSemanticChunkRow targetScope = chunk(
			1,
			"official_doc",
			"2024년 정보화사업 사전협의 안내자료",
			"사전협의 대상",
			"사전협의의 대상사업은 예산과목 및 계약방식과 관계없이 대상기관이 추진하는 모든 정보화사업이다.",
			"사전협의 개요",
			"target_scope"
		);
		LawSemanticChunkRow procedure = chunk(
			2,
			"official_doc",
			"2024년 정보화사업 사전협의 안내자료",
			"사전협의 신청 방법",
			"대상기관은 사전협의 신청서와 첨부자료를 작성하여 제출한다.",
			"사전협의 신청",
			"procedure"
		);

		EvidenceJudge.Result result = judge.judge(
			"사전협의 대상은?",
			List.of(procedure, targetScope),
			Map.of("official_doc:1", 0.4, "official_doc:2", 0.9),
			8
		);

		assertThat(result.chunks()).containsExactly(targetScope);
		assertThat(result.scoreByChunkId().get("official_doc:1"))
			.isGreaterThan(result.scoreByChunkId().get("official_doc:2"));
	}

	@Test
	void preliminaryReviewQuestionPrefersEgovPerformanceGuidelineScopeEvidence() {
		LawSemanticChunkRow egovPerformance = chunk(
			1,
			"admrul",
			"전자정부 성과관리 지침",
			"제12조 예비검토",
			"다음 해에 정보화사업을 추진하고자 하는 중앙행정기관의 장, 시ㆍ도지사 및 시ㆍ도 교육감은 지능정보화 기본법 시행령 제3조제3항에서 정한 기한까지 사업의 목적, 이용자 등 사업의 적용 범위, 구현기능, 소요비용을 협의기관의 장에게 제출하고 예비검토를 신청하여야 한다. 예비검토를 신청하는 사업에 공공애플리케이션을 포함한다."
		);
		LawSemanticChunkRow environmentReview = chunk(
			2,
			"admrul",
			"댐 및 주변지역 친환경 활용계획 평가 및 평가위원회 구성·운영에 관한 규정",
			"제2조 예비검토",
			"예비검토란 활용계획에 대해 기후에너지환경부 업무 담당자가 활용계획 구성의 적정성 및 서류의 완비 여부 등을 확인하는 것을 말한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"지능정보사회 실행계획의 예비검토는 어떤 사업을 대상으로 하는거야?",
			List.of(environmentReview, egovPerformance),
			Map.of("admrul:1", 0.45, "admrul:2", 0.95),
			8
		);

		assertThat(result.chunks()).containsExactly(egovPerformance);
		assertThat(result.chunks()).doesNotContain(environmentReview);
		assertThat(result.directEvidenceFound()).isTrue();
	}

	@Test
	void rerankerBoostsQuestionPreferredDocumentTargetWithoutFilteringOthers() {
		EvidenceReranker reranker = new EvidenceReranker();
		QuestionIntentProfile profile = QuestionIntentProfile.from("IRM 사용자 권한 가이드");
		LawSemanticChunkRow officialManual = chunk(
			1,
			"official_doc",
			"IRM 정보자원 등록관리 업무 담당자 기본 교육자료",
			"사용자 권한",
			"IRM 사용자 권한과 정보자원관리시스템 등록 절차를 설명한다."
		);
		LawSemanticChunkRow lawLikeChunk = chunk(
			2,
			"law",
			"IRM 정보자원 등록관리 업무 담당자 기본 교육자료",
			"사용자 권한",
			"IRM 사용자 권한과 정보자원관리시스템 등록 절차를 설명한다."
		);

		double officialScore = reranker.score(officialManual, profile);
		double lawScore = reranker.score(lawLikeChunk, profile);

		assertThat(profile.preferredTargets()).contains("official_doc", "internal_doc");
		assertThat(officialScore).isGreaterThan(lawScore);
	}

	@Test
	void privacyPurposeQuestionAcceptsPolicyDisclosureEvidence() {
		LawSemanticChunkRow policyPurpose = chunk(
			1,
			"law",
			"개인정보 보호법",
			"제30조(개인정보 처리방침의 수립 및 공개) 문단 2 / 줄 1",
			"""
				제30조(개인정보 처리방침의 수립 및 공개)
				1. 개인정보의 처리 목적
				① 개인정보처리자는 다음 각 호의 사항이 포함된 개인정보의 처리 방침을 정하여야 한다.
				개인정보 처리방침을 정보주체가 쉽게 확인할 수 있는 방법으로 공개하고 있는지 여부
				"""
		);
		LawSemanticChunkRow sourceNotice = chunk(
			2,
			"law",
			"개인정보 보호법",
			"제20조(정보주체 이외로부터 수집한 개인정보의 수집 출처 등 통지)",
			"""
				정보주체 이외로부터 수집한 개인정보의 수집 출처 등 통지
				2. 개인정보의 처리 목적
				3. 개인정보 처리의 정지를 요구할 권리가 있다는 사실
				"""
		);

		EvidenceJudge.Result result = judge.judge(
			"개인정보 보호법상 개인정보 처리 목적은 어떻게 알려야 해?",
			List.of(sourceNotice, policyPurpose),
			Map.of(),
			6
		);

		assertThat(result.chunks()).contains(policyPurpose);
		assertThat(result.chunks()).doesNotContain(sourceNotice);
		assertThat(result.directEvidenceFound()).isTrue();
	}

	@Test
	void regularQuestionSuppressesLawRevisionMarkerOnlyChunks() {
		LawSemanticChunkRow revisionMarker = chunk(
			1,
			"law",
			"개인정보 보호법",
			"제30조(개인정보 처리방침의 수립 및 공개) 문단 1 / 줄 2",
			"<개정"
		);
		LawSemanticChunkRow directEvidence = chunk(
			2,
			"law",
			"개인정보 보호법",
			"제30조(개인정보 처리방침의 수립 및 공개) 문단 2 / 줄 1",
			"개인정보처리자는 개인정보의 처리 목적이 포함된 개인정보 처리방침을 정하여야 하고 정보주체가 쉽게 확인할 수 있도록 공개하여야 한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"개인정보 처리 목적은 어떻게 알려야 해?",
			List.of(revisionMarker, directEvidence),
			Map.of("law:1", 0.99, "law:2", 0.3),
			6
		);

		assertThat(result.chunks()).contains(directEvidence);
		assertThat(result.chunks()).doesNotContain(revisionMarker);
		assertThat(result.directEvidenceFound()).isTrue();
	}

	@Test
	void revisionQuestionDoesNotBlindlySuppressRevisionMarkerChunks() {
		LawSemanticChunkRow revisionMarker = chunk(
			1,
			"law",
			"개인정보 보호법",
			"제30조(개인정보 처리방침의 수립 및 공개) 문단 1 / 줄 2",
			"<개정 2024.3.15>"
		);

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(revisionMarker, "제30조 개정일 알려줘"))
			.isFalse();
	}

	@Test
	void autonomyPreConsultationQuestionRejectsGenericEgovPreConsultationEvidence() {
		LawSemanticChunkRow genericEgov = chunk(
			1,
			"official_doc",
			"2025년 문화체육관광부 정보화사업 사전협의 안내서",
			"p.2 사전협의 대상사업",
			"사전협의 대상사업은 예산과목 및 계약방식과 관계없이 대상기관이 추진하는 모든 정보화사업에 해당한다."
		);
		LawSemanticChunkRow autonomy = chunk(
			2,
			"official_doc",
			"자치분권 사전협의 지침(2024년판)",
			"p.8 대상 기관",
			"대상기관 : 법령 제·개정 권한이 있는 중앙행정기관 - 모든 제·개정 법령안에 대한 자치분권 사전협의 요청, 조문별 제·개정이유서 등 관련 자료 작성·제출"
		);

		EvidenceJudge.Result result = judge.judge(
			"자치분권 사전협의 대상기관은 어디야?",
			List.of(genericEgov, autonomy),
			Map.of("official_doc:1", 0.99, "official_doc:2", 0.2),
			6
		);

		assertThat(result.chunks()).contains(autonomy);
		assertThat(result.chunks()).doesNotContain(genericEgov);
		assertThat(result.directEvidenceFound()).isTrue();
	}

	@Test
	void autonomyPreConsultationProcedureQuestionPrefersProcedureFlowOverTargetOnlyEvidence() {
		LawSemanticChunkRow targetOnly = chunk(
			1,
			"official_doc",
			"자치분권 사전협의 지침(2024년판)",
			"p.8 대상 기관",
			"대상기관 : 법령 제·개정 권한이 있는 중앙행정기관 - 모든 제·개정 법령안에 대한 자치분권 사전협의 요청, 조문별 제·개정이유서 등 관련 자료 작성·제출"
		);
		LawSemanticChunkRow procedureFlow = chunk(
			2,
			"official_doc",
			"자치분권 사전협의 지침(2024년판)",
			"p.19 협의절차 및 내용",
			"협의절차 전체 흐름도 : 사전협의 요청서 작성·제출, 지방자치 관련성 검토, 지방자치단체 및 자문단 의견조회, 법령안 검토, 협의 결과서 통보 순서로 진행된다."
		);

		EvidenceJudge.Result result = judge.judge(
			"자치분권 사전협의 절차는 어떻게 돼?",
			List.of(targetOnly, procedureFlow),
			Map.of("official_doc:1", 0.95, "official_doc:2", 0.2),
			6
		);

		assertThat(result.chunks()).contains(procedureFlow);
		assertThat(result.chunks()).doesNotContain(targetOnly);
		assertThat(result.directEvidenceFound()).isTrue();
	}

	@Test
	void autonomyPreConsultationProcedureQuestionAcceptsExtractedProcedureFlowPage() {
		LawSemanticChunkRow procedureFlow = chunk(
			1,
			"official_doc",
			"자치분권 사전협의 지침(2024년판)",
			"p.19 Ⅱ. 협의절차 및 검토항목",
			"""
			Ⅱ. 협의절차 및 검토항목
			2. 협의절차 및 내용
			< 협의절차 전체 흐름도 >
			사전협의 요청서 작성·제출
			(중앙행정기관장 → 행정안전부장관)
			①
			지방자치 관련성 검토
			(행정안전부장관)
			②
			소관 부처는 사전협의 지침에 따라 『사전협의 요청서』를 작성하여 행정안전부장관에게 제출한다.
			행정안전부장관은 제·개정 법령안의 지방자치 관련성을 검토한다.
			법령안 검토 후 협의 결과서 통보 순서로 진행한다.
			"""
		);

		EvidenceJudge.Result result = judge.judge(
			"자치분권 사전협의 절차는 어떻게 돼?",
			List.of(procedureFlow),
			Map.of("official_doc:1", 0.95),
			6
		);

		assertThat(result.directEvidenceRequired()).isTrue();
		assertThat(result.selectionPolicy()).isEqualTo("direct");
		assertThat(result.chunks()).containsExactly(procedureFlow);
		assertThat(result.directEvidenceFound()).isTrue();
	}

	@Test
	void tvingSmishingQuestionPrefersSpecificReportProcedureOverGenericIncidentReport() {
		LawSemanticChunkRow genericIncident = chunk(
			1,
			"official_doc",
			"개인정보유출등사고대응매뉴얼",
			"p.44 침해사고 신고",
			"침해사고가 발생하면 즉시 관계 기관에 신고한다. 침해사고 신고 : KISA 보호나라 → 침해사고 신고 → 신고하기"
		);
		LawSemanticChunkRow tvingProcedure = chunk(
			2,
			"official_doc",
			"과기정통부 티빙(TVING) 침해사고 조사 착수",
			"p.2 스미싱 피해 신고 절차",
			"티빙(TVING) 침해사고 관련 스미싱 피해 신고 절차는 통신사 고객센터를 통해 소액결제확인서를 발급받고, 관할 경찰서 사이버수사대 또는 민원실을 방문하여 신고한 뒤 사건사고 사실 확인서를 발급받는 것이다."
		);

		EvidenceJudge.Result result = judge.judge(
			"티빙 침해사고 관련 스미싱 피해는 어떻게 신고해?",
			List.of(genericIncident, tvingProcedure),
			Map.of("official_doc:1", 0.95, "official_doc:2", 0.25),
			6
		);

		assertThat(result.chunks()).contains(tvingProcedure);
		assertThat(result.chunks()).doesNotContain(genericIncident);
		assertThat(result.directEvidenceFound()).isTrue();
	}

	@Test
	void cctvPublicPlaceExceptionQuestionAcceptsOfficialTermEvidence() {
		LawSemanticChunkRow procedureOnly = chunk(
			1,
			"official_doc",
			"★고정형 영상정보처리기기 설치 운영 안내서(2024.12)",
			"p.21 절차",
			"공공 기관의 장은 고정형 영상정보처리기기 관련 지침의 준수 여부를 점검하고 개인정보보호위원회에 통보하여야 한다."
		);
		LawSemanticChunkRow directException = chunk(
			2,
			"official_doc",
			"★고정형 영상정보처리기기 설치 운영 안내서(2024.12)",
			"p.15 1. 법령에서 구체적으로 허용하고 있는 경우",
			"누구든지 공개된 장소에 고정형 영상정보처리기기를 설치·운영하는 것은 원칙적으로 금지되며, 다른 법익의 보호를 위하여 필요한 경우 예외적으로 설치·운영이 허용된다. 공개된 장소에서의 설치는 법 제25조에서 정하는 사유에 해당하는 경우에만 가능하고, 관련조항에는 법령에서 구체적으로 허용하고 있는 경우가 포함된다."
		);

		EvidenceJudge.Result result = judge.judge(
			"개인정보보호위원회 CCTV 안내서에서 공개된 장소에 CCTV를 설치할 수 있는 예외는?",
			List.of(procedureOnly, directException),
			Map.of("official_doc:1", 0.95, "official_doc:2", 0.3),
			6
		);

		assertThat(result.directEvidenceRequired()).isTrue();
		assertThat(result.directEvidenceFound()).isTrue();
		assertThat(result.selectionPolicy()).isEqualTo("direct");
		assertThat(result.chunks()).containsExactly(directException);
	}

	@Test
	void genericTargetScopeQuestionAcceptsStrongDocumentAndSectionAnchoredEvidence() {
		LawSemanticChunkRow cctvTarget = chunk(
			1,
			"official_doc",
			"공공기관 고정형 영상정보처리기기 설치·운영 가이드라인",
			"p.4 적용 대상",
			"공공기관이 공개된 장소에 고정형 영상정보처리기기를 설치·운영하는 경우 적용 대상이 된다.",
			"적용 대상",
			"target_scope"
		);
		LawSemanticChunkRow standardizationTarget = chunk(
			2,
			"official_doc",
			"공공데이터베이스 표준화 관리 매뉴얼(2026. 4.)",
			"p.12 표준화 대상 및 적용 범위",
			"공공데이터베이스 표준화 대상은 공공기관이 생성 또는 취득하여 관리하는 공공데이터베이스이다. 적용 범위는 공공데이터베이스의 구축ㆍ운영ㆍ관리 전반이다.",
			"표준화 대상 및 적용 범위",
			"target_scope"
		);

		EvidenceJudge.Result result = judge.judge(
			"공공데이터포털 공공데이터베이스 표준화 관리 매뉴얼에서 표준화 대상은 뭐야?",
			List.of(cctvTarget, standardizationTarget),
			Map.of("official_doc:1", 0.95, "official_doc:2", 0.35),
			6
		);

		assertThat(result.directEvidenceFound()).isTrue();
		assertThat(result.selectionPolicy()).isEqualTo("direct");
		assertThat(result.chunks()).containsExactly(standardizationTarget);
		assertThat(result.chunks()).doesNotContain(cctvTarget);
	}

	@Test
	void publicDataStandardizationRequirementQuestionAcceptsStandardTermEvidence() {
		LawSemanticChunkRow standardTerm = chunk(
			1,
			"official_doc",
			"공공데이터베이스 표준화 관리 매뉴얼(2026. 4.)",
			"p.18 표준용어",
			"표준용어는 공공데이터베이스의 동일한 의미를 일관성 있게 관리하기 위해 정의한다. 데이터 표준과 표준도메인, 표준코드는 기관 내 데이터의 중복과 혼선을 줄이기 위해 관리한다.",
			"표준용어",
			"requirement"
		);
		LawSemanticChunkRow unrelated = chunk(
			2,
			"official_doc",
			"공공데이터 관리지침",
			"p.3 공공데이터 제공",
			"공공데이터 제공 절차와 이용 활성화 계획을 설명한다.",
			"공공데이터 제공",
			"operation_rule"
		);

		EvidenceJudge.Result result = judge.judge(
			"공공데이터베이스 표준용어는 왜 관리해?",
			List.of(unrelated, standardTerm),
			Map.of("official_doc:1", 0.35, "official_doc:2", 0.95),
			6
		);

		assertThat(result.directEvidenceFound()).isTrue();
		assertThat(result.selectionPolicy()).isEqualTo("direct");
		assertThat(result.chunks()).first().isEqualTo(standardTerm);
	}

	@Test
	void publicDataQualityDiagnosisQuestionPrefersOverviewOverDetailCriteria() {
		LawSemanticChunkRow overview = chunk(
			101,
			"official_doc",
			"1. 공공데이터베이스 표준화 관리 매뉴얼(2026. 4.)",
			"p.39 3.1.4 진단 영역 및 항목",
			"""
			공공데이터베이스 표준화 관리 매뉴얼 30 3.1.4 진단 영역 및 항목
			예방적 품질관리 진단영역은 데이터 표준, 데이터 구조, 데이터 값, 데이터 관리체계 4개 영역으로 구성된다.
			4개의 진단영역은 세부 진단항목으로 구성되며, 현재 적용 중인 진단항목은 총 9개로 항목별 2개의 진단기준으로 구성되어 총 18개의 진단기준을 제시하고 있다.
			""",
			"3.1.4 진단 영역 및 항목",
			"requirement"
		);
		LawSemanticChunkRow detail = chunk(
			102,
			"official_doc",
			"1. 공공데이터베이스 표준화 관리 매뉴얼(2026. 4.)",
			"p.47 ① 데이터 값 검증 계획 반영 여부",
			"""
			공공데이터베이스 표준화 관리 매뉴얼 38 데이터 값 영역은 데이터 값 검증, 이관데이터 값 검증 두 개의 진단항목으로 구성된다.
			진단항목 3.1 데이터 값 검증 진단기준 ① 데이터 값 검증 계획 반영 여부 기준설명 사업 유형별 검토 컨설팅 사업 구축사업
			""",
			"3.2.1 예방적 품질관리 진단 기준",
			"requirement"
		);

		EvidenceJudge.Result result = judge.judge(
			"공공데이터베이스 품질관리 진단은 몇 개 영역으로 보나?",
			List.of(detail, overview),
			Map.of("official_doc:101", 0.3, "official_doc:102", 0.95),
			6
		);

		assertThat(result.chunks()).first().isEqualTo(overview);
		assertThat(result.chunks()).doesNotContain(detail);
	}

	@Test
	void publicDataStandardTermQuestionRejectsQualityDiagnosisAndPrivacyNoise() {
		LawSemanticChunkRow standardTerm = chunk(
			111,
			"official_doc",
			"1. 공공데이터베이스 표준화 관리 매뉴얼(2026. 4.)",
			"p.111 2.1 공통표준용어 개요",
			"""
			공공데이터베이스 표준화 관리 매뉴얼 102 2. 공통표준용어 2.1 공통표준용어 개요
			공통표준용어 표준화란 여러 기관에서 공통적으로 사용하는 용어들 가운데 공통표준용어를 정하고 생성·관리 원칙을 수립하는 것을 의미한다.
			범정부 차원에서 데이터의 용이한 식별 및 융합·분석을 위해 일관된 기준을 제공하기 위한 목적으로 추진된다.
			공통표준용어는 기관별로 상이하게 사용하는 동일한 의미의 유사한 컬럼에 대해 공통의 표준용어를 적용함으로써 컬럼을 쉽게 식별한다.
			""",
			"2. 공통표준용어",
			"body"
		);
		LawSemanticChunkRow noisyDetail = chunk(
			112,
			"official_doc",
			"1. 공공데이터베이스 표준화 관리 매뉴얼(2026. 4.)",
			"p.47 데이터 값 검증 계획 반영 여부",
			"공공데이터베이스 진단기준 기준설명 데이터값 검증과 개인정보 처리 관련 점검사항을 설명한다.",
			"3.2.1 예방적 품질관리 진단 기준",
			"requirement"
		);

		EvidenceJudge.Result result = judge.judge(
			"공공데이터베이스 표준용어는 왜 관리해?",
			List.of(noisyDetail, standardTerm),
			Map.of("official_doc:111", 0.35, "official_doc:112", 0.98),
			6
		);

		assertThat(result.directEvidenceFound()).isTrue();
		assertThat(result.chunks()).first().isEqualTo(standardTerm);
		assertThat(result.chunks()).doesNotContain(noisyDetail);
	}

	@Test
	void exploratoryManualLookupCanReturnTitleAnchoredDocumentWhenDirectEvidenceIsNotNeeded() {
		LawSemanticChunkRow manual = chunk(
			1,
			"official_doc",
			"공공데이터베이스 표준화 관리 매뉴얼(2026. 4.)",
			"p.1 공공데이터베이스 표준화 관리 매뉴얼",
			"공공데이터베이스 표준화 관리 매뉴얼의 표지와 문서 개요이다.",
			"공공데이터베이스 표준화 관리 매뉴얼",
			"body"
		);
		LawSemanticChunkRow unrelated = chunk(
			2,
			"official_doc",
			"고정형 영상정보처리기기 설치·운영 가이드라인",
			"p.1 개요",
			"CCTV 설치 운영 가이드라인 문서 개요이다.",
			"개요",
			"body"
		);

		EvidenceJudge.Result result = judge.judge(
			"공공데이터포털 자료실에 공공데이터베이스 표준화 관리 매뉴얼이 있는지 찾아줘",
			List.of(unrelated, manual),
			Map.of("official_doc:1", 0.35, "official_doc:2", 0.95),
			6
		);

		assertThat(result.chunks()).contains(manual);
		assertThat(result.chunks()).doesNotContain(unrelated);
		assertThat(result.selectionPolicy()).isIn("direct", "exploratory_lookup");
	}

	@Test
	void officialReportBodyQuestionUsesStatisticalBodyEvidence() {
		LawSemanticChunkRow unrelated = chunk(
			1,
			"official_doc",
			"개인정보 처리 통합 안내서",
			"p.10 처리 절차",
			"개인정보처리자는 개인정보 처리 목적과 보유기간을 정보주체에게 알리고 동의를 받아야 한다."
		);
		LawSemanticChunkRow statisticsBody = chunk(
			2,
			"official_doc",
			"2023년 기준 콘텐츠산업조사 결과보고서 승인통계용",
			"p.30 콘텐츠산업 매출 현황",
			"콘텐츠산업조사 결과보고서는 콘텐츠산업 사업체 수, 종사자 수, 매출액, 부가가치, 수출입 등 승인통계 항목을 조사하여 콘텐츠산업 현황을 제시한다.",
			"콘텐츠산업조사",
			"body"
		);

		EvidenceJudge.Result result = judge.judge(
			"문체부 콘텐츠산업 조사 문서는 어떤 통계를 다뤄?",
			List.of(unrelated, statisticsBody),
			Map.of("official_doc:1", 0.95, "official_doc:2", 0.35),
			6
		);

		assertThat(result.conceptEvidenceRequired()).isTrue();
		assertThat(result.conceptEvidenceFound()).isTrue();
		assertThat(result.chunks()).contains(statisticsBody);
		assertThat(result.chunks()).doesNotContain(unrelated);
	}

	// 메소드 설명: chunk 처리 흐름을 수행합니다.
	// 메소드 설명: chunk 처리 흐름을 수행합니다.
	@Test
	void koreanFairUseQuestionAcceptsEnglishCriteriaBodyInsteadOfCoverTitle() {
		LawSemanticChunkRow coverTitle = chunk(
			1,
			"official_doc",
			"Guide on Applicability of the Fair Use Doctrine to Training of Generative AI Models",
			"p.1 Guide on Applicability of",
			"Guide on Applicability of the Fair Use Doctrine to Training of Generative AI Models February 2026"
		);
		LawSemanticChunkRow criteriaBody = chunk(
			2,
			"official_doc",
			"Guide on Applicability of the Fair Use Doctrine to Training of Generative AI Models",
			"p.50 7. Case Studies Related to Fair Use",
			"""
			The explanations are solely for reference purposes to explain the general criteria for determining fair use.
			A general-purpose AI foundation model is trained using copyrighted works.
			If a model is trained to generate outputs whose purpose or character is different from the works used in
			training, there is higher likelihood that fair use may be accepted.
			"""
		);

		EvidenceJudge.Result result = judge.judge(
			"\uC0DD\uC131\uD615 AI \uD559\uC2B5\uC758 \uACF5\uC815\uC774\uC6A9 \uD310\uB2E8 \uADFC\uAC70\uB294?",
			List.of(coverTitle, criteriaBody),
			Map.of("official_doc:1", 0.95, "official_doc:2", 0.65),
			6
		);

		assertThat(result.directEvidenceRequired()).isTrue();
		assertThat(result.directEvidenceFound()).isTrue();
		assertThat(result.chunks()).contains(criteriaBody);
		assertThat(result.chunks()).doesNotContain(coverTitle);
	}

	@Test
	void documentAnchoredBodyEvidenceAcceptsMatchingOfficialReportChunk() {
		LawSemanticChunkRow titleOnlyOrLooseBody = chunk(
			1,
			"official_doc",
			"회복과 도약, 모두의 1년 국민주권정부 123대 국정과제 추진 실적 2026년",
			"p.209 소비자 선택 지원",
			"온라인 다크패턴과 소비자 피해 예방 제도 개선 내용을 설명한다."
		);
		LawSemanticChunkRow bodyEvidence = chunk(
			2,
			"official_doc",
			"회복과 도약, 모두의 1년 국민주권정부 123대 국정과제 추진 실적 2026년",
			"p.38 허위조작정보 대응",
			"""
			주요 포털·플랫폼 사업자로 구성된 자율규제 활성화 협의체 운영을 통해 민관 협력 체계를 강화하였다.
			전국 시청자미디어센터를 통해 국민을 대상으로 대상별·맞춤형 교육을 실시하였다.
			"""
		);

		EvidenceJudge.Result result = judge.judge(
			"회복과 도약, 모두의 1년 국민주권정부 123대 국정과제 추진 실적 2026년 문서에서 포털·플랫폼 사업자로 관련 본문 근거를 찾아줘",
			List.of(titleOnlyOrLooseBody, bodyEvidence),
			Map.of("official_doc:1", 0.95, "official_doc:2", 0.4),
			6
		);

		assertThat(result.directEvidenceFound()).isTrue();
		assertThat(result.chunks()).contains(bodyEvidence);
		assertThat(result.chunks()).doesNotContain(titleOnlyOrLooseBody);
	}

	@Test
	void acceptsExactLawTitleAndArticleReferenceAsDirectEvidence() {
		LawSemanticChunkRow looseDiscipline = chunk(
			1,
			"law",
			"공무원 징계령",
			"제9조",
			"징계위원회는 징계의결 요구서를 접수한 때에는 심의 절차를 진행한다."
		);
		LawSemanticChunkRow exactArticle = chunk(
			2,
			"law",
			"감사원 징계 규칙",
			"제9조(징계의결등의 기한) 등",
			"① 징계위원회는 제8조제2항에 따른 징계의결등 요구서를 접수한 날부터 30일 이내에 징계의결등을 하여야 한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"감사원 징계 규칙에서 징계위원회 제8조제2항 관련 조항 근거를 알려줘",
			List.of(looseDiscipline, exactArticle),
			Map.of("law:1", 1.2, "law:2", 0.2),
			5
		);

		assertThat(result.directEvidenceFound()).isTrue();
		assertThat(result.chunks()).contains(exactArticle);
		assertThat(result.chunks()).doesNotContain(looseDiscipline);
	}

	@Test
	void acceptsExactLawTitleStemWhenChunkTitleIsFoldedIntoTitle() {
		LawSemanticChunkRow looseDiscipline = chunk(
			1,
			"law",
			"공무원 징계령 제9조(징계의결등의 기한) 등",
			"제9조(징계의결등의 기한) 등",
			"징계위원회는 징계의결 요구서를 접수한 때에는 심의 절차를 진행한다."
		);
		LawSemanticChunkRow exactArticle = chunk(
			2,
			"law",
			"감사원 징계 규칙 제9조(징계의결등의 기한) 등",
			"제9조(징계의결등의 기한) 등",
			"① 징계위원회는 제8조제2항에 따른 징계의결등 요구서를 접수한 날부터 30일 이내에 징계의결등을 하여야 한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"감사원 징계 규칙에서 징계위원회 제8조제2항 관련 조항 근거를 알려줘",
			List.of(looseDiscipline, exactArticle),
			Map.of("law:1", 1.2, "law:2", 0.2),
			5
		);

		assertThat(result.directEvidenceFound()).isTrue();
		assertThat(result.chunks()).contains(exactArticle);
		assertThat(result.chunks()).doesNotContain(looseDiscipline);
	}

	@Test
	void rejectsNoticeExceptionRepealSupplementWhenReviewBodyIsMissing() {
		LawSemanticChunkRow repealSupplement = chunk(
			1,
			"admrul",
			"면제/예외 인정에 관한 정책지침",
			"부칙",
			"부칙. 제2조(기존 훈령의 폐지) 면제/예외 인정에 관한 정책지침은 이를 폐지한다. 항공안전 의무보고 운영에 관한 규정 등 일괄개정은 발령한 날부터 시행한다."
		);
		LawSemanticChunkRow reviewBody = chunk(
			2,
			"admrul",
			"면제/예외 인정에 관한 정책지침",
			"제4조(처리기준)",
			"소관 과장은 면제/예외 인정을 함에 있어 다음 각 호의 사항을 충분히 검토하여야 한다. 안전기준, 위험평가, 항공환경적인 상황을 검토한다."
		);

		EvidenceJudge.Result result = judge.judge(
			"면제/예외 인정에 관한 정책지침에서 예외 인정은 무엇을 검토해야 해?",
			List.of(repealSupplement, reviewBody),
			Map.of("admrul:1", 2.0, "admrul:2", 0.1),
			5
		);

		assertThat(result.directEvidenceFound()).isTrue();
		assertThat(result.chunks()).contains(reviewBody);
		assertThat(result.chunks()).doesNotContain(repealSupplement);
	}

	@Test
	void acceptsExactAdministrativeRuleParagraphBodyAnchorEvidence() {
		LawSemanticChunkRow similarWasteRule = chunk(
			1,
			"admrul",
			"폐기물처리사업 및 폐기물처리시설 설치ㆍ운영실태 평가방법 및 절차 등에 관한 규정",
			"제1조(목적)",
			"지방자치단체의 폐기물처리사업 및 폐기물처리시설 설치ㆍ운영 실태 등을 조사ㆍ평가한다."
		);
		LawSemanticChunkRow exactParagraph = chunk(
			2,
			"admrul",
			"폐기물처리시설 설치·운영에 따른 환경상영향조사의 조사항목 및 횟수에 관한 기준",
			"문단 1 / 줄 1",
			"가. 폐기물처리시설설치ㆍ운영에 따른 환경상영향조사기준. 비고 1. 조사항목 중 해당처리시설의 설치운영으로 인하여 주변지역에 환경영향이 없거나 경미하다고 인정되는 조사항목은 조정할 수 있다."
		);

		String question = "폐기물처리시설 설치·운영에 따른 환경상영향조사의 조사항목 및 횟수에 관한 기준에서 폐기물처리시설설치ㆍ운영 환경상영향조사기준 관련 조항 근거를 알려줘";
		EvidenceJudge.Result result = judge.judge(
			question,
			List.of(similarWasteRule, exactParagraph),
			Map.of("admrul:1", 2.0, "admrul:2", 0.1),
			5
		);

		assertThat(result.directEvidenceFound())
			.as("profile=%s result=%s", QuestionIntentProfile.from(question), result)
			.isTrue();
		assertThat(result.chunks()).contains(exactParagraph);
		assertThat(result.chunks()).doesNotContain(similarWasteRule);
	}

	private LawSemanticChunkRow chunk(long id, String title, String text) {
		return chunk(id, title, "p." + id, text);
	}

	private LawSemanticChunkRow chunk(long id, String title, String chunkTitle, String text) {
		return chunk(id, "official_doc", title, chunkTitle, text);
	}

	private LawSemanticChunkRow chunk(long id, String target, String title, String chunkTitle, String text) {
		return chunk(id, target, title, chunkTitle, text, null, null);
	}

	private LawSemanticChunkRow chunk(
		long id,
		String target,
		String title,
		String chunkTitle,
		String text,
		String parentSectionTitle,
		String sectionType
	) {
		return new LawSemanticChunkRow(
			id,
			1,
			target,
			String.valueOf(id),
			title,
			"기관",
			"공식 가이드 문서",
			null,
			null,
			"page " + id,
			chunkTitle,
			text,
			(int) id,
			null,
			null,
			(int) id,
			"hash-" + id,
			parentSectionTitle,
			sectionType
		);
	}
}
