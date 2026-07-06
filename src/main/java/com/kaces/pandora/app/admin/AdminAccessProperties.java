package com.kaces.pandora.app.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pandora.admin-access")
public class AdminAccessProperties {
	private boolean enabled = true;
	private boolean localOnly = true;
	private String token = "";

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isLocalOnly() {
		return localOnly;
	}

	public void setLocalOnly(boolean localOnly) {
		this.localOnly = localOnly;
	}

	public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token == null ? "" : token.trim();
	}

	boolean hasToken() {
		return token != null && !token.isBlank();
	}
}
