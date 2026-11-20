package com.kaces.pandora.ai.answer;

import java.text.Normalizer;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** A complete two-norm sentence; its derived atoms are never standalone grounds. */
final class SourceOwnedCheckingConsequence {
    private static final Pattern PAIR = Pattern.compile(
        "^(?<actor>" + KoreanEvidenceAtomParser.INSTITUTION_HEAD_ROLE + ")(?:은|는) "
        + "(?<check>[^.!?;,]{1,1500}준수하였는지 여부를 확인)(?:하여야|해야) 하며, "
        + "(?<consequence>이를 위반한 경우 [^.!?;,]{1,800}할 수 있다)[.]?$"
    );
    private static final Pattern UNKNOWN = Pattern.compile("[^\\p{IsHangul}A-Za-z0-9\\s.,()·ㆍ/-]");

    private SourceOwnedCheckingConsequence() {}

    static String literal(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC)
            .replaceAll("\\s+", " ").trim();
    }

    static Optional<Pair> parse(String text, KoreanEvidenceAtomParser parser) {
        String full = literal(text);
        if (full.length() > 2500 || UNKNOWN.matcher(full).find()) { return Optional.empty(); }
        var match = PAIR.matcher(full);
        if (!match.matches()) { return Optional.empty(); }
        String actor = match.group("actor");
        EvidenceAtom duty = parser.parse(actor + "은 " + match.group("check") + "하여야 한다.");
        EvidenceAtom consequence = parser.parse(actor + "은 " + match.group("consequence") + ".");
        Set<String> owner = parser.parse(actor + "은 확인하여야 한다.").subjects();
        if (owner.isEmpty() || !duty.subjects().containsAll(owner) || !consequence.subjects().equals(owner)
            || duty.parseStatus() != EvidenceAtom.ParseStatus.COMPLETE
            || consequence.parseStatus() != EvidenceAtom.ParseStatus.COMPLETE
            || duty.modality() != EvidenceAtom.Modality.REQUIRED
            || consequence.modality() != EvidenceAtom.Modality.PERMITTED
            || duty.polarity() != EvidenceAtom.Polarity.POSITIVE
            || consequence.polarity() != EvidenceAtom.Polarity.POSITIVE
            || !duty.actions().equals(Set.of("확인")) || consequence.actions().isEmpty()
            || consequence.actions().contains("확인") || consequence.conditions().isEmpty()
            || !duty.exceptions().isEmpty() || !consequence.exceptions().isEmpty()) { return Optional.empty(); }
        return Optional.of(new Pair(full, duty, consequence));
    }

    record Pair(String literal, EvidenceAtom duty, EvidenceAtom consequence) {}
}
