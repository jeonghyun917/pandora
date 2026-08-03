package com.kaces.pandora.semantic.integrity;

public record LawIndexIntegrityRuntimeInfo(String runtimeInstanceId, String indexRevision) {
	public boolean isComplete() {
		return runtimeInstanceId != null && !runtimeInstanceId.isBlank()
			&& indexRevision != null && !indexRevision.isBlank();
	}
}
