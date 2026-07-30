package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.infra.qdrant.QdrantIndexSnapshot;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.semantic.config.LawAiProperties;
import com.kaces.pandora.semantic.provenance.IndexContentSnapshot;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LawAiRuntimeInfoTests {

	@Test
	void runtimeInfoPublishesDynamicRevisionFromBothCurrentIndexes() {
		LawAiProperties properties = properties();
		QdrantClient qdrant = qdrant(properties, true);
		LawAiAnswerService service = service(
			mapper(LawChunkMapper.class, snapshot(20, 'a'), false),
			mapper(RagDocumentMapper.class, snapshot(10, 'b'), false),
			qdrant,
			properties
		);
		try {
			LawAiRuntimeInfo runtimeInfo = service.runtimeInfo();

			assertThat(runtimeInfo.indexRevision()).matches("[0-9a-f]{64}");
			assertThat(runtimeInfo.lexicalRevision()).isEqualTo("legacy-law-like-v1+rag-terms-v2-unavailable");
			assertThat(runtimeInfo.qdrantReady()).isTrue();
			assertThat(runtimeInfo.qdrantSearchFailureCount()).isZero();
		} finally {
			service.shutdownExecutors();
			qdrant.shutdownExecutor();
		}
	}

	@Test
	void runtimeInfoFailsClosedWhenDatabaseSnapshotTimesOut() {
		LawAiProperties properties = properties();
		QdrantClient qdrant = qdrant(properties, true);
		LawAiAnswerService service = service(
			mapper(LawChunkMapper.class, null, true),
			mapper(RagDocumentMapper.class, snapshot(10, 'b'), false),
			qdrant,
			properties
		);
		try {
			LawAiRuntimeInfo runtimeInfo = service.runtimeInfo();

			assertThat(runtimeInfo.indexRevision()).isNull();
			assertThat(runtimeInfo.lexicalRevision()).isEqualTo("legacy-law-like-v1+rag-terms-v2-unavailable");
			assertThat(runtimeInfo.qdrantReady()).isTrue();
		} finally {
			service.shutdownExecutors();
			qdrant.shutdownExecutor();
		}
	}

	private LawAiAnswerService service(
		LawChunkMapper lawMapper,
		RagDocumentMapper ragMapper,
		QdrantClient qdrant,
		LawAiProperties properties
	) {
		return new LawAiAnswerService(
			lawMapper,
			ragMapper,
			null,
			qdrant,
			null,
			new EvidenceJudge(),
			new AnswerGuard(),
			new ClaimVerifier(),
			new AnswerVerificationService(new AnswerGuard(), new ClaimVerifier()),
			new ParentContextAssembler(),
			new EvidenceCandidateDiversifier(),
			new FailureLoggingService(null),
			null,
			properties
		);
	}

	private QdrantClient qdrant(LawAiProperties properties, boolean ready) {
		return new QdrantClient(properties, new ObjectMapper()) {
			@Override
			public boolean isSearchReady() {
				return ready;
			}

			@Override
			public Optional<QdrantIndexSnapshot> indexSnapshot(String collection) {
				if (!ready) {
					return Optional.empty();
				}
				long count = "law_chunks".equals(collection) ? 20 : 10;
				return Optional.of(new QdrantIndexSnapshot(
					collection, "green", 0, count, 1536, "Cosine", count - 1, 4
				));
			}
		};
	}

	private LawAiProperties properties() {
		return new LawAiProperties(
			new LawAiProperties.OpenAi("", "text-embedding-3-small", "gpt-5-mini", "low", "low", 700),
			new LawAiProperties.Qdrant("http://127.0.0.1:1", "law_chunks", "rag_chunks_v4", 1536),
			null,
			null
		);
	}

	private IndexContentSnapshot snapshot(long count, char fingerprint) {
		return new IndexContentSnapshot(
			count,
			String.valueOf(fingerprint).repeat(64),
			"2026-07-15T01:02:03.000000"
		);
	}

	@SuppressWarnings("unchecked")
	private <T> T mapper(Class<T> type, IndexContentSnapshot snapshot, boolean fail) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
			if ("findCurrentIndexedSnapshot".equals(method.getName())) {
				if (fail) {
					throw new IllegalStateException("simulated timeout");
				}
				return snapshot;
			}
			Class<?> returnType = method.getReturnType();
			if (List.class.isAssignableFrom(returnType)) {
				return List.of();
			}
			if (returnType == int.class) {
				return 0;
			}
			if (returnType == long.class) {
				return 0L;
			}
			if (returnType == boolean.class) {
				return false;
			}
			return null;
		});
	}
}
