package com.kaces.pandora.app.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pandora.auth")
public class AdminAuthProperties {
	private boolean enabled = true;
	private int sessionTimeoutSeconds = 43_200;
	private int maxFailedAttempts = 10;
	private int lockMinutes = 15;
	private Bootstrap bootstrap = new Bootstrap();

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int getSessionTimeoutSeconds() {
		return sessionTimeoutSeconds;
	}

	public void setSessionTimeoutSeconds(int sessionTimeoutSeconds) {
		this.sessionTimeoutSeconds = Math.max(300, sessionTimeoutSeconds);
	}

	public int getMaxFailedAttempts() {
		return maxFailedAttempts;
	}

	public void setMaxFailedAttempts(int maxFailedAttempts) {
		this.maxFailedAttempts = Math.max(1, maxFailedAttempts);
	}

	public int getLockMinutes() {
		return lockMinutes;
	}

	public void setLockMinutes(int lockMinutes) {
		this.lockMinutes = Math.max(1, lockMinutes);
	}

	public Bootstrap getBootstrap() {
		return bootstrap;
	}

	public void setBootstrap(Bootstrap bootstrap) {
		this.bootstrap = bootstrap == null ? new Bootstrap() : bootstrap;
	}

	public static class Bootstrap {
		private String username = "";
		private String password = "";
		private String displayName = "Pandora Admin";

		public String getUsername() {
			return username;
		}

		public void setUsername(String username) {
			this.username = username == null ? "" : username.trim();
		}

		public String getPassword() {
			return password;
		}

		public void setPassword(String password) {
			this.password = password == null ? "" : password;
		}

		public String getDisplayName() {
			return displayName;
		}

		public void setDisplayName(String displayName) {
			String normalized = displayName == null ? "" : displayName.trim();
			this.displayName = normalized.isBlank() ? "Pandora Admin" : normalized;
		}

		boolean isConfigured() {
			return !username.isBlank() && !password.isBlank();
		}
	}
}
