package com.kaces.pandora.rag.storage.migration;

import com.kaces.pandora.rag.document.RagDocumentRow;
import java.util.List;

public interface RagObjectStorageMigrationRepository {
	List<RagDocumentRow> findActiveDocuments();

	int assignObjectKeyIfHashMatches(long documentId, String fileHash, String objectKey);
}
