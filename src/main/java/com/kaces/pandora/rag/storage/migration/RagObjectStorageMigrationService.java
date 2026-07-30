package com.kaces.pandora.rag.storage.migration;

import com.kaces.pandora.rag.document.RagDocumentRow;
import com.kaces.pandora.rag.storage.S3RagOriginalDocumentStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.ObjectMapper;

public class RagObjectStorageMigrationService {

	private static final String ELIGIBLE = "ELIGIBLE";
	private final RagObjectStorageMigrationRepository repository;
	private final RagObjectStorageObjectGateway gateway;
	private final ObjectMapper objectMapper;

	public RagObjectStorageMigrationService(
		RagObjectStorageMigrationRepository repository,
		RagObjectStorageObjectGateway gateway,
		ObjectMapper objectMapper
	) {
		this.repository = repository;
		this.gateway = gateway;
		this.objectMapper = objectMapper;
	}

	public RagObjectStorageManifest plan(Path manifestPath) throws IOException {
		List<RagObjectStorageManifestEntry> entries = new ArrayList<>();
		for (RagDocumentRow document : repository.findActiveDocuments()) {
			entries.add(planEntry(document));
		}
		RagObjectStorageManifest manifest = new RagObjectStorageManifest(Instant.now().toString(), "rag_documents", entries);
		Path target = manifestPath.toAbsolutePath().normalize();
		Path parent = target.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		objectMapper.writeValue(target.toFile(), manifest);
		return manifest;
	}

	public RagObjectStorageMigrationResult apply(RagObjectStorageManifest manifest) {
		int updated = 0;
		int failed = 0;
		int skipped = 0;
		for (RagObjectStorageManifestEntry entry : manifest.entries()) {
			if (!ELIGIBLE.equals(entry.status())) {
				skipped++;
				continue;
			}
			try {
				Path source = verifiedSource(entry);
				if (!matches(entry, gateway.find(entry.objectKey()))) {
					gateway.upload(source, entry.objectKey(), contentType(entry.mimeType()), entry.fileHash());
				}
				if (!matches(entry, gateway.find(entry.objectKey()))) {
					failed++;
					continue;
				}
				if (repository.assignObjectKeyIfHashMatches(entry.documentId(), entry.fileHash(), entry.objectKey()) == 1) {
					updated++;
				} else {
					failed++;
				}
			} catch (IOException | RuntimeException exception) {
				failed++;
			}
		}
		return new RagObjectStorageMigrationResult(updated, failed, skipped);
	}

	public RagObjectStorageManifest readManifest(Path manifestPath) throws IOException {
		return objectMapper.readValue(manifestPath.toFile(), RagObjectStorageManifest.class);
	}

	private RagObjectStorageManifestEntry planEntry(RagDocumentRow document) {
		Path source;
		try {
			source = Path.of(document.filePath()).toAbsolutePath().normalize();
		} catch (RuntimeException exception) {
			return rejected(document, "INVALID_PATH", "Document path is invalid");
		}
		if (!Files.isRegularFile(source)) {
			return rejected(document, "MISSING", "Original file is missing");
		}
		try {
			long byteSize = Files.size(source);
			String actualHash = sha256(source);
			if (!actualHash.equals(normalizeHash(document.fileHash()))) {
				return rejected(document, "HASH_MISMATCH", "Original file hash does not match rag_documents.file_hash");
			}
			return new RagObjectStorageManifestEntry(
				document.documentId(), source.toString(), actualHash, document.fileName(), document.mimeType(), byteSize,
				S3RagOriginalDocumentStore.objectKey(actualHash, document.fileName()), ELIGIBLE, ""
			);
		} catch (IOException | IllegalArgumentException exception) {
			return rejected(document, "UNREADABLE", "Original file could not be read or hashed");
		}
	}

	private RagObjectStorageManifestEntry rejected(RagDocumentRow document, String status, String reason) {
		return new RagObjectStorageManifestEntry(
			document.documentId(), document.filePath(), document.fileHash(), document.fileName(), document.mimeType(), -1L,
			"", status, reason
		);
	}

	private Path verifiedSource(RagObjectStorageManifestEntry entry) throws IOException {
		Path source = Path.of(entry.filePath()).toAbsolutePath().normalize();
		if (!Files.isRegularFile(source) || Files.size(source) != entry.byteSize()) {
			throw new IOException("Original file changed after the manifest was created");
		}
		if (!sha256(source).equals(normalizeHash(entry.fileHash()))) {
			throw new IOException("Original file hash changed after the manifest was created");
		}
		return source;
	}

	private boolean matches(RagObjectStorageManifestEntry entry, java.util.Optional<RagObjectStorageObjectMetadata> metadata) {
		if (metadata.isEmpty() || metadata.get().byteSize() != entry.byteSize()) {
			return false;
		}
		try {
			return normalizeHash(entry.fileHash()).equals(normalizeHash(metadata.get().sha256()));
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private String contentType(String mimeType) {
		return mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType;
	}

	private String sha256(Path source) throws IOException {
		try (InputStream input = Files.newInputStream(source)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[8192];
			for (int read; (read = input.read(buffer)) >= 0;) {
				digest.update(buffer, 0, read);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

	private String normalizeHash(String hash) {
		if (hash == null || !hash.matches("[0-9A-Fa-f]{64}")) {
			throw new IllegalArgumentException("SHA-256 hash is required");
		}
		return hash.toLowerCase(Locale.ROOT);
	}
}
