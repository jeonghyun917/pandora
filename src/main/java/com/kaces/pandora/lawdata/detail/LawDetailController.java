package com.kaces.pandora.lawdata.detail;

import com.kaces.pandora.lawdata.client.LawOpenApiService;
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
	
	// 메소드 설명: LawDetailController 처리 흐름을 수행합니다.
	public LawDetailController(LawDetailService lawDetailService, LawOpenApiService lawOpenApiService) {
		this.lawDetailService = lawDetailService;
		this.lawOpenApiService = lawOpenApiService;
	}
	
	@GetMapping("/detail")
	// 메소드 설명: detail 처리 흐름을 수행합니다.
	public ResponseEntity<LawDetailResponse> detail(@RequestParam String link) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			
			.body(lawDetailService.detail(link));
	}
	
	@GetMapping("/proxy")
	// 메소드 설명: proxy 처리 흐름을 수행합니다.
	public ResponseEntity<byte[]> proxy(@RequestParam String link) {
		
		ResponseEntity<byte[]> upstream = lawOpenApiService.proxy(link);
		MediaType contentType = upstream.getHeaders().getContentType();
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.status(upstream.getStatusCode())
			.contentType(contentType == null ? MediaType.TEXT_HTML : contentType);
		String contentDisposition = upstream.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
		if (contentDisposition != null) {
			
			responseBuilder.header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition);
		}
		return responseBuilder.body(upstream.getBody());
	}
}
