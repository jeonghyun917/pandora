package com.kaces.pandora.law;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/law-data")
public class LawOpenApiController {

	private final LawOpenApiService lawOpenApiService;
	private final LawOpenApiSyncService lawOpenApiSyncService;
	private final LawDatabaseQueryService lawDatabaseQueryService;

	public LawOpenApiController(
		LawOpenApiService lawOpenApiService,
		LawOpenApiSyncService lawOpenApiSyncService,
		LawDatabaseQueryService lawDatabaseQueryService
	) {
		this.lawOpenApiService = lawOpenApiService;
		this.lawOpenApiSyncService = lawOpenApiSyncService;
		this.lawDatabaseQueryService = lawDatabaseQueryService;
	}

	@GetMapping("/search")
	public ResponseEntity<String> search(
		@RequestParam(defaultValue = "law") String target,
		@RequestParam(defaultValue = "*") String query,
		@RequestParam(defaultValue = "1") int page,
		@RequestParam(defaultValue = "10") int display
	) {
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.body(lawDatabaseQueryService.search(target, query, page, display));
	}

	@GetMapping("/detail")
	public ResponseEntity<String> detail(@RequestParam String link) {
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.body(lawDatabaseQueryService.detail(link));
	}

	@GetMapping("/proxy")
	public ResponseEntity<byte[]> proxy(@RequestParam String link) {
		ResponseEntity<byte[]> upstream = lawOpenApiService.proxy(link);
		MediaType contentType = upstream.getHeaders().getContentType();
		ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.status(upstream.getStatusCode())
			.contentType(contentType == null ? MediaType.TEXT_HTML : contentType);
		String contentDisposition = upstream.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
		if (contentDisposition != null) {
			responseBuilder.header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition);
		}
		return responseBuilder.body(upstream.getBody());
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

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException exception) {
		return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
	}

	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<Map<String, String>> handleServerState(IllegalStateException exception) {
		return ResponseEntity.internalServerError().body(Map.of("message", exception.getMessage()));
	}
}
