package com.kaces.pandora.ai.answer;

import java.util.List;

/** Trusted verifier output, independent of the legacy oracle verdict and display truncation. */
public record EvaluationVerificationEnvelope(
    int schemaVersion,
    String verifiedAnswer,
    boolean aligned,
    boolean insufficientEvidence,
    List<String> unsupportedAnswerClaims,
    List<String> unsupportedNumericClaims,
    List<String> contradictedAnswerClaims,
    List<ClaimVerifier.ClaimEvidenceLink> claimEvidenceLinks,
    List<LawAiEvalResponse.AttributedClaim> attributedClaims,
    int unsafeSemanticShadowDisagreementCount,
    OfficialEffectivePhaseEvidence referencePhase
) {
    static EvaluationVerificationEnvelope capture(AnswerVerificationService.Result verification,List<LawAiAnswerGround> grounds) {
        if(verification==null || verification.claimResult()==null || verification.alignmentResult()==null || grounds==null)
            return null;
        var claims=verification.claimResult();
        var phases=grounds.stream().filter(g->g!=null && g.officialCommencement()!=null)
            .map(g->g.officialCommencement().referencePhase())
            .filter(java.util.Objects::nonNull).distinct().toList();
        var phase=phases.size()==1 ? phases.get(0) : null;
        if(phase!=null) {
            var selected=phase;
            long owners=grounds.stream().filter(g->g!=null && selected.matchesGround(g)
                && g.officialCommencement()!=null && selected.equals(g.officialCommencement().referencePhase())).count();
            if(owners!=1) phase=null;
        }
        return new EvaluationVerificationEnvelope(1,verification.verifiedAnswer(),
            verification.alignmentResult().evaluated() && verification.alignmentResult().aligned(),
            verification.insufficientEvidence(),List.copyOf(claims.unsupportedClaims()),
            List.copyOf(claims.unsupportedNumericClaims()),List.copyOf(claims.contradictedClaims()),
            List.copyOf(claims.evidenceLinks()),LawAiEvalResponse.attributeClaims(claims.evidenceLinks(),grounds),
            Math.toIntExact(claims.semanticShadowResults().stream().filter(ClaimMatcherShadowResult::unsafeDisagreement).count()),phase);
    }
}
