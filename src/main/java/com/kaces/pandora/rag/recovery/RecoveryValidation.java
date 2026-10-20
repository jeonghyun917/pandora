package com.kaces.pandora.rag.recovery;

import java.util.List;

/** Original-fidelity evidence; not an answer-quality or semantic support verdict. */
public record RecoveryValidation(String generationId,String manifest,String sourceHash,String productSha,
        boolean passed,List<String> failures) {
    public RecoveryValidation { failures=List.copyOf(failures); }
}
