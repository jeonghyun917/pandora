package com.kaces.pandora.rag.recovery;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReviewedPdfDraftTests {
    private final ReviewedPdfUnit a=new ReviewedPdfUnit(List.of(new ReviewedPdfUnit.Line(1,1,"Agency must notify.")));
    private final ReviewedPdfUnit b=new ReviewedPdfUnit(List.of(new ReviewedPdfUnit.Line(2,1,"Only for covered projects.")));

    @Test void reviewIdentityBindsMetadataSourceBaselineProductAndOrderedUnits() {
        var draft=new ReviewedPdfDraft(12,"a".repeat(64),"b".repeat(64),"c".repeat(40),
                "Official rule","https://example.gov/rule.pdf",List.of(
                    new ReviewedPdfDraft.Unit("procedure","Article 1",a),
                    new ReviewedPdfDraft.Unit("scope","Article 2",b)));
        String approved=draft.reviewHash();
        assertThat(draft.requireReview(approved)).isSameAs(draft);
        var omitted=new ReviewedPdfDraft(12,draft.sourceHash(),draft.baselineHash(),draft.productSha(),
                draft.title(),draft.sourceUrl(),List.of(draft.units().get(0)));
        assertThatThrownBy(()->omitted.requireReview(approved)).isInstanceOf(IllegalArgumentException.class);
        var changed=new ReviewedPdfDraft(13,draft.sourceHash(),draft.baselineHash(),draft.productSha(),
                draft.title(),draft.sourceUrl(),draft.units());
        assertThatThrownBy(()->changed.requireReview(approved)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void duplicateUnitIdentityIsAmbiguousEvenWhenBodiesDiffer() {
        assertThatThrownBy(()->new ReviewedPdfDraft(12,"a".repeat(64),"b".repeat(64),"c".repeat(40),
                "Official rule","https://example.gov/rule.pdf",List.of(
                    new ReviewedPdfDraft.Unit("scope","Article 1",a),
                    new ReviewedPdfDraft.Unit("scope","Article 2",b)))).isInstanceOf(IllegalArgumentException.class);
    }
}
