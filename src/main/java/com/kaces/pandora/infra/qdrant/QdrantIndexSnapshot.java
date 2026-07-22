package com.kaces.pandora.infra.qdrant;

public record QdrantIndexSnapshot(
	String collection,
	String status,
	long updateQueueLength,
	long exactPointCount,
	int vectorSize,
	String distance,
	long indexedVectorsCount,
	int segmentsCount
) {
	public boolean isStable() {
		return collection != null
			&& !collection.isBlank()
			&& "green".equalsIgnoreCase(status)
			&& updateQueueLength == 0
			&& exactPointCount > 0
			&& vectorSize > 0
			&& "Cosine".equalsIgnoreCase(distance);
	}
}
