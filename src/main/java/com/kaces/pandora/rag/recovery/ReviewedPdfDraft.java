package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.common.text.LawHashUtils;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

/** Immutable offline replacement input. No searchable rows, asset writes or activation authority. */
record ReviewedPdfDraft(long documentId, String sourceHash, String baselineHash, String productSha,
        String title, String sourceUrl, List<Unit> units) {
    record Unit(String key, String heading, ReviewedPdfUnit review) {
        Unit {
            if (key == null || !key.matches("[a-z][a-z0-9_-]{0,63}") || heading == null
                    || heading.isBlank() || heading.length() > 256 || review == null)
                throw new IllegalArgumentException("Exact reviewed unit identity required");
        }
    }
    record ReplayedUnit(String key, String heading, List<ReviewedPdfUnit.Line> positions, String text) {
        ReplayedUnit { positions = List.copyOf(positions); }
    }

    ReviewedPdfDraft {
        if (documentId <= 0 || sourceHash == null || !sourceHash.matches("[0-9a-f]{64}")
                || baselineHash == null || !baselineHash.matches("[0-9a-f]{64}")
                || productSha == null || !productSha.matches("[0-9a-f]{40}")
                || title == null || title.isBlank() || title.length() > 512
                || sourceUrl == null || sourceUrl.length() > 2048 || !sourceUrl.startsWith("https://")
                || units == null || units.isEmpty() || units.size() > 64)
            throw new IllegalArgumentException("Bounded reviewed source identity required");
        units = List.copyOf(units);
        var keys = new HashSet<String>();
        for (var unit : units) if (!keys.add(unit.key())) throw new IllegalArgumentException("Duplicate reviewed unit");
    }

    String reviewHash() {
        return LawHashUtils.sha256Json(List.of("reviewed-pdf-draft-v1", documentId, sourceHash, baselineHash,
                productSha, title, sourceUrl, units.stream()
                    .map(unit -> List.of(unit.key(), unit.heading(), unit.review().reviewHash(sourceHash))).toList()));
    }

    ReviewedPdfDraft requireReview(String trustedReviewHash) {
        if (!reviewHash().equals(trustedReviewHash)) throw new IllegalArgumentException("Reviewed source manifest changed");
        return this;
    }

    /** Trusted digest must be obtained from a server-owned review record, not the draft request. */
    List<ReplayedUnit> replay(Path original, String trustedReviewHash) {
        requireReview(trustedReviewHash);
        return units.stream().map(unit -> new ReplayedUnit(unit.key(), unit.heading(), unit.review().lines(),
                unit.review().replayPinned(original, sourceHash, unit.review().reviewHash(sourceHash)))).toList();
    }
}
