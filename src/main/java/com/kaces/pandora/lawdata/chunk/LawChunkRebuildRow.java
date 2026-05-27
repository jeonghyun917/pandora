package com.kaces.pandora.lawdata.chunk;

public record LawChunkRebuildRow(
	long documentId,
	long detailId,
	String target,
	String title,
	String detailTitle,
	String rawJson
) {
}
