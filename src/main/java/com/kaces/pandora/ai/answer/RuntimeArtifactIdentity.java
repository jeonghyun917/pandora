package com.kaces.pandora.ai.answer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.boot.system.ApplicationHome;

record RuntimeArtifactIdentity(String kind, String sha256, Long size) {

	static RuntimeArtifactIdentity from(Class<?> anchor) {
		try {
			java.io.File source = new ApplicationHome(anchor).getSource();
			return source == null ? unavailable() : fromPath(source.toPath());
		} catch (RuntimeException exception) {
			return unavailable();
		}
	}

	static RuntimeArtifactIdentity fromPath(Path source) {
		try {
			if (source == null || !Files.exists(source)) {
				return unavailable();
			}
			if (Files.isDirectory(source)) {
				return new RuntimeArtifactIdentity("classes", null, null);
			}
			if (!Files.isRegularFile(source)) {
				return unavailable();
			}
			String fileName = String.valueOf(source.getFileName()).toLowerCase(java.util.Locale.ROOT);
			return new RuntimeArtifactIdentity(
				fileName.endsWith(".jar") ? "jar" : "file",
				sha256(source),
				Files.size(source)
			);
		} catch (IOException | SecurityException exception) {
			return unavailable();
		}
	}

	private static String sha256(Path source) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(source)) {
				byte[] buffer = new byte[8192];
				int length;
				while ((length = input.read(buffer)) >= 0) {
					if (length > 0) {
						digest.update(buffer, 0, length);
					}
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static RuntimeArtifactIdentity unavailable() {
		return new RuntimeArtifactIdentity("unavailable", null, null);
	}
}
