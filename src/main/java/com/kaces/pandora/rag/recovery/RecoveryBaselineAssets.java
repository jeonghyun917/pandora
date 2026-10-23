package com.kaces.pandora.rag.recovery;

/** Existing vector bytes bound to the preserved DB baseline, not a re-embedding claim. */
public record RecoveryBaselineAssets(long documentId,String generationId,String baselineHash,String vectorCollection,
        String embeddingModel,String vectorFingerprint,int chunkCount) {
    public RecoveryBaselineAssets {
        if(documentId<=0 || generationId==null || !generationId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || baselineHash==null || !baselineHash.matches("[0-9a-f]{64}") || vectorCollection==null || vectorCollection.isBlank()
                || vectorCollection.length()>100 || embeddingModel==null || embeddingModel.isBlank() || embeddingModel.length()>100
                || vectorFingerprint==null || !vectorFingerprint.matches("[0-9a-f]{64}") || chunkCount<=0 || chunkCount>10000)
            throw new IllegalArgumentException("Bound baseline assets required");
    }
}
