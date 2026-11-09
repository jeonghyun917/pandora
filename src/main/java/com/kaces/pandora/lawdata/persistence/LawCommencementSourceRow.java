package com.kaces.pandora.lawdata.persistence;

public record LawCommencementSourceRow(
	long chunkId, long documentId, long detailId, String externalId, int chunkVersion,
	String sourcePath, String chunkText, String effectiveDate, String rawJson
) {}
