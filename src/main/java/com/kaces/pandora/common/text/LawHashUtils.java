package com.kaces.pandora.common.text;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class LawHashUtils {
	/** Hash the existing canonical JSON bytes without retaining the serialized corpus. */
	public static String sha256Json(Object value) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			try (var output = new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest)) {
				new tools.jackson.databind.ObjectMapper().writeValue(output, value);
			}
			return java.util.HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException | java.io.IOException exception) {
			throw new IllegalStateException("JSON fingerprint is unavailable", exception);
		}
	}

	
	// 메소드 설명: LawHashUtils 처리 흐름을 수행합니다.
	private LawHashUtils() {
	}

	
	// 메소드 설명: sha256 처리 흐름을 수행합니다.
	public static String sha256(String value) {
		try {
			
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder(hash.length * 2);
			for (byte item : hash) {
				result.append(String.format("%02x", item));
			}
			return result.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available.", exception);
		}
	}
}
