package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvaluationVerificationEnvelopeTests {
    private static final String CLAUSE="제1조(시행일) 이 법은 공포 후 6개월이 경과한 날부터 시행한다.";
    private LawAiAnswerGround ground(int number,long chunk) {
        return new LawAiAnswerGround(number,chunk,42,"law","예시법",null,null,"20260828","CURRENT",
            null,"시행일",null,CLAUSE,"$.법령.부칙.부칙단위[0]",null,1);
    }
    private AnswerVerificationService.Result result(String text,boolean aligned,List<String> numeric) {
        var link=new ClaimVerifier.ClaimEvidenceLink(text,"SUPPORTED",1,text,1,1,1);
        var claims=new ClaimVerifier.VerificationResult(text,false,false,List.of(),numeric,List.of(),List.of(link),1,1);
        return new AnswerVerificationService.Result(text,claims,
            new AnswerQuestionAlignmentVerifier.AlignmentResult(true,aligned,"fixture",List.of(),text));
    }
    @Test void capturesFullTrustedTextAndSerializesWithoutTheLegacyTruncation() throws Exception {
        String text="근거를 보존하는 독립 문장입니다. ".repeat(120);
        var envelope=EvaluationVerificationEnvelope.capture(result(text,true,List.of()),List.of(ground(1,99)));
        assertThat(envelope.schemaVersion()).isEqualTo(1);
        assertThat(envelope.verifiedAnswer()).isEqualTo(text).hasSizeGreaterThan(1500);
        assertThat(envelope.aligned()).isTrue();
        assertThat(envelope.insufficientEvidence()).isFalse();
        assertThat(envelope.attributedClaims()).hasSize(1);
        var json=new tools.jackson.databind.ObjectMapper().valueToTree(envelope);
        assertThat(json.path("verifiedAnswer").asString()).isEqualTo(text);
        assertThat(json.path("schemaVersion").asInt()).isEqualTo(1);
    }
    @Test void doesNotReplaceAlignmentOrNumericFailuresWithAnOracleFlag() {
        var envelope=EvaluationVerificationEnvelope.capture(result("독립 주장",false,List.of("숫자 미지원")),List.of(ground(1,99)));
        assertThat(envelope.aligned()).isFalse();
        assertThat(envelope.insufficientEvidence()).isTrue();
        assertThat(envelope.unsupportedNumericClaims()).containsExactly("숫자 미지원");
        assertThat(envelope.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
        var duplicate=EvaluationVerificationEnvelope.capture(result("독립 주장",true,List.of()),List.of(ground(1,99),ground(1,100)));
        assertThat(duplicate.attributedClaims()).isEmpty();
    }
    @Test void carriesOnlyAnUnambiguousSourceOwnedReferenceReceipt() {
        var base=new OfficialCommencementEvidence(LocalDate.of(2026,8,28),CLAUSE,"$.법령.부칙.부칙단위[0]",
            com.kaces.pandora.common.text.LawHashUtils.sha256(CLAUSE));
        var phase=new OfficialEffectivePhaseEvidence("013792","283735",42,99,2,LocalDate.of(2026,8,28),
            LocalDate.of(2027,2,28),LocalDate.of(2026,11,18),LocalDate.of(2026,11,20),"a".repeat(64),base);
        var owned=ground(1,99).withOfficialCommencement(base.withReferencePhase(phase));
        assertThat(EvaluationVerificationEnvelope.capture(result("독립 주장",true,List.of()),List.of(owned)).referencePhase()).isEqualTo(phase);
        assertThat(EvaluationVerificationEnvelope.capture(result("독립 주장",true,List.of()),List.of(ground(1,100))).referencePhase()).isNull();
        assertThat(EvaluationVerificationEnvelope.capture(null,List.of())).isNull();
    }
}
