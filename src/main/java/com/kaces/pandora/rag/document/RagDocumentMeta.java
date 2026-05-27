package com.kaces.pandora.rag.document;

public record RagDocumentMeta(
	String documentType,
	String title,
	String sourceOrg,
	String documentCategory,
	String documentTopic,
	String publishedDate,
	String version,
	Integer trustLevel,
	String sourceUrl
) {
}
