package com.kaces.pandora.rag.chunk;

import java.util.Optional;
import com.kaces.pandora.common.text.LawHashUtils;

/** Binds two validated PDF span texts to an exact, ordered excerpt in a stored chunk. */
public final class PdfParagraphSpanBinding {
    private PdfParagraphSpanBinding() {}

    public static Optional<String> bind(String rawChunk, String expectedChunkHash, int start, int end,
            String firstSourceLine, String secondSourceLine, int legacyBellOffset) {
        if (rawChunk == null || expectedChunkHash == null || firstSourceLine == null || secondSourceLine == null
                || firstSourceLine.isBlank() || secondSourceLine.isBlank()
                || firstSourceLine.lines().count() != 1 || secondSourceLine.lines().count() != 1
                || start < 0 || end <= start || end > rawChunk.length()
                || (start > 0 && rawChunk.charAt(start-1) != '\n')
                || (end < rawChunk.length() && rawChunk.charAt(end) != '\n')
                || !expectedChunkHash.equals(LawHashUtils.sha256(rawChunk))) {
            return Optional.empty();
        }
        String excerpt = rawChunk.substring(start, end);
        if (legacyBellOffset != -1) {
            if (legacyBellOffset < 0 || legacyBellOffset >= excerpt.length()
                    || excerpt.charAt(legacyBellOffset) != '\u0007'
                    || excerpt.indexOf('\u0007') != excerpt.lastIndexOf('\u0007')) {
                return Optional.empty();
            }
            excerpt = excerpt.substring(0, legacyBellOffset) + excerpt.substring(legacyBellOffset+1);
        }
        if (excerpt.indexOf('\u0007') >= 0 || !excerpt.equals(firstSourceLine + "\n" + secondSourceLine)) {
            return Optional.empty();
        }
        return Optional.of(firstSourceLine + " " + secondSourceLine);
    }
}
