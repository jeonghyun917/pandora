package com.kaces.pandora.rag.recovery;

public record RecoveryGeneration(String id,long documentId,String sourceHash,String productSha,
        String baselineHash,String manifest,String previewHash,String state) { }
