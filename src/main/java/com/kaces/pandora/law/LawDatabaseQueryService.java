package com.kaces.pandora.law;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class LawDatabaseQueryService {

	private static final Pattern ADMIN_ARTICLE_TITLE_PATTERN = Pattern.compile("^(\\uC81C\\d+\\uC870(?:\\uC758\\d+)?\\([^)]*\\)|\\uC81C\\d+\\uC870(?:\\uC758\\d+)?)");

	private static final Pattern ARTICLE_TITLE_PATTERN = Pattern.compile("^(제\\d+조(?:의\\d+)?\\s*\\([^)]*\\)|제\\d+조(?:의\\d+)?|부칙|별표\\s*\\d*)");

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	public LawDatabaseQueryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	public String search(String target, String query, int page, int display) {
		String safeTarget = StringUtils.hasText(target) ? target.trim() : "law";
		String safeQuery = StringUtils.hasText(query) ? query.trim() : "*";
		int safePage = Math.max(page, 1);
		int safeDisplay = Math.min(Math.max(display, 1), 100);
		int offset = (safePage - 1) * safeDisplay;
		boolean searchAll = "*".equals(safeQuery);

		String whereSql = searchAll
			? "target = ? AND use_yn = 'Y'"
			: "target = ? AND use_yn = 'Y' AND (title LIKE ? OR agency_name LIKE ? OR category_name LIKE ?)";
		Object[] countArgs = searchAll
			? new Object[] { safeTarget }
			: new Object[] { safeTarget, like(safeQuery), like(safeQuery), like(safeQuery) };
		Integer total = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM law_api_documents WHERE " + whereSql,
			Integer.class,
			countArgs
		);

		Object[] rowArgs = searchAll
			? new Object[] { safeTarget, safeDisplay, offset }
			: new Object[] { safeTarget, like(safeQuery), like(safeQuery), like(safeQuery), safeDisplay, offset };
		List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
			SELECT document_id, target, external_id, title, agency_name, category_name, source_date, detail_link
			FROM law_api_documents
			WHERE %s
			ORDER BY title, external_id
			LIMIT ? OFFSET ?
			""".formatted(whereSql), rowArgs);

		List<Map<String, Object>> laws = rows.stream()
			.map(this::toLawSearchRow)
			.toList();
		Map<String, Object> lawSearch = new LinkedHashMap<>();
		lawSearch.put("resultCode", "00");
		lawSearch.put("resultMsg", "DB");
		lawSearch.put("target", safeTarget);
		lawSearch.put("query", safeQuery);
		lawSearch.put("page", safePage);
		lawSearch.put("numOfRows", safeDisplay);
		lawSearch.put("totalCnt", total == null ? 0 : total);
		lawSearch.put("law", laws);
		return toJson(Map.of("LawSearch", lawSearch));
	}

	public String detail(String link) {
		long documentId = parseDocumentId(link);
		Map<String, Object> detail = jdbcTemplate.queryForMap("""
			SELECT d.document_id, doc.title, doc.agency_name, doc.source_date,
				d.detail_title, d.sections_json, d.raw_json
			FROM law_api_document_details d
			JOIN law_api_documents doc ON doc.document_id = d.document_id
			WHERE d.document_id = ?
			""", documentId);
		String rawJson = (String) detail.get("raw_json");
		List<Map<String, Object>> sections = readSections(rawJson, (String) detail.get("sections_json"));

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("htmlDetail", true);
		response.put("source", "DB");
		response.put("documentId", documentId);
		response.put("title", firstNonBlank((String) detail.get("detail_title"), (String) detail.get("title")));
		response.put("meta", readDetailMeta(rawJson, detail));
		response.put("sections", sections);
		return toJson(response);
	}

	private List<String> readDetailMeta(String rawJson, Map<String, Object> detail) {
		List<String> meta = new ArrayList<>();
		if (StringUtils.hasText(rawJson)) {
			try {
				JsonNode basic = child(child(objectMapper.readTree(rawJson), "\uBC95\uB839"), "\uAE30\uBCF8\uC815\uBCF4");
				if (basic != null) {
					addMeta(meta, "\uC2DC\uD589 " + formatDate(text(basic, "\uC2DC\uD589\uC77C\uC790", "")));
					addMeta(meta, lawRevisionText(basic));
					for (JsonNode department : nodes(child(child(basic, "\uC5F0\uB77D\uBD80\uC11C"), "\uBD80\uC11C\uB2E8\uC704"))) {
						String agency = text(department, "\uC18C\uAD00\uBD80\uCC98\uBA85", "");
						String name = text(department, "\uBD80\uC11C\uBA85", "");
						String phone = text(department, "\uBD80\uC11C\uC5F0\uB77D\uCC98", "");
						addMeta(meta, agency + (StringUtils.hasText(name) ? " (" + name + ")" : "") + (StringUtils.hasText(phone) ? " " + phone : ""));
					}
				}
			} catch (Exception ignored) {
				// Fall back to document columns below.
			}
		}
		if (meta.isEmpty()) {
			addMeta(meta, String.valueOf(detail.getOrDefault("agency_name", "")));
			addMeta(meta, formatDate(String.valueOf(detail.getOrDefault("source_date", ""))));
		}
		return meta;
	}

	private String lawRevisionText(JsonNode basic) {
		String lawType = text(child(basic, "\uBC95\uC885\uAD6C\uBD84"), "content", "");
		String promulgationNo = text(basic, "\uACF5\uD3EC\uBC88\uD638", "");
		String promulgationDate = formatDate(text(basic, "\uACF5\uD3EC\uC77C\uC790", ""));
		String revisionType = text(basic, "\uC81C\uAC1C\uC815\uAD6C\uBD84", "");
		List<String> parts = new ArrayList<>();
		addMeta(parts, lawType + (StringUtils.hasText(promulgationNo) ? " \uC81C" + promulgationNo + "\uD638" : ""));
		addMeta(parts, promulgationDate);
		addMeta(parts, revisionType);
		return String.join(", ", parts);
	}

	private void addMeta(List<String> meta, String value) {
		if (StringUtils.hasText(value)) {
			meta.add(value.trim());
		}
	}

	private Map<String, Object> toLawSearchRow(Map<String, Object> row) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("documentId", row.get("document_id"));
		result.put("target", row.get("target"));
		result.put("법령일련번호", row.get("external_id"));
		result.put("법령명한글", row.get("title"));
		result.put("소관부처명", row.get("agency_name"));
		result.put("법령구분명", row.get("category_name"));
		result.put("시행일자", row.get("source_date"));
		result.put("법령상세링크", "db:" + row.get("document_id"));
		result.put("source", "DB");
		return result;
	}

	private List<Map<String, Object>> readSections(String rawJson, String sectionsJson) {
		List<Map<String, Object>> articleSections = readArticleSections(rawJson);
		if (!articleSections.isEmpty()) {
			return articleSections;
		}
		if (!StringUtils.hasText(sectionsJson)) {
			return List.of(Map.of("title", "상세 내용", "body", "표시할 상세 내용이 없습니다."));
		}
		try {
			JsonNode root = objectMapper.readTree(sectionsJson);
			if (!root.isArray()) {
				return List.of(Map.of("title", "상세 내용", "body", root.toString()));
			}
			return root.valueStream()
				.map(section -> {
					Map<String, Object> item = new LinkedHashMap<>();
					String body = text(section, "body", "");
					item.put("title", cleanSectionTitle(text(section, "title", ""), body));
					item.put("body", body);
					return item;
				})
				.filter(section -> StringUtils.hasText((String) section.get("body")))
				.toList();
		} catch (Exception exception) {
			throw new IllegalStateException("Stored detail sections could not be parsed.", exception);
		}
	}

	private List<Map<String, Object>> readArticleSections(String rawJson) {
		if (!StringUtils.hasText(rawJson)) {
			return List.of();
		}
		try {
			JsonNode root = objectMapper.readTree(rawJson);
			JsonNode articleUnits = child(child(child(root, "\uBC95\uB839"), "\uC870\uBB38"), "\uC870\uBB38\uB2E8\uC704");
			if (articleUnits == null) {
				return readAdminRuleArticleSections(root);
			}
			return nodes(articleUnits).stream()
				.map(this::toArticleSection)
				.filter(section -> StringUtils.hasText((String) section.get("title")) || StringUtils.hasText((String) section.get("body")))
				.toList();
		} catch (Exception exception) {
			return List.of();
		}
	}

	private List<Map<String, Object>> readAdminRuleArticleSections(JsonNode root) {
		JsonNode serviceRoot = child(root, "AdmRulService");
		JsonNode articleRoot = child(serviceRoot, "\uC870\uBB38");
		JsonNode contents = child(articleRoot, "\uC870\uBB38\uB0B4\uC6A9");
		if (contents == null) {
			contents = child(serviceRoot, "\uC870\uBB38\uB0B4\uC6A9");
		}
		if (contents == null) {
			return List.of();
		}
		List<JsonNode> contentNodes = nodes(contents);
		List<JsonNode> numbers = nodes(child(articleRoot, "\uC870\uBB38\uBC88\uD638"));
		List<JsonNode> titles = nodes(child(articleRoot, "\uC870\uBB38\uC81C\uBAA9"));
		List<Map<String, Object>> sections = new ArrayList<>();
		for (int i = 0; i < contentNodes.size(); i++) {
			String body = nodeText(contentNodes.get(i));
			if (!StringUtils.hasText(body)) {
				continue;
			}
			String title = adminArticleTitle(body);
			if (!StringUtils.hasText(title)) {
				title = formatAdminArticleTitle(nodeTextAt(numbers, i), nodeTextAt(titles, i));
			}
			Map<String, Object> section = new LinkedHashMap<>();
			section.put("title", title);
			section.put("body", stripTitle(body, title));
			sections.add(section);
		}
		return sections;
	}

	private String adminArticleTitle(String body) {
		Matcher matcher = ADMIN_ARTICLE_TITLE_PATTERN.matcher(body.stripLeading());
		return matcher.find() ? matcher.group(1) : "";
	}

	private String formatAdminArticleTitle(String articleNo, String articleTitle) {
		String normalizedNo = articleNo == null ? "" : articleNo.replaceFirst("^0+", "");
		String title = StringUtils.hasText(normalizedNo) ? "\uC81C" + normalizedNo + "\uC870" : "";
		if (StringUtils.hasText(articleTitle)) {
			return title + "(" + articleTitle + ")";
		}
		return title;
	}

	private String nodeTextAt(List<JsonNode> nodes, int index) {
		return index >= 0 && index < nodes.size() ? nodeText(nodes.get(index)) : "";
	}

	private String nodeText(JsonNode node) {
		if (node == null || node.isNull()) {
			return "";
		}
		return node.isTextual() ? node.asText() : node.toString();
	}

	private Map<String, Object> toArticleSection(JsonNode article) {
		String articleNo = text(article, "\uC870\uBB38\uBC88\uD638", "");
		String articleTitle = text(article, "\uC870\uBB38\uC81C\uBAA9", "");
		String articleContent = text(article, "\uC870\uBB38\uB0B4\uC6A9", "");
		String title = formatArticleTitle(articleNo, articleTitle, articleContent);
		List<String> lines = new java.util.ArrayList<>();
		if (!isStructuralTitle(title, articleContent)) {
			addLine(lines, stripTitle(articleContent, title));
		}
		appendUnits(lines, unitNode(article, "\uD56D", "\uD56D\uB2E8\uC704"), "\uD56D\uB0B4\uC6A9");

		Map<String, Object> section = new LinkedHashMap<>();
		section.put("title", title);
		section.put("body", String.join("\n", lines));
		return section;
	}

	private void appendUnits(List<String> lines, JsonNode units, String contentKey) {
		for (JsonNode unit : nodes(units)) {
			addLine(lines, text(unit, contentKey, ""));
			appendUnits(lines, unitNode(unit, "\uD638", "\uD638\uB2E8\uC704"), "\uD638\uB0B4\uC6A9");
			appendUnits(lines, unitNode(unit, "\uBAA9", "\uBAA9\uB2E8\uC704"), "\uBAA9\uB0B4\uC6A9");
		}
	}

	private JsonNode unitNode(JsonNode node, String containerKey, String unitKey) {
		JsonNode container = child(node, containerKey);
		if (container == null || container.isNull()) {
			return node == null ? null : node.findValue(unitKey);
		}
		if (container.isArray()) {
			return container;
		}
		JsonNode unit = child(container, unitKey);
		return unit == null || unit.isNull() ? container : unit;
	}

	private List<JsonNode> nodes(JsonNode node) {
		if (node == null || node.isNull()) {
			return List.of();
		}
		if (node.isArray()) {
			return node.valueStream().toList();
		}
		return List.of(node);
	}

	private JsonNode child(JsonNode node, String fieldName) {
		return node == null || node.isNull() ? null : node.get(fieldName);
	}

	private String formatArticleTitle(String articleNo, String articleTitle, String articleContent) {
		String normalizedContent = articleContent == null ? "" : articleContent.trim();
		if (!StringUtils.hasText(articleTitle) && normalizedContent.matches("^제\\d+[장절관]\\s+.*")) {
			return normalizedContent;
		}
		String formattedNo = articleNo != null && articleNo.matches("\\d+(의\\d+)?") ? "제" + articleNo + "조" : articleNo;
		if (StringUtils.hasText(articleNo) && StringUtils.hasText(articleTitle)) {
			return formattedNo + "(" + articleTitle + ")";
		}
		return firstNonBlank(formattedNo, articleTitle);
	}

	private boolean isStructuralTitle(String title, String articleContent) {
		return StringUtils.hasText(title)
			&& title.equals(articleContent == null ? "" : articleContent.trim())
			&& title.matches("^제\\d+[장절관]\\s+.*");
	}

	private String stripTitle(String body, String title) {
		if (!StringUtils.hasText(body) || !StringUtils.hasText(title)) {
			return body;
		}
		return body.startsWith(title) ? body.substring(title.length()).stripLeading() : body;
	}

	private void addLine(List<String> lines, String value) {
		if (StringUtils.hasText(value)) {
			lines.add(value.trim());
		}
	}

	private String cleanSectionTitle(String storedTitle, String body) {
		if (StringUtils.hasText(storedTitle) && !storedTitle.startsWith("$.")) {
			return storedTitle;
		}

		String normalizedBody = body == null ? "" : body.stripLeading();
		Matcher matcher = ARTICLE_TITLE_PATTERN.matcher(normalizedBody);
		return matcher.find() ? matcher.group(1) : "";
	}

	private String text(JsonNode node, String fieldName, String defaultValue) {
		JsonNode value = node.get(fieldName);
		if (value == null || value.isNull()) {
			return defaultValue;
		}
		return value.isTextual() ? value.asText() : value.toString();
	}

	private long parseDocumentId(String link) {
		if (!StringUtils.hasText(link) || !link.startsWith("db:")) {
			throw new IllegalArgumentException("DB detail link is required.");
		}
		try {
			return Long.parseLong(link.substring(3));
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("Invalid DB detail link.", exception);
		}
	}

	private String like(String value) {
		return "%" + value + "%";
	}

	private String formatDate(String value) {
		if (value == null) {
			return "";
		}
		String digits = value.replaceAll("\\D", "");
		if (digits.length() != 8) {
			return StringUtils.hasText(value) ? value : "";
		}
		return Integer.parseInt(digits.substring(0, 4)) + ". "
			+ Integer.parseInt(digits.substring(4, 6)) + ". "
			+ Integer.parseInt(digits.substring(6, 8)) + ".";
	}

	private String firstNonBlank(String left, String right) {
		return StringUtils.hasText(left) ? left : right;
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (Exception exception) {
			throw new IllegalStateException("JSON serialization failed.", exception);
		}
	}
}
