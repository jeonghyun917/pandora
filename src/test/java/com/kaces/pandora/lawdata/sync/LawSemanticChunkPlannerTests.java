package com.kaces.pandora.lawdata.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static com.kaces.pandora.common.text.LawHashUtils.sha256;

import java.util.List;
import org.junit.jupiter.api.Test;

class LawSemanticChunkPlannerTests {

	private final LawSemanticChunkPlanner planner = new LawSemanticChunkPlanner();
	private final ChunkPlanningContext planningContext = new ChunkPlanningContext("law", 41L, "Personal Information Protection Act");

	@Test
	void planAssignsVersionedParentChildMetadataForSplitProvision() {
		String article = "Article 9 (Exception) "
			+ "A controller may retain information only when the statutory exception applies. ".repeat(110);

		List<PlannedLawChunk> chunks = planner.plan(planningContext, List.of(
			new SyncDetailSection(
				"article",
				"Article 9",
				"Article 9 (Exception)",
				article,
				"$.law.articles[8].body",
				9,
				1
			)
		));

		assertThat(chunks).hasSizeGreaterThan(1);
		assertThat(chunks).allSatisfy(chunk -> {
			assertThat(chunk.chunkSchemaVersion()).isEqualTo(2);
			assertThat(chunk.parentKey()).matches("[0-9a-f]{64}");
			assertThat(chunk.parentTitle()).isNotBlank();
			assertThat(chunk.childOrder()).isGreaterThanOrEqualTo(0);
			assertThat(chunk.qualityStatus()).isIn("PASS", "CONTEXT_ONLY", "REVIEW", "REJECT");
			assertThat(chunk.embeddingText()).contains(
				"Personal Information Protection Act",
				"Article 9",
				"Article 9 (Exception)",
				"article",
				chunk.text()
			);
		});
		assertThat(chunks).extracting(PlannedLawChunk::parentKey).containsOnly(chunks.get(0).parentKey());
		assertThat(chunks).extracting(PlannedLawChunk::childOrder).containsExactly(0, 1, 2, 3);
	}

	@Test
	void planUsesDistinctDocumentScopedParentKeysForAdjacentArticles() {
		List<PlannedLawChunk> chunks = planner.plan(planningContext, List.of(
			new SyncDetailSection("article", "Article 1", "Article 1 (Purpose)", "Purpose ".repeat(130), "$.law.articles[0].body", 1, 1),
			new SyncDetailSection("article", "Article 2", "Article 2 (Scope)", "Scope ".repeat(130), "$.law.articles[1].body", 2, 1)
		));

		assertThat(chunks).hasSize(2);
		assertThat(chunks).extracting(PlannedLawChunk::parentKey).doesNotHaveDuplicates();
	}

	@Test
	void planSeparatesAdjacentArticlesWithTheSameTitleByCanonicalPathAndNumber() {
		List<PlannedLawChunk> chunks = planner.plan(planningContext, List.of(
			new SyncDetailSection("article", "Article 1", "Article (General)", "First provision ".repeat(80), "$.law.articles[0].body", 1, 1),
			new SyncDetailSection("article", "Article 2", "Article (General)", "Second provision ".repeat(80), "$.law.articles[1].body", 2, 1)
		));

		assertThat(chunks).hasSize(2);
		assertThat(chunks).extracting(PlannedLawChunk::parentKey).doesNotHaveDuplicates();
		assertThat(chunks.get(0).parentKey()).isEqualTo(sha256("law\n41\n$.law.articles\nArticle 1"));
		assertThat(chunks.get(0).parentSourcePath()).isEqualTo("$.law.articles");
	}

	@Test
	void planKeepsMeaningfulShortExceptionWithVersionedMetadata() {
		List<PlannedLawChunk> chunks = planner.plan(planningContext, List.of(
			new SyncDetailSection("article", "Article 12", "Article 12 (Exception)",
				"Exception: retention remains permitted when an applicable statute requires it.",
				"$.law.articles[11].body", 12, 1)
		));

		assertThat(chunks).singleElement().satisfies(chunk -> {
			assertThat(chunk.text()).contains("Exception:");
			assertThat(chunk.chunkSchemaVersion()).isEqualTo(2);
			assertThat(chunk.qualityStatus()).isEqualTo("PASS");
		});
	}

	@Test
	void planMergesLineLevelLawFragmentsUnderSameProvision() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("paragraph", "제3조 문단1 줄1", "제3조(피해자 및 피해종교단체의 명예회복) 문단 1 / 줄 1", "위원회는 피해자의 명예회복에 필요한 사항을 심의한다.", "$.법령.조문.조문단위[2].항.항단위[0].항내용", 1, 1),
			new SyncDetailSection("paragraph", "제3조 문단1 줄2", "제3조(피해자 및 피해종교단체의 명예회복) 문단 1 / 줄 2", "<개정", "$.법령.조문.조문단위[2].항.항단위[0].항내용", 1, 2),
			new SyncDetailSection("paragraph", "제3조 문단1 줄3", "제3조(피해자 및 피해종교단체의 명예회복) 문단 1 / 줄 3", "2023.8.8>", "$.법령.조문.조문단위[2].항.항단위[0].항내용", 1, 3),
			new SyncDetailSection("subparagraph", "제3조 문단2 줄1", "제3조(피해자 및 피해종교단체의 명예회복) 문단 2 / 줄 1", "1. 피해 사실 조사에 관한 사항", "$.법령.조문.조문단위[2].항.항단위[0].호.호단위[0].호내용", 2, 1)
		));

		assertThat(chunks).hasSize(1);
		PlannedLawChunk chunk = chunks.get(0);
		assertThat(chunk.title()).isEqualTo("제3조(피해자 및 피해종교단체의 명예회복)");
		assertThat(chunk.no()).isEqualTo("제3조");
		assertThat(chunk.text()).contains("위원회는 피해자의 명예회복에 필요한 사항을 심의한다.");
		assertThat(chunk.text()).contains("<개정");
		assertThat(chunk.text()).contains("1. 피해 사실 조사에 관한 사항");
	}

	@Test
	void planCoalescesAdjacentShortProvisionsIntoSearchSizedChunk() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			shortArticle(1),
			shortArticle(2),
			shortArticle(3),
			shortArticle(4),
			shortArticle(5)
		));

		assertThat(chunks).hasSize(1);
		PlannedLawChunk chunk = chunks.get(0);
		assertThat(chunk.no()).isEqualTo("제1조 등");
		assertThat(chunk.title()).isEqualTo("제1조(시험 조문 1) 등");
		assertThat(chunk.text().length()).isGreaterThanOrEqualTo(800);
		assertThat(chunk.text().length()).isLessThanOrEqualTo(2_500);
		assertThat(chunk.text()).contains("제1조(시험 조문 1)");
		assertThat(chunk.text()).contains("제5조(시험 조문 5)");
	}

	@Test
	void planMergesTrailingShortProvisionIntoPreviousChunk() {
		String longArticle = "제1조(긴 조문) " + "가".repeat(1_500);
		String shortArticle = "제2조(짧은 조문) 삭제";

		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("article", "제1조", "제1조(긴 조문)", longArticle, "$.법령.조문.조문단위[1].조문내용", 1, 1),
			new SyncDetailSection("article", "제2조", "제2조(짧은 조문)", shortArticle, "$.법령.조문.조문단위[2].조문내용", 2, 1)
		));

		assertThat(chunks).hasSize(1);
		assertThat(chunks.get(0).text()).contains("제1조(긴 조문)");
		assertThat(chunks.get(0).text()).contains("제2조(짧은 조문)");
	}

	@Test
	void planRebalancesTinyOrphanWithoutCreatingOversizedSearchChunk() {
		String longArticle = "제1조(긴 조문) " + "가".repeat(2_484);
		String tinyArticle = "제2조 삭제";

		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("article", "제1조", "제1조(긴 조문)", longArticle, "$.법령.조문.조문단위[1].조문내용", 1, 1),
			new SyncDetailSection("article", "제2조", "제2조", tinyArticle, "$.법령.조문.조문단위[2].조문내용", 2, 1)
		));

		assertThat(chunks).hasSize(2);
		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.text().length()).isLessThanOrEqualTo(2_500));
		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.text().length()).isGreaterThanOrEqualTo(80));
		assertThat(chunks.get(0).text()).contains("제1조(긴 조문)");
		assertThat(chunks.get(1).text()).contains("제2조 삭제");
	}

	@Test
	void planInfersHeadingMetadataWhenParserDidNotProvideIt() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("text", "", "", "제15조(시간선택제채용공무원 정원의 운영) 이 조문은 시험용 본문이다.")
		));

		assertThat(chunks).hasSize(1);
		assertThat(chunks.get(0).no()).isEqualTo("제15조");
		assertThat(chunks.get(0).title()).isEqualTo("제15조(시간선택제채용공무원 정원의 운영)");
	}

	@Test
	void planPropagatesInferredArticleHeadingToSplitChildren() {
		String body = "제12조(대표 조문 제목) " + "가".repeat(2_700) + ". " + "나".repeat(1_200) + ".";

		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("article", "", "", body, "$.법령.조문.조문단위[12].조문내용", 1, 1)
		));

		assertThat(chunks).hasSizeGreaterThan(1);
		assertThat(chunks).allSatisfy(chunk -> {
			assertThat(chunk.no()).startsWith("제12조");
			assertThat(chunk.title()).startsWith("제12조(대표 조문 제목)");
		});
		assertThat(chunks.get(0).title()).contains("(1/");
	}

	@Test
	void planAddsFallbackTitleForUntitledAppendixChunks() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("appendix", "", "", "신청인은 다음 각 호의 서류를 제출하여야 한다. " + "제출서류 ".repeat(20), "$.법령.별표.별표단위[12]", 1, 1)
		));

		assertThat(chunks).hasSize(1);
		assertThat(chunks.get(0).no()).isEqualTo("별표/서식 13");
		assertThat(chunks.get(0).title()).isEqualTo("별표/서식 13");
	}

	@Test
	void planAddsSourcePathFallbackForUntitledLawArticleChunks() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("text", "", "", "선거 사무와 절차에 관한 본문이다. " + "선거관리 절차 ".repeat(80), "$.법령.조문.조문단위[7].조문내용", 1, 1)
		));

		assertThat(chunks).hasSize(1);
		assertThat(chunks.get(0).no()).isEqualTo("조문 8");
		assertThat(chunks.get(0).title()).isEqualTo("조문 8");
	}

	@Test
	void planAddsFallbackTitleForRevisionTextChunks() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("text", "", "", "개정된 조문과 적용 시점을 설명하는 개정문 본문이다. " + "개정 적용 ".repeat(80), "$.법령.개정문.개정문내용[0]", 1, 1)
		));

		assertThat(chunks).hasSize(1);
		assertThat(chunks.get(0).no()).isEqualTo("개정문");
		assertThat(chunks.get(0).title()).isEqualTo("개정문");
	}

	@Test
	void planUsesFormFieldHeadingForUntitledAppendixChunks() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("appendix", "", "", "\u2502          \u2502\n\u2502\u25a1 Other requests          \u2502\n\u2502  * Describe requested improvements          \u2502", "$.踰뺣졊.蹂꾪몴.蹂꾪몴?⑥쐞[12]", 1, 1)
		));

		assertThat(chunks).hasSize(1);
		assertThat(chunks.get(0).no()).isEqualTo("Other requests");
		assertThat(chunks.get(0).title()).isEqualTo("Other requests");
	}

	@Test
	void planDropsSparseAppendixFormDecoration() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("appendix", "", "", "\u2503          \u2502          \u2502          \u2503\n\u2503 Phone:                  \u2503\n\u2503          \u2502          \u2502          \u2503", "$.踰뺣졊.蹂꾪몴.蹂꾪몴?⑥쐞[12]", 1, 1)
		));

		assertThat(chunks).isEmpty();
	}

	@Test
	void planStripsHtmlImageTagsBeforeChunking() {
		String text = "1. Broadcasting restriction violation: 3 years or less. "
			+ "<img id=\"100\"></img>".repeat(160)
			+ " 2. This sentence remains searchable.";

		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("admin-rule-article", "", "", text, "$.AdmRulService.조문.조문내용[0]", 1, 1)
		));

		assertThat(chunks).hasSize(1);
		assertThat(chunks.get(0).text()).doesNotContain("<img", "</img>");
		assertThat(chunks.get(0).text()).contains("Broadcasting restriction violation");
		assertThat(chunks.get(0).text()).contains("This sentence remains searchable");
	}

	@Test
	void planDropsTinyFormFieldTail() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("admin-rule-article", "", "", "주소, 저작물명(제호), 종류 □ 개인정보 보유기간 합니다. □ 제3자 제공 년 월 일 소속 직위 성명 (서명)", "$.AdmRulService.조문.조문내용[0]", 1, 1)
		));

		assertThat(chunks).isEmpty();
	}

	@Test
	void planDropsTinyCheckboxSignatureFormTail() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("admin-rule-article", "", "", "□ 사후 V표시) | 통역인 □ ○ □ 기타사항 ○ □ 기타( ) 2021. . . 검 사 인", "$.AdmRulService.서식.기타사항[52]", 1, 1)
		));

		assertThat(chunks).isEmpty();
	}

	@Test
	void planDropsTinyCheckboxBusinessFormTail() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("admin-rule-article", "", "", "□ 사업소득 사항 | 월 | 합계 | 상호: 사업자등록번호: 대표자: (인) 세무서장 귀하", "$.AdmRulService.서식.사업소득[76]", 1, 1)
		));

		assertThat(chunks).isEmpty();
	}

	@Test
	void planKeepsTinyLegalTableRowWithoutCheckboxMarker() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("admin-rule-article", "", "", "판단되는 경우의 예시 제60조제1항제3호 | 발생하지 아니한 경우 제63조제4호", "$.AdmRulService.별표.판단예시[68]", 1, 1)
		));

		assertThat(chunks).hasSize(1);
		assertThat(chunks.get(0).text()).contains("제60조제1항제3호");
	}

	@Test
	void planKeepsTableTextUnderSearchLimitAfterBorderNormalization() {
		String row = "\u250211,460\u250211,480\u25021,228,600\u25021,203,600\u25021,178,600\u25021,153,600\u25021,128,600\u2502\n";
		String text = row.repeat(90);

		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("appendix", "", "", text, "$.법령.별표.별표단위[7]", 1, 1)
		));

		assertThat(chunks).isNotEmpty();
		assertThat(chunks).allSatisfy(chunk -> {
			assertThat(chunk.text()).doesNotContain("\u2502");
			assertThat(chunk.text().length()).isLessThanOrEqualTo(2_500);
		});
	}

	@Test
	void planAvoidsTinyTailWhenSplittingNearLimitTableText() {
		String prefix = "위반행위 | 근거 | 과징금 금액 위반 경우 | ".repeat(110);
		String tinyTail = "우에는 그렇지 않다. | 제17조제1항제2호 | 제17조제1항제3호 |";

		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("appendix", "", "", prefix + tinyTail, "$.법령.별표.별표단위[21]", 1, 1)
		));

		assertThat(chunks).hasSizeGreaterThan(1);
		assertThat(chunks).allSatisfy(chunk -> {
			assertThat(chunk.text().length()).isGreaterThanOrEqualTo(80);
			assertThat(chunk.text().length()).isLessThanOrEqualTo(2_500);
		});
		assertThat(chunks).extracting(PlannedLawChunk::text)
			.anySatisfy(text -> assertThat(text).contains("제17조제1항제3호"));
	}

	@Test
	void planDropsShortEnglishBoxTableFragments() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("appendix", "", "", "\u2502Wind turbine, unlighted and lighted                               \u2502        \u2502140               \u2502\n\u2502Wind turbines, minor group and group in major area, lighted       \u2502        \u2502141               \u2502", "$.법령.별표.별표단위[12]", 1, 1),
			new SyncDetailSection("appendix", "", "", "\u2502       \u226a\u2261                                                                                                                       \u2502\n\u2502Lower Limit           \u2502F)                                                                                                        \u2502\n\u2502Upper Limit           \u2502G)  ) \u226a\u2261", "$.법령.별표.별표단위[13]", 2, 1)
		));

		assertThat(chunks).isEmpty();
	}

	@Test
	void planDropsTinyAppendixFragmentsWithoutLegalCue() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("appendix", "", "", "병무청장 | 보좌\n○병역처분\n각 과별 검사\n병무청장 |\n별 검사\n○심리검사\n○혈압 측정", "$.법령.별표.별표단위[0]", 1, 1)
		));

		assertThat(chunks).isEmpty();
	}

	@Test
	void planDropsTinyAppendixTableCellFragmentEvenWithLegalCue() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("appendix", "", "", "위반행위 | 근거 | 과징금 금액 위반 경우 | 우에는 그렇지 않다. | 위반 제17조제1 | 항제2호 | 항제3호 |", "$.법령.별표.별표단위[21]", 1, 1)
		));

		assertThat(chunks).isEmpty();
	}

	@Test
	void planDropsTinyAppendixDanglingCellFragment() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("appendix", "", "", "항, 제41조, 제42조 제23조, 제36조제4항 제44조 호사건 |", "$.법령.별표.별표단위[5]", 1, 1)
		));

		assertThat(chunks).isEmpty();
	}

	@Test
	void planDropsTinyAppendixFormAttachmentFragment() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("appendix", "", "", "별지 제2호서식의 사유서를 제출해야 합니다. 진단서 1부 보증서 1부", "$.법령.별표.별표단위[9]", 1, 1)
		));

		assertThat(chunks).isEmpty();
	}

	@Test
	void planDropsTinyAppendixCitationOnlyFragment() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("appendix", "", "", "제15조제2항 제15조제3항 제4항 제5항 제6항", "$.법령.별표.별표단위[14]", 1, 1)
		));

		assertThat(chunks).isEmpty();
	}

	@Test
	void planDropsTinyAppendixCheckboxFormFragment() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("appendix", "", "", "성 명 | 소 속 | 연 락 처 | ※ 대리인 신청 시 위임장 첨부 □ 기 타(상세사항 기재 가능)", "$.법령.별표.별표단위[22]", 1, 1)
		));

		assertThat(chunks).isEmpty();
	}

	@Test
	void planDropsTinyAppendixOpinionPlaceholderFragment() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("appendix", "", "", "<의견> 다. <기타의견> <의견> 무□) <의견> 다. <기타의견> <의견>", "$.법령.별표.별표단위[23]", 1, 1)
		));

		assertThat(chunks).isEmpty();
	}

	@Test
	void planDropsTinyAppendixInspectionTableTailFragment() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("appendix", "", "", "조 정기안전검사 | 항 제25조 지 조 호 항 설 비 | 조 규칙」제54조", "$.법령.별표.별표단위[24]", 1, 1)
		));

		assertThat(chunks).isEmpty();
	}

	@Test
	void planDropsTinyAppendixSpeciesFormTailFragment() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("appendix", "", "", "대해 200자 이상 작성) 학명 | 대상 종의 국명 | 삽화(정밀화) | 서식지 [국내] [국외]", "$.법령.별표.별표단위[25]", 1, 1)
		));

		assertThat(chunks).isEmpty();
	}

	@Test
	void planKeepsShortAppendixTextWithKoreanLegalCue() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("appendix", "", "", "\u2503S : Satisfactory, U : Unsatisfactory, N/A : Not Applicable\u2503\n제1조(목적) 이 기준은 항공정보의 제공과 항공지도의 발간에 필요한 사항을 정한다.", "$.법령.별표.별표단위[12]", 1, 1)
		));

		assertThat(chunks).hasSize(1);
		assertThat(chunks.get(0).text()).contains("제1조");
	}

	@Test
	void planNormalizesAppendixTableBordersIntoCellText() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("appendix", "", "", "\u2503제출구분              \u2502내   용                 \u2502관련 규정       \u2503\n\u2503신규 제출             \u2502특허출원서              \u2502제17조          \u2503", "$.법령.별표.별표단위[12]", 1, 1)
		));

		assertThat(chunks).hasSize(1);
		assertThat(chunks.get(0).text()).doesNotContain("\u2503", "\u2502");
		assertThat(chunks.get(0).text()).contains("제출구분 | 내 용 | 관련 규정");
		assertThat(chunks.get(0).text()).contains("신규 제출 | 특허출원서 | 제17조");
		assertThat(chunks.get(0).title()).isEqualTo("제출구분");
	}

	@Test
	void planNormalizesBoxTableLineInsideSupplementaryText() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("text", "", "", "부칙 설명입니다.\n\u2502                \u2502내용                  \u2502규칙 제36조       \u2502\n제1조(시행일) 이 규칙은 공포한 날부터 시행한다. " + "시행일 ".repeat(160), "$.법령.부칙.부칙내용[0]", 1, 1)
		));

		assertThat(chunks).hasSize(1);
		assertThat(chunks.get(0).text()).doesNotContain("\u2502");
		assertThat(chunks.get(0).text()).contains("내용 | 규칙 제36조");
		assertThat(chunks.get(0).title()).isEqualTo("부칙");
	}

	@Test
	void planAddsFallbackTitleForRevisionReasonChunks() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("text", "", "", "개정 이유를 설명하는 본문이다. " + "조직 운영 조정 ".repeat(80), "$.법령.제개정이유.제개정이유내용[0]", 1, 1)
		));

		assertThat(chunks).hasSize(1);
		assertThat(chunks.get(0).no()).isEqualTo("제개정이유");
		assertThat(chunks.get(0).title()).isEqualTo("제개정이유");
	}

	@Test
	void planTruncatesStoredMetadataToColumnSafeLengths() {
		String longTitle = "제99조(" + "긴제목".repeat(180) + ")";
		String longSourcePath = "$.법령.조문.조문단위[99]." + "하위경로".repeat(100);

		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection(
				"article",
				"제99조",
				longTitle,
				longTitle + " " + "본문 ".repeat(300),
				longSourcePath,
				1,
				1
			)
		));

		assertThat(chunks).hasSize(1);
		assertThat(chunks.get(0).title()).hasSizeLessThanOrEqualTo(500);
		assertThat(chunks.get(0).title()).endsWith("...");
		assertThat(chunks.get(0).no()).hasSizeLessThanOrEqualTo(100);
		assertThat(chunks.get(0).sourcePath()).hasSizeLessThanOrEqualTo(500);
	}

	@Test
	void planDropsLowSignalAppendixTableFragments() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("appendix", "", "", "│                                                                          │정한 금액", "$.법령.별표.별표단위[128]", 1, 1)
		));

		assertThat(chunks).isEmpty();
	}

	@Test
	void planGroupsNestedLawArticleSourcePathsUnderArticleParent() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("article", "", "", "제10조(업무) 기관장은 업무를 총괄한다.", "$.법령.조문.조문단위[9].조문내용", 1, 1),
			new SyncDetailSection("paragraph", "", "", "① 기관장은 필요한 계획을 수립하여야 한다.", "$.법령.조문.조문단위[9].항[0].항내용", 1, 2),
			new SyncDetailSection("subparagraph", "", "", "1. 세부 추진계획", "$.법령.조문.조문단위[9].항[0].호[0].호내용", 1, 3)
		));

		assertThat(chunks).hasSize(1);
		assertThat(chunks.get(0).no()).isEqualTo("제10조");
		assertThat(chunks.get(0).title()).isEqualTo("제10조(업무)");
		assertThat(chunks.get(0).sourcePath()).isEqualTo("$.법령.조문.조문단위[9]");
		assertThat(chunks.get(0).text()).contains("① 기관장은 필요한 계획을 수립하여야 한다.");
		assertThat(chunks.get(0).text()).contains("1. 세부 추진계획");
	}

	@Test
	void planKeepsAdministrativeRuleArticleIdentityAcrossSizeSplits() {
		String article = "\uC81C27\uC870(\uB300\uAC00\uC758 \uC9C0\uAE09) "
			+ "\uC6A9\uC5ED \uC644\uB8CC\uC640 \uAC80\uC0AC \uD6C4 \uB300\uAC00\uB97C \uC9C0\uAE09\uD55C\uB2E4. ".repeat(180);

		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection(
				"admin-rule-article",
				"\uC81C27\uC870",
				"\uC81C27\uC870(\uB300\uAC00\uC758 \uC9C0\uAE09)",
				article,
				"$.AdmRulService.\uC870\uBB38\uB0B4\uC6A9[26]",
				27,
				1
			)
		));

		assertThat(chunks).hasSizeGreaterThan(1);
		assertThat(chunks).allSatisfy(chunk -> {
			assertThat(chunk.no()).startsWith("\uC81C27\uC870");
			assertThat(chunk.title()).startsWith("\uC81C27\uC870(\uB300\uAC00\uC758 \uC9C0\uAE09)");
			assertThat(chunk.no()).doesNotStartWith("\uC81C1\uC870");
			assertThat(chunk.sourcePath()).isEqualTo("$.AdmRulService.\uC870\uBB38\uB0B4\uC6A9");
			assertThat(chunk.text()).hasSizeLessThanOrEqualTo(2_500);
		});
	}

	@Test
	void planDoesNotMergeAdjacentAdministrativeRuleArticles() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection(
				"admin-rule-article",
				"\uC81C1\uC870",
				"\uC81C1\uC870(\uBAA9\uC801)",
				"\uC81C1\uC870(\uBAA9\uC801) \uC6A9\uC5ED\uACC4\uC57D\uC758 \uBAA9\uC801\uC744 \uC815\uD55C\uB2E4.",
				"$.AdmRulService.\uC870\uBB38\uB0B4\uC6A9[0]",
				1,
				1
			),
			new SyncDetailSection(
				"admin-rule-article",
				"\uC81C20\uC870",
				"\uC81C20\uC870(\uAC80\uC0AC)",
				"\uC81C20\uC870(\uAC80\uC0AC) \uC644\uB8CC\uB41C \uC6A9\uC5ED\uC744 \uAC80\uC0AC\uD55C\uB2E4.",
				"$.AdmRulService.\uC870\uBB38\uB0B4\uC6A9[1]",
				20,
				1
			),
			new SyncDetailSection(
				"admin-rule-article",
				"\uC81C27\uC870",
				"\uC81C27\uC870(\uB300\uAC00\uC758 \uC9C0\uAE09)",
				"\uC81C27\uC870(\uB300\uAC00\uC758 \uC9C0\uAE09) \uAC80\uC0AC \uD6C4 \uB300\uAC00\uB97C \uC9C0\uAE09\uD55C\uB2E4.",
				"$.AdmRulService.\uC870\uBB38\uB0B4\uC6A9[2]",
				27,
				1
			)
		));

		assertThat(chunks).hasSize(3);
		assertThat(chunks).extracting(PlannedLawChunk::no)
			.containsExactly("\uC81C1\uC870", "\uC81C20\uC870", "\uC81C27\uC870");
	}

	@Test
	void planSplitsLongAdministrativeRuleTextIntoSearchSizedChildren() {
		String longText = "가".repeat(2_700) + ". " + "나".repeat(2_700) + ".";

		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("admin-rule-article", "문단1 줄1", "문단 1 / 줄 1", longText, "$.AdmRulService.조문.조문내용[0]", 1, 1)
		));

		assertThat(chunks).hasSizeGreaterThan(1);
		assertThat(chunks).allSatisfy(chunk -> {
			assertThat(chunk.text().length()).isLessThanOrEqualTo(2_500);
			assertThat(chunk.sourcePath()).isEqualTo("$.AdmRulService.조문.조문내용");
		});
		assertThat(chunks.get(0).title()).isEqualTo("행정규칙 조문 (1/" + chunks.size() + ")");
	}

	@Test
	void planDropsShortChunkFullyContainedElsewhereInDocumentPlan() {
		String duplicate = "Transitional clause applies before the enforcement date. ".repeat(3);
		String middle = "Independent middle context. " + "B".repeat(2_300);
		String context = duplicate + "A".repeat(2_300);

		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection("text", "first", "Contained duplicate", duplicate, "$.law.supplement[1]", 1, 1),
			new SyncDetailSection("text", "middle", "Middle context", middle, "$.law.supplement[2]", 2, 1),
			new SyncDetailSection("text", "second", "Full context", context, "$.law.supplement[3]", 3, 1)
		));

		assertThat(chunks).hasSize(2);
		assertThat(chunks).extracting(PlannedLawChunk::text)
			.anySatisfy(text -> assertThat(text).contains("Independent middle context"))
			.anySatisfy(text -> assertThat(text).contains(duplicate.trim()));
	}

	@Test
	void planDoesNotMergeSupplementaryClauseIntoMainArticle() {
		List<PlannedLawChunk> chunks = planner.plan(List.of(
			new SyncDetailSection(
				"text",
				"부칙",
				"부칙",
				"제1조(시행일) 이 부칙은 시행일과 경과조치를 정한다. " + "부칙 경과조치 ".repeat(35),
				"$.법령.부칙.부칙단위[0].부칙내용[0]",
				1,
				1
			),
			new SyncDetailSection(
				"article",
				"제1조",
				"제1조(목적)",
				"제1조(목적) 이 법은 현행 본문 검색을 위한 목적 조문을 둔다. " + "현행 본문 ".repeat(80),
				"$.법령.조문.조문단위[0].조문내용",
				2,
				1
			)
		));

		assertThat(chunks).hasSize(2);
		assertThat(chunks.get(0).sourcePath()).contains("부칙");
		assertThat(chunks.get(0).text()).doesNotContain("현행 본문 검색");
		assertThat(chunks.get(1).sourcePath()).contains("조문");
		assertThat(chunks.get(1).text()).doesNotContain("부칙은 시행일");
	}

	private SyncDetailSection shortArticle(int articleNo) {
		String title = "제" + articleNo + "조(시험 조문 " + articleNo + ")";
		String body = title + " " + "가".repeat(170);
		return new SyncDetailSection(
			"article",
			"제" + articleNo + "조",
			title,
			body,
			"$.법령.조문.조문단위[" + articleNo + "].조문내용",
			articleNo,
			1
		);
	}
}
