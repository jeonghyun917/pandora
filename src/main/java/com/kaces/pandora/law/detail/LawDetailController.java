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
	public LawDetailController(LawDetailService lawDetailService, LawOpenApiService lawOpenApiService) {
		this.lawDetailService = lawDetailService;
		this.lawOpenApiService = lawOpenApiService;
	}
	@GetMapping("/detail")
	public ResponseEntity<LawDetailResponse> detail(@RequestParam String link) {
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.body(lawDetailService.detail(link));
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
}
