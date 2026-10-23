package com.kaces.pandora.rag.recovery;

/** Immutable intent, persisted before any candidate external asset write. */
public record RecoveryPreparationReceipt(String generationId,String manifest,long baseRevision,
        String routingIdentity,String corpusFingerprint) {
    public RecoveryPreparationReceipt {
        if(generationId==null || !generationId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || baseRevision<0 || !hash(manifest) || !hash(routingIdentity) || !hash(corpusFingerprint))
            throw new IllegalArgumentException("Bound preparation receipt required");
    }
    private static boolean hash(String value) { return value!=null && value.matches("[0-9a-f]{64}"); }
}
