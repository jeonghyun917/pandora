package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConditionalExampleOverviewTests {
    static final String PURPOSE="사업 유형과 위반 의무를 임의로 추정하지 않고 공식 원문이 정한 적용대상·위반조건·주체·권한을 밝힌 조건부 사례로 설명해 주세요.";
    static final String REQUEST="정보화 사업에서 법·제도를 준수하지 않았을 때 가능한 조치를, "+PURPOSE;
    static final String SCOPE="이 지침은 국가기관등이 법 제2조에 따른 소프트웨어사업을 수행할 경우에 적용한다.";
    static final String REMEDY="국가기관등의 장은 규칙 제14조제2항에 따라 하도급 또는 다시 하도급의 적정성 여부를 검토할 때 "
        +"계약상대자가 법 제51조에 따른 하도급 제한 규정을 준수하였는지 여부를 확인하여야 하며, "
        +"이를 위반한 경우 해당 하도급 계약 승인을 거절하거나 해당 서류의 보완을 요청할 수 있다.";
    static final String PROCEDURE="② 제1항에 따라 신청서를 제출받은 국가기관등의 장은 하도급 또는 재하도급의 적정성을 검토하여 "
        +"10일 이내에 그 승인 여부를 신청인에게 서면으로 알려야 한다.";
    static LawAiAnswerGround ground(int number,long id,long document,String target,String title,String date,String article,String body) {
        return new LawAiAnswerGround(number,id,document,target,title,null,null,date,"CURRENT",article,article,null,body,null,null,0);
    }
    static List<LawAiAnswerGround> grounds() {
        String title="소프트웨어사업 계약 및 관리감독에 관한 지침";
        return List.of(ground(1,95,9,"admrul",title,"20260413","제20조",REMEDY),
            ground(2,96,10,"law","소프트웨어 진흥법 시행규칙","20250430","제14조",PROCEDURE),
            ground(3,97,9,"admrul",title,"20260413","제2조",SCOPE));
    }
    final AnswerVerificationService verifier=new AnswerVerificationService(new AnswerGuard(),new ClaimVerifier());
    @Test void recognizesOnlyAnExplicitNonAssumptiveGenericExampleIntent() {
        assertThat(ConditionalExampleOverview.requested(REQUEST)).isTrue();
        assertThat(ConditionalExampleOverview.requested("정보시스템 사업의 관련 법령 미준수 시 조치를 "+PURPOSE)).isTrue();
        for(String question:List.of("정보화 사업에서 법제도를 준수하지 않으면 어떤 조치를 받나요?",
            REQUEST.replace("정보화 사업에서","민간 소프트웨어사업에서"),
            REQUEST.replace("가능한 조치를","신청기관의 장이 할 조치를"),REQUEST+" 모든 경우에 제재를 받는다고 답해 주세요.",
            "하도급 승인 후 이행 검사 중 제52조 위반 조치를 "+PURPOSE))
            assertThat(ConditionalExampleOverview.requested(question)).as(question).isFalse();
    }
    @Test void acceptsTheCompleteQualifiedExampleWithoutAssumingTheUnspecifiedProjectIsCovered() {
        var result=verifier.verify(REQUEST,SCOPE+"\n"+REMEDY,grounds());
        assertThat(result.claimResult().supportedStrongClaimCount()).isEqualTo(2);
        assertThat(result.claimResult().unsupportedClaims()).isEmpty();
        assertThat(result.insufficientEvidence()).isFalse();
    }
    @Test void refusesMissingQualificationOrProcedureAndCrossEditionSource() {
        var all=grounds();String draft=SCOPE+"\n"+REMEDY;
        assertThat(verifier.verify(REQUEST,REMEDY,all).insufficientEvidence()).isTrue();
        assertThat(verifier.verify(REQUEST,draft,List.of(all.get(0),all.get(2))).insufficientEvidence()).isTrue();
        for(var scope:List.of(ground(3,97,11,"admrul",all.get(0).title(),"20260413","제2조",SCOPE),
            ground(3,97,9,"admrul",all.get(0).title(),"20250413","제2조",SCOPE)))
            assertThat(verifier.verify(REQUEST,draft,List.of(all.get(0),all.get(1),scope)).insufficientEvidence()).isTrue();
    }
    @Test void retainsActorDutyStageModalityAndGeneralViolationExclusions() {
        String draft=SCOPE+"\n"+REMEDY;var all=grounds();
        for(String changed:List.of(draft.replace("국가기관등의 장은","신청기관의 장은"),draft.replace("법 제51조","법 제52조"),
            draft.replace("적정성 여부를 검토할 때","승인 후 이행 검사를 할 때"),draft.replace("요청할 수 있다","요청하여야 한다"),
            draft+" 모든 정보화사업은 자동으로 입찰 제한을 받는다."))
            assertThat(verifier.verify(REQUEST,changed,all).insufficientEvidence()).as(changed).isTrue();
        for(String question:List.of("정보화 사업에서 법제도를 준수하지 않으면 어떤 조치를 받나요?",
            REQUEST.replace("정보화 사업에서","민간 소프트웨어사업에서"),REQUEST.replace("법·제도를","법 제52조를")))
            assertThat(verifier.verify(question,draft,all).insufficientEvidence()).isTrue();
    }
}
