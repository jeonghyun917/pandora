package com.kaces.pandora.rag.recovery;

public record RecoveryRollbackIntent(String id,long documentId,String inputHash,long baseRevision,long expectedRevision,
        String activeGeneration,String restoreGeneration,String routingIdentity,String corpusFingerprint,String lexicalFingerprint,String state) { }
