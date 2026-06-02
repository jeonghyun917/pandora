package com.kaces.pandora.lawdata.persistence;

import java.time.LocalDateTime;

public record LawDocumentSyncState(
	String documentContentHash,
	String detailContentHash,
	LocalDateTime detailFetchedAt,
	int activeChunkCount
) {
}
