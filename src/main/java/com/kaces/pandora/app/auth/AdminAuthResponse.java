package com.kaces.pandora.app.auth;

public record AdminAuthResponse(
	boolean authenticated,
	String username,
	String displayName,
	String role,
	String message
) {
	public static AdminAuthResponse authenticated(AdminSession session) {
		return new AdminAuthResponse(
			true,
			session.username(),
			session.displayName(),
			session.role(),
			"authenticated"
		);
	}

	public static AdminAuthResponse unauthenticated(String message) {
		return new AdminAuthResponse(false, null, null, null, message);
	}
}
