package com.kaces.pandora.law.client;

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
	private static final Set<String> HTML_ONLY_DETAIL_TARGETS = Set.of("licbyl", "admbyl", "ordinbyl");
	private static final String NO_TEXT_MESSAGE = "?쒖떆???먮Ц ?띿뒪?몃? 李얠? 紐삵뻽?듬땲??";

	private final LawOpenApiProperties properties;
	private final RestClient restClient;
	private final RestClient lawRootClient;

	/**
	 * 踰뺣졊?쇳꽣 Open API? ?쇰컲 ??寃쎈줈瑜??몄텧??RestClient瑜?珥덇린?뷀빀?덈떎.
	 */
	public LawOpenApiService(LawOpenApiProperties properties) {
		this.properties = properties;
		// Open API 湲곕낯 二쇱냼濡?JSON/HTML ?묐떟??諛쏆쓣 ?대씪?댁뼵?몃? 留뚮벊?덈떎.
		this.restClient = RestClient.builder()
			.baseUrl(properties.baseUrl())
			.defaultHeader("User-Agent", "Mozilla/5.0")
			.defaultHeader("Accept", "application/json, text/html, */*")
			.build();
		// 踰뺣졊?쇳꽣 ???붾㈃怨?泥⑤? ?대?吏瑜??몄텧??蹂꾨룄 猷⑦듃 ?대씪?댁뼵?몃? 留뚮벊?덈떎.
		this.lawRootClient = RestClient.builder()
			.baseUrl("https://www.law.go.kr")
			.defaultHeader("User-Agent", "Mozilla/5.0")
			.defaultHeader("Accept", "text/html, application/xhtml+xml, */*")
			.build();
	}

	/**
	 * 踰뺣졊?쇳꽣 寃??API瑜??몄텧?섍퀬 ?몄쬆?ㅺ? ?몄텧?섏? ?딅룄濡?留덉뒪?뱁븳 JSON??諛섑솚?⑸땲??
	 */
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

		// 寃??URI瑜?議곕┰??踰뺣졊?쇳꽣 Open API??GET ?붿껌??蹂대깄?덈떎.
		byte[] responseBody = restClient.get()
			.uri(uriBuilder -> buildSearchUri(uriBuilder, target, safeQuery, safePage, safeDisplay))
			.retrieve()
			.body(byte[].class);

		String responseJson = new String(responseBody == null ? new byte[0] : responseBody, StandardCharsets.UTF_8);
		return maskApiKey(responseJson);
	}

	/**
	 * 踰뺣졊?쇳꽣 ?곸꽭 留곹겕瑜??몄텧??JSON ?곸꽭 ?먮뒗 HTML ?곸꽭瑜??붾㈃??JSON?쇰줈 蹂?섑빀?덈떎.
	 */
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

		// ?곸꽭 API???먮낯 留곹겕??寃쎈줈? ?뚮씪誘명꽣瑜?蹂댁〈?섎릺 ?몄쬆?ㅼ? ?묐떟 ??낆쓣 ?쒕쾭?먯꽌 蹂닿컯?⑸땲??
		byte[] responseBody = restClient.get()
			.uri(uriBuilder -> buildDetailUri(uriBuilder, endpointPath, queryParams))
			.retrieve()
			.body(byte[].class);

		String responseJson = new String(responseBody == null ? new byte[0] : responseBody, StandardCharsets.UTF_8);
		if (!responseJson.trim().startsWith("{") && !responseJson.trim().startsWith("[")) {
			// HTML ?묐떟 ?덉뿉 iframe???덉쑝硫??ㅼ젣 蹂몃Ц URL???ㅼ떆 ?몄텧??蹂몃Ц HTML???뺣낫?⑸땲??
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

	/**
	 * 踰뺣졊?쇳꽣 ?먮Ц/泥⑤? 由ъ냼?ㅻ? ?쒕쾭 寃쎌쑀 ?묐떟?쇰줈 以묎퀎?⑸땲??
	 */
	public ResponseEntity<byte[]> proxy(String link) {
		if (!StringUtils.hasText(properties.oc())) {
			throw new IllegalStateException("Law Open API key is not configured.");
		}
		// 留곹겕瑜??대? ?몄텧??寃쎈줈? ?뚮씪誘명꽣濡?遺꾪빐?⑸땲??
		ProxyRequest proxyRequest = buildProxyRequest(link);
		RestClient client = proxyRequest.path().startsWith("/LSW/") ? lawRootClient : restClient;
		return client.get()
			.uri(uriBuilder -> buildProxyUri(uriBuilder, proxyRequest.path(), proxyRequest.queryParams()))
			.retrieve()
			.toEntity(byte[].class);
	}

	/**
	 * 寃??API ?몄텧???꾩슂??URI瑜??앹꽦?⑸땲??
	 */
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

	/**
	 * ?곸꽭 API ?몄텧???꾩슂??URI瑜??앹꽦?⑸땲??
	 */
	private java.net.URI buildDetailUri(UriBuilder uriBuilder, String endpointPath, Map<String, String> queryParams) {
		UriBuilder builder = uriBuilder.path(endpointPath).queryParam("OC", properties.oc());
		// ?먮낯 ?곸꽭 留곹겕?먯꽌 諛쏆? ?뚮씪誘명꽣瑜?洹몃?濡??댁뼱 遺숈엯?덈떎.
		queryParams.forEach(builder::queryParam);
		return builder.build();
	}

	/**
	 * ?꾨줉?????由ъ냼???몄텧???꾩슂??URI瑜??앹꽦?⑸땲??
	 */
	private java.net.URI buildProxyUri(UriBuilder uriBuilder, String path, Map<String, String> queryParams) {
		UriBuilder builder = uriBuilder.path(path);
		// 泥⑤? ?뚯씪?대굹 HTML 酉곗뼱???꾩슂???먮낯 ?뚮씪誘명꽣瑜??좎??⑸땲??
		queryParams.forEach(builder::queryParam);
		return builder.build();
	}

	/**
	 * ?몃? 留곹겕瑜??꾨줉???몄텧???ъ슜???덉쟾??寃쎈줈? ?뚮씪誘명꽣濡?蹂?섑빀?덈떎.
	 */
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
		// ?먮낯 荑쇰━?ㅽ듃留곸쓣 Map?쇰줈 諛붽퓭 ?꾨줉??URI ?앹꽦???ъ궗?⑺빀?덈떎.
		Map<String, String> queryParams = parseQuery(queryStart >= 0 ? safeLink.substring(queryStart + 1) : "");
		if (path.startsWith("/DRF/")) {
			queryParams.put("OC", properties.oc());
			queryParams.putIfAbsent("type", "HTML");
		}
		return new ProxyRequest(endpointPath, queryParams);
	}

	/**
	 * 荑쇰━?ㅽ듃留곸쓣 ?쒖꽌瑜??좎??섎뒗 Map?쇰줈 ?뚯떛?⑸땲??
	 */
	private Map<String, String> parseQuery(String query) {
		if (!StringUtils.hasText(query)) {
			return new LinkedHashMap<>();
		}
		return Arrays.stream(query.split("&"))
			.map(parameter -> parameter.split("=", 2))
			.filter(parts -> parts.length == 2 && StringUtils.hasText(parts[0]))
			.collect(Collectors.toMap(parts -> parts[0], parts -> parts[1], (left, right) -> right, LinkedHashMap::new));
	}

	/**
	 * ?묐떟 蹂몃Ц???ы븿??Open API ?몄쬆?ㅻ? 留덉뒪?뱁빀?덈떎.
	 */
	private String maskApiKey(String responseBody) {
		return responseBody.replace("OC=" + properties.oc(), "OC=***");
	}

	/**
	 * HTML ?곸꽭 蹂몃Ц???꾨줎?몄뿏?쒓? 泥섎━?????덈뒗 ?⑥닚 JSON 援ъ“濡?蹂?섑빀?덈떎.
	 */
	private String buildHtmlDetailJson(String html) {
		// HTML title ?쒓렇瑜??곸꽭 ?붾㈃ ?쒕ぉ?쇰줈 ?ъ슜?⑸땲??
		String title = extractHtmlTitle(html);
		// 蹂몃Ц ???ㅼ슫濡쒕뱶 ?대?吏???꾨줉??留곹겕濡?諛붽퓭 ?붾㈃?먯꽌 諛붾줈 ?쒖떆?????덇쾶 ?⑸땲??
		String images = extractImageLinks(html).stream()
			.map(image -> "{\"src\":\"" + escapeJson(toProxyLink(image.src())) + "\",\"alt\":\"" + escapeJson(image.alt()) + "\"}")
			.collect(Collectors.joining(","));
		// ?쒓렇瑜??쒓굅???쎄린???띿뒪?몃? ?뱀뀡 蹂몃Ц?쇰줈 ?ъ슜?⑸땲??
		String text = extractReadableText(html);
		if (NO_TEXT_MESSAGE.equals(text) && StringUtils.hasText(images)) {
			text = "?뚯씪 ?먮Ц???꾨옒 ?대?吏濡??쒖떆?⑸땲??";
		}
		return """
			{"htmlDetail":true,"title":"%s","sections":[{"title":"?먮Ц ?댁슜","body":"%s","images":[%s]}]}
			""".formatted(escapeJson(title), escapeJson(text), images);
	}

	/**
	 * HTML title ?쒓렇?먯꽌 ?곸꽭 臾몄꽌 ?쒕ぉ??異붿텧?⑸땲??
	 */
	private String extractHtmlTitle(String html) {
		Matcher matcher = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
		if (matcher.find()) {
			return htmlToPlainText(matcher.group(1));
		}
		return "?먮Ц ?곸꽭";
	}

	/**
	 * HTML ?묐떟 ?덉뿉 ?ы븿??iframe ?ㅼ젣 蹂몃Ц 二쇱냼瑜?異붿텧?⑸땲??
	 */
	private String extractIframeSource(String html) {
		Matcher matcher = Pattern.compile("(?is)<iframe[^>]+src\\s*=\\s*[\"']?([^\"'\\s>]+)").matcher(html);
		return matcher.find() ? matcher.group(1).trim() : "";
	}

	/**
	 * ?꾨줉??留곹겕?????HTML ?먮뒗 ?뚯씪 ?댁슜??臾몄옄?대줈 議고쉶?⑸땲??
	 */
	private String fetchProxyText(String link) {
		// 留곹겕瑜??대? ?꾨줉???붿껌 媛앹껜濡?蹂?섑빐 ?곸젅??RestClient瑜??좏깮?⑸땲??
		ProxyRequest proxyRequest = buildProxyRequest(link);
		RestClient client = proxyRequest.path().startsWith("/LSW/") ? lawRootClient : restClient;
		byte[] responseBody = client.get()
			.uri(uriBuilder -> buildProxyUri(uriBuilder, proxyRequest.path(), proxyRequest.queryParams()))
			.retrieve()
			.body(byte[].class);
		return new String(responseBody == null ? new byte[0] : responseBody, StandardCharsets.UTF_8);
	}

	/**
	 * ?숈쟻 濡쒕뵫???꾩슂??HTML ?곸꽭?쇰㈃ ?ㅼ젣 蹂몃Ц HTML??異붽? 議고쉶??諛섑솚?⑸땲??
	 */
	private String resolveDynamicHtmlDetail(String link, String html) {
		if (!StringUtils.hasText(html)) {
			return html;
		}
		if (link.contains("admRulBylInfoP.do") || html.contains("admRulBylInfoR.do") || html.contains("admRulBylContentsInfoR.do")) {
			// ?됱젙洹쒖튃 蹂꾪몴瑜??붾㈃? ?좏깮媛믪쑝濡?蹂몃Ц???ㅼ떆 POST 議고쉶?댁빞 蹂몃Ц???섏샃?덈떎.
			return resolveAdminRuleBylHtml(link, html);
		}
		return html;
	}

	/**
	 * ?됱젙洹쒖튃 蹂꾪몴瑜?HTML ?붾㈃?먯꽌 ?좏깮??蹂꾪몴 ?뺣낫瑜?李얠븘 ?ㅼ젣 蹂몃Ц HTML??議고쉶?⑸땲??
	 */
	private String resolveAdminRuleBylHtml(String link, String html) {
		String infoHtml = html;
		// 理쒖큹 留곹겕???ㅼ뼱?덈뒗 蹂꾪몴 ?앸퀎?먮? ?뚮씪誘명꽣濡?蹂듭썝?⑸땲??
		ProxyRequest request = buildProxyRequest(link);
		Map<String, String> initialParams = request.queryParams();
		if (!html.contains("bylAdmRulId") && StringUtils.hasText(initialParams.get("bylSeq")) && StringUtils.hasText(initialParams.get("admRulSeq"))) {
			Map<String, String> params = new LinkedHashMap<>();
			params.put("bylSeq", initialParams.get("bylSeq"));
			params.put("admRulSeq", initialParams.get("admRulSeq"));
			params.put("vSct", initialParams.getOrDefault("vSct", ""));
			infoHtml = postLawForm("/LSW/admRulBylInfoR.do", params);
		}

		// 蹂꾪몴 ?좏깮媛믨낵 ?됱젙洹쒖튃 ID瑜?李얠븘 蹂몃Ц 議고쉶 ?붿껌??留뚮뱾 ???덈뒗吏 ?뺤씤?⑸땲??
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
		// ?좏깮??蹂꾪몴???ㅼ젣 蹂몃Ц HTML??POST濡?諛쏆븘?듬땲??
		String contentHtml = postLawForm("/LSW/admRulBylContentsInfoR.do", params);
		return StringUtils.hasText(extractReadableText(contentHtml)) ? contentHtml : infoHtml;
	}

	/**
	 * 踰뺣졊?쇳꽣 ???붾㈃??form-urlencoded POST ?붿껌??蹂대궡 HTML ?묐떟??諛쏆뒿?덈떎.
	 */
	private String postLawForm(String path, Map<String, String> params) {
		// ???뚮씪誘명꽣??踰뺣졊?쇳꽣 ???붾㈃??湲곕??섎뒗 ?몄퐫??諛⑹떇?쇰줈 議곕┰?⑸땲??
		String formBody = params.entrySet().stream()
			.map(entry -> encodeForm(entry.getKey()) + "=" + encodeForm(entry.getValue()))
			.collect(Collectors.joining("&"));
		// 踰뺣졊?쇳꽣 ??猷⑦듃濡?POST ?붿껌??蹂대궡 ?숈쟻?쇰줈 濡쒕뵫?섎뒗 蹂몃Ц HTML??諛쏆뒿?덈떎.
		byte[] responseBody = lawRootClient.post()
			.uri(path)
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(formBody)
			.retrieve()
			.body(byte[].class);
		return new String(responseBody == null ? new byte[0] : responseBody, StandardCharsets.UTF_8);
	}

	/**
	 * HTML form ?꾩넚??媛믪쑝濡?URL ?몄퐫?⑺빀?덈떎.
	 */
	private String encodeForm(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}

	/**
	 * 吏?뺥븳 id瑜?媛吏?input ?쒓렇??value 媛믪쓣 異붿텧?⑸땲??
	 */
	private String extractInputValue(String html, String id) {
		Matcher matcher = Pattern.compile("(?is)<input\\b[^>]*\\bid\\s*=\\s*[\"']" + Pattern.quote(id) + "[\"'][^>]*>").matcher(html);
		if (matcher.find()) {
			return extractAttribute(matcher.group(), "value");
		}
		return "";
	}

	/**
	 * 吏?뺥븳 select ?쒓렇?먯꽌 ?좏깮??option 媛믪쓣 異붿텧?섍퀬 ?놁쑝硫?泥?option 媛믪쓣 諛섑솚?⑸땲??
	 */
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

	/**
	 * HTML ?쒓렇 臾몄옄?댁뿉??吏?뺥븳 ?띿꽦 媛믪쓣 異붿텧?⑸땲??
	 */
	private String extractAttribute(String tag, String attribute) {
		Matcher matcher = Pattern.compile("(?is)\\b" + Pattern.quote(attribute) + "\\s*=\\s*([\"'])(.*?)\\1").matcher(tag);
		return matcher.find() ? htmlToPlainText(matcher.group(2)) : "";
	}

	/**
	 * HTML 蹂몃Ц?먯꽌 踰뺣졊?쇳꽣 ?뚯씪 ?ㅼ슫濡쒕뱶 ?대?吏 留곹겕瑜??섏쭛?⑸땲??
	 */
	private java.util.List<ImageLink> extractImageLinks(String html) {
		Matcher matcher = Pattern.compile("(?is)<img\\b[^>]*>").matcher(html);
		java.util.List<ImageLink> images = new java.util.ArrayList<>();
		while (matcher.find()) {
			String tag = matcher.group();
			String src = extractAttribute(tag, "src");
			if (StringUtils.hasText(src) && src.contains("/LSW/flDownload.do")) {
				String alt = extractAttribute(tag, "alt");
				images.add(new ImageLink(src, StringUtils.hasText(alt) ? alt : "?먮Ц ?대?吏"));
			}
		}
		return images;
	}

	/**
	 * ?몃? 由ъ냼??留곹겕瑜??꾨줎?몄뿏?쒓? ?몄텧???대? ?꾨줉??API 留곹겕濡?蹂?섑빀?덈떎.
	 */
	private String toProxyLink(String link) {
		String safeLink = link.startsWith("http") ? link : link.startsWith("/") ? link : "/" + link;
		return "/api/law-data/proxy?link=" + encodeForm(safeLink);
	}

	/**
	 * HTML ?쒓렇? 以묐났 以꾩쓣 ?쒓굅???붾㈃??蹂댁뿬以??쎄린???띿뒪?몃? 留뚮벊?덈떎.
	 */
	private String extractReadableText(String html) {
		// ?ㅽ겕由쏀듃/?ㅽ????쒓렇瑜?以꾨컮轅?以묒떖???쇰컲 ?띿뒪?몃줈 ?뺣━?⑸땲??
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

	/**
	 * HTML ?뷀떚?곗? 遺덊븘?뷀븳 怨듬갚???쇰컲 ?띿뒪?몃줈 ?뺣━?⑸땲??
	 */
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

	/**
	 * 吏곸젒 議곕┰?섎뒗 JSON 臾몄옄?댁뿉 ?ㅼ뼱媛?媛믪쓣 ?덉쟾?섍쾶 ?댁뒪耳?댄봽?⑸땲??
	 */
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

	private record ProxyRequest(String path, Map<String, String> queryParams) {
	}

	private record ImageLink(String src, String alt) {
	}
}
