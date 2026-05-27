package com.kaces.pandora.lawdata.search;

public record LawSearchItemResponse(
	long documentId,
	String target,
	String externalId,
	String title,
	String agencyName,
	String categoryName,
	String sourceDate,
	String detailLink,
	String source,
	Long chunkId,
	String chunkNo,
	String chunkTitle,
	String snippet,
	String sourcePath
) {
	public LawSearchItemResponse(
		long documentId,
		String target,
		String externalId,
		String title,
		String agencyName,
		String categoryName,
		String sourceDate,
		String detailLink,
		String source
	) {
		this(documentId, target, externalId, title, agencyName, categoryName, sourceDate, detailLink, source, null, null, null, null, null);
	}
}
