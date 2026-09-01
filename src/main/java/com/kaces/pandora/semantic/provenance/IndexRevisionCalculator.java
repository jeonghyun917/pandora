package com.kaces.pandora.semantic.provenance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class IndexRevisionCalculator {
	private static final String SCHEMA_VERSION = "pandora-index-revision-v2";

	private IndexRevisionCalculator() {
	}

	public static String calculate(String embeddingModel, List<IndexRevisionCollection> collections) {
		if (embeddingModel == null || embeddingModel.isBlank() || collections == null || collections.isEmpty()) {
			return null;
		}
		List<IndexRevisionCollection> ordered = collections.stream()
			.filter(value -> value != null)
			.sorted(Comparator.comparing(IndexRevisionCollection::role)
				.thenComparing(IndexRevisionCollection::collection))
			.toList();
		if (ordered.size() != collections.size() || ordered.stream().anyMatch(value -> !value.isUsable())) {
			return null;
		}
		Set<String> roles = new HashSet<>();
		Set<String> collectionNames = new HashSet<>();
		for (IndexRevisionCollection collection : ordered) {
			if (!roles.add(collection.role()) || !collectionNames.add(collection.collection())) {
				return null;
			}
		}

		StringBuilder canonical = new StringBuilder();
		append(canonical, "schema", SCHEMA_VERSION);
		append(canonical, "embeddingModel", embeddingModel);
		for (IndexRevisionCollection collection : ordered) {
			String prefix = "collection." + collection.role() + ".";
			append(canonical, prefix + "name", collection.collection());
			append(canonical, prefix + "db.currentIndexedCount", collection.database().currentIndexedCount());
			append(canonical, prefix + "db.contentFingerprint",
				collection.database().contentFingerprint().toLowerCase(Locale.ROOT));
			append(canonical, prefix + "qdrant.exactPointCount", collection.qdrant().exactPointCount());
			append(canonical, prefix + "qdrant.vectorSize", collection.qdrant().vectorSize());
			append(canonical, prefix + "qdrant.distance", collection.qdrant().distance().toUpperCase(Locale.ROOT));
		}
		return sha256(canonical.toString());
	}

	private static void append(StringBuilder canonical, String key, Object value) {
		String text = String.valueOf(value);
		canonical.append(key)
			.append('=')
			.append(text.length())
			.append(':')
			.append(text)
			.append('\n');
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}
}
