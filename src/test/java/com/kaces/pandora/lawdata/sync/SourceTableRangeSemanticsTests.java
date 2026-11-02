package com.kaces.pandora.lawdata.sync;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceTableRangeSemanticsTests {
    @Test void provesIndependentRangeTracksAndOwnedMetricContextWithoutCombiningThem() {
        var graph=graph(List.of("90% 이상","90% 미만"),List.of("13년 이상","13년 미만"));
        var proof=new SourceTableRangeSemantics().capture(graph).get(1);
        assertThat(proof.status()).isEqualTo("INDEPENDENT_RANGE_TRACKS_ONLY");
        assertThat(proof.tracks()).hasSize(2);
        assertThat(proof.tracks().get(0).unit()).isEqualTo("%");assertThat(proof.tracks().get(1).unit()).isEqualTo("년");
        var first=proof.tracks().get(0).bands().get(0);
        assertThat(first.lower().value()).isEqualTo("90");assertThat(first.lower().inclusive()).isTrue();assertThat(first.upper()).isNull();
        assertThat(first.condition().sourcePath()).isEqualTo("v0:1");assertThat(first.score().text()).isEqualTo("2");
        assertThat(proof.context()).extracting(SourceTableRangeSemantics.ContextCell::text).containsExactly("비용 측면","운영 적정성(10)","정의·측정산식; 단, 제외 대상은 10점");
        assertThat(proof.context()).extracting(SourceTableRangeSemantics.ContextCell::sourcePath).doesNotContain("unrelated");
    }
    @Test void partitionsPreserveStrictVsInclusiveSignedDecimalBounds() {
        var proof=new SourceTableRangeSemantics().capture(graph(List.of("-10.5% 초과","-10.5% 이하"),null)).get(1);
        assertThat(proof.status()).isEqualTo("INDEPENDENT_RANGE_TRACKS_ONLY");
        assertThat(proof.tracks().get(0).bands().get(0).lower().value()).isEqualTo("-10.5");
        assertThat(proof.tracks().get(0).bands().get(0).lower().inclusive()).isFalse();
        assertThat(proof.tracks().get(0).bands().get(1).upper().inclusive()).isTrue();
    }
    @Test void gapsOverlapsSplitDigitsMixedUnitsAndUnstatedLogicCannotQualify() {
        for(var conditions:List.of(List.of("90% 이상","90% 이하"),List.of("90% 초과","90% 미만"),
            List.of("9 0% 이상","90% 미만"),List.of("90% 이상 또는 13년 이상","90% 미만"),
            List.of("90% 이상","90년 미만"),List.of("100% 이상90% 미만","100% 미만"))) {
            var proof=new SourceTableRangeSemantics().capture(graph(conditions,null)).get(1);
            assertThat(proof.status()).isEqualTo("UNSUPPORTED");assertThat(proof.tracks()).isEmpty();
        }
    }
    @Test void multiRowParentOwnerCannotSilentlyChooseOnlyOneMetricContext() {
        var original=graph(List.of("90% 이상","90% 미만"),null);
        var parent=new SourceHwpxTableGraph.Table("part","outer","ns",null,2,3,List.of(
            cell("category",0,0,2,1,"비용 측면",List.of()),cell("metric",0,1,1,1,"지표 A",List.of()),
            cell("owner",0,2,2,1,"두 지표 공통인지 불명확",List.of("inner")),cell("otherMetric",1,1,1,1,"지표 B",List.of())));
        var proof=new SourceTableRangeSemantics().capture(new SourceHwpxTableGraph.Graph(List.of(parent,original.tables().get(1)))).get(1);
        assertThat(proof.status()).isEqualTo("UNSUPPORTED");assertThat(proof.context()).isEmpty();assertThat(proof.tracks()).isEmpty();
    }
    private static SourceHwpxTableGraph.Graph graph(List<String> percent,List<String> years) {
        int rows=years==null?2:3;var cells=new ArrayList<SourceHwpxTableGraph.Cell>();
        cells.add(cell("label",0,0,rows-1,1,"구간",List.of()));
        for(int c=1;c<3;c++)cells.add(cell("v0:"+c,0,c,1,1,percent.get(c-1),List.of()));
        if(years!=null)for(int c=1;c<3;c++)cells.add(cell("v1:"+c,1,c,1,1,years.get(c-1),List.of()));
        cells.add(cell("scoreLabel",rows-1,0,1,1,"점수",List.of()));
        for(int c=1;c<3;c++)cells.add(cell("score"+c,rows-1,c,1,1,""+(c*2),List.of()));
        var parent=new SourceHwpxTableGraph.Table("part","outer","ns",null,2,3,List.of(
            cell("category",0,0,2,1,"비용 측면",List.of()),cell("metric",0,1,1,1,"운영 적정성(10)",List.of()),
            cell("owner",0,2,1,1,"정의·측정산식; 단, 제외 대상은 10점",List.of("inner")),
            cell("unrelated",1,1,1,2,"다른 지표 예외는 99점",List.of())));
        return new SourceHwpxTableGraph.Graph(List.of(parent,new SourceHwpxTableGraph.Table("part","inner","ns","owner",rows,3,cells)));
    }
    private static SourceHwpxTableGraph.Cell cell(String p,int r,int c,int rs,int cs,String text,List<String> children) {
        return new SourceHwpxTableGraph.Cell(p,r,c,rs,cs,"0",text,children);
    }
}
