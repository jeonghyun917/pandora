package com.kaces.pandora.infra.qdrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.semantic.config.LawAiProperties;
import com.kaces.pandora.semantic.search.QdrantSearchHit;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.ObjectMapper;

class QdrantClientTests {

	private HttpServer server;
	private QdrantClient client;
	private AtomicInteger searchBodyAttempts;

	@AfterEach
	void tearDown() {
		if (client != null) {
			client.shutdownExecutor();
		}
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void findExistingLawPointIdsRejectsNonIntegralNonPositiveAndOutOfRangeResponseIds() throws IOException {
		for (String invalidId : java.util.List.of("10.5", "0", "9223372036854775808")) {
			startPointLookupServer("{\"result\":[{\"id\":" + invalidId + "}]}");
			client = client("law_chunks", "rag");

			assertThatThrownBy(() -> client.findExistingLawPointIds(java.util.List.of(10L)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("malformed point");
			tearDown();
		}
	}

	@Test
	void findExistingLawPointIdsUsesBoundedPointsLookupWithoutPayloadOrVectors() throws IOException {
		java.util.concurrent.atomic.AtomicReference<String> method = new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicReference<String> path = new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicReference<String> body = new java.util.concurrent.atomic.AtomicReference<>();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law_chunks/points", exchange -> {
			method.set(exchange.getRequestMethod());
			path.set(exchange.getRequestURI().getPath());
			body.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
			respond(exchange, 200, "{\"result\":[{\"id\":10},{\"id\":30}]}");
		});
		server.start();
		client = client("law_chunks", "rag");

		Set<Long> existing = client.findExistingLawPointIds(java.util.List.of(10L, 20L, 30L));

		assertThat(existing).containsExactlyInAnyOrder(10L, 30L);
		assertThat(method).hasValue("POST");
		assertThat(path).hasValue("/collections/law_chunks/points");
		assertThat(body.get()).contains("\"with_payload\":false", "\"with_vector\":false", "10", "20", "30");
	}

	@Test
	void lawCandidateAndProductionStatusLookupsBatchMoreThanTwoHundredFiftySixPointIds() throws IOException {
		AtomicInteger candidateRequests = new AtomicInteger();
		AtomicInteger productionRequests = new AtomicInteger();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections", exchange -> {
			String request = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			String result = requestPointIds(request).stream()
				.map(id -> "{\"id\":" + id + ",\"payload\":{\"activationStatus\":\"CANDIDATE\"}}")
				.collect(java.util.stream.Collectors.joining(",", "{\"result\":[", "]}"));
			if (exchange.getRequestURI().getPath().contains("law_chunks_candidate")) candidateRequests.incrementAndGet();
			else productionRequests.incrementAndGet();
			respond(exchange, 200, result);
		});
		server.start();
		client = client("law_chunks", "rag");
		java.util.List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 257).boxed().toList();

		assertThat(client.findExistingLawCandidatePointIds(ids)).containsExactlyInAnyOrderElementsOf(ids);
		assertThat(client.findLawPointIdsWithActivationStatus(ids, "CANDIDATE")).containsExactlyInAnyOrderElementsOf(ids);
		assertThat(client.findLawPointIdsWithActivationStatus(ids, "ACTIVE")).isEmpty();
		assertThat(candidateRequests).hasValue(2);
		assertThat(productionRequests).hasValue(4);
	}

	@Test
	void searchReadinessRequiresBothConfiguredCollections() throws IOException {
		startServer(200, readyCollection(1536, 10));
		client = client();

		assertThat(client.isSearchReady()).isTrue();
	}

	@Test
	void searchReadinessFailsClosedWhenACollectionIsMissing() throws IOException {
		startServer(404, "{}");
		client = client();

		assertThat(client.isSearchReady()).isFalse();
	}

	@Test
	void searchReadinessFailsClosedWhenCollectionConfigurationIsMissing() throws IOException {
		startServer(200, readyCollection(1536, 10));
		client = client(null, "rag");

		assertThat(client.isSearchReady()).isFalse();
	}

	@Test
	void searchReadinessRejectsRecoveringEmptyAndWrongVectorCollections() throws IOException {
		startServer(200, collection("red", 1536, 10));
		client = client();
		assertThat(client.isSearchReady()).isFalse();
		tearDown();

		startServer(200, readyCollection(1536, 0));
		client = client();
		assertThat(client.isSearchReady()).isFalse();
		tearDown();

		startServer(200, readyCollection(3072, 10));
		client = client();
		assertThat(client.isSearchReady()).isFalse();
	}

	@Test
	void indexSnapshotUsesExactCountAndStableCollectionFields() throws IOException {
		startIndexSnapshotServer(
			200,
			collectionWithQueue("green", 1536, 999, 0, 998, 7),
			200,
			"{\"result\":{\"count\":10}}"
		);
		client = client();

		Optional<QdrantIndexSnapshot> snapshot = client.indexSnapshot("law");

		assertThat(snapshot).contains(new QdrantIndexSnapshot(
			"law", "green", 0, 10, 1536, "Cosine", 998, 7
		));
	}

	@Test
	void indexSnapshotFailsClosedForBusyQueueAndMalformedExactCount() throws IOException {
		startIndexSnapshotServer(
			200,
			collectionWithQueue("green", 1536, 10, 1, 10, 2),
			200,
			"{\"result\":{\"count\":10}}"
		);
		client = client();
		assertThat(client.indexSnapshot("law")).isEmpty();
		tearDown();

		startIndexSnapshotServer(
			200,
			collectionWithQueue("green", 1536, 10, 0, 10, 2),
			200,
			"{\"result\":{}}"
		);
		client = client();
		assertThat(client.indexSnapshot("law")).isEmpty();
	}

	@Test
	void indexSnapshotFailsClosedWhenUpdateQueueIsMissing() throws IOException {
		startIndexSnapshotServer(
			200,
			collection("green", 1536, 10),
			200,
			"{\"result\":{\"count\":10}}"
		);
		client = client();

		assertThat(client.indexSnapshot("law")).isEmpty();
	}

	@Test
	void exhaustedSearchFailureIsCountedOnceAfterRetries() throws IOException {
		startSearchServer(503, "{}");
		client = client();

		assertThat(client.search(java.util.List.of(0.25d), "law", 1)).isEmpty();
		assertThat(client.searchFailureCount()).isEqualTo(1L);
	}

	@Test
	void validEmptySearchResultIsNotCountedAsInfrastructureFailure() throws IOException {
		startSearchServer(200, "{\"result\":[]}");
		client = client();

		assertThat(client.search(java.util.List.of(0.25d), "law", 1)).isEmpty();
		assertThat(client.searchFailureCount()).isZero();
	}

	@Test
	void malformedSuccessfulSearchResponseIsCountedAsInfrastructureFailure() throws IOException {
		startSearchServer(200, "{}");
		client = client();

		assertThat(client.search(java.util.List.of(0.25d), "law", 1)).isEmpty();
		assertThat(client.searchFailureCount()).isEqualTo(1L);
	}

	@Test
	void malformedSearchResultItemIsRetriedAndCountedAsInfrastructureFailure() throws IOException {
		startSearchServer(200, "{\"result\":[{}]}");
		client = client();

		assertThat(client.search(java.util.List.of(0.25d), "law", 1)).isEmpty();
		assertThat(client.searchFailureCount()).isEqualTo(1L);
	}

	@Test
	void retrySuccessDoesNotIncrementSearchFailureCount() throws IOException {
		java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law/points/search", exchange -> {
			if (attempts.incrementAndGet() == 1) {
				respond(exchange, 503, "{}");
				return;
			}
			respond(exchange, 200, "{\"result\":[]}");
		});
		server.start();
		client = client();

		assertThat(client.search(java.util.List.of(0.25d), "law", 1)).isEmpty();
		assertThat(attempts).hasValue(2);
		assertThat(client.searchFailureCount()).isZero();
	}

	@Test
	void searchRetriesExactSpringRequestBodyTransportFailureAndReturnsSecondResponse() throws Exception {
		client = clientWithSearchBodyFailures(1);
		AtomicReference<java.util.List<QdrantSearchHit>> result = new AtomicReference<>();

		assertThat(catchThrowable(() -> result.set(client.search(java.util.List.of(0.25d), "law", 1)))).isNull();
		assertThat(result.get())
			.containsExactly(new QdrantSearchHit("law", 42L, 0.9d));
		assertThat(searchBodyAttempts).hasValue(2);
		assertThat(client.searchFailureCount()).isZero();
	}

	@Test
	void searchCountsExactSpringRequestBodyTransportFailureOnceAfterBothAttempts() throws Exception {
		client = clientWithSearchBodyFailures(2);
		AtomicReference<java.util.List<QdrantSearchHit>> result = new AtomicReference<>();

		assertThat(catchThrowable(() -> result.set(client.search(java.util.List.of(0.25d), "law", 1)))).isNull();
		assertThat(result.get()).isEmpty();
		assertThat(searchBodyAttempts).hasValue(2);
		assertThat(client.searchFailureCount()).isEqualTo(1L);
	}

	@Test
	void searchDoesNotRetryNonTransportSpringJsonMappingFailure() throws Exception {
		AtomicInteger attempts = new AtomicInteger();
		HttpMessageNotWritableException mappingFailure = new HttpMessageNotWritableException("Could not write JSON: invalid mapping");
		client = clientWithSearchBodyFailure(attempts, mappingFailure);

		assertThatThrownBy(() -> client.search(java.util.List.of(0.25d), "law", 1)).isSameAs(mappingFailure);
		assertThat(attempts).hasValue(1);
		assertThat(client.searchFailureCount()).isZero();
	}

	private void startServer(int ragStatus, String ragBody) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law", exchange -> respond(exchange, 200, readyCollection(1536, 10)));
		server.createContext("/collections/rag", exchange -> respond(exchange, ragStatus, ragBody));
		server.start();
	}

	@Test
	void productionSearchExcludesCandidateAndRetiredPointsWhenCleanupIsDelayed() throws IOException {
		java.util.concurrent.atomic.AtomicReference<String> body = new java.util.concurrent.atomic.AtomicReference<>();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law/points/search", exchange -> {
			body.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
			respond(exchange, 200, "{\"result\":[]}");
		});
		server.start();
		client = client();

		client.search(java.util.List.of(0.25d), "law", 1);

		assertThat(body.get()).contains("\"activationStatus\"", "\"CANDIDATE\"", "\"RETIRED\"", "\"must_not\"");
	}

	@Test
	void candidateUpsertUsesTheIsolatedCandidateCollection() throws IOException {
		java.util.concurrent.atomic.AtomicReference<String> pointPath = new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicReference<String> pointBody = new java.util.concurrent.atomic.AtomicReference<>();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law_chunks_candidate", exchange -> {
			if (exchange.getRequestURI().getPath().endsWith("/points")) {
				pointPath.set(exchange.getRequestURI().getPath());
				pointBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
			}
			respond(exchange, 200, readyCollection(1536, 1));
		});
		server.start();
		client = client("law_chunks", "rag");

		client.upsertLawCandidates(java.util.List.of(new LawSemanticChunkRow(
			7L, 41L, "law", "x", "Title", null, null, null, null,
			"Article 1", "Article 1", "candidate", null, "$.law.articles[0].body", null,
			0, "hash", "Article 1", "provision", "PASS", "embedding", "parent", 2
		)), java.util.List.of(java.util.List.of(0.25d)));

		assertThat(pointPath).hasValue("/collections/law_chunks_candidate/points");
		assertThat(pointBody.get()).contains("\"activationStatus\":\"CANDIDATE\"");
	}

	@Test
	void upsertUsesExplicitStoredChunkVersionForVersionTwoLawPayload() throws IOException {
		java.util.concurrent.atomic.AtomicReference<String> body = new java.util.concurrent.atomic.AtomicReference<>();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law_chunks/points", exchange -> {
			body.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
			respond(exchange, 200, "{}");
		});
		server.start();
		client = client("law_chunks", "rag");

		client.upsert(java.util.List.of(new LawSemanticChunkRow(
			7L, 41L, "law", "x", "Title", null, null, null, null,
			"Article 1", "Article 1", "child", null, "$.law.articles[0].body", null,
			0, "hash", "Article 1", "provision", "PASS", "embedding", "parent", 2
		)), java.util.List.of(java.util.List.of(0.25d)));

		assertThat(body.get()).contains("\"chunkVersion\":2");
	}

	private void startPointLookupServer(String body) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law_chunks/points", exchange -> respond(exchange, 200, body));
		server.start();
	}

	private java.util.List<Long> requestPointIds(String request) {
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(request);
		java.util.List<Long> ids = new java.util.ArrayList<>();
		while (matcher.find()) ids.add(Long.parseLong(matcher.group()));
		return ids;
	}

	private void startSearchServer(int status, String body) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law/points/search", exchange -> respond(exchange, status, body));
		server.start();
	}

	private QdrantClient clientWithSearchBodyFailures(int failuresBeforeSuccess) throws Exception {
		searchBodyAttempts = new AtomicInteger();
		return clientWithSearchBodyFailure(searchBodyAttempts, exactSpringRequestBodyTransportFailure(), failuresBeforeSuccess);
	}

	private QdrantClient clientWithSearchBodyFailure(AtomicInteger attempts, RuntimeException failure) throws Exception {
		return clientWithSearchBodyFailure(attempts, failure, Integer.MAX_VALUE);
	}

	private QdrantClient clientWithSearchBodyFailure(
		AtomicInteger attempts,
		RuntimeException failure,
		int failuresBeforeSuccess
	) throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.start();
		QdrantClient qdrant = client();
		RestClient restClient = mock(RestClient.class);
		RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
		RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
		RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
		when(restClient.post()).thenReturn(uriSpec);
		when(uriSpec.uri(anyString(), any(Object[].class))).thenReturn(bodySpec);
		when(bodySpec.body(org.mockito.ArgumentMatchers.<Object>any())).thenAnswer(invocation -> {
			if (attempts.incrementAndGet() <= failuresBeforeSuccess) {
				throw failure;
			}
			return bodySpec;
		});
		when(bodySpec.retrieve()).thenReturn(responseSpec);
		when(responseSpec.body(byte[].class)).thenReturn(
			"{\"result\":[{\"id\":7,\"score\":0.9,\"payload\":{\"target\":\"law\",\"chunkId\":42}}]}"
				.getBytes(java.nio.charset.StandardCharsets.UTF_8)
		);
		replaceRestClient(qdrant, restClient);
		return qdrant;
	}

	private HttpMessageNotWritableException exactSpringRequestBodyTransportFailure() {
		return new HttpMessageNotWritableException(
			"Could not write JSON: Error writing request body to server",
			DatabindException.from(
				(JsonGenerator) null,
				"Error writing request body to server",
				new IOException("Error writing request body to server")
			)
		);
	}

	private void replaceRestClient(QdrantClient qdrant, RestClient restClient) throws Exception {
		Field field = QdrantClient.class.getDeclaredField("restClient");
		field.setAccessible(true);
		field.set(qdrant, restClient);
	}

	private void startIndexSnapshotServer(
		int infoStatus,
		String infoBody,
		int countStatus,
		String countBody
	) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law", exchange -> {
			if ("/collections/law/points/count".equals(exchange.getRequestURI().getPath())) {
				respond(exchange, countStatus, countBody);
				return;
			}
			respond(exchange, infoStatus, infoBody);
		});
		server.start();
	}

	private QdrantClient client() {
		return client("law", "rag");
	}

	private QdrantClient client(String lawCollection, String ragCollection) {
		String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
		LawAiProperties properties = new LawAiProperties(
			null,
			new LawAiProperties.Qdrant(baseUrl, lawCollection, ragCollection, 1536),
			null,
			null
		);
		return new QdrantClient(properties, new ObjectMapper());
	}

	private String readyCollection(int vectorSize, long pointsCount) {
		return collectionWithQueue("green", vectorSize, pointsCount, 0, pointsCount, 1);
	}

	private String collection(String status, int vectorSize, long pointsCount) {
		return """
			{"result":{"status":"%s","points_count":%d,"config":{"params":{"vectors":{"size":%d,"distance":"Cosine"}}}}}
			""".formatted(status, pointsCount, vectorSize);
	}

	private String collectionWithQueue(
		String status,
		int vectorSize,
		long pointsCount,
		long updateQueueLength,
		long indexedVectorsCount,
		int segmentsCount
	) {
		return """
			{"result":{"status":"%s","points_count":%d,"indexed_vectors_count":%d,"segments_count":%d,
			"update_queue":{"length":%d},"config":{"params":{"vectors":{"size":%d,"distance":"Cosine"}}}}}
			""".formatted(status, pointsCount, indexedVectorsCount, segmentsCount, updateQueueLength, vectorSize);
	}

	private void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
