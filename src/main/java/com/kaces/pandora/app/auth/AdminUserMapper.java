package com.kaces.pandora.app.auth;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AdminUserMapper {
	int countUsers();

	AdminUserRow findByUsername(@Param("username") String username);

	void insertAdmin(
		@Param("username") String username,
		@Param("passwordHash") String passwordHash,
		@Param("displayName") String displayName,
		@Param("role") String role
	);

	void markLoginSuccess(@Param("adminUserId") long adminUserId);

	void markLoginFailure(
		@Param("adminUserId") long adminUserId,
		@Param("lockedUntil") LocalDateTime lockedUntil
	);
}
