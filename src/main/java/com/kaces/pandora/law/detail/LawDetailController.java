package com.kaces.pandora.law.detail;

import com.kaces.pandora.law.client.LawOpenApiService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/law-data")
public class LawDetailController {

	private final LawDetailService lawDetailService;
	private final LawOpenApiService lawOpenApiService;

	/**
	 * 상세 조회 서비스와 첨부 프록시용 외부 API 서비스를 주입받습니다.
	 */
	public LawDetailController(LawDetailService lawDetailService, LawOpenApiService lawOpenApiService) {
		this.lawDetailService = lawDetailService;
		this.lawOpenApiService = lawOpenApiService;
	}

	/**
	 * 상세 조회 링크를 받아 DB에 저장된 원문과 파싱된 조문 목록을 반환합니다.
	 */
	@GetMapping("/detail")
	public ResponseEntity<String> detail(@RequestParam String link) {
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			// 상세 서비스가 저장된 상세 JSON과 파싱 섹션을 화면 응답으로 조립합니다.
			.body(lawDetailService.detail(link));
	}

	/**
	 * 법령센터 HTML/이미지 첨부 링크를 서버에서 대신 호출해 브라우저로 전달합니다.
	 */
	@GetMapping("/proxy")
	public ResponseEntity<byte[]> proxy(@RequestParam String link) {
		// 외부 첨부 리소스는 서버 프록시를 거쳐 CORS와 인증키 노출 문제를 피합니다.
		ResponseEntity<byte[]> upstream = lawOpenApiService.proxy(link);
		MediaType contentType = upstream.getHeaders().getContentType();
		ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.status(upstream.getStatusCode())
			.contentType(contentType == null ? MediaType.TEXT_HTML : contentType);
		String contentDisposition = upstream.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
		if (contentDisposition != null) {
			// 파일 다운로드 응답이면 원본 Content-Disposition 헤더를 유지합니다.
			responseBuilder.header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition);
		}
		return responseBuilder.body(upstream.getBody());
	}
}
