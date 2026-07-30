package com.kaces.pandora.rag.storage.migration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public interface RagObjectStorageObjectGateway {
	Optional<RagObjectStorageObjectMetadata> find(String objectKey) throws IOException;

	void upload(Path source, String objectKey, String contentType, String sha256) throws IOException;
}
