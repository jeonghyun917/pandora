package com.kaces.pandora.law.sync;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/law-data")
public class LawSyncController {

	private final LawOpenApiSyncService lawOpenApiSyncService;
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

}
