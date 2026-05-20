package com.kaces.pandora.law.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class LawHashUtils {

	private LawHashUtils() {
	}

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
