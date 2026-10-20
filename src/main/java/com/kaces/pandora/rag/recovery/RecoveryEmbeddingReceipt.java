package com.kaces.pandora.rag.recovery;

public record RecoveryEmbeddingReceipt(String generationId, long chunkId, String manifest,
        String embeddingModel, String vectorJson, String receiptHash) { }
