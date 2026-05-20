package com.kaces.pandora.law.search;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/law-data")
public class LawSearchController {

	private final LawSearchService lawSearchService;

	/**
	 * 법령 검색 API가 사용할 검색 서비스를 주입받습니다.
	 */
	public LawSearchController(LawSearchService lawSearchService) {
		this.lawSearchService = lawSearchService;
	}

	/**
	 * 법령/행정규칙 검색 요청을 받아 API 대신 DB에서 검색 결과를 반환합니다.
	 */
	@GetMapping("/search")
	public ResponseEntity<String> search(
		@RequestParam(defaultValue = "law") String target,
		@RequestParam(defaultValue = "*") String query,
		@RequestParam(defaultValue = "1") int page,
		@RequestParam(defaultValue = "10") int display
	) {
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			// 검색 서비스가 target/query/page/display 조건을 DB 검색 결과로 변환합니다.
			.body(lawSearchService.search(target, query, page, display));
	}
}
