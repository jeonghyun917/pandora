package com.kaces.pandora.semantic.batch;

import java.time.LocalDateTime;

public record LawSemanticBatchSchedulerStatus(
	LocalDateTime lastStartedAt,
	LocalDateTime lastFinishedAt,
	boolean running,
	String lastStatus,
	String lastErrorMessage
) {
	// 메소드 설명: idle 처리 흐름을 수행합니다.
	public static LawSemanticBatchSchedulerStatus idle() {
		return new LawSemanticBatchSchedulerStatus(null, null, false, "IDLE", null);
	}
}
