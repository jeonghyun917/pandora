package com.kaces.pandora.semantic.provenance;

import com.kaces.pandora.infra.qdrant.QdrantIndexSnapshot;

public record IndexRevisionCollection(
	String role,
	String collection,
	IndexContentSnapshot database,
	QdrantIndexSnapshot qdrant
) {
	boolean isUsable() {
		return role != null
			&& !role.isBlank()
			&& collection != null
			&& !collection.isBlank()
			&& database != null
			&& database.isUsable()
			&& qdrant != null
			&& qdrant.isStable()
			&& collection.equals(qdrant.collection())
			&& database.currentIndexedCount() == qdrant.exactPointCount();
	}
}
