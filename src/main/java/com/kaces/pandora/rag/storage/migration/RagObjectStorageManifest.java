package com.kaces.pandora.rag.storage.migration;

import java.util.List;

public record RagObjectStorageManifest(
	String createdAt,
	String source,
	List<RagObjectStorageManifestEntry> entries
) {
	public RagObjectStorageManifest {
		entries = entries == null ? List.of() : List.copyOf(entries);
	}
}
