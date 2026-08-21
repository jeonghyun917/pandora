package com.kaces.pandora.lawdata.sync;

record PlannedLawChunk(
	String type,
	String no,
	String title,
	String text,
	String sourcePath,
	int chunkSchemaVersion,
	String parentKey,
	String parentTitle,
	String parentSourcePath,
	int childOrder,
	String embeddingText,
	String qualityStatus,
	String qualityReason
) {
	PlannedLawChunk(String type, String no, String title, String text, String sourcePath) {
		this(type, no, title, text, sourcePath, 1, null, null, null, 0, null, "PASS", null);
	}
}
