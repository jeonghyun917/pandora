package com.kaces.pandora.lawdata.sync;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceTableColumnEvidenceTests {
    @Test void keepsTwoConditionTracksAndSameColumnScoresWithoutAndOrInference() {
        var table=table(3,3,List.of(cell(0,0,2,1,"구간"),cell(0,1,1,1,"90% 이상\n110% 미만"),cell(0,2,1,1,"110% 이상"),
            cell(1,1,1,1,"11년 이상 13년 미만"),cell(1,2,1,1,"13년 이상"),cell(2,0,1,1,"점수"),cell(2,1,1,1,"4"),cell(2,2,1,1,"2")));
        var evidence=new SourceTableColumnEvidence().capture(table);
        assertThat(evidence.status()).isEqualTo("COLUMN_ALIGNMENT_ONLY");
        assertThat(evidence.parentCellPath()).isEqualTo("outer/cell3");
        assertThat(evidence.columns()).hasSize(2);
        assertThat(evidence.columns().get(0).conditions()).extracting(SourceTableColumnEvidence.CellText::text)
            .containsExactly("90% 이상\n110% 미만","11년 이상 13년 미만");
        assertThat(evidence.columns().get(0).score().text()).isEqualTo("4");
        assertThat(evidence.columns().get(1).score().text()).isEqualTo("2");
        assertThat(evidence.columns().get(0).score().sourcePath()).isEqualTo("cell2:1");
        assertThatThrownBy(()->evidence.columns().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void ambiguousLayoutsCannotBecomeColumnProofs() {
        for(var table:List.of(
            table(2,3,List.of(cell(0,0,1,1,"구간"),cell(0,1,1,2,"전체 조건"),cell(1,0,1,1,"점수"),cell(1,1,1,1,"2"),cell(1,2,1,1,"4"))),
            table(2,2,List.of(cell(0,0,1,1,"구간"),cell(0,1,1,1,"점수"),cell(1,0,1,1,"점수"),cell(1,1,1,1,"2"))),
            table(2,2,List.of(cell(0,0,1,1,"구간"),cell(0,1,1,1,"조건"),cell(1,0,1,1,"점수"),cell(1,1,1,1,"미정"))),
            table(2,2,List.of(cell(0,0,1,1,"구간"),cell(0,1,1,1,"조건"),cell(1,0,1,1,"점수"))),
            table(2,2,List.of(cell(0,0,1,1,"구간"),new SourceHwpxTableGraph.Cell("nested",0,1,1,1,"0","조건",List.of("inner")),cell(1,0,1,1,"점수"),cell(1,1,1,1,"2"))))) {
            var evidence=new SourceTableColumnEvidence().capture(table);
            assertThat(evidence.status()).isEqualTo("UNSUPPORTED");assertThat(evidence.columns()).isEmpty();
        }
    }
    @Test void internalScoreWhitespaceCannotMergeMultipleNumbersIntoOneScore() {
        for(String score:List.of("1 2","1\n2","1\t2","1\u00a02")) {
            var source=table(2,2,List.of(cell(0,0,1,1,"구간"),cell(0,1,1,1,"90% 미만"),cell(1,0,1,1,"점수"),cell(1,1,1,1,score)));
            assertThat(new SourceTableColumnEvidence().capture(source).status()).isEqualTo("UNSUPPORTED");
        }
    }
    private static SourceHwpxTableGraph.Table table(int rows,int cols,List<SourceHwpxTableGraph.Cell> cells) {
        return new SourceHwpxTableGraph.Table("Contents/section0.xml","table1","urn:hp","outer/cell3",rows,cols,cells);
    }
    private static SourceHwpxTableGraph.Cell cell(int row,int col,int rs,int cs,String text) {
        return new SourceHwpxTableGraph.Cell("cell"+row+":"+col,row,col,rs,cs,"0",text,List.of());
    }
}
