package com.kaces.pandora.app.auth;

import java.time.LocalDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuthService implements ApplicationRunner {
	private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);
	private static final String ADMIN_ROLE = "ADMIN";

	private final AdminUserMapper adminUserMapper;
	private final AdminAuthProperties properties;
	private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

	public AdminAuthService(AdminUserMapper adminUserMapper, AdminAuthProperties properties) {
		this.adminUserMapper = adminUserMapper;
		this.properties = properties;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (!properties.isEnabled() || adminUserMapper.countUsers() > 0) {
			return;
		}
		AdminAuthProperties.Bootstrap bootstrap = properties.getBootstrap();
		if (!bootstrap.isConfigured()) {
			log.warn("No admin user exists. Set PANDORA_BOOTSTRAP_ADMIN_USERNAME and PANDORA_BOOTSTRAP_ADMIN_PASSWORD to create one.");
			return;
		}
		adminUserMapper.insertAdmin(
			normalizeUsername(bootstrap.getUsername()),
			passwordEncoder.encode(bootstrap.getPassword()),
			bootstrap.getDisplayName(),
			ADMIN_ROLE
		);
		log.info("Bootstrap admin user was created: {}", normalizeUsername(bootstrap.getUsername()));
	}

	@Transactional
	public Optional<AdminSession> authenticate(String username, String password) {
		String normalizedUsername = normalizeUsername(username);
		if (normalizedUsername.isBlank() || password == null || password.isBlank()) {
			return Optional.empty();
		}
		AdminUserRow user = adminUserMapper.findByUsername(normalizedUsername);
		if (user == null || !user.isEnabled() || isLocked(user)) {
			return Optional.empty();
		}
		if (!passwordEncoder.matches(password, user.getPasswordHash())) {
			markFailure(user);
			return Optional.empty();
		}
		adminUserMapper.markLoginSuccess(user.getAdminUserId());
		return Optional.of(new AdminSession(
			user.getAdminUserId(),
			user.getUsername(),
			user.getDisplayName(),
			user.getRole()
		));
	}

	private void markFailure(AdminUserRow user) {
		int nextFailureCount = user.getFailedLoginCount() + 1;
		LocalDateTime lockedUntil = nextFailureCount >= properties.getMaxFailedAttempts()
			? LocalDateTime.now().plusMinutes(properties.getLockMinutes())
			: null;
		adminUserMapper.markLoginFailure(user.getAdminUserId(), lockedUntil);
	}

	private boolean isLocked(AdminUserRow user) {
		return user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now());
	}

	private static String normalizeUsername(String username) {
		return username == null ? "" : username.trim();
	}
}
