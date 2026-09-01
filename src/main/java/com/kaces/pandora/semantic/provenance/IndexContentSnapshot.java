package com.kaces.pandora.semantic.provenance;

public record IndexContentSnapshot(
	long currentIndexedCount,
	String contentFingerprint,
	String updatedWatermark
) {
	boolean isUsable() {
		return currentIndexedCount > 0
			&& contentFingerprint != null
			&& contentFingerprint.matches("[0-9A-Fa-f]{64}");
	}
}
