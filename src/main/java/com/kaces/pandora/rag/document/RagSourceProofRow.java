package com.kaces.pandora.rag.document;

import com.kaces.pandora.rag.chunk.SourceTableBindings;

/** Trusted database identity kept separate from untrusted serialized source metadata. */
public record RagSourceProofRow(long chunkId,long documentId,int chunkVersion,Integer pageNo,
    String chunkText,String sourceFileHash,String sourceProvenance) {
    public SourceTableBindings.Input validationInput() {
        return new SourceTableBindings.Input(documentId,chunkVersion,pageNo,chunkText,sourceFileHash,sourceProvenance);
    }
}
