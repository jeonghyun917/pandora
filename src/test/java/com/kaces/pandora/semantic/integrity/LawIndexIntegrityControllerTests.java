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

class LawIndexIntegrityControllerTests {

	@Test
	void auditBindsServerRuntimeIdentityToTheAuditResponse() {
		LawIndexIntegrityController controller = new LawIndexIntegrityController(
			service(),
			new SequencedRuntimeInfoProvider("instance-a", "revision-a", "instance-a", "revision-a")
		);

		ResponseEntity<LawIndexIntegrityAuditResponse> response = controller.audit("law", 10);

		assertThat(response.getBody()).isEqualTo(new LawIndexIntegrityAuditResponse(
			"law", 10, List.of(), java.util.Map.of(), "instance-a", "revision-a"
		));
	}

	@Test
	void auditFailsClosedWhenRuntimeIdentityChangesDuringTheRequest() {
		LawIndexIntegrityController controller = new LawIndexIntegrityController(
			service(),
			new SequencedRuntimeInfoProvider("instance-a", "revision-a", "instance-b", "revision-a")
		);

		assertThatThrownBy(() -> controller.audit("law", 10))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("drifted");
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
