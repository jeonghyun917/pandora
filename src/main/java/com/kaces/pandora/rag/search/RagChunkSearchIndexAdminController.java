package com.kaces.pandora.rag.search;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/rag/search-index")
public class RagChunkSearchIndexAdminController {

	private final RagChunkSearchIndexService searchIndexService;

	public RagChunkSearchIndexAdminController(RagChunkSearchIndexService searchIndexService) {
		this.searchIndexService = searchIndexService;
	}

	@GetMapping("/status")
	public Map<String, Object> status() {
		return Map.of(
			"ready", searchIndexService.isReady(),
			"missingChunks", searchIndexService.countMissingChunks()
		);
	}

	@PostMapping("/backfill")
	public RagChunkSearchIndexService.BackfillResult backfill(
		@RequestParam(defaultValue = "500") int limit
	) {
		return searchIndexService.backfill(limit);
	}
}
