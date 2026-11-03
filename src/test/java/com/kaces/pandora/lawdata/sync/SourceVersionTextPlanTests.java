package com.kaces.pandora.lawdata.sync;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import com.kaces.pandora.common.json.LawJsonWriter;

class SourceVersionTextPlanTests {
    @Test void sourceSupplementEntriesKeepTheirOwnDateHeadingAndParent() {
        var parser=new LawOpenApiPayloadParser(json,new LawJsonWriter(json));
        String raw="""
            {"AdmRulService":{"조문내용":"제1조(기준) 대상 사업에 적용한다.","부칙":{
              "부칙공포일자":["20250203","20261019"],
              "부칙내용":["부칙 <제2025-12호, 2025. 2. 3.>제1조(시행) 이 고시는 발령한 날부터 시행한다.",
                         "부칙 <제2026-63호, 2026. 10. 19.>제1조(시행) 이 고시는 발령한 날부터 시행한다."]}}}
            """;
        var sections=parser.parseSourceVersionDocument(raw,"지침").sections().stream()
            .filter(s->s.type().equals("supplementary")).toList();
        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).body()).contains("2025-12").doesNotContain("2026-63");
        assertThat(sections.get(1).body()).contains("2026-63").doesNotContain("2025-12");
        assertThat(sections).extracting(SyncDetailSection::sourcePath).containsExactly(
            "$.AdmRulService.부칙.부칙내용[0]","$.AdmRulService.부칙.부칙내용[1]");
        var chunks=new LawSemanticChunkPlanner().plan(new ChunkPlanningContext("admrul",75,"지침"),sections);
        assertThat(chunks.stream().map(PlannedLawChunk::parentKey).distinct().count()).isEqualTo(2);
        assertThat(planner.plan("admrul",75,"지침",raw).unexplainedLossSpanCount()).isZero();
    }
    @Test void nestedSupplementLinesStayTogetherWithoutInventingADateForBareText() {
        var parser=new LawOpenApiPayloadParser(json,new LawJsonWriter(json));
        String raw="""
            {"AdmRulService":{"부칙":{"부칙내용":[["부칙 <제2025-12호>","종전 사업은 제외한다."],
             "이 고시는 발령한 날부터 시행한다."]}}}
            """;
        var sections=parser.parseSourceVersionDocument(raw,"지침").sections();
        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).body()).isEqualTo("부칙 <제2025-12호>\n종전 사업은 제외한다.");
        assertThat(sections.get(1).body()).isEqualTo("이 고시는 발령한 날부터 시행한다.");
        assertThat(sections.get(1).title()).doesNotContain("2025","2026");
    }
    private final ObjectMapper json=new ObjectMapper();
    private final SourceVersionTextPlan planner=new SourceVersionTextPlan(new LawOpenApiPayloadParser(json,new LawJsonWriter(json)));
    private final SourceVersionTextPlan lossyPlanner=new SourceVersionTextPlan(new LawOpenApiPayloadParser(json,new LawJsonWriter(json)) {
        @Override public SyncDetailDocument parseSourceVersionDocument(String raw,String title) {return super.parseDetailDocument(raw,title);}
    });
    @Test void auxiliaryDateAndDownloadArraysAreMetadataNotInventedBodySpans() {
        String raw=json.writeValueAsString(java.util.Map.of("AdmRulService",java.util.Map.of(
            "조문내용","제1조(기준) 담당청은 정해진 기준을 준수하여야 한다.",
            "부칙",java.util.Map.of("부칙공포일자",java.util.List.of("20261019"),"부칙내용",java.util.List.of("이 지침은 발령한 날부터 시행한다.")))));
        assertThat(planner.plan("admrul",75,"테스트 지침",raw).unexplainedLossSpanCount()).isZero();
    }
    @Test void administrativeAuxiliaryTextCannotReplaceMissingArticleBody() {
        String raw=json.writeValueAsString(java.util.Map.of("AdmRulService",java.util.Map.of(
            "조문내용"," ","별표",java.util.Map.of("별표단위",java.util.Map.of("별표내용","담당청은 적용 조건과 경과조치의 내용을 보존하여야 한다. ".repeat(10))))));
        assertThatThrownBy(()->planner.plan("admrul",75,"테스트 지침",raw)).isInstanceOf(IllegalStateException.class);
    }
    @Test void preservesFullyParsedAuxiliaryProseWithoutGrantingReferenceReadiness() {
        String supplement="담당청은 종전 사업의 적용 조건과 경과조치를 확인하고 해당 내용을 보존하여야 한다. ".repeat(10);
        String raw=json.writeValueAsString(java.util.Map.of("AdmRulService",java.util.Map.of(
            "조문내용","제1조(기준) 담당청은 정해진 기준을 준수하여야 한다.",
            "부칙",java.util.Map.of("부칙단위",java.util.Map.of("부칙내용",supplement)))));
        assertThat(planner.plan("admrul",75,"테스트 지침",raw).unexplainedLossSpanCount()).isZero();
    }
    @Test void rejectsAdministrativeAnnexTextDiscardedByTheParser() {
        String raw=json.writeValueAsString(java.util.Map.of("AdmRulService",java.util.Map.of(
            "조문내용","제1조(기준) 담당청은 정해진 기준을 준수하여야 한다.",
            "별표",java.util.Map.of("별표단위",java.util.Map.of("별표내용",java.util.List.of("90% 이상 110% 미만은 4점"))))));
        assertThatThrownBy(()->lossyPlanner.plan("admrul",75,"테스트 지침",raw)).isInstanceOf(IllegalStateException.class);
        assertThat(planner.plan("admrul",75,"테스트 지침",raw).unexplainedLossSpanCount()).isZero();
    }
    @Test void rejectsAdministrativeSupplementTextDiscardedByTheParser() {
        String raw=json.writeValueAsString(java.util.Map.of("AdmRulService",java.util.Map.of(
            "조문내용","제1조(기준) 담당청은 정해진 기준을 준수하여야 한다.",
            "부칙",java.util.Map.of("부칙단위",java.util.Map.of("부칙내용","종전 사업은 적용에서 제외한다.")))));
        assertThatThrownBy(()->lossyPlanner.plan("admrul",75,"테스트 지침",raw)).isInstanceOf(IllegalStateException.class);
        assertThat(planner.plan("admrul",75,"테스트 지침",raw).unexplainedLossSpanCount()).isZero();
    }
    @Test void rejectsLawAnnexTextDiscardedByTheParser() {
        String raw="{\"법령\":{\"조문\":{\"조문단위\":[{\"조문번호\":\"1\",\"조문여부\":\"조문\",\"조문내용\":\"제1조(기준) 담당청은 정해진 기준을 준수하여야 한다.\"}]},\"별표\":{\"별표단위\":{\"별표내용\":[\"90% 이상 110% 미만은 4점\"]}}}}";
        assertThatThrownBy(()->lossyPlanner.plan("law",75,"테스트 법",raw)).isInstanceOf(IllegalStateException.class);
        assertThat(planner.plan("law",75,"테스트 법",raw).unexplainedLossSpanCount()).isZero();
    }
    @Test void plansTheNewOfficialTextWithoutComparingItToTheOldVersion() {
        String body="제1조(적용) 신청기관의 장은 대상 사업에 이 지침을 적용하여야 한다. 제2조(시정) 협의기관의 장은 사전협의 미이행 시 신청기관에 시정을 요구할 수 있다.";
        String raw=json.writeValueAsString(java.util.Map.of("AdmRulService",java.util.Map.of("조문내용",body)));
        var plan=planner.plan("admrul",75,"테스트 지침",raw);
        assertThat(plan.chunks()).isNotEmpty();
        assertThat(plan.chunks().stream().map(PlannedLawChunk::text).collect(java.util.stream.Collectors.joining("\n"))).contains("사전협의 미이행 시","요구할 수 있다");
        assertThat(plan.unexplainedLossSpanCount()).isZero();
        assertThat(plan.manifest()).matches("[0-9a-f]{64}");
    }
    @Test void rejectsMetadataFallbackAndWrongTargetRoot() {
        String metadata=json.writeValueAsString(java.util.Map.of("기타",java.util.Map.of("본문","공식 조문이 아닌 안내 내용입니다. ".repeat(100))));
        assertThatThrownBy(()->planner.plan("admrul",75,"테스트 지침",metadata)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->planner.plan("law",75,"테스트 법",json.writeValueAsString(java.util.Map.of("AdmRulService",java.util.Map.of("조문내용","잘못된 루트입니다. ".repeat(100)))))).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsBlankTextAndParserDiscardedLegalContent() {
        assertThatThrownBy(()->planner.plan("admrul",75,"테스트 지침","{\"AdmRulService\":{\"조문내용\":\" \"}}")).isInstanceOf(IllegalStateException.class);
        // The legacy adapter still loses a heading; the loss gate must keep rejecting it.
        String raw="{\"AdmRulService\":{\"조문내용\":[\"제1장 총칙\",\"제1조(목적) 담당청은 이 기준을 적용하여야 한다.\"]}}";
        assertThatThrownBy(()->lossyPlanner.plan("admrul",75,"테스트 지침",raw)).isInstanceOf(IllegalStateException.class);
        assertThat(planner.plan("admrul",75,"테스트 지침",raw).unexplainedLossSpanCount()).isZero();
    }
    @Test void changedConditionOrDocumentIdentityChangesTheManifest() {
        String raw="{\"AdmRulService\":{\"조문내용\":\"담당청은 확인 결과 위반이 인정되는 경우에만 개선을 권고할 수 있다.\"}}";
        var first=planner.plan("admrul",75,"테스트 지침",raw);
        assertThat(planner.plan("admrul",76,"테스트 지침",raw).manifest()).isNotEqualTo(first.manifest());
        assertThat(planner.plan("admrul",75,"테스트 지침",raw.replace("경우에만","경우")).manifest()).isNotEqualTo(first.manifest());
    }
    @Test void validatesOwnedLawArticlesButDoesNotTreatSupplementOnlyAsAWholeLaw() {
        String raw="{\"법령\":{\"조문\":{\"조문단위\":[{\"조문번호\":\"1\",\"조문여부\":\"조문\",\"조문제목\":\"목적\",\"조문내용\":\"제1조(목적) 담당청은 공정한 절차를 마련하여야 한다.\"}]}}}";
        assertThat(planner.plan("law",75,"테스트 법",raw).chunks()).isNotEmpty();
        String supplement="{\"법령\":{\"부칙\":{\"부칙단위\":[{\"부칙내용\":\"제1조(시행일) 이 법은 공포한 날부터 시행한다.\"}]}}}";
        assertThatThrownBy(()->planner.plan("law",75,"테스트 법",supplement)).isInstanceOf(IllegalStateException.class);
    }
}
