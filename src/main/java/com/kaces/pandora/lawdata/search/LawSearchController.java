package com.kaces.pandora.lawdata.search;

import java.util.Map;
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
	
	// 메소드 설명: LawSearchController 처리 흐름을 수행합니다.
	public LawSearchController(LawSearchService lawSearchService) {
		this.lawSearchService = lawSearchService;
	}
	
	@GetMapping("/search")
	public ResponseEntity<Map<String, LawSearchPayloadResponse>> search(
		@RequestParam(defaultValue = "law") String target,
		@RequestParam(defaultValue = "*") String query,
		@RequestParam(defaultValue = "1") int page,
		@RequestParam(defaultValue = "10") int display
	) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			
			.body(lawSearchService.search(target, query, page, display));
	}

	
	@GetMapping("/chunk-search")
	public ResponseEntity<Map<String, LawSearchPayloadResponse>> chunkSearch(
		@RequestParam(defaultValue = "law") String target,
		@RequestParam(defaultValue = "*") String query,
		@RequestParam(defaultValue = "1") int page,
		@RequestParam(defaultValue = "10") int display
	) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.body(lawSearchService.chunkSearch(target, query, page, display));
	}
}
