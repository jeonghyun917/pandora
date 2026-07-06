package com.kaces.pandora.app.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminLoginRequest(
	@NotBlank
	@Size(max = 50)
	String username,

	@NotBlank
	@Size(max = 200)
	String password
) {
}
