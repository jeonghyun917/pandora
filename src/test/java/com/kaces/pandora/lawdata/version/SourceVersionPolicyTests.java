package com.kaces.pandora.lawdata.version;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SourceVersionPolicyTests {
    private static final LocalDate TODAY=LocalDate.of(2026,10,28);
    private static final String HASH="a".repeat(64), RAW="b".repeat(64), SHA="c".repeat(40);
    private SourceVersionPolicy.Baseline baseline() {
        return new SourceVersionPolicy.Baseline(22614,0,"admrul","admrul:전자정부성과관리지침","old-official-version","20251219",HASH);
    }
    private SourceVersionPolicy.Source source(String target,String title,String externalId,String date) {
        return new SourceVersionPolicy.Source(target,title,externalId,date,RAW);
    }
    @Test void stagesNewerDistinctOfficialVersionWithoutChangingBaseline() {
        var baseline=baseline();
        var candidate=SourceVersionPolicy.stage(baseline,source("admrul","전자정부 성과관리 지침","new-official-version","20261019"),SHA,TODAY);
        assertThat(candidate.baseline()).isEqualTo(baseline);
        assertThat(candidate.state()).isEqualTo("STAGED");
        assertThat(candidate.source().externalId()).isEqualTo("new-official-version");
        assertThat(candidate.inputHash()).matches("[0-9a-f]{64}");
        assertThat(baseline.documentId()).isEqualTo(22614);
    }
    @Test void rejectsWrongGroupTargetOrExternalIdentity() {
        for(var invalid:java.util.List.of(
                source("law","전자정부 성과관리 지침","new","20261019"),
                source("admrul","다른 지침","new","20261019"),
                source("admrul","전자정부 성과관리 지침","old-official-version","20261019"))) {
            assertThatThrownBy(()->SourceVersionPolicy.stage(baseline(),invalid,SHA,TODAY)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void rejectsUnknownFutureMalformedOrNonNewerDate() {
        for(String date:java.util.List.of("","20260230","20261029","20251219","20250101","2026-10-19")) {
            assertThatThrownBy(()->SourceVersionPolicy.stage(baseline(),source("admrul","전자정부 성과관리 지침","new",date),SHA,TODAY))
                .as(date).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void changedPayloadBaselineRevisionOrProductCannotReuseCandidateIdentity() {
        var s=source("admrul","전자정부 성과관리 지침","new","20261019");
        var first=SourceVersionPolicy.stage(baseline(),s,SHA,TODAY);
        assertThat(SourceVersionPolicy.stage(baseline(),s,SHA,TODAY).inputHash()).isEqualTo(first.inputHash());
        var newerBaseline=new SourceVersionPolicy.Baseline(22614,1,"admrul","admrul:전자정부성과관리지침","old-official-version","20251219",HASH);
        assertThat(SourceVersionPolicy.stage(newerBaseline,s,SHA,TODAY).inputHash()).isNotEqualTo(first.inputHash());
        assertThat(SourceVersionPolicy.stage(baseline(),new SourceVersionPolicy.Source(s.target(),s.title(),s.externalId(),s.effectiveDate(),"d".repeat(64)),SHA,TODAY).inputHash()).isNotEqualTo(first.inputHash());
        assertThat(SourceVersionPolicy.stage(baseline(),s,"d".repeat(40),TODAY).inputHash()).isNotEqualTo(first.inputHash());
    }
    @Test void rejectsUntrustedBaselineAndMalformedHashes() {
        var s=source("admrul","전자정부 성과관리 지침","new","20261019");
        for(var b:java.util.List.of(
                new SourceVersionPolicy.Baseline(0,0,"admrul","admrul:전자정부성과관리지침","old","20251219",HASH),
                new SourceVersionPolicy.Baseline(22614,-1,"admrul","admrul:전자정부성과관리지침","old","20251219",HASH),
                new SourceVersionPolicy.Baseline(22614,0,"admrul","wrong-group","old","20251219",HASH),
                new SourceVersionPolicy.Baseline(22614,0,"admrul","admrul:전자정부성과관리지침","old","20251219","bad"))) {
            assertThatThrownBy(()->SourceVersionPolicy.stage(b,s,SHA,TODAY)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->SourceVersionPolicy.stage(baseline(),s,"bad",TODAY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SourceVersionPolicy.stage(baseline(),new SourceVersionPolicy.Source(s.target(),s.title(),s.externalId(),s.effectiveDate(),"bad"),SHA,TODAY)).isInstanceOf(IllegalArgumentException.class);
    }
}
