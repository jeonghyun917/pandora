package com.kaces.pandora.semantic.integrity;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/law-index-integrity")
public class LawIndexIntegrityController {
	private final LawIndexIntegrityService integrityService;

	public LawIndexIntegrityController(LawIndexIntegrityService integrityService) {
		this.integrityService = integrityService;
	}

	@GetMapping("/audit")
	public ResponseEntity<LawIndexIntegrityReport> audit(
		@RequestParam(defaultValue = "") String target,
		@RequestParam(defaultValue = "1000") int limit
	) {
		return ResponseEntity.ok(integrityService.audit(target, limit));
	}

	@PostMapping("/repair")
	public ResponseEntity<LawIndexIntegrityService.RepairPreview> repair(@RequestBody RepairRequest request) {
		if (request == null || request.cause() == null || request.issues() == null || request.issues().isEmpty()) {
			throw new IllegalArgumentException("Repair requires a cause and explicit issue IDs with content hashes.");
		}
		if (!request.isPreview()) {
			throw new IllegalArgumentException("No mutation policy is configured; repair remains an explicit preview.");
		}
		LawIndexIntegrityService.RepairPreview preview = integrityService.previewRepair(
			request.target(), request.cause(), request.issues()
		);
		return preview.rejectedIssueIds().isEmpty()
			? ResponseEntity.ok(preview)
			: ResponseEntity.status(HttpStatus.CONFLICT).body(preview);
	}

	public record RepairRequest(
		String target,
		LawIndexIntegrityIssue.Cause cause,
		List<LawIndexIntegrityService.RepairCandidate> issues,
		Boolean preview
	) {
		public boolean isPreview() {
			return preview == null || preview;
		}
	}
}
