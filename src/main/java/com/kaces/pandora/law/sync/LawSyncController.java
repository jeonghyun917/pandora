package com.kaces.pandora.law.sync;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/law-data")
public class LawSyncController {

	private final LawOpenApiSyncService lawOpenApiSyncService;

	/**
	 * 법령 동기화 API가 사용할 동기화 서비스를 주입받습니다.
	 */
	public LawSyncController(LawOpenApiSyncService lawOpenApiSyncService) {
		this.lawOpenApiSyncService = lawOpenApiSyncService;
	}

	/**
	 * 법령센터 API 데이터를 DB 문서/상세/청크/첨부 테이블로 동기화합니다.
	 */
	@PostMapping("/sync")
	public LawOpenApiSyncService.SyncResult sync(
		@RequestParam(defaultValue = "law") String target,
		@RequestParam(defaultValue = "*") String query,
		@RequestParam(defaultValue = "1") int page,
		@RequestParam(defaultValue = "10") int display,
		@RequestParam(defaultValue = "true") boolean fetchDetails
	) {
		// 동기화 서비스가 외부 API 조회, 파싱, DB 저장 과정을 순서대로 수행합니다.
		return lawOpenApiSyncService.syncLaws(target, query, page, display, fetchDetails);
	}

	/**
	 * 요청 파라미터 오류를 400 응답으로 변환해 클라이언트에 전달합니다.
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException exception) {
		return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
	}

	/**
	 * 서버 설정이나 실행 상태 오류를 500 응답으로 변환해 클라이언트에 전달합니다.
	 */
	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<Map<String, String>> handleServerState(IllegalStateException exception) {
		return ResponseEntity.internalServerError().body(Map.of("message", exception.getMessage()));
	}
}
