package com.kaces.pandora.semantic.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaces.pandora.semantic.lexical.CoverageAwareFusion;
import org.junit.jupiter.api.Test;

class LawAiCoverageAwarePropertiesTests {

	@Test
	void keepsCoverageDisabledByDefault() {
		LawAiCoverageAwareProperties defaults = new LawAiCoverageAwareProperties(false, 0, 1, 30);

		assertThat(defaults.enabled()).isFalse();
		assertThat(defaults.policy()).isEqualTo(new CoverageAwareFusion.Policy(false, 0, 1, 30));
	}

	@Test
	void rejectsInvalidBudgetsAndRankLimits() {
		assertThatThrownBy(() -> new LawAiCoverageAwareProperties(true, 1, 2, 30))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LawAiCoverageAwareProperties(true, 3, 1, 30))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LawAiCoverageAwareProperties(true, -1, 1, 30))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LawAiCoverageAwareProperties(true, 1, 1, 0))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
