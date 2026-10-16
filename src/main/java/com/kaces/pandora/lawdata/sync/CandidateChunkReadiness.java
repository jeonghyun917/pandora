package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.lawdata.chunk.LawChunkVersionVerification;

/** Advisory snapshot only. Release/evaluation authorization is never granted by this read. */
public record CandidateChunkReadiness(long documentId,int chunkVersion,String versionStatus,
    int storedChunkCount,LawChunkVersionVerification verification,boolean stagingPointsPresent,
    int activeVersion,boolean activationAuthorized) { }
