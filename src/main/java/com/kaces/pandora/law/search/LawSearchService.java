package com.kaces.pandora.law.search;

import com.kaces.pandora.law.common.LawDatabaseQueryService;
import org.springframework.stereotype.Service;

@Service
public class LawSearchService {

	private final LawDatabaseQueryService lawDatabaseQueryService;

	/**
	 * 검색 서비스가 사용할 DB 조회 코어를 주입받습니다.
	 */
	public LawSearchService(LawDatabaseQueryService lawDatabaseQueryService) {
		this.lawDatabaseQueryService = lawDatabaseQueryService;
	}

	/**
	 * 법령/행정규칙 검색 조건을 DB 조회 코어에 전달하고 검색 JSON을 반환합니다.
	 */
	public String search(String target, String query, int page, int display) {
		// 실제 검색 조건 정규화와 MyBatis 조회는 공통 DB 조회 코어에서 수행합니다.
		return lawDatabaseQueryService.search(target, query, page, display);
	}
}
