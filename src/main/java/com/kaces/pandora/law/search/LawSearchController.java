package com.kaces.pandora.law.search;

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
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.body(lawSearchService.search(target, query, page, display));
	}
}
