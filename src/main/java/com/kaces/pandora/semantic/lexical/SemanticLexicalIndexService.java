package com.kaces.pandora.semantic.lexical;

import com.kaces.pandora.semantic.config.LawAiLexicalProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SemanticLexicalIndexService {

	private static final int WRITE_BATCH_SIZE = 500;
	private final SemanticLexicalMapper mapper;
	private final KoreanLexicalTokenizer tokenizer;
	private final Supplier<String> versionSupplier;
	private final TransactionTemplate transactionTemplate;
	private final List<Field> fields;
    private org.springframework.beans.factory.ObjectProvider<com.kaces.pandora.rag.recovery.RagRecoveryCorpusRefreshService> recoveryRefresh;
    @Autowired
    void configureRecoveryRefresh(org.springframework.beans.factory.ObjectProvider<com.kaces.pandora.rag.recovery.RagRecoveryCorpusRefreshService> refresh) {
        recoveryRefresh=refresh;
    }

	public SemanticLexicalIndexService(SemanticLexicalMapper mapper, KoreanLexicalTokenizer tokenizer) {
		this(mapper, tokenizer, () -> UUID.randomUUID().toString(), null, defaultLexical());
	}

	@Autowired
	public SemanticLexicalIndexService(
		SemanticLexicalMapper mapper,
		KoreanLexicalTokenizer tokenizer,
		PlatformTransactionManager transactionManager,
		LawAiLexicalProperties properties
	) {
		this(
			mapper, tokenizer, () -> UUID.randomUUID().toString(),
			new TransactionTemplate(transactionManager), properties
		);
	}

	SemanticLexicalIndexService(
		SemanticLexicalMapper mapper,
		KoreanLexicalTokenizer tokenizer,
		Supplier<String> versionSupplier
	) {
		this(mapper, tokenizer, versionSupplier, null, defaultLexical());
	}

	SemanticLexicalIndexService(
		SemanticLexicalMapper mapper,
		KoreanLexicalTokenizer tokenizer,
		Supplier<String> versionSupplier,
		LawAiLexicalProperties properties
	) {
		this(mapper, tokenizer, versionSupplier, null, properties);
	}

	private SemanticLexicalIndexService(
		SemanticLexicalMapper mapper,
		KoreanLexicalTokenizer tokenizer,
		Supplier<String> versionSupplier,
		TransactionTemplate transactionTemplate,
		LawAiLexicalProperties properties
	) {
		this.mapper = mapper;
		this.tokenizer = tokenizer;
		this.versionSupplier = versionSupplier;
		this.transactionTemplate = transactionTemplate;
		this.fields = List.of(
			new Field("document_title", properties.documentTitleWeight(), LexicalChunkDocument::documentTitle),
			new Field("parent_title", properties.parentTitleWeight(), LexicalChunkDocument::parentTitle),
			new Field("chunk_title", properties.chunkTitleWeight(), LexicalChunkDocument::chunkTitle),
			new Field("body", properties.bodyWeight(), LexicalChunkDocument::body)
		);
	}

	public String currentRevision() {
		try {
            if(recoveryRefresh!=null) {
                var refresh=recoveryRefresh.getObject();
                if(refresh.isRequired())return refresh.currentRevision();
            }
			return mapper.findReadyRevision();
		} catch (RuntimeException exception) {
			return null;
		}
	}

	public boolean recoverAbandonedBuild(String indexVersion) {
		if (indexVersion == null || !indexVersion.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
			throw new IllegalArgumentException("A canonical index version is required.");
		}
		return mapper.markIndexFailed(indexVersion) == 1;
	}

	public BuildResult rebuild() {
        if(recoveryRefresh!=null) {
            var refresh=recoveryRefresh.getObject();
            if(refresh.isRequired())return refresh.rebuild();
        }
		String indexVersion = versionSupplier.get();
		List<LexicalChunkDocument> documents = mapper.findActiveSearchableChunks();
		if (documents == null) {
			documents = List.of();
		}
		return rebuild(indexVersion, documents, false);
	}

	/** Builds a full corpus snapshot without making it the ordinary READY revision. */
	public BuildResult rebuildRecovery(String generationId, List<LexicalChunkDocument> documents) {
		if (generationId == null || !generationId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
			throw new IllegalArgumentException("A canonical generation is required.");
		}
		List<LexicalChunkDocument> snapshot = List.copyOf(documents);
		if (snapshot.isEmpty() || snapshot.stream().map(row -> row.target() + ":" + row.chunkId()).distinct().count() != snapshot.size()) {
			throw new IllegalArgumentException("A nonempty snapshot with unique identities is required.");
		}
		return rebuild("recovery_" + generationId, snapshot, true);
	}

	private BuildResult rebuild(String indexVersion, List<LexicalChunkDocument> documents, boolean recovery) {
		mapper.insertIndexState(new SemanticLexicalMapper.IndexStateRow(
			indexVersion,
			tokenizer.version(),
			0,
			0.0,
			null,
			"BUILDING",
			null
		));
		try {
			return build(indexVersion, documents, recovery);
		} catch (RuntimeException exception) {
			try {
				mapper.markIndexFailed(indexVersion);
			} catch (RuntimeException failureUpdate) {
				exception.addSuppressed(failureUpdate);
			}
			throw exception;
		}
	}

	private BuildResult build(String indexVersion, List<LexicalChunkDocument> documents, boolean recovery) {
		long totalWeightedLength = 0;
		int termRows = 0;
		for (int start = 0; start < documents.size(); start += WRITE_BATCH_SIZE) {
			int end = Math.min(start + WRITE_BATCH_SIZE, documents.size());
			PreparedBatch prepared = prepareBatch(documents.subList(start, end));
			totalWeightedLength += prepared.weightedLength();
			mapper.insertChunks(indexVersion, prepared.chunks());
			writeBatches(prepared.terms(), batch -> mapper.insertTerms(indexVersion, batch));
			termRows += prepared.terms().size();
		}
		mapper.populateTermStats(indexVersion);

		double averageWeightedLength = documents.isEmpty()
			? 0.0
			: (double) totalWeightedLength / documents.size();
		String fingerprint = recovery ? recoveryFingerprint(documents) : contentFingerprint(documents);
		publish(indexVersion, fingerprint, documents.size(), termRows, averageWeightedLength, recovery);
		return new BuildResult(
			indexVersion,
			fingerprint,
			documents.size(),
			termRows,
			averageWeightedLength
		);
	}

	/** Bounded full readback; no ordinary READY revision or baseline asset is changed. */
	public String verifyRecovery(String generationId, List<LexicalChunkDocument> documents) {
		if (generationId == null || !generationId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
			throw new IllegalArgumentException("A canonical generation is required.");
		}
		List<LexicalChunkDocument> snapshot = List.copyOf(documents);
		if (snapshot.isEmpty() || snapshot.stream().map(row -> row.target() + ":" + row.chunkId()).distinct().count() != snapshot.size()) {
			throw new IllegalArgumentException("A nonempty snapshot with unique identities is required.");
		}
		String version = "recovery_" + generationId;
		String fingerprint = recoveryFingerprint(snapshot);
		var before = mapper.findRecoveryState(version);
		if (before == null || !fingerprint.equals(before.contentFingerprint()) || !tokenizer.version().equals(before.tokenizerVersion())
				|| before.activeChunkCount() != snapshot.size()) throw new IllegalStateException("Recovery lexical state mismatch.");
		long termCount = 0, weightedLength = 0;
		for (int start = 0; start < snapshot.size(); start += WRITE_BATCH_SIZE) {
			var batch = snapshot.subList(start, Math.min(start + WRITE_BATCH_SIZE, snapshot.size()));
			var expected = prepareBatch(batch);
			var chunks = mapper.findRecoveryChunks(version, batch);
			var terms = mapper.findRecoveryTerms(version, batch);
			if (chunks == null || terms == null || chunks.size() != expected.chunks().size() || terms.size() != expected.terms().size()
					|| !new java.util.HashSet<>(chunks).equals(new java.util.HashSet<>(expected.chunks()))
					|| !new java.util.HashSet<>(terms).equals(new java.util.HashSet<>(expected.terms()))) {
				throw new IllegalStateException("Recovery lexical rows differ from prepared corpus.");
			}
			termCount += expected.terms().size(); weightedLength += expected.weightedLength();
		}
		var counts = mapper.recoveryCounts(version);
		if (counts == null || counts.chunkCount() != snapshot.size() || counts.termCount() != termCount || counts.statisticMismatches() != 0
				|| Double.compare(before.averageWeightedLength(), (double) weightedLength / snapshot.size()) != 0
				|| !before.equals(mapper.findRecoveryState(version))) {
			throw new IllegalStateException("Recovery lexical parity or statistics changed.");
		}
		return fingerprint;
	}

	private PreparedBatch prepareBatch(List<LexicalChunkDocument> documents) {
		var chunks = new ArrayList<SemanticLexicalMapper.ChunkRow>(documents.size());
		var terms = new ArrayList<SemanticLexicalMapper.TermRow>();
		long total = 0;
		for (var document : documents) {
			int length = 0;
			for (var field : fields) {
				for (var frequency : tokenizer.tokenize(field.reader().read(document)).entrySet()) {
					terms.add(new SemanticLexicalMapper.TermRow(document.target(), document.chunkId(), frequency.getKey(),
						field.kind(), frequency.getValue(), field.weight()));
					length += frequency.getValue() * field.weight();
				}
			}
			total += length;
			chunks.add(new SemanticLexicalMapper.ChunkRow(document.target(), document.chunkId(), document.documentId(),
				document.parentKey(), document.contentHash(), length));
		}
		return new PreparedBatch(chunks, terms, total);
	}

	private record PreparedBatch(List<SemanticLexicalMapper.ChunkRow> chunks, List<SemanticLexicalMapper.TermRow> terms, long weightedLength) { }

	private void publish(
		String indexVersion,
		String fingerprint,
		int activeChunkCount,
		int termCount,
		double averageWeightedLength,
		boolean recovery
	) {
		Runnable publication = () -> {
			mapper.markChunksReady(indexVersion);
			if (recovery) {
                if(mapper.recordRecoveryIntegrity(indexVersion,activeChunkCount,termCount)!=1)
                    throw new IllegalStateException("Recovery integrity receipt publication failed");
				if (mapper.markRecoveryIndexReady(indexVersion, fingerprint, activeChunkCount, averageWeightedLength) != 1) {
					throw new IllegalStateException("Recovery lexical publication failed.");
				}
			} else {
				mapper.markIndexReady(indexVersion, fingerprint, activeChunkCount, averageWeightedLength);
			}
		};
		if (transactionTemplate == null) {
			publication.run();
			return;
		}
		transactionTemplate.executeWithoutResult(status -> publication.run());
	}

	private <T> void writeBatches(List<T> rows, BatchWriter<T> writer) {
		for (int start = 0; start < rows.size(); start += WRITE_BATCH_SIZE) {
			int end = Math.min(start + WRITE_BATCH_SIZE, rows.size());
			writer.write(List.copyOf(rows.subList(start, end)));
		}
	}

	private String contentFingerprint(List<LexicalChunkDocument> documents) {
		StringBuilder canonical = new StringBuilder(tokenizer.version()).append('\n');
		documents.stream()
			.sorted(Comparator.comparing(LexicalChunkDocument::target)
				.thenComparingLong(LexicalChunkDocument::chunkId))
			.forEach(document -> canonical
				.append(document.target()).append('\t')
				.append(document.chunkId()).append('\t')
				.append(document.documentId()).append('\t')
				.append(nullToEmpty(document.parentKey())).append('\t')
				.append(nullToEmpty(document.contentHash())).append('\n'));
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private String recoveryFingerprint(List<LexicalChunkDocument> documents) {
		var canonical = new ArrayList<Object>();
		canonical.add(tokenizer.version());
		canonical.add(fields.stream().map(field -> List.of(field.kind(), field.weight())).toList());
		canonical.add(documents.stream().sorted(Comparator.comparing(LexicalChunkDocument::target)
			.thenComparingLong(LexicalChunkDocument::chunkId)).toList());
		return com.kaces.pandora.common.text.LawHashUtils.sha256Json(canonical);
	}

	private String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private static LawAiLexicalProperties defaultLexical() {
		return new LawAiLexicalProperties(1.2, 0.75, 8, 6, 7, 1, 24, 100);
	}

	public record BuildResult(
		String indexVersion,
		String contentFingerprint,
		int activeChunkCount,
		int termRowCount,
		double averageWeightedLength
	) {
	}

	private record Field(String kind, int weight, FieldReader reader) {
	}

	@FunctionalInterface
	private interface FieldReader {
		String read(LexicalChunkDocument document);
	}

	@FunctionalInterface
	private interface BatchWriter<T> {
		void write(List<T> rows);
	}
}
