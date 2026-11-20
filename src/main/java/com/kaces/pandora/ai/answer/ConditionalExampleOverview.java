package com.kaces.pandora.ai.answer;

/** An explicit request for source-qualified examples, not a real-project applicability assertion. */
final class ConditionalExampleOverview {
    private ConditionalExampleOverview() {}
    private static final String PURPOSE="사업유형과위반의무를임의로추정하지않고";
    private static final java.util.regex.Pattern TOPIC=java.util.regex.Pattern.compile(
        "^(?:(?:정보화|정보시스템|소프트웨어|sw|it)사업|정보시스템)(?:에서|의)?"
        +"(?:법제도|(?:관련)?법령)(?:를|을)?(?:미준수(?:시|한경우)|준수하지(?:않았을때|않은경우))"
        +"(?:의)?(?:가능한)?(?:조치|제재|불이익)(?:를|을)?$");
    private static final java.util.regex.Pattern FORMAT=java.util.regex.Pattern.compile(
        "^공식원문이정한적용대상위반조건주체권한을밝힌조건부(?:사례|예시)로설명해주세요$");
    static boolean requested(String question) {
        if(question==null || question.length()>400) return false;
        String original=java.text.Normalizer.normalize(question,java.text.Normalizer.Form.NFC);
        // Search normalization is lossy. Unknown scripts/format controls may carry restrictions.
        if(!original.matches("(?U)[가-힣A-Za-z0-9\\s·ㆍ,，.。?？!！:：()「」『』\"'-]+")) return false;
        String normalized=com.kaces.pandora.common.text.KoreanQueryNormalizer.normalizeForMatch(original);
        int boundary=normalized.indexOf(PURPOSE);
        return boundary>0 && boundary==normalized.lastIndexOf(PURPOSE)
            && TOPIC.matcher(normalized.substring(0,boundary)).matches()
            && FORMAT.matcher(normalized.substring(boundary+PURPOSE.length())).matches();
    }
}
