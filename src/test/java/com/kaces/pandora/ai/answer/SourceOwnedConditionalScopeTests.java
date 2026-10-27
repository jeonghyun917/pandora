package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceOwnedConditionalScopeTests {
    @Test void oppositePermissionsOnlyInsideDifferentAntecedentsAreNotAConflict() {
        String permitted="기관은 자료를 공개할 수 있는 경우에는 심사 또는 검토를 진행한다.";
        String unavailable="기관은 자료를 공개할 수 없는 경우에는 심사 또는 검토를 진행한다.";
        assertThat(new ClaimEvidenceMatcher().match(permitted,List.of(operativeCopy(unavailable))).status())
            .isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
    }
    @Test void copiedOperativeInAnotherGroundCannotHideTheOriginalProofOwner() {
        var copy=operativeCopy(RULE);
        var grounds=List.of(copy,ground(SOURCE));
        var control=new ClaimEvidenceMatcher();
        assertThat(control.match(RULE,grounds).groundNumber()).isEqualTo(2);
        assertThat(control.match(CLAIM,grounds).status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
        assertThat(control.match(CLAIM,grounds).groundNumber()).isEqualTo(1);
        var semantic=new SemanticEvidenceMatcher();
        var result=semantic.match(new KoreanEvidenceAtomParser().parse(CLAIM),semantic.index(grounds));
        assertThat(result.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
        assertThat(result.groundNumber()).isEqualTo(1);
    }
    @Test void originalOwnerSelectionDoesNotOverrideAnOperativeConflict() {
        var grounds=List.of(operativeCopy(SOURCE.replace("개선을 권고할 수 있다","개선을 권고할 수 없다")),ground(SOURCE));
        assertThat(new ClaimEvidenceMatcher().match(RULE,grounds).status()).isEqualTo(ClaimEvidenceMatcher.Status.CONFLICTED);
        assertUnsupported(CLAIM,grounds);
    }
    private static LawAiAnswerGround operativeCopy(String body) {
        return new LawAiAnswerGround(2,2,2,"official_doc","원문 안내","기관","공식 문서","20260101","CURRENT","본문","본문",null,body,"","",1.0,body,null,List.of(),"matched_child_only","direct");
    }
    static final String SCOPE="국가기관등의 장이 소프트웨어사업을 추진하는 경우";
    static final String FIRST="과학기술정보통신부장관은 "+SCOPE+" 이 법 또는 관련 법령을 준수하였는지를 확인하고, 그 결과를 공개할 수 있다.";
    static final String RULE="과학기술정보통신부장관은 제1항에 따른 확인결과 국가기관등의 장이 이 법 또는 관련 법령을 위반하였다고 인정하는 경우에는 개선을 권고할 수 있다.";
    static final String SOURCE="① "+FIRST+"\n② "+RULE+"\n③ 국가기관등의 장은 조치 결과를 통보하여야 한다.";
    static final String CLAIM="정보화시스템 사업이 "+SCOPE+"에 해당하면 "+RULE;

    @Test void literalHypothesisRetainsTheWholeSourceOwnedConditionAndOperativeRule() {
        var grounds=List.of(ground(SOURCE));
        assertThat(new ClaimEvidenceMatcher().match(CLAIM,grounds).status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
    }
    @Test void semanticConsumerRequiresTheSameLiteralCertificate() {
        var grounds=List.of(ground(SOURCE));
        assertThat(new SemanticEvidenceMatcher().match(new KoreanEvidenceAtomParser().parse(CLAIM),new SemanticEvidenceMatcher().index(grounds)).status())
            .isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
    }
    @Test void cannotDropActorScopeFindingOrDiscretionOrAddAConsequence() {
        for(String bad:List.of(CLAIM.replace("국가기관등의 장이 소프트웨어사업을 추진하는 경우에 해당하면","소프트웨어사업에 해당하면"),
            CLAIM.replace("소프트웨어사업을 추진하는 경우에 해당하면","정보화사업을 추진하는 경우에 해당하면"),
            CLAIM.replace("위반하였다고 인정하는 경우에는","위반하면"),
            CLAIM.replace("개선을 권고할 수 있다","개선을 권고하여야 한다"),
            CLAIM.replace("과학기술정보통신부장관은 제1항","행정안전부장관은 제1항"),
            CLAIM.replace("경우에 해당하면","경우에 해당하므로"),
            CLAIM+" 입찰 참가자격도 자동 제한된다.")) assertUnsupported(bad,List.of(ground(SOURCE)));
    }
    @Test void cannotBorrowMissingAmbiguousCrossArticleOrAnnotatedOwnership() {
        for(String badSource:List.of(SOURCE.replace("\n③", "\n④"),SOURCE.replace("② ","② 제58조(다른 조문) "),
            SOURCE.replace("제1항에 따른 확인결과","제2항에 따른 확인결과"),
            SOURCE.replace("② ","① 추가 조건\n② "),
            SOURCE.replace("준수하였는지를 확인하고,", "특별한 사유가 있는 경우에만 준수하였는지를 확인하고,"),
            SOURCE.replace("② "+RULE,"② "+RULE+"*\n* 별도 조건에 한정")))
            assertUnsupported(CLAIM,List.of(ground(badSource)));
        assertUnsupported(CLAIM,List.of(ground("① "+FIRST),new LawAiAnswerGround(2,2,2,"law","다른 원문","","법률","","CURRENT","제2조","",null,"② "+RULE+"\n③ 끝","","",1.0,"② "+RULE+"\n③ 끝","",List.of(),"direct","direct")));
    }
    @Test void shortenedChildCannotBorrowAParentRuleAndUnconditionalGeneralizationFails() {
        var shortened=new LawAiAnswerGround(1,1,1,"law","원문","","법률","","CURRENT","제57조","",null,RULE,"","",1.0,RULE,SOURCE,List.of(),"context","direct");
        assertUnsupported(CLAIM,List.of(shortened));
        assertUnsupported("정보화시스템 법제도를 준수하지 않으면 개선권고를 받는다.",List.of(ground(SOURCE)));
    }
    @Test void cannotHideAnOuterOrFollowingScopeRestriction() {
        assertUnsupported(CLAIM,List.of(ground("이 조는 특정 사업에만 적용한다.\n"+SOURCE)));
        assertUnsupported(CLAIM,List.of(ground(SOURCE+"\n⑥ 제2항에도 불구하고 특정 사업은 제외한다.")));
        assertUnsupported(CLAIM,List.of(ground(SOURCE.replace("국가기관등의 장은 조치 결과를 통보하여야 한다.","제2항은 특정 사업에 한하여 적용한다."))));
        var hidden=new LawAiAnswerGround(1,1,1,"law","원문","","법률","","CURRENT","제57조","",null,SOURCE,"","",1.0,SOURCE,SOURCE+"\n⑥ 특정 사업에만 적용한다.",List.of(),"context","direct");
        assertUnsupported(CLAIM,List.of(hidden));
    }
    @Test void followingNonApplicabilityCannotBeDiscardedByTheCertificate() {
        for(String restriction:List.of("제2항은 민간 사업에 적용하지 않는다.","민간 사업은 제2항의 적용 대상이 아니다.","민간 사업은 제2항의 적용을 면제한다.")) {
            String restricted=SOURCE.replace("국가기관등의 장은 조치 결과를 통보하여야 한다.",restriction);
            assertUnsupported(CLAIM,List.of(ground(restricted)));
        }
    }
    @Test void checkingObjectQualifierCannotDisappearFromTheHypothesis() {
        String qualified=SOURCE.replace("이 법 또는 관련 법령을 준수하였는지를", "예산이 10억원 이상인 사업이 이 법 또는 관련 법령을 준수하였는지를");
        assertUnsupported(CLAIM,List.of(ground(qualified)));
    }
    @Test void anExternalArticleReferenceCannotBorrowTheLocalFirstParagraph() {
        String source=SOURCE.replace("제1항에 따른 확인결과","제59조제1항에 따른 확인결과");
        String claim=CLAIM.replace("제1항에 따른 확인결과","제59조제1항에 따른 확인결과");
        assertUnsupported(claim,List.of(ground(source)));
    }
    @Test void businessLabelCannotSmuggleAnUnsupportedAssertion() {
        assertUnsupported(CLAIM.replace("정보화시스템 사업이","과태료 100만원을 내야 하는 정보화시스템 사업이"),List.of(ground(SOURCE)));
    }
    @Test void finalAlignmentConsumesOnlyTheProvedLiteralLegalCondition() {
        var grounds=List.of(ground(SOURCE));
        var verified=new ClaimVerifier().verifyDetailed(CLAIM,grounds);
        assertThat(verified.insufficientEvidence()).isFalse();
        var result=new AnswerQuestionAlignmentVerifier().verify("정보화시스템 법제도 준수안하면 어떤 불이익?",verified,grounds);
        assertThat(result.aligned()).as(result.toString()).isTrue();
    }
    @Test void conditionEquivalenceIsNotQuestionSpecific() {
        var grounds=List.of(ground(SOURCE));
        String claim=CLAIM.replace("정보화시스템 사업", "디지털서비스 사업");
        var verified=new ClaimVerifier().verifyDetailed(claim,grounds);
        var result=new AnswerQuestionAlignmentVerifier().verify("디지털서비스 사업 법령을 준수하지 않으면 어떤 조치가 있나요?",verified,grounds);
        assertThat(result.aligned()).as(result.toString()).isTrue();
    }
    @Test void finalAlignmentCannotDiscardAnotherConditionOrUseAContractBreach() {
        var grounds=List.of(ground(SOURCE));var verified=new ClaimVerifier().verifyDetailed(CLAIM,grounds);
        for(String question:List.of("정보화시스템 법제도 준수안하면 개인정보를 공개하면 어떤 불이익?", "정보화시스템 법제도 준수하면 어떤 불이익?"))
            assertThat(new AnswerQuestionAlignmentVerifier().verify(question,verified,grounds).aligned()).as(question).isFalse();
        String contract=SOURCE.replace("이 법 또는 관련 법령을 위반하였다고", "이 계약서 또는 관련 합의를 위반하였다고");
        String claim=CLAIM.replace("이 법 또는 관련 법령을 위반하였다고", "이 계약서 또는 관련 합의를 위반하였다고");
        var contractGrounds=List.of(ground(contract));var contractVerified=new ClaimVerifier().verifyDetailed(claim,contractGrounds);
        assertThat(new AnswerQuestionAlignmentVerifier().verify("정보화시스템 법제도 준수안하면 어떤 불이익?",contractVerified,contractGrounds).aligned()).isFalse();
    }
    static LawAiAnswerGround ground(String source) {
        return new LawAiAnswerGround(1,1,1,"law","원문 법률","과학기술정보통신부","법률","20250423","CURRENT","제57조","",null,source,"","",1.0,source,source,List.of(),"source-owned","direct");
    }
    private static void assertUnsupported(String claim,List<LawAiAnswerGround> grounds) {
        assertThat(new ClaimEvidenceMatcher().match(claim,grounds).status()).as(claim).isNotEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
        assertThat(new SemanticEvidenceMatcher().match(new KoreanEvidenceAtomParser().parse(claim),new SemanticEvidenceMatcher().index(grounds)).status())
            .as(claim).isNotEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
    }
}
