package com.kaces.pandora.app.admin;

import com.kaces.pandora.app.auth.AdminSessionSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminAccessInterceptor implements HandlerInterceptor {
	private static final String ADMIN_TOKEN_HEADER = "X-Pandora-Admin-Token";

	private final AdminAccessProperties properties;

	public AdminAccessInterceptor(AdminAccessProperties properties) {
		this.properties = properties;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
		throws IOException {
		if (!properties.isEnabled()) {
			return true;
		}
		if (AdminSessionSupport.isAuthenticated(request)) {
			return true;
		}
		if (hasValidToken(request)) {
			return true;
		}
		if (properties.isLocalOnly() && isLocalRequest(request)) {
			return true;
		}
		writeForbidden(response);
		return false;
	}

	private boolean hasValidToken(HttpServletRequest request) {
		if (!properties.hasToken()) {
			return false;
		}
		String provided = request.getHeader(ADMIN_TOKEN_HEADER);
		if (provided == null || provided.isBlank()) {
			return false;
		}
		byte[] expected = properties.getToken().getBytes(StandardCharsets.UTF_8);
		byte[] actual = provided.trim().getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(expected, actual);
	}

	private boolean isLocalRequest(HttpServletRequest request) {
		String remoteAddress = request.getRemoteAddr();
		return "127.0.0.1".equals(remoteAddress)
			|| "0:0:0:0:0:0:0:1".equals(remoteAddress)
			|| "::1".equals(remoteAddress)
			|| "localhost".equalsIgnoreCase(remoteAddress);
	}

	private void writeForbidden(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.getWriter().write("""
			{"status":403,"error":"Forbidden","message":"Admin/debug API is restricted to local access or a valid admin token."}
			""");
	}
}
