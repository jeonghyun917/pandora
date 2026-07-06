package com.kaces.pandora.ai.answer;

import java.util.List;

public record LawAiDebugRequest(
	String target,
	List<String> targets,
	String question,
	Integer limit,
	Boolean includeFuture
) {
	public boolean includeFutureEnabled() {
		return includeFuture == null || includeFuture;
	}
}
