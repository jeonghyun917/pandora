package com.kaces.pandora.ai.answer;

import java.nio.charset.StandardCharsets;
import java.util.List;

class PriorConsultationOmissionControl {
    public static void main(String[] args) throws Exception {
        String article = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!article.startsWith("제13조(사전협의 신청)")) throw new IllegalStateException("Expected Article 13");
        int start = article.indexOf("⑤"), end = article.indexOf("⑥", start);
        if (start < 0 || end < 0) throw new IllegalStateException("Expected official clause boundary");
        var literal = article.substring(start + 1, end);
        var ground = new LawAiAnswerGround(1, 1L, 1L, "admrul", "전자정부 성과관리 지침",
            null, null, null, "CURRENT", "제13조", "제13조", null, literal, null, null, 0);
        check("literal", literal, ground);
        check("omission-removed", literal.replace("정보화사업의 사전협의 신청 누락이 있는 경우에는 ", ""), ground);
        check("scope-expanded", literal.replace("정보화사업의 사전협의 신청 누락이 있는 경우", "모든 정보화사업에서 법제도를 준수하지 않은 경우"), ground);
        check("sanction-substitution", literal.replace("해당기관의 장은 즉시 신청하여야 한다", "해당기관의 입찰 참가자격을 제한하여야 한다"), ground);
    }

    private static void check(String name, String claim, LawAiAnswerGround ground) {
        var result = new ClaimVerifier().verifyDetailed(claim, List.of(ground));
        System.out.printf("%s supported=%d/%d unsupported=%d insufficient=%s%n", name,
            result.supportedStrongClaimCount(), result.strongClaimCount(),
            result.unsupportedClaims().size(), result.insufficientEvidence());
    }
}
