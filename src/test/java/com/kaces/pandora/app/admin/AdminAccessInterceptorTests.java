package com.kaces.pandora.app.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminAccessInterceptorTests {

	@Test
	void allowsLocalRequestWhenLocalOnlyIsEnabled() throws Exception {
		AdminAccessProperties properties = new AdminAccessProperties();
		properties.setEnabled(true);
		properties.setLocalOnly(true);
		AdminAccessInterceptor interceptor = new AdminAccessInterceptor(properties);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/pipelines");
		request.setRemoteAddr("127.0.0.1");
		MockHttpServletResponse response = new MockHttpServletResponse();

		boolean allowed = interceptor.preHandle(request, response, new Object());

		assertThat(allowed).isTrue();
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	void blocksRemoteRequestWithoutToken() throws Exception {
		AdminAccessProperties properties = new AdminAccessProperties();
		properties.setEnabled(true);
		properties.setLocalOnly(true);
		AdminAccessInterceptor interceptor = new AdminAccessInterceptor(properties);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/pipelines");
		request.setRemoteAddr("10.0.0.25");
		MockHttpServletResponse response = new MockHttpServletResponse();

		boolean allowed = interceptor.preHandle(request, response, new Object());

		assertThat(allowed).isFalse();
		assertThat(response.getStatus()).isEqualTo(403);
	}

	@Test
	void allowsRemoteRequestWithToken() throws Exception {
		AdminAccessProperties properties = new AdminAccessProperties();
		properties.setEnabled(true);
		properties.setLocalOnly(true);
		properties.setToken("secret-token");
		AdminAccessInterceptor interceptor = new AdminAccessInterceptor(properties);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/pipelines");
		request.setRemoteAddr("10.0.0.25");
		request.addHeader("X-Pandora-Admin-Token", "secret-token");
		MockHttpServletResponse response = new MockHttpServletResponse();

		boolean allowed = interceptor.preHandle(request, response, new Object());

		assertThat(allowed).isTrue();
	}
}
