package com.kaces.pandora.rag.storage;

import com.kaces.pandora.rag.document.RagDocumentRow;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

public interface RagOriginalDocumentStore {

	boolean exists(RagDocumentRow document);

	StoredOriginal open(RagDocumentRow document) throws IOException;

	Path materialize(RagDocumentRow document) throws IOException;

	record StoredOriginal(InputStream inputStream, long contentLength, String contentType) implements AutoCloseable {

		@Override
		public void close() throws IOException {
			inputStream.close();
		}
	}
}
