package com.kaces.pandora.rag.recovery;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.kaces.pandora.rag.document.RagDocumentChunkRow;

class RecoveryCandidatePlanTests {
    private static final String SOURCE="a".repeat(64), PRODUCT="b".repeat(40);
    @Test void candidateManifestBindsAllTextAndOriginalIdentity() {
        var original=plan(List.of(row(0,42,"기관은 원본을 보존한다.",0)));
        var changed=plan(List.of(row(0,42,"기관은 원본을 삭제한다.",0)));
        assertThat(original.manifest()).matches("[0-9a-f]{64}").isNotEqualTo(changed.manifest());
        assertThat(original.chunks()).extracting(RagDocumentChunkRow::chunkId).containsExactly(0L);
        assertThatThrownBy(()->original.chunks().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void baselineMutationChangesPreviewTokenEvenWithUnchangedContentHashField() {
        var a=RecoveryCandidatePlan.of(42,SOURCE,PRODUCT,List.of(row(101,42,"기존 원문",0)),List.of(row(0,42,"후보 원문",0)));
        var b=RecoveryCandidatePlan.of(42,SOURCE,PRODUCT,List.of(row(101,42,"변경 원문",0)),List.of(row(0,42,"후보 원문",0)));
        assertThat(a.previewHash()).isNotEqualTo(b.previewHash());
    }
    @Test void rejectsCrossDocumentDuplicateAndAlreadyAssignedCandidateIds() {
        assertThatThrownBy(()->plan(List.of(row(0,99,"다른 문서",0)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->plan(List.of(row(10,42,"이미 저장됨",0)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->plan(List.of(row(0,42,"하나",0),row(0,42,"둘",0)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->plan(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void malformedIdentityAndRejectedCandidateFailClosed() {
        assertThatThrownBy(()->RecoveryCandidatePlan.of(0,SOURCE,PRODUCT,List.of(row(1,42,"원문",0)),List.of(row(0,42,"후보",0)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->RecoveryCandidatePlan.of(42,"bad",PRODUCT,List.of(row(1,42,"원문",0)),List.of(row(0,42,"후보",0)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->RecoveryCandidatePlan.of(42,SOURCE,"unknown",List.of(row(1,42,"원문",0)),List.of(row(0,42,"후보",0)))).isInstanceOf(IllegalArgumentException.class);
        var rejected=new RagDocumentChunkRow(0,42,4,"page 1",null,"제목","body","후보",null,1,"source",null,0,SOURCE,"REJECT",null,null);
        assertThatThrownBy(()->plan(List.of(rejected))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rowOrderingIsCanonicalButBaselineIdentityIsNotDiscarded() {
        var a=RecoveryCandidatePlan.of(42,SOURCE,PRODUCT,List.of(row(102,42,"둘",1),row(101,42,"하나",0)),List.of(row(0,42,"후보",0)));
        var b=RecoveryCandidatePlan.of(42,SOURCE,PRODUCT,List.of(row(101,42,"하나",0),row(102,42,"둘",1)),List.of(row(0,42,"후보",0)));
        assertThat(a.previewHash()).isEqualTo(b.previewHash());
        var c=RecoveryCandidatePlan.of(42,SOURCE,PRODUCT,List.of(row(103,42,"하나",0),row(102,42,"둘",1)),List.of(row(0,42,"후보",0)));
        assertThat(c.baselineHash()).isNotEqualTo(b.baselineHash());
    }
    private static RecoveryCandidatePlan plan(List<RagDocumentChunkRow> chunks) {
        return RecoveryCandidatePlan.of(42,SOURCE,PRODUCT,List.of(row(101,42,"원본",0)),chunks);
    }
    private static RagDocumentChunkRow row(long id,long doc,String text,int order) {
        return new RagDocumentChunkRow(id,doc,4,"page 1",null,"제목","body",text,text,1,"$.pages[1]",null,order,SOURCE,"PASS",null,null);
    }
}
