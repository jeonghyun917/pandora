package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.infra.qdrant.QdrantIndexSnapshot;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.semantic.config.LawAiCoverageAwareProperties;
import com.kaces.pandora.semantic.config.LawAiDocumentExpansionProperties;
import com.kaces.pandora.semantic.config.LawAiLexicalProperties;
import com.kaces.pandora.semantic.config.LawAiProperties;
import com.kaces.pandora.semantic.config.LawAiRrfProperties;
import com.kaces.pandora.semantic.lexical.SemanticLexicalIndexService;
import com.kaces.pandora.semantic.provenance.IndexContentSnapshot;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LawAiRuntimeInfoTests {
    @Test void activatedSourceIdentityChangesRuntimeIndexFingerprintAndReadinessCannotBeSkipped() {
        var properties=properties();var q=qdrant(properties,true);
        var service=service(mapper(LawChunkMapper.class,snapshot(20,'a'),false),mapper(RagDocumentMapper.class,snapshot(10,'b'),false),q,properties);
        var router=org.mockito.Mockito.mock(com.kaces.pandora.rag.recovery.RagRecoveryRoutingService.class);
        var readiness=org.mockito.Mockito.mock(com.kaces.pandora.rag.recovery.RagRecoveryReadinessService.class);
        try {
            String baseline=service.runtimeInfo().indexRevision();
            String id="00000000-0000-0000-0000-000000000001",group="a".repeat(64);
            var route=new com.kaces.pandora.lawdata.version.SourceVersionRoutingSnapshot.Route(group,1,"admrul","admrul:지침",10,20,java.util.Set.of(10L,20L),id,"b".repeat(64),"law_source_"+id.replace("-",""),"c".repeat(64),"d".repeat(64),"text-embedding-3-small",1,1);
            var captured=new com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot(1,java.util.Map.of(),java.util.Map.of(),java.util.Set.of(),"e".repeat(64),"recovery_"+id,new com.kaces.pandora.lawdata.version.SourceVersionRoutingSnapshot(1,java.util.Map.of(group,route)));
            org.mockito.Mockito.when(router.capture()).thenReturn(captured);service.configureRecoveryRouting(router);service.configureRecoveryReadiness(readiness);
            var active=service.runtimeInfo();
            assertThat(active.indexRevision()).isNotNull().isNotEqualTo(baseline);assertThat(active.qdrantReady()).isTrue();
            org.mockito.Mockito.verify(readiness).verify(captured);
            org.mockito.Mockito.doThrow(new IllegalStateException("source vector namespace missing")).when(readiness).verify(captured);
            var unavailable=service.runtimeInfo();
            assertThat(unavailable.qdrantReady()).isFalse();assertThat(unavailable.indexRevision()).isNull();
        } finally {service.shutdownExecutors();q.shutdownExecutor();}
    }

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
			assertThat(runtimeInfo.lawQdrantExactPointCount()).isEqualTo(20L);
			assertThat(runtimeInfo.ragQdrantExactPointCount()).isEqualTo(10L);
			assertThat(runtimeInfo.lawDatabaseIndexedCount()).isEqualTo(20L);
			assertThat(runtimeInfo.ragDatabaseIndexedCount()).isEqualTo(10L);
			assertThat(runtimeInfo.lawDatabaseContentFingerprint()).isEqualTo("a".repeat(64));
			assertThat(runtimeInfo.ragDatabaseContentFingerprint()).isEqualTo("b".repeat(64));
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

	@Test
	void runtimeInfoPublishesReadyCommonLexicalRevision() {
		LawAiProperties properties = properties();
		QdrantClient qdrant = qdrant(properties, true);
		SemanticLexicalIndexService lexicalIndex = org.mockito.Mockito.mock(SemanticLexicalIndexService.class);
		org.mockito.Mockito.when(lexicalIndex.currentRevision()).thenReturn("common-lexical-revision");
		LawAiAnswerService service = service(
			mapper(LawChunkMapper.class, snapshot(20, 'a'), false),
			mapper(RagDocumentMapper.class, snapshot(10, 'b'), false),
			qdrant,
			properties,
			lexicalIndex
		);
		try {
			assertThat(service.runtimeInfo().lexicalRevision()).isEqualTo("common-lexical-revision");
		} finally {
			service.shutdownExecutors();
			qdrant.shutdownExecutor();
		}
	}

	@Test
	void runtimeInfoFingerprintsTheInjectedDocumentExpansionPolicy() {
		LawAiProperties properties = properties();
		LawAiDocumentExpansionProperties expansion = new LawAiDocumentExpansionProperties(true, false, 3, 8, 24);
		QdrantClient qdrant = qdrant(properties, false);
		LawAiAnswerService service = service(
			mapper(LawChunkMapper.class, null, false),
			mapper(RagDocumentMapper.class, null, false),
			qdrant,
			properties,
			null,
			expansion
		);
		try {
			assertThat(service.runtimeInfo().runtimeConfigSha256()).isEqualTo(RuntimeConfigurationIdentity.sha256(
				properties,
				new LawAiLexicalProperties(1.2, 0.75, 8, 6, 7, 1, 24, 100),
				new LawAiRrfProperties(false, false, 60, 1.0, 1.0, 100),
				new LawAiCoverageAwareProperties(false, 0, 1, 30),
				expansion
			));
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
		return service(lawMapper, ragMapper, qdrant, properties, null);
	}

	private LawAiAnswerService service(
		LawChunkMapper lawMapper,
		RagDocumentMapper ragMapper,
		QdrantClient qdrant,
		LawAiProperties properties,
		SemanticLexicalIndexService lexicalIndex
	) {
		return service(
			lawMapper,
			ragMapper,
			qdrant,
			properties,
			lexicalIndex,
			new LawAiDocumentExpansionProperties(false, false, 0, 0, 0)
		);
	}

	private LawAiAnswerService service(
		LawChunkMapper lawMapper,
		RagDocumentMapper ragMapper,
		QdrantClient qdrant,
		LawAiProperties properties,
		SemanticLexicalIndexService lexicalIndex,
		LawAiDocumentExpansionProperties documentExpansionProperties
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
			properties,
			null,
			null,
			lexicalIndex,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			documentExpansionProperties
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
