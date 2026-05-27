package com.kaces.pandora.lawdata.sync;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/law-data")
public class LawSyncController {

	private final LawOpenApiSyncService lawOpenApiSyncService;
	
	// 메소드 설명: LawSyncController 처리 흐름을 수행합니다.
	public LawSyncController(LawOpenApiSyncService lawOpenApiSyncService) {
		this.lawOpenApiSyncService = lawOpenApiSyncService;
	}
	
	@PostMapping("/sync")
	public LawOpenApiSyncService.SyncResult sync(
		@RequestParam(defaultValue = "law") String target,
		@RequestParam(defaultValue = "*") String query,
		@RequestParam(defaultValue = "1") int page,
		@RequestParam(defaultValue = "10") int display,
		@RequestParam(defaultValue = "true") boolean fetchDetails
	) {
		
		return lawOpenApiSyncService.syncLaws(target, query, page, display, fetchDetails);
	}

	
	@PostMapping("/chunks/rebuild")
	public LawOpenApiSyncService.ChunkRebuildResult rebuildChunks(
		@RequestParam(defaultValue = "law") String target,
		@RequestParam(defaultValue = "100") int limit,
		@RequestParam(defaultValue = "0") int offset
	) {
		return lawOpenApiSyncService.rebuildChunks(target, limit, offset);
	}

}
