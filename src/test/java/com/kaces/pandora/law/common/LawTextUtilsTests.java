package com.kaces.pandora.law.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LawTextUtilsTests {

	@Test
	void formatDateNormalizesEightDigitDates() {
		assertThat(LawTextUtils.formatDate("20260520")).isEqualTo("2026. 5. 20.");
	}

	@Test
	void formatDateKeepsNonStandardValues() {
		assertThat(LawTextUtils.formatDate("2026-05")).isEqualTo("2026-05");
	}

	@Test
	void emptyToNullReturnsNullForBlankText() {
		assertThat(LawTextUtils.emptyToNull("  ")).isNull();
		assertThat(LawTextUtils.emptyToNull("value")).isEqualTo("value");
	}
}
