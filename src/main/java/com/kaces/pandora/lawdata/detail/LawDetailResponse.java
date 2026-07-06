package com.kaces.pandora.lawdata.detail;

import java.util.List;


public record LawDetailResponse(
	boolean htmlDetail,
	String source,
	long documentId,
	String title,
	List<String> meta,
	List<LawDetailSectionResponse> sections,
	String originalFileUrl,
	String originalFileName,
	String originalMimeType,
	String previewFileUrl,
	String previewHtmlUrl,
	String detailLink
) {
	public LawDetailResponse(
		boolean htmlDetail,
		String source,
		long documentId,
		String title,
		List<String> meta,
		List<LawDetailSectionResponse> sections
	) {
		this(htmlDetail, source, documentId, title, meta, sections, null, null, null, null, null, null);
	}

	public LawDetailResponse(
		boolean htmlDetail,
		String source,
		long documentId,
		String title,
		List<String> meta,
		List<LawDetailSectionResponse> sections,
		String originalFileUrl,
		String originalFileName,
		String originalMimeType
	) {
		this(htmlDetail, source, documentId, title, meta, sections, originalFileUrl, originalFileName, originalMimeType, null, null, null);
	}

	public LawDetailResponse(
		boolean htmlDetail,
		String source,
		long documentId,
		String title,
		List<String> meta,
		List<LawDetailSectionResponse> sections,
		String originalFileUrl,
		String originalFileName,
		String originalMimeType,
		String previewFileUrl
	) {
		this(htmlDetail, source, documentId, title, meta, sections, originalFileUrl, originalFileName, originalMimeType, previewFileUrl, null, null);
	}
}
