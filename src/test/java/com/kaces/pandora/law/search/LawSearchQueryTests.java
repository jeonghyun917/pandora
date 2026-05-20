package com.kaces.pandora.law.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LawSearchQueryTests {

	@Test
	void normalizeDefaultsBlankValuesAndBoundsPaging() {
		LawSearchQuery query = LawSearchQuery.normalize(" ", " ", -1, 200);

		assertThat(query.target()).isEqualTo("law");
		assertThat(query.query()).isEqualTo("*");
		assertThat(query.page()).isEqualTo(1);
		assertThat(query.display()).isEqualTo(100);
		assertThat(query.offset()).isZero();
		assertThat(query.searchAll()).isTrue();
	}

	@Test
	void normalizeTrimsValuesAndCalculatesOffset() {
		LawSearchQuery query = LawSearchQuery.normalize(" admrul ", " 개인정보 ", 3, 20);

		assertThat(query.target()).isEqualTo("admrul");
		assertThat(query.query()).isEqualTo("개인정보");
		assertThat(query.page()).isEqualTo(3);
		assertThat(query.display()).isEqualTo(20);
		assertThat(query.offset()).isEqualTo(40);
		assertThat(query.searchAll()).isFalse();
	}
}
