package com.kaces.pandora.lawdata.sync;


import com.kaces.pandora.common.text.LawHashUtils;
import static com.kaces.pandora.common.text.LawHashUtils.sha256;
import static com.kaces.pandora.common.text.LawTextUtils.stripHtmlTags;

import com.kaces.pandora.common.json.LawJsonWriter;
import com.kaces.pandora.lawdata.version.LawVersionUtils;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class LawOpenApiPayloadParser {

	private static final Pattern LAW_ARTICLE_NODE_PATH = Pattern.compile("^\\$\\.법령\\.조문\\.조문단위\\[[0-9]+]$");
	private static final Pattern ARTICLE_NO_PREFIX = Pattern.compile("^(제\\d+조(?:의\\d+)?)");

	private static final Pattern ARTICLE_HEADING_PREFIX = Pattern.compile("^(\\uC81C\\d+\\uC870(?:\\uC758\\d+)?\\([^\\n]{1,120}?\\))");
	private static final Pattern ADMIN_RULE_ARTICLE_HEADING_PREFIX = Pattern.compile(
		"^(\\uC81C\\d+\\uC870(?:\\uC758\\d+)?)(?![\\p{L}\\p{N}])(\\([^\\n]{1,120}?\\))?"
	);
	private static final Pattern ADMIN_RULE_CHAPTER_HEADING = Pattern.compile(
		"^(\\uC81C\\d+\\uC7A5(?:\\uC758\\d+)?(?:\\s+[^\\n]{1,120})?)$"
	);
	private static final String ADMIN_RULE_ARTICLE_CONTENT_PATH = "$.AdmRulService.\uC870\uBB38\uB0B4\uC6A9";
	private static final String ARTICLE_EFFECTIVE_DATE_TEXT_KEY = "\uC870\uBB38\uC2DC\uD589\uC77C\uC790\uBB38\uC790\uC5F4";
	private static final String REVISION_TEXT_PATH_TOKEN = ".\uAC1C\uC815\uBB38.";
	private static final String REVISION_REASON_PATH_TOKEN = ".\uC81C\uAC1C\uC815\uC774\uC720.";
	private static final String REVISION_REASON_ALT_PATH_TOKEN = ".\uAC1C\uC815\uC774\uC720.";

	private final ObjectMapper objectMapper;
	private final LawJsonWriter jsonWriter;
	private final Clock clock;
	// 메소드 설명: LawOpenApiPayloadParser 처리 흐름을 수행합니다.
	public LawOpenApiPayloadParser(ObjectMapper objectMapper, LawJsonWriter jsonWriter) {
		this.objectMapper = objectMapper;
		this.jsonWriter = jsonWriter;
		this.clock = Clock.system(ZoneId.of("Asia/Seoul"));
	}
	// 메소드 설명: parseSearchDocuments 처리 흐름을 수행합니다.
	public List<SearchDocument> parseSearchDocuments(String target, String searchJson) {
		try {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			JsonNode payload = objectMapper.readTree(searchJson);
			return extractSearchRows(payload).stream()
				.map(row -> toSearchDocument(target, row))
				.toList();
		} catch (Exception exception) {
			throw new IllegalStateException("Law search payload parsing failed.", exception);
		}
	}
	// 메소드 설명: parseDetailDocument 처리 흐름을 수행합니다.
	public SyncDetailDocument parseDetailDocument(String detailJson, String fallbackTitle) {
		try {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			JsonNode payload = objectMapper.readTree(detailJson);
			return toDetailDocument(payload, fallbackTitle);
		} catch (Exception exception) {
			throw new IllegalStateException("Law detail payload parsing failed.", exception);
		}
	}
	// 메소드 설명: extractSearchRows 처리 흐름을 수행합니다.
	private List<JsonNode> extractSearchRows(JsonNode payload) {
		List<JsonNode> rows = new ArrayList<>();
		if (payload == null || payload.isNull()) {
			return rows;
		}
		JsonNode root = payload;
		if (payload.size() == 1) {
			root = payload.properties().iterator().next().getValue();
		}
		if (root.isArray()) {
			root.valueStream().forEach(rows::add);
			return rows;
		}
		root.properties().forEach(entry -> {
			if (isReservedSearchKey(entry.getKey())) {
				return;
			}
			JsonNode value = entry.getValue();
			if (value.isArray()) {
				value.valueStream().forEach(rows::add);
			} else if (value.isObject()) {
				rows.add(value);
			}
		});
		return rows;
	}
	// 메소드 설명: isReservedSearchKey 처리 흐름을 수행합니다.
	private boolean isReservedSearchKey(String key) {
		return "resultCode".equals(key)
			|| "resultMsg".equals(key)
			|| "target".equals(key)
			|| "키워드".equals(key)
			|| "section".equals(key)
			|| "totalCnt".equals(key)
			|| "page".equals(key)
			|| "numOfRows".equals(key);
	}
	// 메소드 설명: toSearchDocument 처리 흐름을 수행합니다.
	private SearchDocument toSearchDocument(String target, JsonNode row) {
		String title = firstText(row, "법령명한글", "법령명", "법령명_한글", "행정규칙명", "자치법규명", "판례명", "사건명", "제목");
		String externalId = firstText(row, "법령일련번호", "행정규칙일련번호", "자치법규일련번호", "판례일련번호", "행정규칙ID", "ID", "id");
		if (!StringUtils.hasText(externalId)) {
			externalId = sha256(toJson(row)).substring(0, 32);
		}
		if (!StringUtils.hasText(title)) {
			title = target + "-" + externalId;
		}
		String agency = firstText(row, "소관부처명", "부처명", "기관명", "법원명", "자치단체명", "발령기관명");
		String category = firstText(row, "법령구분명", "행정규칙종류", "자치법규종류", "구분", "분류");
		String sourceDate = firstText(row, "시행일자", "공포일자", "발령일자", "선고일자", "의결일자", "자치법규시행일자");
		String canonicalKey = LawVersionUtils.canonicalKey(target, title);
		String effectiveDate = LawVersionUtils.normalizeEffectiveDate(sourceDate);
		String effectiveStatus = LawVersionUtils.initialStatus(target, effectiveDate, clock);
		String detailLink = findDetailLink(row);
		if (!StringUtils.hasText(detailLink)) {
			detailLink = buildFallbackDetailLink(target, externalId);
		}
		return new SearchDocument(target, externalId, title, agency, category, sourceDate, canonicalKey, effectiveDate, effectiveStatus, detailLink, toJson(row));
	}
	// 메소드 설명: buildFallbackDetailLink 처리 흐름을 수행합니다.
	private String buildFallbackDetailLink(String target, String externalId) {
		if ("admrul".equals(target)) {
			return "/DRF/lawService.do?OC=***&target=admrul&ID=" + externalId + "&type=HTML&mobileYn=";
		}
		return "/DRF/lawService.do?OC=***&target=law&MST=" + externalId + "&type=HTML&mobileYn=";
	}
	// 메소드 설명: toDetailDocument 처리 흐름을 수행합니다.
	private SyncDetailDocument toDetailDocument(JsonNode payload, String fallbackTitle) {
		String title = firstText(payload, "title", "법령명_한글", "법령명한글", "법령명", "행정규칙명");
		if (!StringUtils.hasText(title)) {
			title = fallbackTitle;
		}
		List<SyncDetailSection> sections = extractSections(payload);
		if (sections.isEmpty()) {
			String text = firstLongText(payload);
			if (StringUtils.hasText(text)) {
				sections.add(new SyncDetailSection("detail", null, "상세 내용", text));
			}
		}
		return new SyncDetailDocument(title, sections, extractAssets(payload));
	}
	// 메소드 설명: extractSections 처리 흐름을 수행합니다.
	private List<SyncDetailSection> extractSections(JsonNode payload) {
		List<SyncDetailSection> sections = new ArrayList<>();
		JsonNode existingSections = payload.get("sections");
		if (existingSections != null && existingSections.isArray()) {
			for (JsonNode section : existingSections) {
				sections.add(new SyncDetailSection(
					firstText(section, "type"),
					firstText(section, "no"),
					firstText(section, "title"),
					firstText(section, "body", "text", "content")
				));
			}
			return sections;
		}
		collectLongTextSections(payload, "$", sections);
		return sections;
	}
	// 메소드 설명: collectLongTextSections 처리 흐름을 수행합니다.
	private void collectLongTextSections(JsonNode node, String path, List<SyncDetailSection> sections) {
		if (node == null || node.isNull()) {
			return;
		}
		if (node.isArray() && ADMIN_RULE_ARTICLE_CONTENT_PATH.equals(path) && hasAdministrativeRuleArticles(node)) {
			collectAdministrativeRuleSections(node, path, sections);
			return;
		}
		if (node.isObject() && LAW_ARTICLE_NODE_PATH.matcher(path).matches()) {
			collectLawArticleSections(node, path, sections);
			return;
		}
		if (node.isTextual()) {
			if (isExcludedLongTextPath(path)) {
				return;
			}
			addTextSections(sections, "text", null, path, node.asText());
			return;
		}
		if (node.isArray()) {
			int index = 0;
			for (JsonNode item : node) {
				collectLongTextSections(item, path + "[" + index + "]", sections);
				index++;
			}
			return;
		}
		node.properties().forEach(entry -> {
			String nextPath = "$".equals(path) ? "$." + entry.getKey() : path + "." + entry.getKey();
			collectLongTextSections(entry.getValue(), nextPath, sections);
		});
	}

	private boolean hasAdministrativeRuleArticles(JsonNode node) {
		for (JsonNode item : node) {
			if (item.isTextual()
				&& ADMIN_RULE_ARTICLE_HEADING_PREFIX.matcher(stripHtmlTags(item.asText()).stripLeading()).find()) {
				return true;
			}
		}
		return false;
	}

	private void collectAdministrativeRuleSections(JsonNode node, String path, List<SyncDetailSection> sections) {
		for (int index = 0; index < node.size(); index++) {
			JsonNode item = node.get(index);
			String itemPath = path + "[" + index + "]";
			if (!item.isTextual()) {
				collectLongTextSections(item, itemPath, sections);
				continue;
			}
			String body = stripHtmlTags(item.asText());
			if (!StringUtils.hasText(body)) {
				continue;
			}
			String leading = body.strip();
			var article = ADMIN_RULE_ARTICLE_HEADING_PREFIX.matcher(leading);
			if (article.find()) {
				String no = article.group(1);
				String suffix = article.group(2) == null ? "" : article.group(2);
				addStructuredSection(
					sections,
					"admin-rule-article",
					no,
					no + suffix,
					body,
					itemPath,
					index + 1,
					1
				);
				continue;
			}
			var chapter = ADMIN_RULE_CHAPTER_HEADING.matcher(leading);
			if (chapter.matches()) {
				continue;
			}
			addTextSections(sections, "text", null, itemPath, body);
		}
	}

	private boolean isExcludedLongTextPath(String path) {
		return StringUtils.hasText(path)
			&& (path.endsWith("." + ARTICLE_EFFECTIVE_DATE_TEXT_KEY)
				|| path.contains(REVISION_TEXT_PATH_TOKEN)
				|| path.contains(REVISION_REASON_PATH_TOKEN)
				|| path.contains(REVISION_REASON_ALT_PATH_TOKEN));
	}

	private void collectLawArticleSections(JsonNode article, String path, List<SyncDetailSection> sections) {
		String articleBody = stripHtmlTags(firstText(article, "조문내용"));
		String articleNo = articleNo(article, articleBody);
		String articleTitle = articleTitle(article, articleBody, articleNo);
		if (!hasLawUnits(article.get("항")) || !isHeadingOnlyArticleBody(articleBody, articleTitle)) {
			addStructuredSection(sections, "article", articleNo, articleTitle, articleBody, path + ".조문내용", 0, 0);
		}
		collectLawUnits(article.get("항"), path + ".항", "항단위", "paragraph", "항내용", articleNo, articleTitle, sections);
	}

	private boolean hasLawUnits(JsonNode node) {
		return node != null && !node.isNull() && !unitNodes(node, "", "항단위").isEmpty();
	}

	private boolean isHeadingOnlyArticleBody(String articleBody, String articleTitle) {
		if (!StringUtils.hasText(articleBody)) {
			return true;
		}
		if (!StringUtils.hasText(articleTitle)) {
			return false;
		}
		String normalizedBody = stripHtmlTags(articleBody).trim();
		String normalizedTitle = stripHtmlTags(articleTitle).trim();
		return normalizedBody.equals(normalizedTitle);
	}

	private void collectLawUnits(
		JsonNode node,
		String path,
		String wrapperName,
		String type,
		String contentField,
		String articleNo,
		String articleTitle,
		List<SyncDetailSection> sections
	) {
		if (node == null || node.isNull()) {
			return;
		}
		List<UnitNode> units = unitNodes(node, path, wrapperName);
		for (int index = 0; index < units.size(); index++) {
			UnitNode unit = units.get(index);
			addStructuredSection(
				sections,
				type,
				articleNo,
				articleTitle,
				firstText(unit.node(), contentField),
				unit.path() + "." + contentField,
				index + 1,
				1
			);
			collectLawUnits(unit.node().get("호"), unit.path() + ".호", "호단위", "subparagraph", "호내용", articleNo, articleTitle, sections);
			collectLawUnits(unit.node().get("목"), unit.path() + ".목", "목단위", "item", "목내용", articleNo, articleTitle, sections);
		}
	}

	private List<UnitNode> unitNodes(JsonNode node, String path, String wrapperName) {
		List<UnitNode> units = new ArrayList<>();
		if (node.isArray()) {
			for (int index = 0; index < node.size(); index++) {
				units.add(new UnitNode(node.get(index), path + "[" + index + "]"));
			}
			return units;
		}
		JsonNode wrapped = node.get(wrapperName);
		if (wrapped != null && wrapped.isArray()) {
			for (int index = 0; index < wrapped.size(); index++) {
				units.add(new UnitNode(wrapped.get(index), path + "." + wrapperName + "[" + index + "]"));
			}
			return units;
		}
		if (node.isObject()) {
			units.add(new UnitNode(node, path));
		}
		return units;
	}

	private void addStructuredSection(List<SyncDetailSection> sections, String type, String no, String title, String body, String sourcePath, int paragraphNo, int lineNo) {
		String normalized = stripHtmlTags(body);
		if (!StringUtils.hasText(normalized)) {
			return;
		}
		sections.add(new SyncDetailSection(type, no, title, normalized, sourcePath, paragraphNo, lineNo));
	}

	private String articleNo(JsonNode article, String articleBody) {
		var matcher = ARTICLE_NO_PREFIX.matcher(articleBody.stripLeading());
		if (matcher.find()) {
			return matcher.group(1);
		}
		String number = firstText(article, "조문번호");
		if (!StringUtils.hasText(number)) {
			return "";
		}
		String branch = firstText(article, "조문가지번호");
		if (StringUtils.hasText(branch) && !"0".equals(branch)) {
			return "제" + number + "조의" + branch;
		}
		return "제" + number + "조";
	}

	private String articleTitle(JsonNode article, String articleBody, String articleNo) {
		String body = articleBody.stripLeading();
		String heading = articleHeading(body);
		if (StringUtils.hasText(heading)) {
			return heading;
		}
		String title = firstText(article, "조문제목");
		if (StringUtils.hasText(articleNo) && StringUtils.hasText(title)) {
			return articleNo + "(" + title + ")";
		}
		if (StringUtils.hasText(body)) {
			return body;
		}
		return title;
	}
	// 메소드 설명: addTextSections 처리 흐름을 수행합니다.
	private String articleHeading(String body) {
		if (!StringUtils.hasText(body)) {
			return "";
		}
		var matcher = ARTICLE_HEADING_PREFIX.matcher(body.stripLeading());
		if (!matcher.find()) {
			return "";
		}
		return matcher.group(1);
	}

	private void addTextSections(List<SyncDetailSection> sections, String type, String no, String title, String text) {
		String normalized = stripHtmlTags(text);
		if (normalized.length() < 80) {
			return;
		}
		int chunkSize = 4_000;
		for (int start = 0; start < normalized.length(); start += chunkSize) {
			int end = Math.min(start + chunkSize, normalized.length());
			int chunkNo = (start / chunkSize) + 1;
			sections.add(new SyncDetailSection(type, no, title + "#" + chunkNo, normalized.substring(start, end), title, 0, chunkNo));
		}
	}
	// 메소드 설명: extractAssets 처리 흐름을 수행합니다.
	private List<SyncAsset> extractAssets(JsonNode payload) {
		Map<String, SyncAsset> assets = new LinkedHashMap<>();
		findAssetLinks(payload, assets);
		return List.copyOf(assets.values());
	}
	// 메소드 설명: findAssetLinks 처리 흐름을 수행합니다.
	private void findAssetLinks(JsonNode node, Map<String, SyncAsset> assets) {
		if (node == null || node.isNull()) {
			return;
		}
		if (node.isTextual()) {
			addAsset(assets, node.asText(), "");
			return;
		}
		if (node.isArray()) {
			node.valueStream().forEach(item -> findAssetLinks(item, assets));
			return;
		}
		node.properties().forEach(entry -> findAssetLinks(entry.getValue(), assets));
	}
	// 메소드 설명: addAsset 처리 흐름을 수행합니다.
	private void addAsset(Map<String, SyncAsset> assets, String sourceUrl, String altText) {
		if (!StringUtils.hasText(sourceUrl) || !(sourceUrl.contains("flDownload.do") || sourceUrl.matches("(?i).+\\.(png|jpg|jpeg|gif|pdf|hwp|hwpx)$"))) {
			return;
		}
		String safeUrl = sourceUrl.startsWith("http") || sourceUrl.startsWith("/") ? sourceUrl : "/" + sourceUrl;
		String proxyUrl = "/api/law-data/proxy?link=" + URLEncoder.encode(safeUrl, StandardCharsets.UTF_8);
		assets.putIfAbsent(safeUrl, new SyncAsset("file", safeUrl, proxyUrl, fileName(safeUrl), fileExtension(safeUrl), altText));
	}
	// 메소드 설명: findDetailLink 처리 흐름을 수행합니다.
	private String findDetailLink(JsonNode row) {
		for (var field : row.properties()) {
			String key = field.getKey().toLowerCase();
			if (key.contains("link") || field.getKey().contains("상세링크") || field.getKey().contains("파일링크")) {
				return field.getValue().isTextual() ? field.getValue().asText() : field.getValue().toString();
			}
		}
		return "";
	}
	// 메소드 설명: firstText 처리 흐름을 수행합니다.
	private String firstText(JsonNode node, String... fieldNames) {
		for (String fieldName : fieldNames) {
			JsonNode found = node.findValue(fieldName);
			if (found != null && !found.isNull()) {
				String value = found.isTextual() ? found.asText() : found.toString();
				if (StringUtils.hasText(value)) {
					return value;
				}
			}
		}
		return "";
	}
	// 메소드 설명: firstLongText 처리 흐름을 수행합니다.
	private String firstLongText(JsonNode node) {
		List<String> texts = new ArrayList<>();
		collectTexts(node, texts);
		return texts.stream().filter(text -> text.length() >= 80).findFirst().orElse("");
	}
	// 메소드 설명: collectTexts 처리 흐름을 수행합니다.
	private void collectTexts(JsonNode node, List<String> texts) {
		if (node == null || node.isNull()) {
			return;
		}
		if (node.isTextual()) {
			texts.add(stripHtmlTags(node.asText()));
			return;
		}
		if (node.isArray()) {
			node.valueStream().forEach(item -> collectTexts(item, texts));
			return;
		}
		node.properties().forEach(entry -> collectTexts(entry.getValue(), texts));
	}
	// 메소드 설명: toJson 처리 흐름을 수행합니다.
	private String toJson(Object value) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return jsonWriter.write(value);
	}
	// 메소드 설명: fileName 처리 흐름을 수행합니다.
	private String fileName(String sourceUrl) {
		int queryStart = sourceUrl.indexOf('?');
		String path = queryStart >= 0 ? sourceUrl.substring(0, queryStart) : sourceUrl;
		int slash = path.lastIndexOf('/');
		return slash >= 0 ? path.substring(slash + 1) : path;
	}
	// 메소드 설명: fileExtension 처리 흐름을 수행합니다.
	private String fileExtension(String sourceUrl) {
		String fileName = fileName(sourceUrl);
		int dot = fileName.lastIndexOf('.');
		return dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "";
	}

	private record UnitNode(JsonNode node, String path) {
	}
}
