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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.kaces.pandora.app.admin.AdminAccessInterceptor;
import com.kaces.pandora.app.admin.AdminAccessProperties;

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

	@Test
	void stepReturnsCurrentDurableStateAndUnknownOperationIsNotFound() {
		LawMissingEmbeddingRepairOperationService service = mock(LawMissingEmbeddingRepairOperationService.class);
		LawMissingEmbeddingRepairOperationController controller = new LawMissingEmbeddingRepairOperationController(service);
		java.util.UUID foundId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000010");
		java.util.UUID missingId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000099");
		when(service.step(foundId)).thenReturn(Optional.of(view()));
		when(service.step(missingId)).thenReturn(Optional.empty());

		assertThat(controller.stepOperation(foundId).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(controller.stepOperation(missingId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void mockMvcBindsAcceptedConflictBadRequestNotFoundOrderedItemsAndProtectedBoundary() throws Exception {
		LawMissingEmbeddingRepairOperationService service = mock(LawMissingEmbeddingRepairOperationService.class);
		LawMissingEmbeddingRepairOperationController controller = new LawMissingEmbeddingRepairOperationController(service);
		LawMissingEmbeddingRepairOperationService.OperationView view = viewWithOrderedItems();
		when(service.register(org.mockito.ArgumentMatchers.any())).thenReturn(view);
		when(service.find(java.util.UUID.fromString(view.operation().request().operationId()))).thenReturn(Optional.of(view));
		when(service.find(java.util.UUID.fromString("00000000-0000-0000-0000-000000000099"))).thenReturn(Optional.empty());
		when(service.step(java.util.UUID.fromString(view.operation().request().operationId()))).thenReturn(Optional.of(view));
		AdminAccessProperties properties = new AdminAccessProperties();
		properties.setEnabled(true);
		properties.setLocalOnly(true);
		MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
			.addInterceptors(new AdminAccessInterceptor(properties)).build();
		String body = """
			{"target":"law","expectedRuntimeInstanceId":"00000000-0000-0000-0000-000000000001",
			"expectedIndexRevision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","apply":true,
			"expectedDocumentIds":[11],"candidates":[{"chunkId":101,"expectedChunkContentHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}]}""";

		mvc.perform(post("/api/admin/law-index-integrity/missing-embedding-repair-operations").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isAccepted()).andExpect(jsonPath("$.items[0].ordinal").value(0));
		mvc.perform(get("/api/admin/law-index-integrity/missing-embedding-repair-operations/00000000-0000-0000-0000-000000000010"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].ordinal").value(0)).andExpect(jsonPath("$.items[1].ordinal").value(1));
		mvc.perform(get("/api/admin/law-index-integrity/missing-embedding-repair-operations/not-a-uuid")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/admin/law-index-integrity/missing-embedding-repair-operations/00000000-0000-0000-0000-000000000099")).andExpect(status().isNotFound());
		mvc.perform(post("/api/admin/law-index-integrity/missing-embedding-repair-operations/00000000-0000-0000-0000-000000000010/step"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.operation.request.operationId").value("00000000-0000-0000-0000-000000000010"));
		mvc.perform(get("/api/admin/law-index-integrity/missing-embedding-repair-operations/00000000-0000-0000-0000-000000000010")
			.with(request -> { request.setRemoteAddr("10.0.0.1"); return request; })).andExpect(status().isForbidden());
		org.mockito.Mockito.doThrow(new LawMissingEmbeddingRepairOperationService.RegistrationRejectedException(LawMissingEmbeddingRepairOperationService.Rejection.BAD_REQUEST))
			.when(service).register(org.mockito.ArgumentMatchers.any());
		mvc.perform(post("/api/admin/law-index-integrity/missing-embedding-repair-operations").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
		org.mockito.Mockito.doThrow(new LawMissingEmbeddingRepairOperationService.RegistrationRejectedException(LawMissingEmbeddingRepairOperationService.Rejection.CONFLICT))
			.when(service).register(org.mockito.ArgumentMatchers.any());
		mvc.perform(post("/api/admin/law-index-integrity/missing-embedding-repair-operations").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
	}

	private LawMissingEmbeddingRepairOperationService.OperationView viewWithOrderedItems() {
		LawMissingEmbeddingRepairOperationService.OperationView base = view();
		Instant now = Instant.parse("2026-08-05T00:00:00Z");
		return new LawMissingEmbeddingRepairOperationService.OperationView(base.operation(), List.of(
			new LawMissingEmbeddingRepairOperation.Item(base.operation().request().operationId(), 0, 101L, 11L, "b".repeat(64), LawMissingEmbeddingRepairOperation.ItemState.READY, null, null, null, null, null, now, now),
			new LawMissingEmbeddingRepairOperation.Item(base.operation().request().operationId(), 1, 102L, 11L, "c".repeat(64), LawMissingEmbeddingRepairOperation.ItemState.READY, null, null, null, null, null, now, now)
		));
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
