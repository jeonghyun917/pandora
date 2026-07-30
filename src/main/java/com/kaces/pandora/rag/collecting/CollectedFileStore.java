package com.kaces.pandora.rag.collecting;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
class CollectedFileStore {
	boolean shouldReuse(
		RagCollectedAttachmentRow attachment,
		Path articleDirectory,
		boolean refreshExisting
	) {
		return !refreshExisting && isReusable(attachment, articleDirectory);
	}

	boolean isReusable(RagCollectedAttachmentRow attachment, Path articleDirectory) {
		if (attachment == null || isBlank(attachment.fileHash()) || isBlank(attachment.localPath())) {
			return false;
		}
		try {
			Path root = articleDirectory.toAbsolutePath().normalize();
			Path file = Path.of(attachment.localPath()).toAbsolutePath().normalize();
			return file.startsWith(root)
				&& Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
				&& !Files.isSymbolicLink(file)
				&& attachment.fileHash().equalsIgnoreCase(sha256(file));
		} catch (IOException | RuntimeException exception) {
			return false;
		}
	}

	StoredFile store(Path articleDirectory, String fileName, byte[] bytes) throws IOException {
		Path root = articleDirectory.toAbsolutePath().normalize();
		Files.createDirectories(root);
		String hash = sha256(bytes);
		Path existing = findByHash(root, hash);
		if (existing != null) {
			return new StoredFile(existing, hash, false);
		}

		Path intended = resolveChild(root, fileName);
		Path destination = intended;
		if (Files.exists(intended, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isRegularFile(intended, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(intended)) {
				throw new IOException("Destination is not a regular file: " + intended);
			}
			destination = resolveChild(root, versionedName(fileName, hash));
			if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
				if (hash.equalsIgnoreCase(sha256(destination))) {
					return new StoredFile(destination, hash, false);
				}
				throw new IOException("Hash-versioned destination contains different bytes: " + destination);
			}
		}

		Path temporary = Files.createTempFile(root, ".pandora-download-", ".tmp");
		try {
			Files.write(temporary, bytes);
			if (!hash.equalsIgnoreCase(sha256(temporary))) {
				throw new IOException("Temporary attachment hash verification failed.");
			}
			moveAtomically(temporary, destination);
			return new StoredFile(destination, hash, true);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private Path findByHash(Path root, String expectedHash) throws IOException {
		try (Stream<Path> files = Files.list(root)) {
			for (Path file : files
				.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
				.filter(path -> !Files.isSymbolicLink(path))
				.filter(path -> !path.getFileName().toString().endsWith(".meta.json"))
				.filter(path -> !path.getFileName().toString().endsWith(".tmp"))
				.toList()) {
				if (expectedHash.equalsIgnoreCase(sha256(file))) {
					return file.toAbsolutePath().normalize();
				}
			}
		}
		return null;
	}

	private Path resolveChild(Path root, String fileName) throws IOException {
		Path resolved = root.resolve(fileName).toAbsolutePath().normalize();
		if (!resolved.startsWith(root) || resolved.equals(root)) {
			throw new IOException("Attachment path escapes article directory.");
		}
		return resolved;
	}

	private String versionedName(String fileName, String hash) {
		int dot = fileName.lastIndexOf('.');
		String base = dot > 0 ? fileName.substring(0, dot) : fileName;
		String extension = dot > 0 ? fileName.substring(dot) : "";
		return base + "-" + hash.substring(0, 12) + extension;
	}

	private void moveAtomically(Path source, Path destination) throws IOException {
		try {
			Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(source, destination);
		}
	}

	static String sha256(Path file) throws IOException {
		MessageDigest digest = sha256Digest();
		try (InputStream input = Files.newInputStream(file)) {
			byte[] buffer = new byte[1024 * 1024];
			int read;
			while ((read = input.read(buffer)) >= 0) {
				if (read > 0) {
					digest.update(buffer, 0, read);
				}
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	static String sha256(byte[] bytes) {
		return HexFormat.of().formatHex(sha256Digest().digest(bytes));
	}

	private static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	record StoredFile(Path path, String sha256, boolean created) {
	}
}
