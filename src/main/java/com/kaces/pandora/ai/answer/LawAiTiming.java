package com.kaces.pandora.ai.answer;

public record LawAiTiming(
	long embeddingMs,
	long qdrantMs,
	long dbMs,
	long vectorDbMs,
	long lexicalMs,
	long plannerMs,
	long candidateBuildMs,
	long rerankMs,
	long intentFilterMs,
	long judgePrepMs,
	long parentContextMs,
	long fallbackMs,
	long groundsMs,
	long answerContextMs,
	long streamSendMs,
	long verifyMs,
	long failureLogMs,
	long unmeasuredMs,
	long judgeMs,
	long answerMs,
	long totalMs,
	boolean cacheHit
) {
	/**
	 * Calculates the residual wall-clock time from non-overlapping pipeline stages.
	 * Nested diagnostics, such as individual stream sends inside answer generation,
	 * must not be passed here because they would be counted twice.
	 */
	static long unmeasuredWallClockMs(long totalMs, long... stageMillis) {
		long measuredMs = 0;
		if (stageMillis != null) {
			for (long stageMs : stageMillis) {
				measuredMs += Math.max(0, stageMs);
			}
		}
		return Math.max(0, totalMs - measuredMs);
	}

	public static LawAiTiming cacheHit(long totalMs) {
		return new LawAiTiming(
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			Math.max(0, totalMs),
			0,
			0,
			Math.max(0, totalMs),
			true
		);
	}
}
