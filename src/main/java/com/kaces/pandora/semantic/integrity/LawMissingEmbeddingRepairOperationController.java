package com.kaces.pandora.semantic.integrity;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Protected admin boundary for registering and observing durable repair work. */
@RestController
@RequestMapping("/api/admin/law-index-integrity")
public class LawMissingEmbeddingRepairOperationController {
	private final LawMissingEmbeddingRepairOperationService operationService;

	public LawMissingEmbeddingRepairOperationController(LawMissingEmbeddingRepairOperationService operationService) {
		this.operationService = operationService;
	}

	@PostMapping("/missing-embedding-repair-operations")
	public ResponseEntity<LawMissingEmbeddingRepairOperationService.OperationView> registerOperation(
		@RequestBody LawMissingEmbeddingRepairOperationService.RepairRequest request
	) {
		try {
			return ResponseEntity.status(HttpStatus.ACCEPTED).body(operationService.register(request));
		} catch (LawMissingEmbeddingRepairOperationService.RegistrationRejectedException exception) {
			HttpStatus status = exception.rejection() == LawMissingEmbeddingRepairOperationService.Rejection.BAD_REQUEST
				? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
			return ResponseEntity.status(status).build();
		}
	}

	@GetMapping("/missing-embedding-repair-operations/{operationId}")
	public ResponseEntity<LawMissingEmbeddingRepairOperationService.OperationView> getOperation(@PathVariable UUID operationId) {
		return operationService.find(operationId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
	}

	@PostMapping("/missing-embedding-repair-operations/{operationId}/step")
	public ResponseEntity<LawMissingEmbeddingRepairOperationService.OperationView> stepOperation(@PathVariable UUID operationId) {
		return operationService.step(operationId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
	}
}
