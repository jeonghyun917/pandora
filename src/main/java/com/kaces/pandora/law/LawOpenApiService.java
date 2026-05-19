package com.kaces.pandora.law;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

@Service
public class LawOpenApiService {

	private static final Set<String> SUPPORTED_TARGETS = Set.of(
		"law",
		"prec",
		"admrul",
		"ordin",
		"detc",
		"expc",
		"decc",
		"trty",
		"licbyl",
		"admbyl",
		"ordinbyl",
		"lnkLs"
	);

	private final LawOpenApiProperties properties;
	private final RestClient restClient;

	public LawOpenApiService(LawOpenApiProperties properties) {
		this.properties = properties;
		this.restClient = RestClient.builder().baseUrl(properties.baseUrl()).build();
	}

	public String search(String target, String query, int page, int display) {
		if (!StringUtils.hasText(properties.oc())) {
			throw new IllegalStateException("Law Open API key is not configured.");
		}
		if (!SUPPORTED_TARGETS.contains(target)) {
			throw new IllegalArgumentException("Unsupported law open API target: " + target);
		}

		int safePage = Math.max(page, 1);
		int safeDisplay = Math.min(Math.max(display, 1), 100);
		String safeQuery = StringUtils.hasText(query) ? query.trim() : "*";

		byte[] responseBody = restClient.get()
			.uri(uriBuilder -> buildSearchUri(uriBuilder, target, safeQuery, safePage, safeDisplay))
			.retrieve()
			.body(byte[].class);

		String responseJson = new String(responseBody == null ? new byte[0] : responseBody, StandardCharsets.UTF_8);
		return maskApiKey(responseJson);
	}

	public String detail(String link) {
		if (!StringUtils.hasText(properties.oc())) {
			throw new IllegalStateException("Law Open API key is not configured.");
		}
		if (!StringUtils.hasText(link)) {
			throw new IllegalArgumentException("Detail link is required.");
		}

		String safeLink = link.replace("OC=***", "OC=");
		int queryStart = safeLink.indexOf('?');
		String path = queryStart >= 0 ? safeLink.substring(0, queryStart) : safeLink;
		if (path.startsWith("http://www.law.go.kr")) {
			path = path.substring("http://www.law.go.kr".length());
		}
		if (!path.startsWith("/DRF/")) {
			throw new IllegalArgumentException("Unsupported detail link.");
		}

		String endpointPath = path.substring("/DRF".length());
		Map<String, String> queryParams = parseQuery(queryStart >= 0 ? safeLink.substring(queryStart + 1) : "");
		queryParams.remove("OC");
		queryParams.put("type", "JSON");

		byte[] responseBody = restClient.get()
			.uri(uriBuilder -> buildDetailUri(uriBuilder, endpointPath, queryParams))
			.retrieve()
			.body(byte[].class);

		String responseJson = new String(responseBody == null ? new byte[0] : responseBody, StandardCharsets.UTF_8);
		if (!responseJson.trim().startsWith("{") && !responseJson.trim().startsWith("[")) {
			return """
				{"unsupported":true,"reason":"NON_JSON_DETAIL"}
				""";
		}
		return maskApiKey(responseJson);
	}

	public ResponseEntity<byte[]> proxy(String link) {
		if (!StringUtils.hasText(properties.oc())) {
			throw new IllegalStateException("Law Open API key is not configured.");
		}
		ProxyRequest proxyRequest = buildProxyRequest(link);
		return restClient.get()
			.uri(uriBuilder -> buildProxyUri(uriBuilder, proxyRequest.path(), proxyRequest.queryParams()))
			.retrieve()
			.toEntity(byte[].class);
	}

	private java.net.URI buildSearchUri(UriBuilder uriBuilder, String target, String query, int page, int display) {
		return uriBuilder
			.path("/lawSearch.do")
			.queryParam("OC", properties.oc())
			.queryParam("target", target)
			.queryParam("type", "JSON")
			.queryParam("query", query)
			.queryParam("page", page)
			.queryParam("display", display)
			.build();
	}

	private java.net.URI buildDetailUri(UriBuilder uriBuilder, String endpointPath, Map<String, String> queryParams) {
		UriBuilder builder = uriBuilder.path(endpointPath).queryParam("OC", properties.oc());
		queryParams.forEach(builder::queryParam);
		return builder.build();
	}

	private java.net.URI buildProxyUri(UriBuilder uriBuilder, String path, Map<String, String> queryParams) {
		UriBuilder builder = uriBuilder.path(path);
		queryParams.forEach(builder::queryParam);
		return builder.build();
	}

	private ProxyRequest buildProxyRequest(String link) {
		if (!StringUtils.hasText(link)) {
			throw new IllegalArgumentException("Proxy link is required.");
		}

		String safeLink = link.replace("OC=***", "OC=");
		int queryStart = safeLink.indexOf('?');
		String path = queryStart >= 0 ? safeLink.substring(0, queryStart) : safeLink;
		if (path.startsWith("http://www.law.go.kr")) {
			path = path.substring("http://www.law.go.kr".length());
		}
		if (!path.startsWith("/DRF/") && !path.startsWith("/LSW/")) {
			throw new IllegalArgumentException("Unsupported proxy link.");
		}

		String endpointPath = path.startsWith("/DRF") ? path.substring("/DRF".length()) : path;
		Map<String, String> queryParams = parseQuery(queryStart >= 0 ? safeLink.substring(queryStart + 1) : "");
		if (path.startsWith("/DRF/")) {
			queryParams.put("OC", properties.oc());
			queryParams.putIfAbsent("type", "HTML");
		}
		return new ProxyRequest(endpointPath, queryParams);
	}

	private Map<String, String> parseQuery(String query) {
		if (!StringUtils.hasText(query)) {
			return new LinkedHashMap<>();
		}
		return Arrays.stream(query.split("&"))
			.map(parameter -> parameter.split("=", 2))
			.filter(parts -> parts.length == 2 && StringUtils.hasText(parts[0]))
			.collect(Collectors.toMap(parts -> parts[0], parts -> parts[1], (left, right) -> right, LinkedHashMap::new));
	}

	private String maskApiKey(String responseBody) {
		return responseBody.replace("OC=" + properties.oc(), "OC=***");
	}

	private record ProxyRequest(String path, Map<String, String> queryParams) {
	}
}
