package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import java.util.List;

class CompoundCheckingNormTests {
    static final String ACTOR="심사기관의 장은 ";
    static final String DUTY=ACTOR+"규칙 제8조에 따라 신청의 적정성을 검토할 때 신청자가 제12조에 따른 허가 기준을 준수하였는지 여부를 확인하여야 한다.";
    static final String CONSEQUENCE=ACTOR+"이를 위반한 경우 해당 신청을 거절하거나 해당 자료의 보완을 요청할 수 있다.";
    static final String SOURCE=DUTY.replace("확인하여야 한다.","확인하여야 하며, ")+CONSEQUENCE.substring(ACTOR.length());
    @Test void characterizesSeparateDutyAndPermissionWithoutRemovingMixedModalityGuard() {
        var parser=new KoreanEvidenceAtomParser();
        var whole=parser.parse(SOURCE);
        assertThat(whole.parseStatus()).isEqualTo(EvidenceAtom.ParseStatus.AMBIGUOUS);
        assertThat(whole.reasonCodes()).containsExactly("AMBIGUOUS_MIXED_MODALITY");
        var duty=parser.parse(DUTY);var permission=parser.parse(CONSEQUENCE);
        assertThat(duty.parseStatus()).as(duty.toString()).isEqualTo(EvidenceAtom.ParseStatus.COMPLETE);
        assertThat(duty.modality()).isEqualTo(EvidenceAtom.Modality.REQUIRED);
        assertThat(permission.parseStatus()).as(permission.toString()).isEqualTo(EvidenceAtom.ParseStatus.COMPLETE);
        assertThat(permission.modality()).isEqualTo(EvidenceAtom.Modality.PERMITTED);
        assertThat(permission.conditions()).isNotEmpty();
        assertThat(duty.subjects()).containsAll(permission.subjects());
    }
    static LawAiAnswerGround ground(int number,String text) {
        return new LawAiAnswerGround(number,9900+number,990,"admrul","검토 규정",null,null,"20261120","CURRENT",
            "제9조","제9조",null,text,null,null,1.0);
    }
    @Test void completeSameOwnerPairSupportsTheWholeLiteralButNotDetachedOrAlteredNorms() {
        var parser=new KoreanEvidenceAtomParser();var matcher=new SemanticEvidenceMatcher(parser);
        var index=matcher.index(List.of(ground(1,SOURCE)));
        assertThat(matcher.matchText(SOURCE,index).status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
        for(String altered:List.of(CONSEQUENCE,DUTY,
            SOURCE.replace("신청의 적정성을 검토할 때 ",""),SOURCE.replace("제12조","제13조"),
            SOURCE.replace("이를 위반한 경우 ",""),SOURCE.replace("심사기관의 장은","신청기관의 장은"),
            SOURCE.replace("요청할 수 있다","요청하여야 한다"),SOURCE.replace("자료의 보완","자료(民間專用)의 보완")))
            assertThat(matcher.matchText(altered,index).status()).as(altered).isNotEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
        assertThat(matcher.matchText(SOURCE,matcher.index(List.of(ground(1,DUTY),ground(2,CONSEQUENCE)))).status())
            .isNotEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
    }
    @Test void contradictoryGlobalConsequenceAndDifferentActorsStayRejected() {
        var parser=new KoreanEvidenceAtomParser();var matcher=new SemanticEvidenceMatcher(parser);
        var conflict=matcher.matchText(SOURCE,matcher.index(List.of(ground(1,SOURCE),
            ground(2,CONSEQUENCE.replace("할 수 있다","할 수 없다")))));
        assertThat(conflict.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONFLICTED);
        String mixed=SOURCE.replace("이를 위반한 경우 ","이를 위반한 경우 신청기관은 ");
        assertThat(matcher.matchText(mixed,matcher.index(List.of(ground(1,mixed)))).status())
            .isNotEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
    }
    @Test void compatibilityNormalizationMustNotTurnChangedAmountsIntoExactSourceProof() {
        var parser=new KoreanEvidenceAtomParser();var matcher=new SemanticEvidenceMatcher(parser);
        String superscript=SOURCE.replace("규칙 제8조", "10⁶원 기준 규칙 제8조");
        String changed=superscript.replace("10⁶", "106");
        assertThat(matcher.match(parser.parse(changed),matcher.index(List.of(ground(1,superscript)))).status())
            .isNotEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
        assertThat(matcher.matchText(changed,matcher.index(List.of(ground(1,superscript)))).status())
            .isNotEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
        assertThat(matcher.matchText(superscript,matcher.index(List.of(ground(1,changed)))).status())
            .isNotEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
        assertThat(matcher.matchText(superscript,matcher.index(List.of(ground(1,superscript)))).status())
            .isNotEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
        assertThat(matcher.matchText(SOURCE,matcher.index(List.of(ground(0,SOURCE)))).status())
            .isNotEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
    }
}
