package com.kaces.pandora.lawdata.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LawSearchQueryTests {

	@Test
	// 메소드 설명: normalizeDefaultsBlankValuesAndBoundsPaging 처리 흐름을 수행합니다.
	void normalizeDefaultsBlankValuesAndBoundsPaging() {
		LawSearchQuery query = LawSearchQuery.normalize(" ", " ", -1, 200);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(query.target()).isEqualTo("law");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(query.query()).isEqualTo("*");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(query.page()).isEqualTo(1);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(query.display()).isEqualTo(100);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(query.offset()).isZero();
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(query.searchAll()).isTrue();
	}

	@Test
	// 메소드 설명: normalizeTrimsValuesAndCalculatesOffset 처리 흐름을 수행합니다.
	void normalizeTrimsValuesAndCalculatesOffset() {
		LawSearchQuery query = LawSearchQuery.normalize(" admrul ", " 개인정보 ", 3, 20);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(query.target()).isEqualTo("admrul");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(query.query()).isEqualTo("개인정보");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(query.page()).isEqualTo(3);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(query.display()).isEqualTo(20);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(query.offset()).isEqualTo(40);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(query.searchAll()).isFalse();
	}
}
