package com.kaces.pandora.semantic.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class LawMissingEmbeddingRepairOperationControllerTests {

	@Test
	void registrationReturnsAcceptedAndGetReturnsTheDurableOperation() {
		LawMissingEmbeddingRepairOperationService service = mock(LawMissingEmbeddingRepairOperationService.class);
		LawMissingEmbeddingRepairOperationController controller = new LawMissingEmbeddingRepairOperationController(service);
		LawMissingEmbeddingRepairOperationService.RepairRequest request = new LawMissingEmbeddingRepairOperationService.RepairRequest(
			"law", "00000000-0000-0000-0000-000000000001", "a".repeat(64), true,
			List.of(11L), List.of(new LawMissingEmbeddingRepairOperationService.RepairCandidate(101L, "b".repeat(64)))
		);
		LawMissingEmbeddingRepairOperationService.OperationView view = view();
		when(service.register(request)).thenReturn(view);
		when(service.find(java.util.UUID.fromString(view.operation().request().operationId()))).thenReturn(Optional.of(view));

		ResponseEntity<LawMissingEmbeddingRepairOperationService.OperationView> registered = controller.registerOperation(request);
		ResponseEntity<LawMissingEmbeddingRepairOperationService.OperationView> found = controller.getOperation(java.util.UUID.fromString(view.operation().request().operationId()));

		assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(registered.getBody()).isEqualTo(view);
		assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(found.getBody()).isEqualTo(view);
	}

	@Test
	void registrationRejectsInvalidOrDriftedRequestsAndUnknownOperationIsNotFound() {
		LawMissingEmbeddingRepairOperationService service = mock(LawMissingEmbeddingRepairOperationService.class);
		LawMissingEmbeddingRepairOperationController controller = new LawMissingEmbeddingRepairOperationController(service);
		LawMissingEmbeddingRepairOperationService.RepairRequest request = new LawMissingEmbeddingRepairOperationService.RepairRequest(
			"law", "00000000-0000-0000-0000-000000000001", "a".repeat(64), true,
			List.of(11L), List.of(new LawMissingEmbeddingRepairOperationService.RepairCandidate(101L, "b".repeat(64)))
		);
		when(service.register(request)).thenThrow(new LawMissingEmbeddingRepairOperationService.RegistrationRejectedException(
			LawMissingEmbeddingRepairOperationService.Rejection.CONFLICT
		));
		when(service.find(java.util.UUID.fromString("00000000-0000-0000-0000-000000000099"))).thenReturn(Optional.empty());

		ResponseEntity<LawMissingEmbeddingRepairOperationService.OperationView> rejected = controller.registerOperation(request);
		ResponseEntity<LawMissingEmbeddingRepairOperationService.OperationView> missing = controller.getOperation(java.util.UUID.fromString("00000000-0000-0000-0000-000000000099"));

		assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	private LawMissingEmbeddingRepairOperationService.OperationView view() {
		Instant now = Instant.parse("2026-08-05T00:00:00Z");
		LawMissingEmbeddingRepairOperation operation = new LawMissingEmbeddingRepairOperation(
			new LawMissingEmbeddingRepairOperation.Request("00000000-0000-0000-0000-000000000010", "c".repeat(64), "normalized", "c".repeat(64),
				"law", "00000000-0000-0000-0000-000000000001", 1, 1, now),
			new LawMissingEmbeddingRepairOperation.Progress("a".repeat(64), LawMissingEmbeddingRepairOperation.Status.READY, 0, 0, null, null, null, now)
		);
		return new LawMissingEmbeddingRepairOperationService.OperationView(operation, List.of());
	}
}
