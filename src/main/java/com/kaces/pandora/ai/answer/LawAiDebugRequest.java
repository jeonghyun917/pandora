package com.kaces.pandora.ai.answer;

import java.util.List;

public record LawAiDebugRequest(
	String target,
	List<String> targets,
	String question,
	Integer limit,
	Boolean includeFuture,
	Boolean includeMatchedChildText
) {
	public boolean includeFutureEnabled() {
		return includeFuture == null || includeFuture;
	}

	public boolean includeMatchedChildTextEnabled() {
		return Boolean.TRUE.equals(includeMatchedChildText);
	}
}
