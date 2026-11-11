package com.kaces.pandora.rag.recovery;

import java.net.URI;

/** Server-captured replacement source identity; never a client-selected metadata overlay. */
public record RecoveryPdfSource(String generationId,long documentId,String draftId,String sourceHash,
        String reviewHash,String manifest,String title,String sourceUrl) {
    public RecoveryPdfSource {
        String uuid="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        if(generationId==null || !generationId.matches(uuid) || draftId==null || !draftId.matches(uuid)
                || documentId<=0 || sourceHash==null || !sourceHash.matches("[0-9a-f]{64}")
                || reviewHash==null || !reviewHash.matches("[0-9a-f]{64}")
                || manifest==null || !manifest.matches("[0-9a-f]{64}")
                || title==null || title.isBlank() || title.length()>512 || title.codePoints().anyMatch(Character::isISOControl)
                || sourceUrl==null || sourceUrl.length()>2048)
            throw new IllegalArgumentException("Exact replacement source identity required");
        var uri=URI.create(sourceUrl);
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null)
            throw new IllegalArgumentException("Public HTTPS source location required");
    }
    String receiptHash(String preparedManifest,String productSha) {
        if(preparedManifest==null || !preparedManifest.matches("[0-9a-f]{64}")
                || productSha==null || !productSha.matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("Exact prepared source identity required");
        return com.kaces.pandora.common.text.LawHashUtils.sha256Json(java.util.List.of(
                "recovery-pdf-source-v1",generationId,documentId,draftId,sourceHash,reviewHash,
                manifest,preparedManifest,productSha,title,sourceUrl));
    }
}
