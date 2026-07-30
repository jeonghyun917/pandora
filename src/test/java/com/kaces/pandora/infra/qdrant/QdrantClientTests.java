package com.kaces.pandora.infra.qdrant;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.semantic.config.LawAiProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class QdrantClientTests {

	private HttpServer server;
	private QdrantClient client;

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

	private void startServer(int ragStatus, String ragBody) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law", exchange -> respond(exchange, 200, readyCollection(1536, 10)));
		server.createContext("/collections/rag", exchange -> respond(exchange, ragStatus, ragBody));
		server.start();
	}

	private void startSearchServer(int status, String body) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law/points/search", exchange -> respond(exchange, status, body));
		server.start();
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
