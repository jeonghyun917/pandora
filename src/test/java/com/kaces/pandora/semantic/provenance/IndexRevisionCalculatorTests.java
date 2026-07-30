package com.kaces.pandora.semantic.provenance;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.infra.qdrant.QdrantIndexSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndexRevisionCalculatorTests {

	@Test
	void createsStableCanonicalRevisionIndependentOfInputOrder() {
		IndexRevisionCollection law = collection("law", "law_chunks", 20, fingerprint('a'), "2026-07-15T01:02:03.000000");
		IndexRevisionCollection rag = collection("rag", "rag_chunks_v4", 10, fingerprint('b'), "2026-07-15T01:02:04.000000");

		String first = IndexRevisionCalculator.calculate("text-embedding-3-small", List.of(law, rag));
		String second = IndexRevisionCalculator.calculate("text-embedding-3-small", List.of(rag, law));

		assertThat(first).matches("[0-9a-f]{64}");
		assertThat(second).isEqualTo(first);
	}

	@Test
	void sameCountContentReplacementChangesRevision() {
		IndexRevisionCollection before = collection("law", "law_chunks", 20, fingerprint('a'), "2026-07-15T01:02:03.000000");
		IndexRevisionCollection after = collection("law", "law_chunks", 20, fingerprint('c'), "2026-07-15T01:02:05.000000");

		assertThat(IndexRevisionCalculator.calculate("text-embedding-3-small", List.of(after)))
			.isNotEqualTo(IndexRevisionCalculator.calculate("text-embedding-3-small", List.of(before)));
	}

	@Test
	void optimizerCountersDoNotChangeRevision() {
		IndexContentSnapshot database = database(20, fingerprint('a'), "2026-07-15T01:02:03.000000");
		QdrantIndexSnapshot before = qdrant("law_chunks", 20, 19, 3);
		QdrantIndexSnapshot after = qdrant("law_chunks", 20, 20, 9);

		String first = IndexRevisionCalculator.calculate(
			"text-embedding-3-small",
			List.of(new IndexRevisionCollection("law", "law_chunks", database, before))
		);
		String second = IndexRevisionCalculator.calculate(
			"text-embedding-3-small",
			List.of(new IndexRevisionCollection("law", "law_chunks", database, after))
		);

		assertThat(second).isEqualTo(first);
	}

	@Test
	void countMismatchAndUnstableQdrantSnapshotsAreUnavailable() {
		IndexContentSnapshot database = database(20, fingerprint('a'), "2026-07-15T01:02:03.000000");

		assertThat(IndexRevisionCalculator.calculate(
			"text-embedding-3-small",
			List.of(new IndexRevisionCollection("law", "law_chunks", database, qdrant("law_chunks", 19, 19, 3)))
		)).isNull();
		assertThat(IndexRevisionCalculator.calculate(
			"text-embedding-3-small",
			List.of(new IndexRevisionCollection(
				"law",
				"law_chunks",
				database,
				new QdrantIndexSnapshot("law_chunks", "red", 0, 20, 1536, "Cosine", 20, 3)
			))
		)).isNull();
		assertThat(IndexRevisionCalculator.calculate(
			"text-embedding-3-small",
			List.of(new IndexRevisionCollection(
				"law",
				"law_chunks",
				database,
				new QdrantIndexSnapshot("law_chunks", "green", 1, 20, 1536, "Cosine", 20, 3)
			))
		)).isNull();
	}

	@Test
	void malformedDatabaseSnapshotIsUnavailable() {
		IndexContentSnapshot malformed = database(20, "not-a-fingerprint", "");

		assertThat(IndexRevisionCalculator.calculate(
			"text-embedding-3-small",
			List.of(new IndexRevisionCollection("law", "law_chunks", malformed, qdrant("law_chunks", 20, 20, 3)))
		)).isNull();
	}

	private IndexRevisionCollection collection(
		String role,
		String collection,
		long count,
		String fingerprint,
		String watermark
	) {
		return new IndexRevisionCollection(
			role,
			collection,
			database(count, fingerprint, watermark),
			qdrant(collection, count, count, 4)
		);
	}

	private IndexContentSnapshot database(long count, String fingerprint, String watermark) {
		return new IndexContentSnapshot(count, fingerprint, watermark);
	}

	private QdrantIndexSnapshot qdrant(String collection, long count, long indexedVectors, int segments) {
		return new QdrantIndexSnapshot(collection, "green", 0, count, 1536, "Cosine", indexedVectors, segments);
	}

	private String fingerprint(char value) {
		return String.valueOf(value).repeat(64);
	}
}
