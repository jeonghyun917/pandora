package com.kaces.pandora.ai.answer;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;

class OfficialEffectiveHistoryServiceTests {
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"),ZoneOffset.UTC);
    private static final String QUESTION="2026년11월18일기준예시법의일반시행일과현재시행상태는무엇인가요";
    @Test void loadsOnlyCanonicalOfficialHistoryForTheExactNamedStoredEdition() throws Exception {
        String html=OfficialEffectivePhaseEvidenceTests.history();
        var service=new OfficialEffectiveHistoryService(CLOCK,uri -> {
            assertThat(uri.toString()).isEqualTo("https://www.law.go.kr/LSW/lsHstListR.do?lsiSeq=283735&lsId=013792&ancYnChk=0");
            return html;
        });
        assertThat(service.resolve(QUESTION,OfficialEffectivePhaseEvidenceTests.anchor("283735"),
            OfficialEffectivePhaseEvidenceTests.row(OfficialEffectivePhaseEvidenceTests.RAW))).isPresent();
    }
    @Test void incompatibleQuestionsAndUnverifiedStoredDetailNeverMakeARequest() {
        var calls=new AtomicInteger();
        var service=new OfficialEffectiveHistoryService(CLOCK,uri -> {calls.incrementAndGet();return "";});
        for(String question:java.util.List.of("예시법의시행일은언제인가요",QUESTION.replace("18일","32일"),
            QUESTION.replace("2026년11월18일","2027년11월18일"),QUESTION.replace("예시법","다른법")))
            assertThat(service.resolve(question,OfficialEffectivePhaseEvidenceTests.anchor("283735"),
                OfficialEffectivePhaseEvidenceTests.row(OfficialEffectivePhaseEvidenceTests.RAW))).isEmpty();
        assertThat(service.resolve(QUESTION,OfficialEffectivePhaseEvidenceTests.anchor("283735"),
            OfficialEffectivePhaseEvidenceTests.row("{"))).isEmpty();
        assertThat(calls.get()).isZero();
    }
    @Test void unavailableOrInvalidHistoryFailsClosedWithoutRetry() {
        var calls=new AtomicInteger();
        var service=new OfficialEffectiveHistoryService(CLOCK,uri -> {calls.incrementAndGet();throw new IllegalStateException("unavailable");});
        assertThat(service.resolve(QUESTION,OfficialEffectivePhaseEvidenceTests.anchor("283735"),
            OfficialEffectivePhaseEvidenceTests.row(OfficialEffectivePhaseEvidenceTests.RAW))).isEmpty();
        assertThat(calls.get()).isEqualTo(1);
    }
}
