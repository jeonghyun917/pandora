package com.kaces.pandora.lawdata.sync;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import com.kaces.pandora.common.json.LawJsonWriter;

class SourceVersionStructureTests {
    private final ObjectMapper json=new ObjectMapper();
    private final LawOpenApiPayloadParser parser=new LawOpenApiPayloadParser(json,new LawJsonWriter(json));
    @Test void nonBodyMetadataCannotBecomeSearchableSourceGrounds() {
        String body="제1조(기준) 담당청은 정해진 기준을 준수하여야 한다.";
        String raw=json.writeValueAsString(Map.of("AdmRulService",Map.of("조문내용",body,"검토의견","이 메타데이터는 법적 근거가 아니다. ".repeat(20))));
        assertThat(parser.parseSourceVersionDocument(raw,"지침").sections()).extracting(SyncDetailSection::body).containsExactly(body);
        assertThat(parser.parseDetailDocument(raw,"지침").sections()).hasSize(2);
    }
    @Test void malformedDeclaredLinkShapesMustFailClosed() {
        for(Object declaration:List.of(123,Map.of("value","/flDownload.do?flSeq=123"),List.of(123))) {
            String raw=json.writeValueAsString(Map.of("AdmRulService",Map.of("조문내용","제1조(기준) 기준을 준수한다.","첨부파일링크",declaration)));
            assertThatThrownBy(()->parser.parseSourceVersionDocument(raw,"지침")).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void retainsChapterOrderAndOwnedPathsWithoutChangingLegacyParsing() {
        String raw=json.writeValueAsString(Map.of("AdmRulService",Map.of("조문내용",List.of(
            "제1장 총칙","제1조(목적) 담당청은 이 기준을 적용하여야 한다.",
            "제2장 예외","제2조(제외) 종전 사업은 이 기준에서 제외한다."))));
        var sections=parser.parseSourceVersionDocument(raw,"지침").sections();
        assertThat(sections).extracting(SyncDetailSection::body).containsExactly(
            "제1장 총칙","제1조(목적) 담당청은 이 기준을 적용하여야 한다.",
            "제2장 예외","제2조(제외) 종전 사업은 이 기준에서 제외한다.");
        assertThat(sections.get(0).sourcePath()).isEqualTo("$.AdmRulService.조문내용[0]");
        assertThat(sections.get(2).type()).isEqualTo("structure-heading");
        assertThat(parser.parseDetailDocument(raw,"지침").sections()).hasSize(2);
        var chunks=new LawSemanticChunkPlanner().plan(new ChunkPlanningContext("admrul",1,"지침"),sections);
        assertThat(chunks.stream().filter(c->c.text().equals("제2장 예외")).toList()).singleElement()
            .satisfies(c->assertThat(c.qualityStatus()).isEqualTo("CONTEXT_ONLY"));
    }
    @Test void retainsShortAnnexRowsAndGridCoordinatesAsOneOwnedSection() {
        List<String> rows=List.of("┌────┬────┐","│구간  │점수│","├────┼────┤","│90% 이상│4   │","│90% 미만│2   │","└────┴────┘");
        String raw=json.writeValueAsString(Map.of("AdmRulService",Map.of(
            "조문내용","제1조(배점) 별표의 구간에 따라 점수를 부여한다.",
            "별표",Map.of("별표단위",Map.of("별표내용",List.of(rows))))));
        var table=parser.parseSourceVersionDocument(raw,"지침").sections().stream().filter(s->s.type().equals("source-table")).toList();
        assertThat(table).singleElement().satisfies(s->{
            assertThat(s.body()).isEqualTo(String.join("\n",rows));
            assertThat(s.sourcePath()).isEqualTo("$.AdmRulService.별표.별표단위.별표내용");
        });
        assertThat(parser.parseDetailDocument(raw,"지침").sections()).noneMatch(s->s.body().contains("│90% 이상│4"));
    }
    @Test void rejectsAmbiguousRootAndDoesNotTrustUnownedSections() {
        assertThatThrownBy(()->parser.parseSourceVersionDocument("{\"AdmRulService\":{},\"법령\":{}}","title")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->parser.parseSourceVersionDocument("{\"sections\":[{\"body\":\"fabricated\"}]}","title")).isInstanceOf(IllegalStateException.class);
    }
    @Test void referenceNamesCannotBecomeInventedDownloadUrls() {
        String raw=json.writeValueAsString(Map.of("AdmRulService",Map.of(
            "조문내용","제1조(배점) 별표의 구간에 따라 점수를 부여한다.",
            "첨부파일",Map.of("첨부파일링크",List.of("/LSW/flDownload.do?flSeq=123"),"첨부파일명",List.of("공식 원문.pdf")))));
        assertThat(parser.parseSourceVersionDocument(raw,"지침").assets()).extracting(SyncAsset::sourceUrl).containsExactly("/LSW/flDownload.do?flSeq=123");
        assertThat(parser.parseDetailDocument(raw,"지침").assets()).hasSize(2);
    }
    @Test void unsupportedDeclaredReferencesCannotDisappearFromRequiredAssets() {
        String raw="{\"AdmRulService\":{\"조문내용\":\"제1조(기준) 원문을 보존한다.\",\"첨부파일링크\":\"https://evil.test/unknown.txt\"}}";
        assertThatThrownBy(()->parser.parseSourceVersionDocument(raw,"지침")).isInstanceOf(IllegalStateException.class);
    }
}
