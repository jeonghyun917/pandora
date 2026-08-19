package com.kaces.pandora.semantic.lexical;

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
	private static final List<Field> FIELDS = List.of(
		new Field("document_title", 8, LexicalChunkDocument::documentTitle),
		new Field("parent_title", 6, LexicalChunkDocument::parentTitle),
		new Field("chunk_title", 7, LexicalChunkDocument::chunkTitle),
		new Field("body", 1, LexicalChunkDocument::body)
	);

	private final SemanticLexicalMapper mapper;
	private final KoreanLexicalTokenizer tokenizer;
	private final Supplier<String> versionSupplier;
	private final TransactionTemplate transactionTemplate;

	public SemanticLexicalIndexService(SemanticLexicalMapper mapper, KoreanLexicalTokenizer tokenizer) {
		this(mapper, tokenizer, () -> UUID.randomUUID().toString(), null);
	}

	@Autowired
	public SemanticLexicalIndexService(
		SemanticLexicalMapper mapper,
		KoreanLexicalTokenizer tokenizer,
		PlatformTransactionManager transactionManager
	) {
		this(mapper, tokenizer, () -> UUID.randomUUID().toString(), new TransactionTemplate(transactionManager));
	}

	SemanticLexicalIndexService(
		SemanticLexicalMapper mapper,
		KoreanLexicalTokenizer tokenizer,
		Supplier<String> versionSupplier
	) {
		this(mapper, tokenizer, versionSupplier, null);
	}

	private SemanticLexicalIndexService(
		SemanticLexicalMapper mapper,
		KoreanLexicalTokenizer tokenizer,
		Supplier<String> versionSupplier,
		TransactionTemplate transactionTemplate
	) {
		this.mapper = mapper;
		this.tokenizer = tokenizer;
		this.versionSupplier = versionSupplier;
		this.transactionTemplate = transactionTemplate;
	}

	public String currentRevision() {
		try {
			return mapper.findReadyRevision();
		} catch (RuntimeException exception) {
			return null;
		}
	}

	public BuildResult rebuild() {
		String indexVersion = versionSupplier.get();
		List<LexicalChunkDocument> documents = mapper.findActiveSearchableChunks();
		if (documents == null) {
			documents = List.of();
		}
		mapper.insertIndexState(new SemanticLexicalMapper.IndexStateRow(
			indexVersion,
			tokenizer.version(),
			0,
			0.0,
			null,
			"BUILDING",
			null
		));

		long totalWeightedLength = 0;
		int termRows = 0;
		for (int start = 0; start < documents.size(); start += WRITE_BATCH_SIZE) {
			int end = Math.min(start + WRITE_BATCH_SIZE, documents.size());
			List<SemanticLexicalMapper.ChunkRow> chunks = new ArrayList<>(end - start);
			List<SemanticLexicalMapper.TermRow> terms = new ArrayList<>();
			for (LexicalChunkDocument document : documents.subList(start, end)) {
				int weightedLength = 0;
				for (Field field : FIELDS) {
					Map<String, Integer> frequencies = tokenizer.tokenize(field.reader().read(document));
					for (Map.Entry<String, Integer> frequency : frequencies.entrySet()) {
						terms.add(new SemanticLexicalMapper.TermRow(
							document.target(), document.chunkId(), frequency.getKey(), field.kind(),
							frequency.getValue(), field.weight()
						));
						weightedLength += frequency.getValue() * field.weight();
					}
				}
				totalWeightedLength += weightedLength;
				chunks.add(new SemanticLexicalMapper.ChunkRow(
					document.target(), document.chunkId(), document.documentId(), document.parentKey(),
					document.contentHash(), weightedLength
				));
			}
			mapper.insertChunks(indexVersion, chunks);
			writeBatches(terms, batch -> mapper.insertTerms(indexVersion, batch));
			termRows += terms.size();
		}
		mapper.populateTermStats(indexVersion);

		double averageWeightedLength = documents.isEmpty()
			? 0.0
			: (double) totalWeightedLength / documents.size();
		String fingerprint = contentFingerprint(documents);
		publish(indexVersion, fingerprint, documents.size(), averageWeightedLength);
		return new BuildResult(
			indexVersion,
			fingerprint,
			documents.size(),
			termRows,
			averageWeightedLength
		);
	}

	private void publish(
		String indexVersion,
		String fingerprint,
		int activeChunkCount,
		double averageWeightedLength
	) {
		Runnable publication = () -> {
			mapper.markChunksReady(indexVersion);
			mapper.markIndexReady(indexVersion, fingerprint, activeChunkCount, averageWeightedLength);
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

	private String nullToEmpty(String value) {
		return value == null ? "" : value;
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
