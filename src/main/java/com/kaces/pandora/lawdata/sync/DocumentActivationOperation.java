package com.kaces.pandora.lawdata.sync;

import java.time.Instant;

public record DocumentActivationOperation(
	long documentId, int candidateVersion, String owner, String runtimeInstanceId, Instant leaseExpiresAt, String phase,
	int priorActiveVersion, String priorPointIdsJson, String candidatePointIdsJson, String lastError
) {
	boolean leaseExpired(Instant now) { return leaseExpiresAt == null || !leaseExpiresAt.isAfter(now); }
}
