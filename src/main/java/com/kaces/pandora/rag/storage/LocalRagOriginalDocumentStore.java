package com.kaces.pandora.rag.storage;

import com.kaces.pandora.rag.document.RagDocumentRow;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class LocalRagOriginalDocumentStore implements RagOriginalDocumentStore {

	@Override
	public boolean exists(RagDocumentRow document) {
		return source(document) != null && Files.isRegularFile(source(document));
	}

	@Override
	public StoredOriginal open(RagDocumentRow document) throws IOException {
		Path source = requiredSource(document);
		String contentType = Files.probeContentType(source);
		if (contentType == null || contentType.isBlank()) {
			contentType = document.mimeType();
		}
		return new StoredOriginal(Files.newInputStream(source), Files.size(source), contentType == null ? "" : contentType);
	}

	@Override
	public Path materialize(RagDocumentRow document) throws IOException {
		return requiredSource(document);
	}

	private Path requiredSource(RagDocumentRow document) throws FileNotFoundException {
		Path source = source(document);
		if (source == null || !Files.isRegularFile(source)) {
			throw new FileNotFoundException("Original document is not available on local storage: " + document.documentId());
		}
		return source;
	}

	private Path source(RagDocumentRow document) {
		if (document == null || document.filePath() == null || document.filePath().isBlank()) {
			return null;
		}
		return Path.of(document.filePath()).toAbsolutePath().normalize();
	}
}
