package com.kaces.pandora.ai.answer;

import java.util.List;

public record LawAiAnswerRequest(
	String target,
	List<String> targets,
	String question,
	Integer limit
) {
}
