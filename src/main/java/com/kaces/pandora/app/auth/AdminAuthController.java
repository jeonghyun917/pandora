package com.kaces.pandora.app.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AdminAuthController {
	private final AdminAuthService adminAuthService;
	private final AdminAuthProperties properties;

	public AdminAuthController(AdminAuthService adminAuthService, AdminAuthProperties properties) {
		this.adminAuthService = adminAuthService;
		this.properties = properties;
	}

	@GetMapping("/me")
	public AdminAuthResponse me(HttpServletRequest request) {
		AdminSession session = AdminSessionSupport.current(request);
		if (session == null) {
			return AdminAuthResponse.unauthenticated("not authenticated");
		}
		return AdminAuthResponse.authenticated(session);
	}

	@PostMapping("/login")
	public ResponseEntity<AdminAuthResponse> login(
		@Valid @RequestBody AdminLoginRequest loginRequest,
		HttpServletRequest servletRequest
	) {
		Optional<AdminSession> authenticated = adminAuthService.authenticate(
			loginRequest.username(),
			loginRequest.password()
		);
		if (authenticated.isEmpty()) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(AdminAuthResponse.unauthenticated("Invalid username or password."));
		}
		HttpSession existingSession = servletRequest.getSession(false);
		if (existingSession != null) {
			existingSession.invalidate();
		}
		HttpSession session = servletRequest.getSession(true);
		session.setMaxInactiveInterval(properties.getSessionTimeoutSeconds());
		session.setAttribute(AdminSessionSupport.SESSION_ATTRIBUTE, authenticated.get());
		return ResponseEntity.ok(AdminAuthResponse.authenticated(authenticated.get()));
	}

	@PostMapping("/logout")
	public AdminAuthResponse logout(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		return AdminAuthResponse.unauthenticated("logged out");
	}
}
