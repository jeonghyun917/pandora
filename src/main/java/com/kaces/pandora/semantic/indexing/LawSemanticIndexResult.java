package com.kaces.pandora.semantic.indexing;

public record LawSemanticIndexResult(
	String collection,
	String model,
	int requested,
	int indexed
) {
}
