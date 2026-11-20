package com.kaces.pandora.ai.answer;
import java.time.LocalDate;
import java.util.Optional;

record ReferenceCommencementRequest(LocalDate referenceDate,String title) {
    static Optional<ReferenceCommencementRequest> parseQuestion(String question) {
        if(question==null || question.length()>400) return Optional.empty();
        String original=java.text.Normalizer.normalize(question,java.text.Normalizer.Form.NFC);
        // Never authorize a named-law lookup after erasing an unrecognized restriction.
        if(!original.matches("(?U)[가-힣A-Za-z0-9\\s·ㆍ,，.。?？!！:：()「」『』\"'-]+")) return Optional.empty();
        return parse(com.kaces.pandora.common.text.KoreanQueryNormalizer.normalizeForMatch(original));
    }
    private static final java.util.regex.Pattern REQUEST=java.util.regex.Pattern.compile(
        "^([12][0-9]{3})년([0-9]{1,2})월([0-9]{1,2})일기준([가-힣]{2,120}?)(?:의)?일반시행일과(?:현재)?시행상태(?:는무엇인가요|를알려주세요|를알려줘)$");
    static Optional<ReferenceCommencementRequest> parse(String normalized) {
        if(normalized==null || normalized.length()>200) return Optional.empty();
        var match=REQUEST.matcher(normalized);
        if(!match.matches()) return Optional.empty();
        String title=match.group(4);
        if(!title.matches(".+(?:법률|법|시행령|시행규칙)") || title.matches(".*(?:법률|법)(?:및|과|와).*(?:법률|법)$"))
            return Optional.empty();
        try {
            return Optional.of(new ReferenceCommencementRequest(LocalDate.of(Integer.parseInt(match.group(1)),
                Integer.parseInt(match.group(2)),Integer.parseInt(match.group(3))),title));
        } catch(java.time.DateTimeException invalid) { return Optional.empty(); }
    }
    boolean matchesTitle(String normalizedTitle) { return title.equals(normalizedTitle); }
}
