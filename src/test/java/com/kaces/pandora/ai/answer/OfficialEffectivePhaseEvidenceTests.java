package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDate;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.persistence.LawCommencementSourceRow;

class OfficialEffectivePhaseEvidenceTests {
    private static final java.net.URI URL=java.net.URI.create("https://www.law.go.kr/LSW/lsHstListR.do?lsiSeq=283735&lsId=013792&ancYnChk=0");
    private static final String CLAUSE = "제1조(시행일) 이 법은 공포 후 6개월이 경과한 날부터 시행한다. 다만, 제32조는 공포 후 1년이 경과한 날부터 시행한다.";
    static final String RAW = """
        {"법령":{"기본정보":{"법령ID":"013792","법령명_한글":"예시법","공포번호":"21392","공포일자":"20260227","시행일자":"20260828"},
        "부칙":{"부칙단위":[{"부칙공포번호":"21392","부칙공포일자":"20260227","부칙내용":[["부칙","%s"]]}]}}}
        """.formatted(CLAUSE);
    static LawSemanticChunkRow anchor(String edition) {
        return new LawSemanticChunkRow(99,42,"law",edition,"예시법",null,null,"20260828","CURRENT",null,
            "목적","제1조(목적) 이 법은 공공행정의 원칙을 정한다.",null,"$.법령.조문.조문단위[0]",null,0,null,null,null,"PASS",null,null,2);
    }
    static LawCommencementSourceRow row(String raw) {
        return new LawCommencementSourceRow(99,42,7,"283735",2,anchor("283735").sourcePath(),
            anchor("283735").chunkText(),"20260828",raw);
    }
    static String history() throws Exception {
        return Files.readString(Path.of("docs/evidence/rag-quality/law-013792-history-20261120.html"));
    }
    private static java.util.Optional<OfficialEffectivePhaseEvidence> prove(String html,String raw,String edition,String asOf) {
        return OfficialEffectivePhaseEvidence.resolve(anchor(edition),row(raw),URL,html,
            LocalDate.parse(asOf),LocalDate.of(2026,11,20));
    }
    @Test void joinsOnlyTheSameEditionAndRetainsDelayedProvisionText() throws Exception {
        assertThat(prove(history(),RAW,"283735","2026-11-18")).hasValueSatisfying(proof -> {
            assertThat(proof.effectiveFrom()).isEqualTo(LocalDate.of(2026,8,28));
            assertThat(proof.effectiveUntilExclusive()).isEqualTo(LocalDate.of(2027,2,28));
            assertThat(proof.referenceDate()).isEqualTo(LocalDate.of(2026,11,18));
            assertThat(proof.commencement().clause()).isEqualTo(CLAUSE);
            assertThat(proof.lawId()).isEqualTo("013792");
            assertThat(proof.sourceSha256()).matches("[a-f0-9]{64}");
        });
    }
    @Test void refusesMismatchedEditionPromulgationOrUnboundSource() throws Exception {
        String html=history();
        assertThat(prove(html,RAW,"251025","2026-11-18")).isEmpty();
        for(String raw: java.util.List.of(RAW.replace("013792","013793"),RAW.replace("21392","21393"),
            RAW.replace("20260227","20260226"),RAW.replace("20260828","20270228"),"{"))
            assertThat(prove(html,raw,"283735","2026-11-18")).isEmpty();
        assertThat(OfficialEffectivePhaseEvidence.resolve(anchor("283735"),null,URL,html,
            LocalDate.of(2026,11,18),LocalDate.of(2026,11,20))).isEmpty();
    }
    @Test void rejectsIncompleteAmbiguousAndUnboundedHistories() throws Exception {
        String html=history();
        for(String changed: java.util.List.of(html.replace("</html>",""),html.replace("20270228","20260828"),
            html.replace("'20270228'","'invalid'"),html.replace("lsViewLsHst2(","unknown(")))
            assertThat(prove(changed,RAW,"283735","2026-11-18")).isEmpty();
        assertThat(prove(html,RAW,"283735","2026-08-27")).isEmpty();
        assertThat(prove(html,RAW,"283735","2027-02-28")).isEmpty();
        assertThat(prove(html,RAW,"283735","2026-11-21")).isEmpty();
    }
    @Test void rejectsAnUnrelatedOrAmbiguousPublicSourceUrl() throws Exception {
        for(String url:java.util.List.of(URL.toString().replace("www.law.go.kr","example.com"),
            URL.toString()+"&lsId=013793",URL.toString().replace("283735","251025"),
            URL.toString().replace("https:","http:"))) {
            assertThat(OfficialEffectivePhaseEvidence.resolve(anchor("283735"),row(RAW),java.net.URI.create(url),history(),
                LocalDate.of(2026,11,18),LocalDate.of(2026,11,20))).isEmpty();
        }
    }
    @Test void referenceReceiptCannotMoveToAnIdenticalBodyFromAnotherOwnerOrVersion() throws Exception {
        var phase=prove(history(),RAW,"283735","2026-11-18").orElseThrow();
        var proof=phase.commencement().withReferencePhase(phase);
        for(int mutation=0;mutation<3;mutation++) {
            var original=anchor("283735");
            var other=new LawSemanticChunkRow(mutation==0?100:99,mutation==0?43:42,"law",mutation==1?"251025":"283735",
                "예시법",null,null,"20260828","CURRENT",null,"목적",original.chunkText(),null,original.sourcePath(),
                null,0,null,null,null,"PASS",null,null,mutation==2?3:2);
            var ground=new LawAiAnswerGround(1,other.chunkId(),other.documentId(),"law","예시법",null,null,"20260828","CURRENT",
                null,"목적",null,other.chunkText(),other.sourcePath(),null,1);
            assertThat(ground.withOfficialCommencement(proof,other).officialCommencement()).isNull();
        }
    }
}
