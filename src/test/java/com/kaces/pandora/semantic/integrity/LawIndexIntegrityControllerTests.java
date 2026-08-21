package com.kaces.pandora.semantic.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LawIndexIntegrityControllerTests {

	@Test
	void auditBindsServerRuntimeIdentityToTheAuditResponse() {
		LawIndexIntegrityController controller = new LawIndexIntegrityController(
			service(),
			new SequencedRuntimeInfoProvider("instance-a", "revision-a", "instance-a", "revision-a")
		);

		ResponseEntity<LawIndexIntegrityAuditResponse> response = controller.audit("law", 10, 0L);

		assertThat(response.getBody()).isEqualTo(new LawIndexIntegrityAuditResponse(
			"law", 10, 0, 0L, List.of(), java.util.Map.of(), "instance-a", "revision-a"
		));
	}

	@Test
	void auditFailsClosedWhenRuntimeIdentityChangesDuringTheRequest() {
		LawIndexIntegrityController controller = new LawIndexIntegrityController(
			service(),
			new SequencedRuntimeInfoProvider("instance-a", "revision-a", "instance-b", "revision-a")
		);

		assertThatThrownBy(() -> controller.audit("law", 10, 0L))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("drifted");
	}

	@Test
	void missingEmbeddingRepairEndpointReturnsTheFencedPerIdPreview() {
		LawMissingEmbeddingRepairService repairService = mock(LawMissingEmbeddingRepairService.class);
		LawIndexIntegrityController controller = new LawIndexIntegrityController(
			service(), new SequencedRuntimeInfoProvider("instance-a", "revision-a", "instance-a", "revision-a"), repairService
		);
		LawMissingEmbeddingRepairService.RepairRequest request = new LawMissingEmbeddingRepairService.RepairRequest(
			"law", "instance-a", "revision-a", List.of(11L),
			List.of(new LawMissingEmbeddingRepairService.RepairCandidate(101L, "a".repeat(64))), false
		);
		LawMissingEmbeddingRepairService.RepairResult expected = new LawMissingEmbeddingRepairService.RepairResult(
			false, false, new LawIndexIntegrityRuntimeInfo("instance-a", "revision-a"),
			List.of(new LawMissingEmbeddingRepairService.RepairOutcome(101L, 11L,
				LawMissingEmbeddingRepairService.RepairState.READY, "Current active chunk is classified MISSING_EMBEDDING_ROW."))
		);
		when(repairService.repair(request)).thenReturn(expected);

		ResponseEntity<LawMissingEmbeddingRepairService.RepairResult> response = controller.repairMissingEmbedding(request);

		assertThat(response.getBody()).isEqualTo(expected);
	}

	private LawIndexIntegrityService service() {
		LawChunkMapper mapper = (LawChunkMapper) Proxy.newProxyInstance(
			getClass().getClassLoader(),
			new Class<?>[] { LawChunkMapper.class },
			(proxy, method, args) -> "findLawIndexIntegrityRows".equals(method.getName()) ? List.of() : null
		);
		return new LawIndexIntegrityService(mapper, ids -> Set.of());
	}

	private static final class SequencedRuntimeInfoProvider implements LawIndexIntegrityRuntimeInfoProvider {
		private final List<LawIndexIntegrityRuntimeInfo> values;
		private final AtomicInteger next = new AtomicInteger();

		private SequencedRuntimeInfoProvider(String firstInstance, String firstRevision, String secondInstance, String secondRevision) {
			this.values = List.of(
				new LawIndexIntegrityRuntimeInfo(firstInstance, firstRevision),
				new LawIndexIntegrityRuntimeInfo(secondInstance, secondRevision)
			);
		}

		@Override
		public LawIndexIntegrityRuntimeInfo current() {
			return values.get(next.getAndIncrement());
		}
	}
}
