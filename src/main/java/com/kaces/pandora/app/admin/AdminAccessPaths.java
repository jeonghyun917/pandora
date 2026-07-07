package com.kaces.pandora.app.admin;

public final class AdminAccessPaths {
	public static final String[] PATTERNS = {
		"/api/admin/**",
		"/api/law-data/ai/debug/**",
		"/api/rag-collection/**",
		"/api/rag-documents/import-folder",
		"/api/law-data/semantic/collection",
		"/api/law-data/semantic/index-sample",
		"/api/law-data/semantic/index-documents",
		"/api/law-data/semantic/batch-file",
		"/api/law-data/semantic/batches/**"
	};

	private AdminAccessPaths() {
	}
}
