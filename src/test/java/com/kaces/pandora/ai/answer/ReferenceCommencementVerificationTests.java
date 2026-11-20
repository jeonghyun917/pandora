package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReferenceCommencementVerificationTests {
    private final AnswerVerificationService verifier=new AnswerVerificationService(new AnswerGuard(),new ClaimVerifier());
    private static final String TITLE="예시행정법";
    private static final String CLAUSE="제1조(시행일) 이 법은 공포 후 6개월이 경과한 날부터 시행한다. 다만, 제32조는 공포 후 1년이 경과한 날부터 시행한다.";
    private static final String QUESTION="2026년 11월 18일 기준, 예시행정법의 일반 시행일과 현재 시행 상태는 무엇인가요?";
    private LawAiAnswerGround ground(boolean history) {
        var base=new OfficialCommencementEvidence(LocalDate.of(2026,8,28),CLAUSE,"$.법령.부칙.부칙단위[0]",
            com.kaces.pandora.common.text.LawHashUtils.sha256(CLAUSE));
        var proof=history ? base.withReferencePhase(new OfficialEffectivePhaseEvidence("013792","283735",42,99,2,
            LocalDate.of(2026,8,28),LocalDate.of(2027,2,28),LocalDate.of(2026,11,18),LocalDate.of(2026,11,20),"a".repeat(64),base)) : base;
        return new LawAiAnswerGround(1,99,42,"law",TITLE,null,null,"20260828","CURRENT",null,"시행일",null,
            CLAUSE,"$.법령.부칙.부칙단위[0]",null,1).withOfficialCommencement(proof);
    }
    @Test void verifiesOnlyTheGeneralDateAndTheSameReferencePhase() {
        String answer=ground(true).officialCommencement().officialDateStatement(TITLE)+"\n"
            +ground(true).officialCommencement().officialReferenceStatement(TITLE);
        var result=verifier.verify(QUESTION,answer,List.of(ground(true)));
        assertThat(result.alignmentResult().aligned()).isTrue();
        assertThat(result.claimResult().unsupportedClaims()).isEmpty();
    }
    @Test void currentFlagAloneDoesNotProveReferenceStatusOrEveryProvision() {
        String answer=ground(true).officialCommencement().officialDateStatement(TITLE)+"\n"
            +ground(true).officialCommencement().officialReferenceStatement(TITLE);
        assertThat(verifier.verify(QUESTION,answer,List.of(ground(false))).alignmentResult().aligned()).isFalse();
        for(String question:List.of(QUESTION.replace("18일","17일"),QUESTION.replace(TITLE,"다른행정법"),
            QUESTION.replace("일반 시행일","제32조 시행일")))
            assertThat(verifier.verify(question,answer,List.of(ground(true))).alignmentResult().aligned()).isFalse();
        var all=verifier.verify(QUESTION,answer+" 모든 조문이 시행 중이다.",List.of(ground(true)));
        assertThat(all.claimResult().unsupportedClaims()).isNotEmpty();
    }
}
