package com.kaces.pandora.rag.recovery;

public record RecoverySnapshot(long documentId,long revision,String activeGeneration,String sourceHash,String baselineHash) { }
