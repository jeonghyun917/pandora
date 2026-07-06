package com.kaces.pandora.app.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

public final class AdminSessionSupport {
	public static final String SESSION_ATTRIBUTE = "PANDORA_ADMIN_SESSION";

	private AdminSessionSupport() {
	}

	public static boolean isAuthenticated(HttpServletRequest request) {
		return current(request) != null;
	}

	public static AdminSession current(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return null;
		}
		Object value = session.getAttribute(SESSION_ATTRIBUTE);
		return value instanceof AdminSession adminSession ? adminSession : null;
	}
}
