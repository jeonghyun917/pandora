package com.kaces.pandora.rag.recovery;

public record RecoveryAssets(String generationId, String manifest, String vectorCollection,
        String vectorFingerprint, String lexicalFingerprint, int chunkCount) { }
