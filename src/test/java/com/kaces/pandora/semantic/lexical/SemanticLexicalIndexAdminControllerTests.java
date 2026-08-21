package com.kaces.pandora.semantic.lexical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SemanticLexicalIndexAdminControllerTests {

	@Test
	void reportsTheReadyRevisionAndBuildState() {
		SemanticLexicalIndexService service = mock(SemanticLexicalIndexService.class);
		when(service.currentRevision()).thenReturn("ready-revision");
		SemanticLexicalIndexAdminController controller = new SemanticLexicalIndexAdminController(service);

		assertThat(controller.status()).isEqualTo(
			new SemanticLexicalIndexAdminController.Status("ready-revision", false)
		);
	}

	@Test
	void rebuildReturnsThePublishedSideBySideRevision() {
		SemanticLexicalIndexService service = mock(SemanticLexicalIndexService.class);
		SemanticLexicalIndexService.BuildResult expected = new SemanticLexicalIndexService.BuildResult(
			"build-a", "a".repeat(64), 100, 500, 12.5
		);
		when(service.rebuild()).thenReturn(expected);
		SemanticLexicalIndexAdminController controller = new SemanticLexicalIndexAdminController(service);

		assertThat(controller.rebuild()).isEqualTo(expected);
		assertThat(controller.status().rebuilding()).isFalse();
	}

	@Test
	void rejectsAConcurrentRebuild() throws Exception {
		SemanticLexicalIndexService service = mock(SemanticLexicalIndexService.class);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		when(service.rebuild()).thenAnswer(invocation -> {
			entered.countDown();
			if (!release.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("test rebuild was not released");
			}
			return new SemanticLexicalIndexService.BuildResult("build-a", "a".repeat(64), 1, 1, 1.0);
		});
		SemanticLexicalIndexAdminController controller = new SemanticLexicalIndexAdminController(service);

		var executor = Executors.newSingleThreadExecutor();
		try {
			Future<SemanticLexicalIndexService.BuildResult> first = executor.submit(controller::rebuild);
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

			assertThatThrownBy(controller::rebuild)
				.isInstanceOf(ResponseStatusException.class)
				.satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
					.isEqualTo(HttpStatus.CONFLICT));

			release.countDown();
			assertThat(first.get(5, TimeUnit.SECONDS).indexVersion()).isEqualTo("build-a");
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
	}
}
