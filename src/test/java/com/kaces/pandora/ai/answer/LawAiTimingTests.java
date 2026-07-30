package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LawAiTimingTests {

	@Test
	void calculatesResidualFromNonOverlappingPipelineStages() {
		assertThat(LawAiTiming.unmeasuredWallClockMs(100, 20, 30, 15)).isEqualTo(35);
	}

	@Test
	void clampsResidualWhenDiagnosticsExceedRequestWallClock() {
		assertThat(LawAiTiming.unmeasuredWallClockMs(100, 60, 50)).isZero();
	}

	@Test
	void ignoresNegativeStageMeasurements() {
		assertThat(LawAiTiming.unmeasuredWallClockMs(100, 30, -20)).isEqualTo(70);
	}
}
