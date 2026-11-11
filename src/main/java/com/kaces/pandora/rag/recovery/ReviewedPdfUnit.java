package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.rag.importing.ExtractedPage;
import com.kaces.pandora.lawdata.sync.SourcePdfInspection;
import com.kaces.pandora.common.text.LawHashUtils;
import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;

/** Physical pages and normalized-extraction line ordinals; not raw glyph geometry or an approval receipt. */
record ReviewedPdfUnit(List<Line> lines) {
    private static final String EXTRACTION_PROFILE = "rag-pdf-normalized-lines-v1";
    record Line(int page, int number, String literal) { }

    ReviewedPdfUnit {
        if (lines == null || lines.isEmpty() || lines.size() > 256)
            throw new IllegalArgumentException("Bounded reviewed lines required");
        lines = List.copyOf(lines);
        Line previous = null;
        for (var line : lines) {
            if (line.page() < 1 || line.page() > 1000 || line.number() < 1
                    || line.literal() == null || line.literal().isBlank() || line.literal().length() > 4096
                    || line.literal().codePoints().anyMatch(Character::isISOControl)
                    || previous != null && (line.page() < previous.page()
                            || line.page() == previous.page() && line.number() <= previous.number()))
                throw new IllegalArgumentException("Invalid reviewed line");
            previous = line;
        }
    }

    String reviewHash(String artifactHash) {
        if (artifactHash == null || !artifactHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Exact artifact hash required");
        return LawHashUtils.sha256Json(List.of(EXTRACTION_PROFILE, artifactHash,
                lines.stream().map(line -> List.of(line.page(), line.number(), line.literal())).toList()));
    }

    /** Expected hashes must come from trusted offline review, never from the submitted candidate. */
    String replayPinned(Path file, String expectedArtifactHash, String expectedReviewHash) {
        if (!reviewHash(expectedArtifactHash).equals(expectedReviewHash))
            throw new IllegalArgumentException("Reviewed PDF identity rejected");
        var text = new StringJoiner(" ");
        for (int start = 0, batches = 0; start < lines.size();) {
            if (++batches > 16) throw new IllegalArgumentException("Reviewed range count exceeded");
            var first = lines.get(start);
            int end = start + 1;
            while (end < lines.size() && lines.get(end).page() == first.page()
                    && lines.get(end).number() - first.number() == end - start) end++;
            var extracted = SourcePdfInspection.readReviewedLines(file, expectedArtifactHash, first.page(),
                    first.number(), lines.get(end - 1).number());
            for (int i = start; i < end; i++) append(text, lines.get(i), extracted.get(i - start));
            start = end;
        }
        return text.toString();
    }

    String replay(List<ExtractedPage> pages) {
        var text = new StringJoiner(" ");
        int currentPage = 0;
        List<String> extracted = List.of();
        for (var line : lines) {
            if (line.page() != currentPage) {
                if (line.page() > pages.size()) throw new IllegalArgumentException("Missing reviewed page");
                var page = pages.get(line.page() - 1);
                if (!Integer.valueOf(line.page()).equals(page.pageNo()))
                    throw new IllegalArgumentException("Wrong reviewed page");
                extracted = page.text().lines().toList();
                currentPage = line.page();
            }
            if (line.number() > extracted.size()) throw new IllegalArgumentException("Missing reviewed line");
            append(text, line, extracted.get(line.number() - 1));
        }
        return text.toString();
    }

    private static void append(StringJoiner text, Line line, String actual) {
        if (!line.literal().equals(actual)) throw new IllegalArgumentException("Reviewed line differs from extraction");
        text.add(actual.strip());
    }
}
