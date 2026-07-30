package com.kaces.pandora.rag.storage;

import com.kaces.pandora.rag.document.RagDocumentRow;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class S3RagOriginalDocumentStore implements RagOriginalDocumentStore {

	private final S3Client s3Client;
	private final String bucket;
	private final Path cacheRoot;

	public S3RagOriginalDocumentStore(S3Client s3Client, RagObjectStorageProperties properties) {
		this.s3Client = s3Client;
		this.bucket = properties.getBucket();
		this.cacheRoot = properties.getCacheRoot().toAbsolutePath().normalize();
	}

	@Override
	public boolean exists(RagDocumentRow document) {
		String objectKey = objectKeyOrNull(document);
		if (objectKey == null) {
			return false;
		}
		try {
			s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
			return true;
		} catch (NoSuchKeyException exception) {
			return false;
		} catch (S3Exception exception) {
			return false;
		}
	}

	@Override
	public StoredOriginal open(RagDocumentRow document) throws IOException {
		String objectKey = requiredObjectKey(document);
		try {
			ResponseInputStream<GetObjectResponse> response = s3Client.getObject(
				GetObjectRequest.builder().bucket(bucket).key(objectKey).build()
			);
			GetObjectResponse metadata = response.response();
			return new StoredOriginal(
				response,
				metadata.contentLength() == null ? -1L : metadata.contentLength(),
				metadata.contentType() == null ? "" : metadata.contentType()
			);
		} catch (NoSuchKeyException exception) {
			throw new FileNotFoundException("Original document object does not exist: " + document.documentId());
		} catch (S3Exception exception) {
			if (exception.statusCode() == 404) {
				throw new FileNotFoundException("Original document object does not exist: " + document.documentId());
			}
			throw new IOException("Could not open original document object: " + document.documentId(), exception);
		}
	}

	@Override
	public Path materialize(RagDocumentRow document) throws IOException {
		String expectedHash = requiredHash(document);
		Path target = cacheRoot.resolve(expectedHash + extension(document.fileName())).normalize();
		if (!target.startsWith(cacheRoot)) {
			throw new IOException("Invalid object storage cache target");
		}
		if (Files.isRegularFile(target) && expectedHash.equals(sha256(target))) {
			return target;
		}
		Files.createDirectories(cacheRoot);
		Path temporary = Files.createTempFile(cacheRoot, expectedHash + "-", ".download");
		try {
			try (StoredOriginal original = open(document)) {
				Files.copy(original.inputStream(), temporary, StandardCopyOption.REPLACE_EXISTING);
			}
			if (!expectedHash.equals(sha256(temporary))) {
				throw new IOException("Original document object hash does not match document metadata: " + document.documentId());
			}
			moveIntoCache(temporary, target);
			return target;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	public static String objectKey(String fileHash, String fileName) {
		String hash = normalizeHash(fileHash);
		return "rag-originals/sha256/" + hash.substring(0, 2) + "/" + hash + extension(fileName);
	}

	private void moveIntoCache(Path temporary, Path target) throws IOException {
		try {
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private String requiredObjectKey(RagDocumentRow document) throws FileNotFoundException {
		String objectKey = objectKeyOrNull(document);
		if (objectKey == null) {
			throw new FileNotFoundException("Original document has no object storage key: " + document.documentId());
		}
		return objectKey;
	}

	private String objectKeyOrNull(RagDocumentRow document) {
		if (document == null || document.objectKey() == null || document.objectKey().isBlank()) {
			return null;
		}
		return document.objectKey();
	}

	private String requiredHash(RagDocumentRow document) throws IOException {
		try {
			return normalizeHash(document.fileHash());
		} catch (IllegalArgumentException exception) {
			throw new IOException("Original document has an invalid file hash: " + document.documentId(), exception);
		}
	}

	private static String normalizeHash(String fileHash) {
		if (fileHash == null || !fileHash.matches("[0-9A-Fa-f]{64}")) {
			throw new IllegalArgumentException("fileHash must be a SHA-256 hex digest");
		}
		return fileHash.toLowerCase(Locale.ROOT);
	}

	private static String extension(String fileName) {
		if (fileName == null) {
			return "";
		}
		int lastDot = fileName.lastIndexOf('.');
		if (lastDot < 0 || lastDot == fileName.length() - 1) {
			return "";
		}
		return fileName.substring(lastDot).toLowerCase(Locale.ROOT);
	}

	private String sha256(Path path) throws IOException {
		try (InputStream input = Files.newInputStream(path)) {
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
}
