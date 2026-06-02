package com.kaces.pandora.lawdata.client;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

@Service
public class LawOpenApiService {

	private static final Set<String> SUPPORTED_TARGETS = Set.of(
		"law",
		"admrul"
	);
	private static final Set<String> HTML_ONLY_DETAIL_TARGETS = Set.of();
	private static final String NO_TEXT_MESSAGE = "표시할 원문 텍스트를 찾지 못했습니다.";

	private final LawOpenApiProperties properties;
	private final RestClient restClient;
	private final RestClient lawRootClient;
	
	// 메소드 설명: LawOpenApiService 처리 흐름을 수행합니다.
	public LawOpenApiService(LawOpenApiProperties properties) {
		this.properties = properties;
		this.restClient = RestClient.builder()
			.baseUrl(properties.baseUrl())
			.defaultHeader("User-Agent", "Mozilla/5.0")
			.defaultHeader("Accept", "application/json, text/html, */*")
			.build();
		this.lawRootClient = RestClient.builder()
			.baseUrl("https://www.law.go.kr")
			.defaultHeader("User-Agent", "Mozilla/5.0")
			.defaultHeader("Accept", "text/html, application/xhtml+xml, */*")
			.build();
	}
	
	// 메소드 설명: search 처리 흐름을 수행합니다.
	public String search(String target, String query, int page, int display) {
		return search(target, query, page, display, "");
	}

	public String search(String target, String query, int page, int display, String sort) {
		return search(target, query, page, display, sort, "", "", "");
	}

	public String search(String target, String query, int page, int display, String sort, String date, String efYd, String ancYd) {
		if (!StringUtils.hasText(properties.oc())) {
			throw new IllegalStateException("Law Open API key is not configured.");
		}
		if (!SUPPORTED_TARGETS.contains(target)) {
			throw new IllegalArgumentException("Unsupported law open API target: " + target);
		}

		int safePage = Math.max(page, 1);
		int safeDisplay = Math.min(Math.max(display, 1), 100);
		String safeQuery = StringUtils.hasText(query) ? query.trim() : "*";
		String safeSort = StringUtils.hasText(sort) ? sort.trim() : "";
		String safeDate = StringUtils.hasText(date) ? date.trim() : "";
		String safeEfYd = StringUtils.hasText(efYd) ? efYd.trim() : "";
		String safeAncYd = StringUtils.hasText(ancYd) ? ancYd.trim() : "";
		
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		byte[] responseBody = restClient.get()
			.uri(uriBuilder -> buildSearchUri(uriBuilder, target, safeQuery, safePage, safeDisplay, safeSort, safeDate, safeEfYd, safeAncYd))
			.retrieve()
			.body(byte[].class);

		String responseJson = new String(responseBody == null ? new byte[0] : responseBody, StandardCharsets.UTF_8);
		return maskApiKey(responseJson);
	}
	
	// 메소드 설명: detail 처리 흐름을 수행합니다.
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
		String target = queryParams.get("target");
		queryParams.put("type", HTML_ONLY_DETAIL_TARGETS.contains(target) ? "HTML" : "JSON");
		
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		byte[] responseBody = restClient.get()
			.uri(uriBuilder -> buildDetailUri(uriBuilder, endpointPath, queryParams))
			.retrieve()
			.body(byte[].class);

		String responseJson = new String(responseBody == null ? new byte[0] : responseBody, StandardCharsets.UTF_8);
		if (!responseJson.trim().startsWith("{") && !responseJson.trim().startsWith("[")) {
			
			String iframeSource = extractIframeSource(responseJson);
			if (StringUtils.hasText(iframeSource)) {
				responseJson = fetchProxyText(iframeSource);
				responseJson = resolveDynamicHtmlDetail(iframeSource, responseJson);
			} else {
				responseJson = resolveDynamicHtmlDetail(link, responseJson);
			}
			return buildHtmlDetailJson(responseJson);
		}
		return maskApiKey(responseJson);
	}
	
	// 메소드 설명: proxy 처리 흐름을 수행합니다.
	public ResponseEntity<byte[]> proxy(String link) {
		if (!StringUtils.hasText(properties.oc())) {
			throw new IllegalStateException("Law Open API key is not configured.");
		}
		ProxyRequest proxyRequest = buildProxyRequest(link);
		RestClient client = proxyRequest.path().startsWith("/LSW/") ? lawRootClient : restClient;
		
		return client.get()
			.uri(uriBuilder -> buildProxyUri(uriBuilder, proxyRequest.path(), proxyRequest.queryParams()))
			.retrieve()
			.toEntity(byte[].class);
	}
	
	// 메소드 설명: buildSearchUri 처리 흐름을 수행합니다.
	private java.net.URI buildSearchUri(UriBuilder uriBuilder, String target, String query, int page, int display, String sort, String date, String efYd, String ancYd) {
		UriBuilder builder = uriBuilder
			.path("/lawSearch.do")
			.queryParam("OC", properties.oc())
			.queryParam("target", target)
			.queryParam("type", "JSON")
			.queryParam("query", query)
			.queryParam("page", page)
			.queryParam("display", display);
		if (StringUtils.hasText(sort)) {
			builder.queryParam("sort", sort);
		}
		if (StringUtils.hasText(date)) {
			builder.queryParam("date", date);
		}
		if (StringUtils.hasText(efYd)) {
			builder.queryParam("efYd", efYd);
		}
		if (StringUtils.hasText(ancYd)) {
			builder.queryParam("ancYd", ancYd);
		}
		return builder.build();
	}
	
	// 메소드 설명: buildDetailUri 처리 흐름을 수행합니다.
	private java.net.URI buildDetailUri(UriBuilder uriBuilder, String endpointPath, Map<String, String> queryParams) {
		UriBuilder builder = uriBuilder.path(endpointPath).queryParam("OC", properties.oc());
		queryParams.forEach(builder::queryParam);
		return builder.build();
	}
	
	// 메소드 설명: buildProxyUri 처리 흐름을 수행합니다.
	private java.net.URI buildProxyUri(UriBuilder uriBuilder, String path, Map<String, String> queryParams) {
		UriBuilder builder = uriBuilder.path(path);
		queryParams.forEach(builder::queryParam);
		return builder.build();
	}
	
	// 메소드 설명: buildProxyRequest 처리 흐름을 수행합니다.
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
	
	// 메소드 설명: parseQuery 처리 흐름을 수행합니다.
	private Map<String, String> parseQuery(String query) {
		if (!StringUtils.hasText(query)) {
			return new LinkedHashMap<>();
		}
		return Arrays.stream(query.split("&"))
			.map(parameter -> parameter.split("=", 2))
			.filter(parts -> parts.length == 2 && StringUtils.hasText(parts[0]))
			.collect(Collectors.toMap(parts -> parts[0], parts -> parts[1], (left, right) -> right, LinkedHashMap::new));
	}
	
	// 메소드 설명: maskApiKey 처리 흐름을 수행합니다.
	private String maskApiKey(String responseBody) {
		return responseBody.replace("OC=" + properties.oc(), "OC=***");
	}
	
	// 메소드 설명: buildHtmlDetailJson 처리 흐름을 수행합니다.
	private String buildHtmlDetailJson(String html) {
		String title = extractHtmlTitle(html);
		String images = extractImageLinks(html).stream()
			
			.map(image -> "{\"src\":\"" + escapeJson(toProxyLink(image.src())) + "\",\"alt\":\"" + escapeJson(image.alt()) + "\"}")
			.collect(Collectors.joining(","));
		String text = extractReadableText(html);
		if (NO_TEXT_MESSAGE.equals(text) && StringUtils.hasText(images)) {
			text = "파일 원문은 아래 이미지로 표시됩니다.";
		}
		return """
			{"htmlDetail":true,"title":"%s","sections":[{"title":"원문 내용","body":"%s","images":[%s]}]}
			""".formatted(escapeJson(title), escapeJson(text), images);
	}
	
	// 메소드 설명: extractHtmlTitle 처리 흐름을 수행합니다.
	private String extractHtmlTitle(String html) {
		Matcher matcher = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
		if (matcher.find()) {
			return htmlToPlainText(matcher.group(1));
		}
		return "원문 상세";
	}
	
	// 메소드 설명: extractIframeSource 처리 흐름을 수행합니다.
	private String extractIframeSource(String html) {
		Matcher matcher = Pattern.compile("(?is)<iframe[^>]+src\\s*=\\s*[\"']?([^\"'\\s>]+)").matcher(html);
		return matcher.find() ? matcher.group(1).trim() : "";
	}
	
	// 메소드 설명: fetchProxyText 처리 흐름을 수행합니다.
	private String fetchProxyText(String link) {
		ProxyRequest proxyRequest = buildProxyRequest(link);
		RestClient client = proxyRequest.path().startsWith("/LSW/") ? lawRootClient : restClient;
		
		byte[] responseBody = client.get()
			.uri(uriBuilder -> buildProxyUri(uriBuilder, proxyRequest.path(), proxyRequest.queryParams()))
			.retrieve()
			.body(byte[].class);
		return new String(responseBody == null ? new byte[0] : responseBody, StandardCharsets.UTF_8);
	}
	
	// 메소드 설명: resolveDynamicHtmlDetail 처리 흐름을 수행합니다.
	private String resolveDynamicHtmlDetail(String link, String html) {
		if (!StringUtils.hasText(html)) {
			return html;
		}
		if (link.contains("admRulBylInfoP.do") || html.contains("admRulBylInfoR.do") || html.contains("admRulBylContentsInfoR.do")) {
			return resolveAdminRuleBylHtml(link, html);
		}
		return html;
	}
	
	// 메소드 설명: resolveAdminRuleBylHtml 처리 흐름을 수행합니다.
	private String resolveAdminRuleBylHtml(String link, String html) {
		String infoHtml = html;
		ProxyRequest request = buildProxyRequest(link);
		Map<String, String> initialParams = request.queryParams();
		if (!html.contains("bylAdmRulId") && StringUtils.hasText(initialParams.get("bylSeq")) && StringUtils.hasText(initialParams.get("admRulSeq"))) {
			Map<String, String> params = new LinkedHashMap<>();
			params.put("bylSeq", initialParams.get("bylSeq"));
			params.put("admRulSeq", initialParams.get("admRulSeq"));
			params.put("vSct", initialParams.getOrDefault("vSct", ""));
			
			infoHtml = postLawForm("/LSW/admRulBylInfoR.do", params);
		}
		String selectedOption = extractSelectedOptionValue(infoHtml, "bylList");
		String admRulId = extractInputValue(infoHtml, "bylAdmRulId");
		if (!StringUtils.hasText(selectedOption) || !StringUtils.hasText(admRulId)) {
			return infoHtml;
		}

		String[] optionParts = selectedOption.split(",");
		if (optionParts.length < 4) {
			return infoHtml;
		}

		Map<String, String> params = new LinkedHashMap<>();
		params.put("bylSeq", optionParts[0]);
		params.put("bylNo", optionParts[1]);
		params.put("bylBrNo", optionParts[2]);
		params.put("bylClsCd", optionParts[3]);
		params.put("admRulId", admRulId);
		params.put("vSct", extractInputValue(infoHtml, "vSct"));
		
		String contentHtml = postLawForm("/LSW/admRulBylContentsInfoR.do", params);
		return StringUtils.hasText(extractReadableText(contentHtml)) ? contentHtml : infoHtml;
	}
	
	// 메소드 설명: postLawForm 처리 흐름을 수행합니다.
	private String postLawForm(String path, Map<String, String> params) {
		String formBody = params.entrySet().stream()
			
			.map(entry -> encodeForm(entry.getKey()) + "=" + encodeForm(entry.getValue()))
			.collect(Collectors.joining("&"));
		byte[] responseBody = lawRootClient.post()
			.uri(path)
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(formBody)
			.retrieve()
			.body(byte[].class);
		return new String(responseBody == null ? new byte[0] : responseBody, StandardCharsets.UTF_8);
	}
	
	// 메소드 설명: encodeForm 처리 흐름을 수행합니다.
	private String encodeForm(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}
	
	// 메소드 설명: extractInputValue 처리 흐름을 수행합니다.
	private String extractInputValue(String html, String id) {
		Matcher matcher = Pattern.compile("(?is)<input\\b[^>]*\\bid\\s*=\\s*[\"']" + Pattern.quote(id) + "[\"'][^>]*>").matcher(html);
		if (matcher.find()) {
			return extractAttribute(matcher.group(), "value");
		}
		return "";
	}
	
	// 메소드 설명: extractSelectedOptionValue 처리 흐름을 수행합니다.
	private String extractSelectedOptionValue(String html, String selectId) {
		Matcher selectMatcher = Pattern.compile("(?is)<select\\b[^>]*\\bid\\s*=\\s*[\"']" + Pattern.quote(selectId) + "[\"'][^>]*>(.*?)</select>").matcher(html);
		String selectHtml = selectMatcher.find() ? selectMatcher.group(1) : html;
		Matcher selectedMatcher = Pattern.compile("(?is)<option\\b(?=[^>]*\\bselected\\b)[^>]*>").matcher(selectHtml);
		if (selectedMatcher.find()) {
			return extractAttribute(selectedMatcher.group(), "value");
		}
		Matcher firstMatcher = Pattern.compile("(?is)<option\\b[^>]*>").matcher(selectHtml);
		return firstMatcher.find() ? extractAttribute(firstMatcher.group(), "value") : "";
	}
	
	// 메소드 설명: extractAttribute 처리 흐름을 수행합니다.
	private String extractAttribute(String tag, String attribute) {
		Matcher matcher = Pattern.compile("(?is)\\b" + Pattern.quote(attribute) + "\\s*=\\s*([\"'])(.*?)\\1").matcher(tag);
		return matcher.find() ? htmlToPlainText(matcher.group(2)) : "";
	}
	
	// 메소드 설명: extractImageLinks 처리 흐름을 수행합니다.
	private java.util.List<ImageLink> extractImageLinks(String html) {
		Matcher matcher = Pattern.compile("(?is)<img\\b[^>]*>").matcher(html);
		java.util.List<ImageLink> images = new java.util.ArrayList<>();
		while (matcher.find()) {
			String tag = matcher.group();
			String src = extractAttribute(tag, "src");
			if (StringUtils.hasText(src) && src.contains("/LSW/flDownload.do")) {
				String alt = extractAttribute(tag, "alt");
				images.add(new ImageLink(src, StringUtils.hasText(alt) ? alt : "원문 이미지"));
			}
		}
		return images;
	}
	
	// 메소드 설명: toProxyLink 처리 흐름을 수행합니다.
	private String toProxyLink(String link) {
		String safeLink = link.startsWith("http") ? link : link.startsWith("/") ? link : "/" + link;
		return "/api/law-data/proxy?link=" + encodeForm(safeLink);
	}
	
	// 메소드 설명: extractReadableText 처리 흐름을 수행합니다.
	private String extractReadableText(String html) {
		String text = html
			.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
			.replaceAll("(?is)<style[^>]*>.*?</style>", " ")
			.replaceAll("(?i)<br\\s*/?>", "\n")
			.replaceAll("(?i)</(td|th)>", " ")
			.replaceAll("(?i)</(p|div|li|tr|h[1-6]|table|section|article)>", "\n")
			.replaceAll("(?is)<[^>]+>", " ");
		text = htmlToPlainText(text);
		
		text = Arrays.stream(text.split("\\R"))
			.map(String::trim)
			.filter(StringUtils::hasText)
			.distinct()
			.limit(120)
			.collect(Collectors.joining("\n"));
		return StringUtils.hasText(text) ? text : NO_TEXT_MESSAGE;
	}
	
	// 메소드 설명: htmlToPlainText 처리 흐름을 수행합니다.
	private String htmlToPlainText(String value) {
		return value
			.replace("&nbsp;", " ")
			.replace("&amp;", "&")
			.replace("&lt;", "<")
			.replace("&gt;", ">")
			.replace("&quot;", "\"")
			.replace("&#39;", "'")
			.replaceAll("&#x([0-9a-fA-F]+);", " ")
			.replaceAll("&#\\d+;", " ")
			.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
			.trim();
	}
	
	// 메소드 설명: escapeJson 처리 흐름을 수행합니다.
	private String escapeJson(String value) {
		return value
			.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\b", "\\b")
			.replace("\f", "\\f")
			.replace("\n", "\\n")
			.replace("\r", "\\r")
			.replace("\t", "\\t");
	}

	// 메소드 설명: ProxyRequest 처리 흐름을 수행합니다.
	private record ProxyRequest(String path, Map<String, String> queryParams) {
	}

	// 메소드 설명: ImageLink 처리 흐름을 수행합니다.
	private record ImageLink(String src, String alt) {
	}
}
