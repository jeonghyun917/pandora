package com.kaces.pandora.app.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminOverviewController {
	private final AdminOverviewService overviewService;

	public AdminOverviewController(AdminOverviewService overviewService) {
		this.overviewService = overviewService;
	}

	@GetMapping("/overview")
	public ResponseEntity<AdminOverviewResponse> overview() {
		return ResponseEntity.ok(overviewService.overview());
	}

	@GetMapping("/pipelines")
	public ResponseEntity<AdminOverviewResponse> pipelines(@RequestParam(defaultValue = "false") boolean refresh) {
		return ResponseEntity.ok(overviewService.pipelineOverview(refresh));
	}

	@GetMapping("/operations")
	public ResponseEntity<AdminOverviewResponse> operations(@RequestParam(defaultValue = "false") boolean refresh) {
		return ResponseEntity.ok(overviewService.operationsOverview(refresh));
	}
}
