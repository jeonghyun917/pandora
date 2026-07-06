package com.kaces.pandora.app.auth;

import java.time.LocalDateTime;

public class AdminUserRow {
	private long adminUserId;
	private String username;
	private String passwordHash;
	private String displayName;
	private String role;
	private boolean enabled;
	private int failedLoginCount;
	private LocalDateTime lockedUntil;
	private LocalDateTime lastLoginAt;
	private LocalDateTime lastFailedAt;

	public long getAdminUserId() {
		return adminUserId;
	}

	public void setAdminUserId(long adminUserId) {
		this.adminUserId = adminUserId;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getRole() {
		return role;
	}

	public void setRole(String role) {
		this.role = role;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int getFailedLoginCount() {
		return failedLoginCount;
	}

	public void setFailedLoginCount(int failedLoginCount) {
		this.failedLoginCount = failedLoginCount;
	}

	public LocalDateTime getLockedUntil() {
		return lockedUntil;
	}

	public void setLockedUntil(LocalDateTime lockedUntil) {
		this.lockedUntil = lockedUntil;
	}

	public LocalDateTime getLastLoginAt() {
		return lastLoginAt;
	}

	public void setLastLoginAt(LocalDateTime lastLoginAt) {
		this.lastLoginAt = lastLoginAt;
	}

	public LocalDateTime getLastFailedAt() {
		return lastFailedAt;
	}

	public void setLastFailedAt(LocalDateTime lastFailedAt) {
		this.lastFailedAt = lastFailedAt;
	}
}
