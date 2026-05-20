package com.kaces.pandora.law;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class LawOpenApiSyncService {

	private static final int MAX_CHUNKS_PER_DOCUMENT = 1000;
	private static final int MAX_CHUNK_TEXT_CHARS = 60_000;
	private static final int MAX_STORED_DETAIL_JSON_CHARS = 4_000_000;
	private static final Pattern FILE_LINK_PATTERN = Pattern.compile("(?i)(?:https?://www\\.law\\.go\\.kr)?/LSW/flDownload\\.do\\?[^\\s\"'<>]+");

	private final LawOpenApiService lawOpenApiService;
	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	public LawOpenApiSyncService(
		LawOpenApiService lawOpenApiService,
		JdbcTemplate jdbcTemplate,
		ObjectMapper objectMapper
	) {
		this.lawOpenApiService = lawOpenApiService;
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public SyncResult syncLaws(String target, String query, int page, int display, boolean fetchDetails) {
		String safeTarget = StringUtils.hasText(target) ? target.trim() : "law";
		String safeQuery = StringUtils.hasText(query) ? query.trim() : "*";
		int safePage = Math.max(page, 1);
		int safeDisplay = Math.min(Math.max(display, 1), 100);
		long historyId = insertSyncHistory(safeTarget, safeQuery, safePage, safeDisplay, fetchDetails);

		try {
			String searchJson = lawOpenApiService.search(safeTarget, safeQuery, safePage, safeDisplay);
			JsonNode searchPayload = objectMapper.readTree(searchJson);
			List<JsonNode> rows = extractSearchRows(searchPayload);
			int detailCount = 0;
			int chunkCount = 0;
			int assetCount = 0;

			for (JsonNode row : rows) {
				SearchDocument document = toSearchDocument(safeTarget, row);
				long documentId = upsertDocument(document);
				if (!fetchDetails || !StringUtils.hasText(document.detailLink())) {
					continue;
				}

				String detailJson = lawOpenApiService.detail(document.detailLink());
				JsonNode detailPayload = objectMapper.readTree(detailJson);
				DetailDocument detail = toDetailDocument(detailPayload, document.title());
				long detailId = upsertDetail(documentId, detail, detailJson);
				chunkCount += replaceChunks(documentId, detailId, detail.sections(), document.detailLink());
				assetCount += replaceAssets(documentId, detailId, detail.assets());
				detailCount++;
			}

			SyncResult result = new SyncResult(historyId, safeTarget, safeQuery, rows.size(), detailCount, chunkCount, assetCount);
			markSyncSuccess(historyId, toJson(result));
			return result;
		} catch (Exception exception) {
			markSyncFailure(historyId, exception);
			throw new IllegalStateException("Law API sync failed: " + exception.getMessage(), exception);
		}
	}

	private long insertSyncHistory(String target, String query, int page, int display, boolean fetchDetails) {
		String requestJson = toJson(Map.of(
			"target", target,
			"query", query,
			"page", page,
			"display", display,
			"fetchDetails", fetchDetails
		));
		jdbcTemplate.update("""
			INSERT INTO law_api_sync_history (sync_type, target, status, request_json)
			VALUES ('full-sync', ?, 'RUNNING', ?)
			""", target, requestJson);
		return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private void markSyncSuccess(long historyId, String responseJson) {
		jdbcTemplate.update("""
			UPDATE law_api_sync_history
			SET status = 'SUCCESS', response_json = ?, finished_at = CURRENT_TIMESTAMP
			WHERE sync_history_id = ?
			""", responseJson, historyId);
	}

	private void markSyncFailure(long historyId, Exception exception) {
		jdbcTemplate.update("""
			UPDATE law_api_sync_history
			SET status = 'FAILED', error_message = ?, finished_at = CURRENT_TIMESTAMP
			WHERE sync_history_id = ?
			""", exception.getMessage(), historyId);
	}

	private List<JsonNode> extractSearchRows(JsonNode payload) {
		JsonNode root = payload;
		if (payload.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> fields = payload.properties().iterator();
			while (fields.hasNext()) {
				JsonNode value = fields.next().getValue();
				if (value.isObject() && (value.has("totalCnt") || value.has("resultCode") || value.has("resultMsg"))) {
					root = value;
					break;
				}
			}
		}

		List<JsonNode> rows = new ArrayList<>();
		if (!root.isObject()) {
			return rows;
		}

		Iterator<Map.Entry<String, JsonNode>> fields = root.properties().iterator();
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> field = fields.next();
			String key = field.getKey();
			JsonNode value = field.getValue();
			if (isReservedSearchKey(key)) {
				continue;
			}
			if (value.isArray()) {
				value.forEach(rows::add);
				return rows;
			}
			if (value.isObject()) {
				rows.add(value);
				return rows;
			}
		}
		return rows;
	}

	private boolean isReservedSearchKey(String key) {
		return "resultCode".equals(key)
			|| "resultMsg".equals(key)
			|| "target".equals(key)
			|| "\uD0A4\uC6CC\uB4DC".equals(key)
			|| "section".equals(key)
			|| "totalCnt".equals(key)
			|| "page".equals(key)
			|| "numOfRows".equals(key);
	}

	private SearchDocument toSearchDocument(String target, JsonNode row) {
		String title = firstText(row,
			"\uBC95\uB839\uBA85\uD55C\uAE00",
			"\uBC95\uB839\uBA85",
			"\uBC95\uB839\uBA85_\uD55C\uAE00",
			"\uD589\uC815\uADDC\uCE59\uBA85",
			"\uC81C\uBAA9"
		);
		String externalId = firstText(row,
			"\uBC95\uB839\uC77C\uB828\uBC88\uD638",
			"\uD589\uC815\uADDC\uCE59\uC77C\uB828\uBC88\uD638",
			"\uD589\uC815\uADDC\uCE59ID",
			"ID",
			"id"
		);
		if (!StringUtils.hasText(externalId)) {
			externalId = sha256(toJson(row)).substring(0, 32);
		}
		if (!StringUtils.hasText(title)) {
			title = target + "-" + externalId;
		}

		String agency = firstText(row,
			"\uC18C\uAD00\uBD80\uCC98\uBA85",
			"\uBD80\uCC98\uBA85",
			"\uAE30\uAD00\uBA85"
		);
		String category = firstText(row,
			"\uBC95\uB839\uAD6C\uBD84\uBA85",
			"\uD589\uC815\uADDC\uCE59\uC885\uB958",
			"\uD604\uD589\uC5F0\uD601\uCF54\uB4DC\uBA85",
			"\uAD6C\uBD84"
		);
		String sourceDate = firstText(row,
			"\uC2DC\uD589\uC77C\uC790",
			"\uBC1C\uB839\uC77C\uC790",
			"\uACF5\uD3EC\uC77C\uC790",
			"\uC0DD\uC131\uC77C\uC790",
			"\uC81C\uAC1C\uC815\uAD6C\uBD84\uBA85"
		);
		String detailLink = findDetailLink(row);
		if (!StringUtils.hasText(detailLink)) {
			detailLink = buildFallbackDetailLink(target, externalId);
		}

		return new SearchDocument(target, externalId, title, agency, category, sourceDate, detailLink, toJson(row));
	}

	private String buildFallbackDetailLink(String target, String externalId) {
		if ("admrul".equals(target)) {
			return "/DRF/lawService.do?OC=***&target=admrul&ID=" + externalId + "&type=HTML&mobileYn=";
		}
		return "/DRF/lawService.do?OC=***&target=law&MST=" + externalId + "&type=HTML&mobileYn=";
	}
	private long upsertDocument(SearchDocument document) {
		jdbcTemplate.update("""
			INSERT INTO law_api_documents (
				target, external_id, title, agency_name, category_name, source_date,
				detail_link, raw_json, content_hash, sync_status, last_error_message, fetched_at
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'SYNCED', NULL, CURRENT_TIMESTAMP)
			ON DUPLICATE KEY UPDATE
				title = VALUES(title),
				agency_name = VALUES(agency_name),
				category_name = VALUES(category_name),
				source_date = VALUES(source_date),
				detail_link = VALUES(detail_link),
				raw_json = VALUES(raw_json),
				content_hash = VALUES(content_hash),
				sync_status = 'SYNCED',
				last_error_message = NULL,
				fetched_at = CURRENT_TIMESTAMP
			""",
			document.target(),
			document.externalId(),
			document.title(),
			emptyToNull(document.agencyName()),
			emptyToNull(document.categoryName()),
			emptyToNull(document.sourceDate()),
			emptyToNull(document.detailLink()),
			document.rawJson(),
			sha256(document.rawJson())
		);
		return jdbcTemplate.queryForObject("""
			SELECT document_id
			FROM law_api_documents
			WHERE target = ? AND external_id = ?
			""", Long.class, document.target(), document.externalId());
	}

	private DetailDocument toDetailDocument(JsonNode payload, String fallbackTitle) {
		String title = firstText(payload,
			"title",
			"\uBC95\uB839\uBA85_\uD55C\uAE00",
			"\uBC95\uB839\uBA85\uD55C\uAE00",
			"\uBC95\uB839\uBA85",
			"\uD589\uC815\uADDC\uCE59\uBA85"
		);
		if (!StringUtils.hasText(title)) {
			title = fallbackTitle;
		}

		List<DetailSection> sections = extractSections(payload);
		if (sections.isEmpty()) {
			String text = firstLongText(payload);
			if (StringUtils.hasText(text)) {
				sections.add(new DetailSection("detail", null, "?곸꽭 ?댁슜", text));
			}
		}
		List<AssetDocument> assets = extractAssets(payload);
		return new DetailDocument(title, sections, assets);
	}

	private long upsertDetail(long documentId, DetailDocument detail, String rawJson) {
		String sectionsJson = toJson(detail.sections());
		String rawJsonForStorage = compactLargeJson(rawJson, "raw_json");
		String sectionsJsonForStorage = compactLargeJson(sectionsJson, "sections_json");
		String metaJson = toJson(Map.of(
			"sectionCount", detail.sections().size(),
			"assetCount", detail.assets().size(),
			"rawJsonCharLength", rawJson.length(),
			"rawJsonContentHash", sha256(rawJson),
			"sectionsJsonCharLength", sectionsJson.length(),
			"sectionsJsonContentHash", sha256(sectionsJson)
		));
		jdbcTemplate.update("""
			INSERT INTO law_api_document_details (
				document_id, detail_title, meta_json, sections_json, raw_json,
				content_hash, sync_status, last_error_message, fetched_at
			)
			VALUES (?, ?, ?, ?, ?, ?, 'SYNCED', NULL, CURRENT_TIMESTAMP)
			ON DUPLICATE KEY UPDATE
				detail_title = VALUES(detail_title),
				meta_json = VALUES(meta_json),
				sections_json = VALUES(sections_json),
				raw_json = VALUES(raw_json),
				content_hash = VALUES(content_hash),
				sync_status = 'SYNCED',
				last_error_message = NULL,
				fetched_at = CURRENT_TIMESTAMP
			""",
			documentId,
			emptyToNull(detail.title()),
			metaJson,
			sectionsJsonForStorage,
			rawJsonForStorage,
			sha256(rawJson)
		);
		return jdbcTemplate.queryForObject("""
			SELECT detail_id
			FROM law_api_document_details
			WHERE document_id = ?
		""", Long.class, documentId);
	}

	private String compactLargeJson(String json, String fieldName) {
		if (json.length() <= MAX_STORED_DETAIL_JSON_CHARS) {
			return json;
		}
		return toJson(Map.of(
			"storage", "compacted",
			"field", fieldName,
			"originalCharLength", json.length(),
			"originalContentHash", sha256(json),
			"reason", "exceeds-db-packet-safe-size"
		));
	}

	private int replaceChunks(long documentId, long detailId, List<DetailSection> sections, String sourceUrl) {
		jdbcTemplate.update("DELETE FROM law_api_document_chunks WHERE document_id = ?", documentId);
		int count = 0;
		for (DetailSection section : sections) {
			String body = section.body();
			if (!StringUtils.hasText(body)) {
				continue;
			}
			jdbcTemplate.update("""
				INSERT INTO law_api_document_chunks (
					document_id, detail_id, chunk_type, chunk_no, chunk_title, chunk_text,
					source_path, source_url, sort_order, content_hash, index_status
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
				""",
				documentId,
				detailId,
				section.type(),
				emptyToNull(section.no()),
				emptyToNull(section.title()),
				body,
				null,
				emptyToNull(sourceUrl),
				count,
				sha256(body)
			);
			count++;
		}
		return count;
	}

	private int replaceAssets(long documentId, long detailId, List<AssetDocument> assets) {
		jdbcTemplate.update("DELETE FROM law_api_assets WHERE document_id = ?", documentId);
		int count = 0;
		for (AssetDocument asset : assets) {
			jdbcTemplate.update("""
				INSERT INTO law_api_assets (
					document_id, detail_id, asset_type, source_url, proxy_url, file_name,
					file_extension, mime_type, alt_text, raw_json, sort_order
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
				documentId,
				detailId,
				asset.type(),
				asset.sourceUrl(),
				asset.proxyUrl(),
				emptyToNull(asset.fileName()),
				emptyToNull(asset.fileExtension()),
				null,
				emptyToNull(asset.altText()),
				toJson(asset),
				count
			);
			count++;
		}
		return count;
	}

	private List<DetailSection> extractSections(JsonNode payload) {
		List<DetailSection> sections = new ArrayList<>();
		if (payload.has("sections") && payload.get("sections").isArray()) {
			for (JsonNode section : payload.get("sections")) {
				sections.add(new DetailSection(
					"section",
					null,
					firstText(section, "title"),
					firstText(section, "body", "text", "content")
				));
			}
			return sections;
		}

		collectLongTextSections(payload, "$", sections);
		return sections.stream()
			.filter(section -> StringUtils.hasText(section.body()))
			.limit(MAX_CHUNKS_PER_DOCUMENT)
			.toList();
	}

	private void collectLongTextSections(JsonNode node, String path, List<DetailSection> sections) {
		if (sections.size() >= MAX_CHUNKS_PER_DOCUMENT || node == null || node.isNull()) {
			return;
		}
		if (node.isTextual()) {
			String text = normalizeText(node.asText());
			if (text.length() >= 40) {
				addTextSections(sections, "paragraph", null, path, text);
			}
			return;
		}
		if (node.isArray()) {
			for (int i = 0; i < node.size(); i++) {
				collectLongTextSections(node.get(i), path + "[" + i + "]", sections);
			}
			return;
		}
		if (node.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				collectLongTextSections(field.getValue(), path + "." + field.getKey(), sections);
			}
		}
	}

	private void addTextSections(List<DetailSection> sections, String type, String no, String title, String text) {
		if (text.length() <= MAX_CHUNK_TEXT_CHARS) {
			sections.add(new DetailSection(type, no, title, text));
			return;
		}
		int chunkNo = 1;
		for (int start = 0; start < text.length() && sections.size() < MAX_CHUNKS_PER_DOCUMENT; start += MAX_CHUNK_TEXT_CHARS) {
			int end = Math.min(start + MAX_CHUNK_TEXT_CHARS, text.length());
			sections.add(new DetailSection(type, no, title + "#" + chunkNo, text.substring(start, end)));
			chunkNo++;
		}
	}

	private List<AssetDocument> extractAssets(JsonNode payload) {
		Map<String, AssetDocument> assets = new LinkedHashMap<>();
		if (payload.has("sections") && payload.get("sections").isArray()) {
			for (JsonNode section : payload.get("sections")) {
				JsonNode images = section.get("images");
				if (images != null && images.isArray()) {
					for (JsonNode image : images) {
						addAsset(assets, firstText(image, "src"), firstText(image, "alt"));
					}
				}
			}
		}
		findAssetLinks(payload, assets);
		return new ArrayList<>(assets.values());
	}

	private void findAssetLinks(JsonNode node, Map<String, AssetDocument> assets) {
		if (node == null || node.isNull()) {
			return;
		}
		if (node.isTextual()) {
			Matcher matcher = FILE_LINK_PATTERN.matcher(node.asText());
			while (matcher.find()) {
				addAsset(assets, matcher.group(), "");
			}
			return;
		}
		if (node.isArray()) {
			node.forEach(child -> findAssetLinks(child, assets));
			return;
		}
		if (node.isObject()) {
			node.forEach(child -> findAssetLinks(child, assets));
		}
	}

	private void addAsset(Map<String, AssetDocument> assets, String sourceUrl, String altText) {
		if (!StringUtils.hasText(sourceUrl) || assets.containsKey(sourceUrl)) {
			return;
		}
		String extension = fileExtension(sourceUrl);
		String type = List.of("png", "jpg", "jpeg", "gif", "webp").contains(extension) ? "image" : "file";
		assets.put(sourceUrl, new AssetDocument(
			type,
			sourceUrl,
			sourceUrl.startsWith("/api/") ? sourceUrl : null,
			fileName(sourceUrl),
			extension,
			altText
		));
	}

	private String findDetailLink(JsonNode row) {
		if (!row.isObject()) {
			return "";
		}
		Iterator<Map.Entry<String, JsonNode>> fields = row.properties().iterator();
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> field = fields.next();
			String key = field.getKey();
			if ((key.contains("\uC0C1\uC138\uB9C1\uD06C")
				|| key.contains("\uD30C\uC77C\uB9C1\uD06C")
				|| key.toLowerCase().contains("link"))
				&& field.getValue().isTextual()) {
				return field.getValue().asText();
			}
		}
		return "";
	}

	private String firstText(JsonNode node, String... fieldNames) {
		for (String fieldName : fieldNames) {
			JsonNode value = node.findValue(fieldName);
			if (value != null && !value.isNull()) {
				String text = value.isTextual() ? value.asText() : value.toString();
				if (StringUtils.hasText(text)) {
					return normalizeText(text);
				}
			}
		}
		return "";
	}

	private String firstLongText(JsonNode node) {
		if (node == null || node.isNull()) {
			return "";
		}
		if (node.isTextual()) {
			String text = normalizeText(node.asText());
			return text.length() >= 40 ? text : "";
		}
		if (node.isArray() || node.isObject()) {
			Iterator<JsonNode> elements = node.values().iterator();
			while (elements.hasNext()) {
				String text = firstLongText(elements.next());
				if (StringUtils.hasText(text)) {
					return text;
				}
			}
		}
		return "";
	}

	private String normalizeText(String value) {
		return value == null ? "" : value.replaceAll("\\s+", " ").trim();
	}

	private String emptyToNull(String value) {
		return StringUtils.hasText(value) ? value : null;
	}

	private String toJson(Object value) {
		try {
			if (value instanceof JsonNode jsonNode) {
				return objectMapper.writeValueAsString(jsonNode);
			}
			return objectMapper.writeValueAsString(value);
		} catch (Exception exception) {
			throw new IllegalStateException("JSON serialization failed.", exception);
		}
	}

	private String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder(hash.length * 2);
			for (byte item : hash) {
				result.append(String.format("%02x", item));
			}
			return result.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available.", exception);
		}
	}

	private String fileName(String sourceUrl) {
		String cleaned = sourceUrl.replaceAll("[?#].*$", "");
		int slash = cleaned.lastIndexOf('/');
		return slash >= 0 ? cleaned.substring(slash + 1) : cleaned;
	}

	private String fileExtension(String sourceUrl) {
		String name = fileName(sourceUrl).toLowerCase();
		int dot = name.lastIndexOf('.');
		return dot >= 0 ? name.substring(dot + 1) : "";
	}

	public record SyncResult(
		long syncHistoryId,
		String target,
		String query,
		int documents,
		int details,
		int chunks,
		int assets
	) {
	}

	private record SearchDocument(
		String target,
		String externalId,
		String title,
		String agencyName,
		String categoryName,
		String sourceDate,
		String detailLink,
		String rawJson
	) {
	}

	private record DetailDocument(String title, List<DetailSection> sections, List<AssetDocument> assets) {
	}

	private record DetailSection(String type, String no, String title, String body) {
	}

	private record AssetDocument(
		String type,
		String sourceUrl,
		String proxyUrl,
		String fileName,
		String fileExtension,
		String altText
	) {
	}
}

