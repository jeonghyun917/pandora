package com.kaces.pandora.app.auth;

import java.io.Serializable;

public record AdminSession(
	long adminUserId,
	String username,
	String displayName,
	String role
) implements Serializable {
}
