package com.kaces.pandora.lawdata.sync;

import java.util.List;
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
		@RequestParam(defaultValue = "true") boolean fetchDetails,
		@RequestParam(defaultValue = "") String sort,
		@RequestParam(defaultValue = "") String date,
		@RequestParam(defaultValue = "") String efYd,
		@RequestParam(defaultValue = "") String ancYd
	) {
		
		return lawOpenApiSyncService.syncLaws(target, query, page, display, fetchDetails, sort, date, efYd, ancYd);
	}

	
	@PostMapping("/chunks/rebuild")
	public LawOpenApiSyncService.ChunkRebuildResult rebuildChunks(
		@RequestParam(defaultValue = "law") String target,
		@RequestParam(defaultValue = "100") int limit,
		@RequestParam(defaultValue = "0") int offset
	) {
		return lawOpenApiSyncService.rebuildChunks(target, limit, offset);
	}

	@PostMapping("/chunks/rebuild-preview")
	public LawOpenApiSyncService.ChunkRebuildPreviewResult previewRebuildChunks(
		@RequestParam(defaultValue = "law") String target,
		@RequestParam(defaultValue = "100") int limit,
		@RequestParam(defaultValue = "0") int offset
	) {
		return lawOpenApiSyncService.previewRebuildChunks(target, limit, offset);
	}

	@PostMapping("/chunks/rebuild-preview-by-document-ids")
	public LawOpenApiSyncService.ChunkRebuildPreviewResult previewRebuildChunksByDocumentIds(
		@RequestParam(defaultValue = "law") String target,
		@RequestParam List<Long> documentIds
	) {
		return lawOpenApiSyncService.previewRebuildChunksByDocumentIds(target, documentIds);
	}

	@PostMapping("/sync/detail")
	public LawOpenApiSyncService.SyncResult syncDetail(
		@RequestParam(defaultValue = "law") String target,
		@RequestParam String externalId,
		@RequestParam(defaultValue = "") String title,
		@RequestParam(defaultValue = "") String sourceDate,
		@RequestParam(defaultValue = "") String agencyName,
		@RequestParam(defaultValue = "") String categoryName,
		@RequestParam(defaultValue = "") String detailLink
	) {
		return lawOpenApiSyncService.syncDetail(target, externalId, title, sourceDate, agencyName, categoryName, detailLink);
	}

	@PostMapping("/chunks/rebuild-by-document-ids")
	public LawOpenApiSyncService.ChunkRebuildResult rebuildChunksByDocumentIds(
		@RequestParam(defaultValue = "law") String target,
		@RequestParam List<Long> documentIds
	) {
		return lawOpenApiSyncService.rebuildChunksByDocumentIds(target, documentIds);
	}

}
