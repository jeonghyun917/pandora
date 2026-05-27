package com.kaces.pandora.ai.answer;

import java.util.List;

public record LawAiAnswerResponse(
	String resultCode,
	String resultMsg,
	String target,
	String question,
	String model,
	String answer,
	int totalCnt,
	List<LawAiAnswerGround> grounds,
	LawAiTiming timing
) {
}
