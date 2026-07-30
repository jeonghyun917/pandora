package com.kaces.pandora.rag.storage;

import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pandora.object-storage")
public class RagObjectStorageProperties {

	private boolean enabled;
	private String endpoint = "";
	private String region = "auto";
	private String bucket = "";
	private String accessKeyId = "";
	private String secretAccessKey = "";
	private boolean pathStyle;
	private Path cacheRoot = Path.of(System.getProperty("java.io.tmpdir"), "pandora-object-cache");
	private final Migration migration = new Migration();

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getEndpoint() {
		return endpoint;
	}

	public void setEndpoint(String endpoint) {
		this.endpoint = valueOrEmpty(endpoint);
	}

	public String getRegion() {
		return region;
	}

	public void setRegion(String region) {
		this.region = valueOrEmpty(region);
	}

	public String getBucket() {
		return bucket;
	}

	public void setBucket(String bucket) {
		this.bucket = valueOrEmpty(bucket);
	}

	public String getAccessKeyId() {
		return accessKeyId;
	}

	public void setAccessKeyId(String accessKeyId) {
		this.accessKeyId = valueOrEmpty(accessKeyId);
	}

	public String getSecretAccessKey() {
		return secretAccessKey;
	}

	public void setSecretAccessKey(String secretAccessKey) {
		this.secretAccessKey = valueOrEmpty(secretAccessKey);
	}

	public boolean isPathStyle() {
		return pathStyle;
	}

	public void setPathStyle(boolean pathStyle) {
		this.pathStyle = pathStyle;
	}

	public Path getCacheRoot() {
		return cacheRoot;
	}

	public void setCacheRoot(Path cacheRoot) {
		this.cacheRoot = cacheRoot;
	}

	public Migration getMigration() {
		return migration;
	}

	public static class Migration {
		private boolean enabled;
		private String mode = "";
		private Path manifestPath;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getMode() {
			return mode;
		}

		public void setMode(String mode) {
			this.mode = valueOrEmpty(mode);
		}

		public Path getManifestPath() {
			return manifestPath;
		}

		public void setManifestPath(Path manifestPath) {
			this.manifestPath = manifestPath;
		}
	}

	public URI validatedEndpoint() {
		requireValue("endpoint", endpoint);
		try {
			URI uri = URI.create(endpoint);
			if (uri.getScheme() == null || uri.getHost() == null) {
				throw new IllegalArgumentException("endpoint must be an absolute URI");
			}
			return uri;
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("pandora.object-storage.endpoint must be a valid absolute URI", exception);
		}
	}

	public void validateEnabledConfiguration() {
		if (!enabled) {
			return;
		}
		validatedEndpoint();
		requireValue("region", region);
		requireValue("bucket", bucket);
		requireValue("access-key-id", accessKeyId);
		requireValue("secret-access-key", secretAccessKey);
		if (cacheRoot == null) {
			throw new IllegalStateException("pandora.object-storage.cache-root is required when storage is enabled");
		}
	}

	private void requireValue(String property, String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("pandora.object-storage." + property + " is required when storage is enabled");
		}
	}

	private static String valueOrEmpty(String value) {
		return value == null ? "" : value.trim();
	}
}
