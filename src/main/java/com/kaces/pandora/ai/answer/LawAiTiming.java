package com.kaces.pandora.ai.answer;

public record LawAiTiming(
	long embeddingMs,
	long qdrantMs,
	long dbMs,
	long judgeMs,
	long answerMs,
	long totalMs,
	boolean cacheHit
) {
	// 메소드 설명: cacheHit 처리 흐름을 수행합니다.
	public static LawAiTiming cacheHit(long totalMs) {
		return new LawAiTiming(0, 0, 0, 0, 0, totalMs, true);
	}
}
