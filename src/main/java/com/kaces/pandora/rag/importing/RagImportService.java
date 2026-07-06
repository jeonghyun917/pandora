package com.kaces.pandora.rag.importing;


import com.kaces.pandora.rag.chunk.RagChunker;
import com.kaces.pandora.rag.document.RagDocumentMeta;
import com.kaces.pandora.rag.document.RagDocumentType;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.rag.document.RagDocumentChunkRow;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.document.RagDocumentRow;
import com.kaces.pandora.rag.importing.RagImportJobKey;
import com.kaces.pandora.semantic.config.LawAiProperties;
import com.kaces.pandora.infra.openai.OpenAiEmbeddingClient;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Service
public class RagImportService {
	private static final List<String> SUPPORTED_EXTENSIONS = List.of(".pdf", ".docx", ".hwpx", ".txt", ".md");
	private static final int ACTIVE_CHUNK_VERSION = RagChunker.V4_CHUNK_VERSION;
	private static final Pattern DOCUMENT_DATE_PATTERN = Pattern.compile("^\\d{4}[.\\-/]\\s*\\d{1,2}(?:[.\\-/]\\s*\\d{1,2})?\\.?$");
	private static final int TITLE_SCAN_LINE_LIMIT = 8;
	private static final int TITLE_LINE_LIMIT = 3;

	private final RagDocumentMapper mapper;
	private final RagTextExtractor textExtractor;
	private final RagChunker chunker;
	private final OpenAiEmbeddingClient embeddingClient;
	private final QdrantClient qdrantClient;
	private final LawAiProperties properties;
	private final ObjectMapper objectMapper;

	public RagImportService(
		RagDocumentMapper mapper,
		RagTextExtractor textExtractor,
		RagChunker chunker,
		OpenAiEmbeddingClient embeddingClient,
		QdrantClient qdrantClient,
		LawAiProperties properties,
		ObjectMapper objectMapper
	) {
		this.mapper = mapper;
		this.textExtractor = textExtractor;
		this.chunker = chunker;
		this.embeddingClient = embeddingClient;
		this.qdrantClient = qdrantClient;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	// 메소드 설명: importFolder 처리 흐름을 수행합니다.
	public RagImportResponse importFolder(String documentType, String pathValue) {
		return importFolder(documentType, pathValue, true);
	}

	// 메소드 설명: importFolder 처리 흐름을 수행합니다.
	public RagImportResponse importFolder(String documentType, String pathValue, boolean indexNow) {
		return importFolder(documentType, pathValue, indexNow, false);
	}

	public RagImportResponse importFolder(String documentType, String pathValue, boolean indexNow, boolean force) {
		String requestedType = documentType == null || documentType.isBlank() ? "" : RagDocumentType.normalize(documentType);
		Path root = resolveImportPath(requestedType, pathValue);
		RagImportJobKey jobKey = new RagImportJobKey();
		mapper.insertImportJob(jobKey, root.toAbsolutePath().toString(), requestedType.isBlank() ? null : requestedType);
		long jobId = jobKey.getImportJobId();

		int discovered = 0;
		int imported = 0;
		int skipped = 0;
		int failed = 0;
		int indexed = 0;
		String lastError = null;

		try {
			List<Path> candidateFiles;
			if (Files.isRegularFile(root)) {
				candidateFiles = isSupported(root) ? List.of(root) : List.of();
			} else {
				Files.createDirectories(root);
				candidateFiles = discoverCandidateFiles(root);
			}
			List<Path> files = selectPreferredFiles(candidateFiles);
			deactivateUnpreferredDuplicateDocuments(candidateFiles, files);
			discovered = files.size();
			for (Path file : files) {
				try {
					ImportOutcome outcome = importFile(file, requestedType, indexNow, force);
					if (outcome.skipped()) {
						skipped++;
					} else {
						imported++;
						indexed += outcome.indexedCount();
					}
				} catch (Exception exception) {
					failed++;
					lastError = exception.getMessage();
				}
			}
			String status = failed > 0 ? "PARTIAL_SUCCESS" : "SUCCESS";
			mapper.finishImportJob(jobId, status, discovered, imported, skipped, failed, indexed, lastError);
			return new RagImportResponse(jobId, status, root.toString(), requestedType, discovered, imported, skipped, failed, indexed, lastError);
		} catch (Exception exception) {
			lastError = exception.getMessage();
			mapper.finishImportJob(jobId, "FAILED", discovered, imported, skipped, failed, indexed, lastError);
			return new RagImportResponse(jobId, "FAILED", root.toString(), requestedType, discovered, imported, skipped, failed, indexed, lastError);
		}
	}

	public RagImportResponse reimportExistingDocuments(String documentType, boolean indexNow, boolean force) {
		String requestedType = documentType == null || documentType.isBlank() ? "" : RagDocumentType.normalize(documentType);
		String importPath = "existing-db:" + (requestedType.isBlank() ? "all-rag-documents" : requestedType);
		RagImportJobKey jobKey = new RagImportJobKey();
		mapper.insertImportJob(jobKey, importPath, requestedType.isBlank() ? null : requestedType);
		long jobId = jobKey.getImportJobId();

		int imported = 0;
		int skipped = 0;
		int failed = 0;
		int indexed = 0;
		String lastError = null;
		List<RagDocumentRow> documents = mapper.findDocumentsForReimport(requestedType);
		for (RagDocumentRow document : documents) {
			try {
				String targetType = requestedType.isBlank() ? document.documentType() : requestedType;
				ImportOutcome outcome = importFile(Path.of(document.filePath()), targetType, indexNow, force);
				if (outcome.skipped()) {
					skipped++;
				} else {
					imported++;
					indexed += outcome.indexedCount();
				}
			} catch (Exception exception) {
				failed++;
				lastError = exception.getMessage();
			}
		}
		String status = failed > 0 ? "PARTIAL_SUCCESS" : "SUCCESS";
		mapper.finishImportJob(jobId, status, documents.size(), imported, skipped, failed, indexed, lastError);
		return new RagImportResponse(jobId, status, importPath, requestedType, documents.size(), imported, skipped, failed, indexed, lastError);
	}

	@Transactional
	// 메소드 설명: importFile 처리 흐름을 수행합니다.
	protected ImportOutcome importFile(Path file, String requestedType) throws IOException {
		return importFile(file, requestedType, true);
	}

	@Transactional
	// 메소드 설명: importFile 처리 흐름을 수행합니다.
	protected ImportOutcome importFile(Path file, String requestedType, boolean indexNow) throws IOException {
		return importFile(file, requestedType, indexNow, false);
	}

	@Transactional
	protected ImportOutcome importFile(Path file, String requestedType, boolean indexNow, boolean force) throws IOException {
		String fileHash = sha256(file);
		RagDocumentRow existing = mapper.findDocumentByHash(fileHash);
		if (!force && existing != null) {
			int activeChunkCount = mapper.countActiveChunksByDocumentIdAndVersion(existing.documentId(), ACTIVE_CHUNK_VERSION);
			if (activeChunkCount > 0) {
				return new ImportOutcome(true, 0);
			}
		}

		RagDocumentMeta meta = readMeta(file);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		ExtractedDocument extracted = textExtractor.extract(file);
		if (extracted.text().isBlank()) {
			throw new IllegalStateException("No text was extracted from " + file.getFileName());
		}
		String documentType = requestedType.isBlank()
			? RagDocumentType.normalize(meta.documentType())
			: requestedType;
		String title = resolveTitle(meta, extracted, file.getFileName().toString());
		int trustLevel = meta.trustLevel() == null ? defaultTrustLevel(documentType) : meta.trustLevel();
		if (trustLevel != 1 && trustLevel != 5) {
			trustLevel = defaultTrustLevel(documentType);
		}

		mapper.upsertDocument(
			documentType,
			title,
			meta.sourceOrg(),
			meta.documentCategory(),
			meta.documentTopic(),
			meta.publishedDate(),
			meta.version(),
			trustLevel,
			file.getFileName().toString(),
			file.toAbsolutePath().toString(),
			fileHash,
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			Files.probeContentType(file),
			meta.sourceUrl(),
			"IMPORTED",
			null
		);
		long documentId = mapper.findDocumentIdByHash(fileHash);
		List<Long> oldChunkIds = mapper.findChunkIdsByDocumentId(documentId);
		markEmbeddingsSuperseded(oldChunkIds);
		mapper.deactivateChunksByDocumentIdAndVersion(documentId, ACTIVE_CHUNK_VERSION);
		List<RagDocumentChunkRow> chunks = chunker.chunkV4(documentId, extracted, meta.sourceUrl(), title);
		for (RagDocumentChunkRow chunk : chunks) {
			mapper.insertChunk(chunk);
		}
		if (!indexNow) {
			return new ImportOutcome(false, 0);
		}
		int indexed = indexDocumentChunks(documentId);
		deleteOldQdrantPointsAfterCommit(oldChunkIds);
		mapper.upsertDocument(
			documentType,
			title,
			meta.sourceOrg(),
			meta.documentCategory(),
			meta.documentTopic(),
			meta.publishedDate(),
			meta.version(),
			trustLevel,
			file.getFileName().toString(),
			file.toAbsolutePath().toString(),
			fileHash,
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			Files.probeContentType(file),
			meta.sourceUrl(),
			"INDEXED",
			null
		);
		return new ImportOutcome(false, indexed);
	}

	private void deleteOldQdrantPointsAfterCommit(List<Long> oldChunkIds) {
		if (oldChunkIds == null || oldChunkIds.isEmpty()) {
			return;
		}
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			qdrantClient.deleteRagPointsBestEffort(oldChunkIds);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				qdrantClient.deleteRagPointsBestEffort(oldChunkIds);
			}
		});
	}

	// 메소드 설명: indexDocumentChunks 처리 흐름을 수행합니다.
	private int indexDocumentChunks(long documentId) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		qdrantClient.ensureRagCollection();
		String model = properties.openai().embeddingModel();
		String vectorStore = properties.qdrant().ragCollection();
		int batchSize = properties.rag().importBatchSize();
		int indexed = 0;
		List<LawSemanticChunkRow> chunks = mapper.findSemanticIndexChunksByDocumentId(documentId, ACTIVE_CHUNK_VERSION);
		for (int start = 0; start < chunks.size(); start += batchSize) {
			int end = Math.min(chunks.size(), start + batchSize);
			List<LawSemanticChunkRow> batch = chunks.subList(start, end);
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			List<List<Double>> vectors = embeddingClient.embed(batch.stream().map(LawSemanticChunkRow::embeddingInput).toList());
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			qdrantClient.upsertRag(batch, vectors);
			for (LawSemanticChunkRow chunk : batch) {
				mapper.upsertEmbeddingStatus(
					chunk.chunkId(),
					model,
					vectorStore,
					String.valueOf(QdrantClient.ragPointId(chunk.chunkId())),
					chunk.contentHash(),
					"INDEXED",
					null
				);
			}
			indexed += batch.size();
		}
		return indexed;
	}

	// 메소드 설명: resolveImportPath 처리 흐름을 수행합니다.
	private Path resolveImportPath(String documentType, String pathValue) {
		if (pathValue != null && !pathValue.isBlank()) {
			return Path.of(pathValue).toAbsolutePath().normalize();
		}
		Path root = Path.of(properties.rag().uploadRoot());
		return documentType == null || documentType.isBlank()
			? root.toAbsolutePath().normalize()
			: root.resolve(documentType).toAbsolutePath().normalize();
	}

	// 메소드 설명: discoverFiles 처리 흐름을 수행합니다.
	private List<Path> discoverCandidateFiles(Path root) throws IOException {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		try (var stream = Files.walk(root)) {
			return stream
				.filter(Files::isRegularFile)
				.filter(path -> !path.getFileName().toString().toLowerCase().endsWith(".meta.json"))
				.filter(this::isSupported)
				.sorted(Comparator.comparing(Path::toString))
				.toList();
		}
	}

	static List<Path> selectPreferredFiles(List<Path> files) {
		if (files == null || files.isEmpty()) {
			return List.of();
		}
		Map<String, List<Path>> byDuplicateKey = new LinkedHashMap<>();
		for (Path file : files) {
			byDuplicateKey.computeIfAbsent(duplicateKey(file), ignored -> new ArrayList<>()).add(file);
		}
		return byDuplicateKey.values().stream()
			.map(RagImportService::preferredFile)
			.sorted(Comparator.comparing(RagImportService::absolutePathString))
			.toList();
	}

	private void deactivateUnpreferredDuplicateDocuments(List<Path> candidates, List<Path> selected) {
		if (candidates == null || candidates.isEmpty()) {
			return;
		}
		Set<String> selectedPaths = selected == null ? Set.of() : selected.stream()
			.map(RagImportService::absolutePathString)
			.collect(java.util.stream.Collectors.toSet());
		List<String> unpreferredPaths = candidates.stream()
			.map(RagImportService::absolutePathString)
			.filter(path -> !selectedPaths.contains(path))
			.toList();
		if (unpreferredPaths.isEmpty()) {
			return;
		}
		List<Long> oldChunkIds = mapper.findActiveChunkIdsByFilePathsAndVersion(unpreferredPaths, ACTIVE_CHUNK_VERSION);
		markEmbeddingsSuperseded(oldChunkIds);
		mapper.deactivateChunksByFilePathsAndVersion(unpreferredPaths, ACTIVE_CHUNK_VERSION);
		mapper.deactivateDocumentsByFilePaths(unpreferredPaths);
		deleteOldQdrantPointsAfterCommit(oldChunkIds);
	}

	private void markEmbeddingsSuperseded(List<Long> chunkIds) {
		if (chunkIds == null || chunkIds.isEmpty()) {
			return;
		}
		mapper.markEmbeddingsSupersededByChunkIds(chunkIds);
	}

	private static Path preferredFile(List<Path> files) {
		return files.stream()
			.min(Comparator
				.comparingInt(RagImportService::extensionPreference)
				.thenComparing(RagImportService::absolutePathString))
			.orElseThrow();
	}

	private static int extensionPreference(Path path) {
		return switch (extensionOfPath(path)) {
			case ".pdf" -> 0;
			case ".docx" -> 1;
			case ".hwpx" -> 2;
			case ".txt" -> 3;
			case ".md" -> 4;
			default -> 9;
		};
	}

	private static String duplicateKey(Path path) {
		Path normalized = path.toAbsolutePath().normalize();
		Path parent = normalized.getParent();
		String fileName = normalized.getFileName().toString();
		return String.valueOf(parent) + "|" + normalizeDuplicateBase(stripExtensionStatic(fileName));
	}

	private static String normalizeDuplicateBase(String value) {
		return String.valueOf(value == null ? "" : value)
			.replaceAll("\\s+\\(\\d+\\)$", "")
			.replaceAll("\\s+", " ")
			.trim()
			.toLowerCase(Locale.ROOT);
	}

	private static String absolutePathString(Path path) {
		return path.toAbsolutePath().normalize().toString();
	}

	private static String extensionOfPath(Path path) {
		String fileName = path.getFileName().toString();
		int extensionIndex = fileName.lastIndexOf('.');
		return extensionIndex < 0 ? "" : fileName.substring(extensionIndex).toLowerCase(Locale.ROOT);
	}

	// 메소드 설명: isSupported 처리 흐름을 수행합니다.
	private boolean isSupported(Path path) {
		String fileName = path.getFileName().toString().toLowerCase();
		return SUPPORTED_EXTENSIONS.stream().anyMatch(fileName::endsWith);
	}

	// 메소드 설명: readMeta 처리 흐름을 수행합니다.
	private RagDocumentMeta readMeta(Path file) {
		Path metaFile = metaFile(file);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		if (!Files.exists(metaFile)) {
			return new RagDocumentMeta(null, null, null, null, null, null, null, null, null);
		}
		try {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			return objectMapper.readValue(metaFile.toFile(), RagDocumentMeta.class);
		} catch (Exception exception) {
			throw new IllegalStateException("Invalid metadata JSON: " + metaFile, exception);
		}
	}

	// 메소드 설명: metaFile 처리 흐름을 수행합니다.
	private Path metaFile(Path file) {
		String name = file.getFileName().toString();
		int extensionIndex = name.lastIndexOf('.');
		String baseName = extensionIndex < 0 ? name : name.substring(0, extensionIndex);
		return file.resolveSibling(baseName + ".meta.json");
	}

	// 메소드 설명: defaultTrustLevel 처리 흐름을 수행합니다.
	private int defaultTrustLevel(String documentType) {
		return RagDocumentType.REFERENCE_DOC.equals(documentType) ? 5 : 1;
	}

	// 메소드 설명: stripExtension 처리 흐름을 수행합니다.
	private String stripExtension(String fileName) {
		int extensionIndex = fileName.lastIndexOf('.');
		return extensionIndex < 0 ? fileName : fileName.substring(0, extensionIndex);
	}

	static String resolveTitle(RagDocumentMeta meta, ExtractedDocument extracted, String fileName) {
		String fileTitle = stripExtensionStatic(fileName).trim();
		if (shouldPreferFileNameTitle(meta, fileTitle)) {
			return fileTitle;
		}
		if (meta != null && meta.title() != null && !meta.title().isBlank()) {
			return meta.title().trim();
		}
		String extractedTitle = extractDisplayTitle(extracted);
		return extractedTitle.isBlank() || isSuspiciousTitle(extractedTitle)
			? stripExtensionStatic(fileName)
			: extractedTitle;
	}

	private static boolean shouldPreferFileNameTitle(RagDocumentMeta meta, String fileTitle) {
		if (meta == null || fileTitle == null || fileTitle.isBlank() || isGenericAttachmentTitle(fileTitle)) {
			return false;
		}
		return isCollectedDocumentMeta(meta);
	}

	private static boolean isCollectedDocumentMeta(RagDocumentMeta meta) {
		String category = String.valueOf(meta.documentCategory() == null ? "" : meta.documentCategory())
			.toLowerCase(Locale.ROOT);
		String topic = String.valueOf(meta.documentTopic() == null ? "" : meta.documentTopic())
			.toLowerCase(Locale.ROOT);
		return category.startsWith("ministry_doc")
			|| topic.startsWith("rss ")
			|| topic.startsWith("backfill ");
	}

	private static boolean isGenericAttachmentTitle(String title) {
		String normalized = title.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
		return normalized.matches("attachment\\d*")
			|| normalized.matches("첨부\\d*")
			|| normalized.matches("붙임\\d*");
	}

	// 메소드 설명: extractDisplayTitle 처리 흐름을 수행합니다.
	private static String extractDisplayTitle(ExtractedDocument extracted) {
		List<String> lines = extracted.pages().stream()
			.findFirst()
			.map(ExtractedPage::text)
			.orElse("")
			.lines()
			.map(String::trim)
			.filter(line -> !line.isBlank())
			.limit(TITLE_SCAN_LINE_LIMIT)
			.toList();
		if (lines.isEmpty()) {
			return "";
		}

		int dateIndex = findDateLineIndex(lines);
		String documentDate = dateIndex >= 0 ? normalizeDate(lines.get(dateIndex)) : "";
		List<String> titleLines = new ArrayList<>();
		if (dateIndex == 0) {
			for (int index = 1; index < lines.size() && titleLines.size() < TITLE_LINE_LIMIT; index++) {
				if (isDocumentDate(lines.get(index))) {
					break;
				}
				titleLines.add(lines.get(index));
			}
		} else {
			int end = dateIndex > 0 ? dateIndex : Math.min(lines.size(), TITLE_LINE_LIMIT);
			for (int index = 0; index < end && titleLines.size() < TITLE_LINE_LIMIT; index++) {
				if (isDocumentDate(lines.get(index))) {
					break;
				}
				titleLines.add(lines.get(index));
			}
		}

		String title = String.join(" ", titleLines).replaceAll("\\s+", " ").trim();
		if (title.isBlank()) {
			return "";
		}
		return documentDate.isBlank() ? title : title + "(" + documentDate + ")";
	}

	// 메소드 설명: findDateLineIndex 처리 흐름을 수행합니다.
	private static int findDateLineIndex(List<String> lines) {
		for (int index = 0; index < lines.size(); index++) {
			if (isDocumentDate(lines.get(index))) {
				return index;
			}
		}
		return -1;
	}

	// 메소드 설명: isDocumentDate 처리 흐름을 수행합니다.
	private static boolean isDocumentDate(String value) {
		return DOCUMENT_DATE_PATTERN.matcher(value.replaceAll("\\s+", " ").trim()).matches();
	}

	// 메소드 설명: normalizeDate 처리 흐름을 수행합니다.
	private static String normalizeDate(String value) {
		return value.replaceAll("\\s+", " ").trim();
	}

	// 메소드 설명: stripExtensionStatic 처리 흐름을 수행합니다.
	private static String stripExtensionStatic(String fileName) {
		int extensionIndex = fileName.lastIndexOf('.');
		return extensionIndex < 0 ? fileName : fileName.substring(0, extensionIndex);
	}

	// 메소드 설명: isSuspiciousTitle 처리 흐름을 수행합니다.
	private static boolean isSuspiciousTitle(String title) {
		String normalized = title == null ? "" : title.replaceAll("\\s+", " ").trim();
		if (normalized.isBlank()) {
			return true;
		}
		if (normalized.contains("^")) {
			return true;
		}
		if (normalized.matches("[-\\d\\s.()]+")) {
			return true;
		}
		long lettersOrDigits = normalized.codePoints()
			.filter(Character::isLetterOrDigit)
			.count();
		return lettersOrDigits < Math.max(4, normalized.length() / 3);
	}

	// 메소드 설명: sha256 처리 흐름을 수행합니다.
	private String sha256(Path file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			digest.update(Files.readAllBytes(file));
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available.", exception);
		}
	}

	// 메소드 설명: ImportOutcome 처리 흐름을 수행합니다.
	private record ImportOutcome(boolean skipped, int indexedCount) {
	}
}
