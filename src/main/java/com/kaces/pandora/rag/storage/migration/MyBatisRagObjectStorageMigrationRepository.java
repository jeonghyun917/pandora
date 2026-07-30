package com.kaces.pandora.rag.storage.migration;

import com.kaces.pandora.rag.document.RagDocumentRow;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import java.util.List;

public class MyBatisRagObjectStorageMigrationRepository implements RagObjectStorageMigrationRepository {

	private final RagDocumentMapper mapper;

	public MyBatisRagObjectStorageMigrationRepository(RagDocumentMapper mapper) {
		this.mapper = mapper;
	}

	@Override
	public List<RagDocumentRow> findActiveDocuments() {
		return mapper.findActiveDocumentsForObjectStorage();
	}

	@Override
	public int assignObjectKeyIfHashMatches(long documentId, String fileHash, String objectKey) {
		return mapper.assignObjectKeyIfHashMatches(documentId, fileHash, objectKey);
	}
}
