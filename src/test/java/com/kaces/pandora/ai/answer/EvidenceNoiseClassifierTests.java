package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import org.junit.jupiter.api.Test;

class EvidenceNoiseClassifierTests {

	@Test
	void suppressesImageOnlyInstructionChunks() {
		LawSemanticChunkRow chunk = chunk("official_doc", "<img src=\"guide.png\" alt=\"\">");

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(chunk, "개인정보 처리방침 공개 기준 알려줘"))
			.isTrue();
	}

	@Test
	void suppressesShortAttachmentNavigationNotices() {
		LawSemanticChunkRow chunk = chunk("official_doc", "자세한 내용은 상단 첨부파일을 다운로드하여 확인하십시오.");

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(chunk, "공공데이터 제공 신청 절차 알려줘"))
			.isTrue();
	}

	@Test
	void suppressesNavigationNoticeEvenWhenDocumentTitleContainsPolicyWords() {
		LawSemanticChunkRow chunk = chunk("admrul", "2025년도 기록관리기준표의 자세한 내용은 상단 메뉴 버튼을 이용하십시오.");

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(chunk, "기록관리 기준 알려줘"))
			.isTrue();
	}

	@Test
	void keepsSubstantiveShortLegalEvidence() {
		LawSemanticChunkRow chunk = chunk("law", "개인정보처리자는 개인정보 처리방침을 공개하여야 한다.");

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(chunk, "개인정보 처리방침 공개해야 해?"))
			.isFalse();
	}

	@Test
	void suppressesRevisionMarkersUnlessQuestionAsksRevisionHistory() {
		LawSemanticChunkRow chunk = chunk("law", "<개정 2024. 3. 15.>");

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(chunk, "개인정보 처리방침 공개 기준 알려줘"))
			.isTrue();
		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(chunk, "개정 이력 알려줘"))
			.isFalse();
	}

	@Test
	void downranksShortHeadingFragmentsWithoutSuppressingThem() {
		LawSemanticChunkRow chunk = chunk("official_doc", "1. Overview");

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(chunk, "project review overview"))
			.isFalse();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(chunk))
			.isTrue();
	}

	@Test
	void keepsSubstantiveShortOfficialEvidenceOutOfContextOnlyPenalty() {
		LawSemanticChunkRow chunk = chunk("official_doc", "Applicants must submit the project plan before review.");

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(chunk, "what should applicants submit"))
			.isFalse();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(chunk))
			.isFalse();
	}

	@Test
	void suppressesRepeatedPublicationFooterFragments() {
		LawSemanticChunkRow chunk = chunk(
			"official_doc",
			"Restoring Public Finances Enabling Effective Government",
			"RESTORING PUBLIC FINANCES © OECD 2026"
		);

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(chunk, "public finances report"))
			.isTrue();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(chunk))
			.isTrue();
	}

	@Test
	void downranksShortDocumentTitleOnlyFragmentsWithoutSuppressingSubstantiveEvidence() {
		LawSemanticChunkRow titleOnly = chunk(
			"official_doc",
			"Restoring Public Finances Enabling Effective Government",
			"Restoring Public Finances"
		);
		LawSemanticChunkRow substantive = chunk(
			"official_doc",
			"통일백서",
			"대상 기관은 소속 공무원과 직원에게 매년 1회 이상 통일교육을 실시하여야 한다."
		);

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(titleOnly, "public finances"))
			.isFalse();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(titleOnly))
			.isTrue();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(substantive))
			.isFalse();
	}

	@Test
	void downranksRequirementFieldLabelsWithoutSuppressingThem() {
		LawSemanticChunkRow label = chunk("official_doc", "공공데이터베이스 표준화 관리 매뉴얼", "요구사항 고유번호 DAR-001");
		LawSemanticChunkRow evidence = chunk("official_doc", "공공데이터베이스 표준화 관리 매뉴얼", "요구사항 명칭 데이터 표준 관리");

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(label, "데이터 표준 관리 요구사항"))
			.isFalse();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(label))
			.isTrue();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(evidence))
			.isTrue();
	}

	@Test
	void suppressesBareSentenceTailFragments() {
		LawSemanticChunkRow chunk = chunk("official_doc", "다.");

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(chunk, "국가이미지 조사 결과"))
			.isTrue();
	}

	@Test
	void suppressesStandaloneTableUnitPageMarkers() {
		LawSemanticChunkRow chunk = chunk("official_doc", "인구동향조사 보도자료", "- 44 - - 자연증가 - (단위: 명)");

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(chunk, "자연증가 통계 의미 알려줘"))
			.isTrue();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(chunk))
			.isTrue();
	}

	@Test
	void downranksRunningHeaderEchoesWithoutSuppressingThem() {
		LawSemanticChunkRow chunk = chunk(
			"official_doc",
			"회복과 도약, 모두의 1년 국민주권정부 123대 국정과제 추진 실적 2026년",
			"019국민이 만든 대전환의 길 국민주권정부 123대 국정과제 추진 실적"
		);

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(chunk, "국정과제 추진 실적 알려줘"))
			.isFalse();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(chunk))
			.isTrue();
	}

	@Test
	void downranksCoverTitleEchoesWithDatesWithoutSuppressingThem() {
		LawSemanticChunkRow chunk = chunk(
			"official_doc",
			"Guide on Applicability of the Fair Use Doctrine to Training of Generative AI Models",
			"Guide on Applicability of the Fair Use Doctrine to Training of Generative AI Models February 2026"
		);

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(chunk, "fair use doctrine guide"))
			.isFalse();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(chunk))
			.isTrue();
	}

	@Test
	void downranksRomanNumeralTocAndEnglishWhitePaperHeaders() {
		LawSemanticChunkRow romanToc = chunk("official_doc", "월간 재정동향 2026년 5월", "Ⅲ. 주요 재정통계 21");
		LawSemanticChunkRow whitePaperHeader = chunk(
			"official_doc",
			"2026 통일백서",
			"UNIFICATION WHITE PAPER 남북대화 제1절 남북대화 재개 노력"
		);

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(romanToc, "재정통계 주요 내용"))
			.isFalse();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(romanToc))
			.isTrue();
		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(whitePaperHeader, "남북대화 재개 노력"))
			.isFalse();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(whitePaperHeader))
			.isTrue();
	}

	@Test
	void downranksDenseTocListsWithoutTreatingThemAsEvidence() {
		LawSemanticChunkRow numberedToc = chunk(
			"official_doc",
			"월간 재정동향 2026년 5월",
			"1. 총수입 2. 총지출 3. 재정수지 4. 국가채무 5. 국채시장"
		);
		LawSemanticChunkRow appendixToc = chunk(
			"official_doc",
			"2026 통일백서",
			"UNIFICATION WHITE PAPER Ⅰ. 남북관계 주요 일지 Ⅱ. 남북관계 관련 주요 통계 Ⅲ. 남북협력기금 관련 통계 Ⅳ. 통일부 국정과제 현황 부록 APPENDIX"
		);

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(numberedToc, "국가채무 현황 알려줘"))
			.isFalse();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(numberedToc))
			.isTrue();
		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(appendixToc, "통일교육 지원 근거 알려줘"))
			.isFalse();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(appendixToc))
			.isTrue();
	}

	@Test
	void keepsShortSubstantiveTargetScopeDespitePageOrTitleCues() {
		LawSemanticChunkRow chunk = chunk(
			"official_doc",
			"2026 통일백서",
			"대상 기관은 소속 공무원과 직원에게 매년 1회 이상 통일교육을 실시하여야 한다."
		);

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(chunk, "공공부문 통일교육 대상 기관은?"))
			.isFalse();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(chunk))
			.isFalse();
	}

	@Test
	void downranksPageWrappedHeadingsWithoutSuppressingThem() {
		LawSemanticChunkRow stageHeading = chunk(
			"official_doc",
			"의약품 중 불순물 저감화 사례집",
			"- 29 - 제제화단계 (메트포르민 중 NDMA 저감화)"
		);
		LawSemanticChunkRow formHeading = chunk(
			"official_doc",
			"아동보호서비스 업무 매뉴얼",
			"| 281 (뒤쪽) 가정환경 (성장 과정) 상담자 의견 지도·판정"
		);
		LawSemanticChunkRow figureCaption = chunk(
			"official_doc",
			"세계유산 창덕궁과 종묘에 사용된 석재의 과학정보",
			"37Ⅱ. 보존현황 및 부재번호 그림 20. 창덕궁 돈화문 전각에 사용된 석재의 부재번호2."
		);

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(stageHeading, "NDMA 저감화 기준 알려줘"))
			.isFalse();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(stageHeading))
			.isTrue();
		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(formHeading, "아동보호 상담자 의견 작성 방법"))
			.isFalse();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(formHeading))
			.isTrue();
		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(figureCaption, "창덕궁 석재 부재번호"))
			.isFalse();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(figureCaption))
			.isTrue();
	}

	@Test
	void downranksBrokenTitleEchoButKeepsRequirementEvidence() {
		LawSemanticChunkRow titleEcho = chunk(
			"official_doc",
			"Restoring Public Finances Enabling Effective Government",
			"Restoring Public Finances Enabling Effective Government Restoring Public Finances Enabling Effective Governm ent"
		);
		LawSemanticChunkRow requirementEvidence = chunk(
			"official_doc",
			"KOREA AEROSPACE ADMINISTRATION AIRCRAFT COMPONENT ENVIRONMENTAL TESTING & EVALUATION",
			"요구사항에 대한 추적성 확보 및 표준 규격을 충족함과 동시에 세부 추가 조건에 대한 비교 검증 실시"
		);

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(titleEcho, "public finances"))
			.isFalse();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(titleEcho))
			.isTrue();
		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(requirementEvidence, "항공 부품 검증 요구사항"))
			.isFalse();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(requirementEvidence))
			.isFalse();
	}

	@Test
	void downranksResidualShortStructureFragments() {
		LawSemanticChunkRow bracketedIndex = chunk("official_doc", "정보화사업 사전협의 안내서", "[전자정부] ⑬");
		LawSemanticChunkRow reportHeading = chunk("official_doc", "2024년도 국가이미지 조사 결과보고서", "조사 개요 01 국가이미지 조사 보고서");
		LawSemanticChunkRow midPageHeading = chunk(
			"official_doc",
			"대한민국 문화도시 조성계획",
			"전주문화도시 조성계획서 - 12 - ■ 대한민국 문화도시 전주의 비전 체계"
		);
		LawSemanticChunkRow decorative = chunk(
			"official_doc",
			"대한민국 문화도시 조성계획",
			"三樂   三樂    三樂  三寶三樂 三寶 三樂"
		);
		LawSemanticChunkRow fieldLabel = chunk("official_doc", "정보화사업 사전협의 안내서", "준수항목");
		LawSemanticChunkRow reportTitle = chunk("official_doc", "2025 국가이미지 조사", "외국인 조사표 국가이미지 조사 보고서1");
		LawSemanticChunkRow figureCaption = chunk(
			"official_doc",
			"2025 국가이미지 조사",
			"나. 국가별 결과 (단위 : %, 중복응답, 국가별 상위 5위) [그림 2-23] 국가별 한국 이미지"
		);
		LawSemanticChunkRow coverLogo = chunk("official_doc", "대한민국 문화도시 조성계획", "음식문화도시 속초 Culinary City SOKCHO");

		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(bracketedIndex))
			.isTrue();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(reportHeading))
			.isTrue();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(midPageHeading))
			.isTrue();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(decorative))
			.isTrue();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(fieldLabel))
			.isTrue();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(reportTitle))
			.isTrue();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(figureCaption))
			.isTrue();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(coverLogo))
			.isTrue();
	}

	@Test
	void keepsShortStatisticalFootnoteWithSubstantiveExclusionSignal() {
		LawSemanticChunkRow chunk = chunk(
			"official_doc",
			"산업활동동향 보도자료",
			"전자·통신을 제외한 제조업 생산지수임 반도체를 제외한 제조업 생산지수임"
		);

		assertThat(EvidenceNoiseClassifier.shouldSuppressAsEvidence(chunk, "제조업 생산지수 제외 기준"))
			.isFalse();
		assertThat(EvidenceNoiseClassifier.shouldDownrankAsContextOnly(chunk))
			.isFalse();
	}

	private LawSemanticChunkRow chunk(String target, String text) {
		return chunk(target, "테스트 문서", text);
	}

	private LawSemanticChunkRow chunk(String target, String title, String text) {
		return new LawSemanticChunkRow(
			1,
			1,
			target,
			"external-1",
			title,
			"기관",
			"분류",
			null,
			"CURRENT",
			"제1조",
			"테스트",
			text,
			1,
			null,
			null,
			1,
			"hash-1",
			"상위",
			"body"
		);
	}
}
