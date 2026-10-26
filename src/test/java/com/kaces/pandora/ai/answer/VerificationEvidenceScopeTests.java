package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class VerificationEvidenceScopeTests {
    @Test void repeatedVerificationReusesOnlyTheExactScopedGroundsAndStillRejectsUnsupportedClaims() {
        var controlBuilds=new AtomicInteger();var semanticBuilds=new AtomicInteger();
        var control=new ClaimEvidenceMatcher(){@Override public EvidenceIndex index(List<LawAiAnswerGround> grounds){controlBuilds.incrementAndGet();return super.index(grounds);}};
        var semantic=new SemanticEvidenceMatcher(){@Override public EvidenceIndex index(List<LawAiAnswerGround> grounds,com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot routing){semanticBuilds.incrementAndGet();return super.index(grounds,routing);}};
        var verifier=new ClaimVerifier(control,semantic,new KoreanEvidenceAtomParser(),new com.kaces.pandora.semantic.config.LawAiVerificationProperties(true,false,20));
        var grounds=List.of(ground("기관은 자료를 공개할 수 있다."));
        var baseline=verifier.verifyDetailed("기관은 자료를 공개할 수 있다.",grounds);
        int controlBefore=controlBuilds.get(),semanticBefore=semanticBuilds.get();
        var routing=new com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot(0,java.util.Map.of(),java.util.Map.of(),java.util.Set.of(),null,null);
        try(var scope=verifier.openEvidenceScope(grounds,routing)) {
            assertThat(verifier.verifyDetailed("기관은 자료를 공개할 수 있다.",grounds,routing)).isEqualTo(baseline);
            assertThat(verifier.verifyDetailed("기관은 자료를 공개할 수 있다.",grounds,routing)).isEqualTo(baseline);
            assertThat(verifier.verifyDetailed("기관은 반드시 벌금 100만원을 부과하여야 한다.",grounds,routing).insufficientEvidence()).isTrue();
            assertThat(controlBuilds.get()-controlBefore).isEqualTo(1);
            assertThat(semanticBuilds.get()-semanticBefore).isEqualTo(1);
            assertThat(verifier.verifyDetailed("기관은 자료를 공개할 수 있다.",List.of(ground("기관은 자료를 공개할 수 없다.")),routing).insufficientEvidence()).isTrue();
            assertThat(controlBuilds.get()-controlBefore).isEqualTo(2);
        }
        verifier.verifyDetailed("기관은 자료를 공개할 수 있다.",grounds);
        assertThat(controlBuilds.get()-controlBefore).isEqualTo(3);
    }

    @Test void mutableContextMembershipCannotKeepAStaleScopedIndex() {
        var builds=new AtomicInteger();var ids=new java.util.ArrayList<Long>(java.util.Arrays.asList(2L,null));
        var control=new ClaimEvidenceMatcher(){@Override public EvidenceIndex index(List<LawAiAnswerGround> grounds){builds.incrementAndGet();return super.index(grounds);}};
        var verifier=new ClaimVerifier(control);
        var grounds=List.of(new LawAiAnswerGround(1,1,1,"official_doc","자료 공개 지침","기관","공식 문서",null,null,"본문","본문",null,"기관은 자료를 공개할 수 있다.",null,null,1.0,"기관은 자료를 공개할 수 있다.",null,ids,"context","direct"));
        try(var scope=verifier.openEvidenceScope(grounds,null)) {
            verifier.verifyDetailed("기관은 자료를 공개할 수 있다.",grounds);
            ids.add(3L);
            verifier.verifyDetailed("기관은 자료를 공개할 수 있다.",grounds);
            assertThat(builds.get()).isEqualTo(2);
        }
    }

    @Test void uncapturedRoutingMustRefreshSourceTableProofOnEveryVerification() {
        var reads=new AtomicInteger();
        var tables=new com.kaces.pandora.rag.chunk.RagSourceTableEvidenceService(null,null){
            @Override public List<com.kaces.pandora.rag.chunk.SourceTableBindings.Binding> fromSelectedChunks(List<Long> ids){reads.incrementAndGet();return List.of();}
        };
        var verifier=new ClaimVerifier(new ClaimEvidenceMatcher(),new SemanticEvidenceMatcher(new KoreanEvidenceAtomParser(),tables),new KoreanEvidenceAtomParser(),new com.kaces.pandora.semantic.config.LawAiVerificationProperties(true,false,20));
        var grounds=List.of(ground("기관은 자료를 공개할 수 있다."));
        try(var scope=verifier.openEvidenceScope(grounds,null)) {
            assertThat(verifier.verifyDetailed("기관은 자료를 공개할 수 있다.",grounds).insufficientEvidence()).isFalse();
            assertThat(verifier.verifyDetailed("기관은 자료를 공개할 수 있다.",grounds).insufficientEvidence()).isFalse();
            assertThat(reads.get()).isEqualTo(2);
        }
    }

    private static LawAiAnswerGround ground(String body) {
        return new LawAiAnswerGround(1,1,1,"official_doc","자료 공개 지침","기관","공식 문서",null,null,"본문","본문",null,body,null,null,1.0);
    }

    @Test void nestedScopeAndExceptionRestoreOnlyTheOuterRequestAndNeverTheNextRequest() {
        var builds=new AtomicInteger();
        var control=new ClaimEvidenceMatcher(){@Override public EvidenceIndex index(List<LawAiAnswerGround> grounds){builds.incrementAndGet();return super.index(grounds);}};
        var verifier=new ClaimVerifier(control);var outer=List.of(ground("기관은 자료를 공개할 수 있다."));
        var inner=List.of(ground("기관은 자료를 공개할 수 없다."));
        try(var scope=verifier.openEvidenceScope(outer,null)) {
            verifier.verifyDetailed("기관은 자료를 공개할 수 있다.",outer);
            try(var nested=verifier.openEvidenceScope(inner,null)) {
                verifier.verifyDetailed("기관은 자료를 공개할 수 없다.",inner);
                throw new IllegalStateException("synthetic request failure");
            } catch(IllegalStateException expected) {assertThat(expected).hasMessage("synthetic request failure");}
            assertThat(verifier.verifyDetailed("기관은 자료를 공개할 수 있다.",outer).insufficientEvidence()).isFalse();
            assertThat(builds.get()).isEqualTo(2);
        }
        verifier.verifyDetailed("기관은 자료를 공개할 수 있다.",outer);
        assertThat(builds.get()).isEqualTo(3);
    }

    @Test void routingIdentityAndConcurrentRequestsCannotReuseAnotherScope() throws Exception {
        var builds=new AtomicInteger();
        var control=new ClaimEvidenceMatcher(){@Override public EvidenceIndex index(List<LawAiAnswerGround> grounds){builds.incrementAndGet();return super.index(grounds);}};
        var verifier=new ClaimVerifier(control);var grounds=List.of(ground("기관은 자료를 공개할 수 있다."));
        var first=new com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot(0,java.util.Map.of(),java.util.Map.of(),java.util.Set.of(),null,null);
        var second=new com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot(1,java.util.Map.of(),java.util.Map.of(),java.util.Set.of(),null,null);
        try(var scope=verifier.openEvidenceScope(grounds,first)) {
            verifier.verifyDetailed("기관은 자료를 공개할 수 있다.",grounds,first);
            verifier.verifyDetailed("기관은 자료를 공개할 수 있다.",grounds,second);
            verifier.verifyDetailed("기관은 자료를 공개할 수 있다.",grounds,first);
            assertThat(builds.get()).isEqualTo(2);
            var other=new java.util.concurrent.FutureTask<>(()->{
                try(var nested=verifier.openEvidenceScope(grounds,first)) {
                    verifier.verifyDetailed("기관은 자료를 공개할 수 있다.",grounds,first);
                    return verifier.verifyDetailed("기관은 자료를 공개할 수 있다.",grounds,first).insufficientEvidence();
                }
            });
            new Thread(other).start();assertThat(other.get(10,java.util.concurrent.TimeUnit.SECONDS)).isFalse();
            verifier.verifyDetailed("기관은 자료를 공개할 수 있다.",grounds,first);
            assertThat(builds.get()).isEqualTo(3);
        }
    }

    @Test void repairBindsAllCandidateVerificationsButReleasesItsScopeAfterReturning() {
        var builds=new AtomicInteger();var calls=new AtomicInteger();
        var control=new ClaimEvidenceMatcher(){@Override public EvidenceIndex index(List<LawAiAnswerGround> grounds){builds.incrementAndGet();return super.index(grounds);}};
        var verifier=new ClaimVerifier(control);
        var verification=new AnswerVerificationService(new AnswerGuard(),verifier){
            @Override public Result verify(String question,String answer,List<LawAiAnswerGround> grounds){calls.incrementAndGet();return super.verify(question,answer,grounds);}
        };
        var grounds=List.of(ground("기관은 자료를 공개할 수 있다. 기관은 자료를 보관하여야 한다."));
        var repair=new GroundedAnswerRepairService(verification,null);
        assertThat(repair.verifyAndRepair("기관의 자료 보관 절차는 무엇인가요?","기관은 벌금 100만원을 부과하여야 한다.",grounds).insufficientEvidence()).isTrue();
        assertThat(calls.get()).isGreaterThan(1);
        assertThat(builds.get()).isEqualTo(1);
        verification.verify("기관의 자료 보관 절차는 무엇인가요?","기관은 자료를 보관하여야 한다.",grounds);
        assertThat(builds.get()).isEqualTo(2);
    }
}
