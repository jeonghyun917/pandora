package com.kaces.pandora.law.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class LawOpenApiPayloadParser {

	private final ObjectMapper objectMapper;

	/**
	 * 외부 API 응답 JSON을 읽을 ObjectMapper를 주입받습니다.
	 */
	public LawOpenApiPayloadParser(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * 검색 API 응답을 DB 저장용 문서 목록으로 변환합니다.
	 */
	public List<SearchDocument> parseSearchDocuments(String target, String searchJson) {
		try {
			// 검색 응답 루트에서 실제 목록 row만 골라 SearchDocument로 변환합니다.
			JsonNode payload = objectMapper.readTree(searchJson);
			return extractSearchRows(payload).stream()
				.map(row -> toSearchDocument(target, row))
				.toList();
		} catch (Exception exception) {
			throw new IllegalStateException("Law search payload parsing failed.", exception);
		}
	}

	/**
	 * 상세 API 응답을 DB 상세/청크/첨부 저장용 문서로 변환합니다.
	 */
	public SyncDetailDocument parseDetailDocument(String detailJson, String fallbackTitle) {
		try {
			// 상세 응답 전체에서 제목, 본문 섹션, 첨부 링크를 추출합니다.
			JsonNode payload = objectMapper.readTree(detailJson);
			return toDetailDocument(payload, fallbackTitle);
		} catch (Exception exception) {
			throw new IllegalStateException("Law detail payload parsing failed.", exception);
		}
	}

	/**
	 * 검색 응답에서 메타 필드를 제외하고 목록 row 후보를 추출합니다.
	 */
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

	/**
	 * 검색 응답에서 목록이 아닌 메타 필드인지 확인합니다.
	 */
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

	/**
	 * 검색 row 하나를 DB 문서 저장 DTO로 변환합니다.
	 */
	private SearchDocument toSearchDocument(String target, JsonNode row) {
		String title = firstText(row, "법령명한글", "법령명", "법령명_한글", "행정규칙명", "자치법규명", "판례명", "사건명", "제목");
		String externalId = firstText(row, "법령일련번호", "행정규칙일련번호", "자치법규일련번호", "판례일련번호", "행정규칙ID", "ID", "id");
		if (!StringUtils.hasText(externalId)) {
			// 원본 식별자가 없는 특수 응답은 row JSON 해시를 안정적인 외부 ID로 사용합니다.
			externalId = sha256(toJson(row)).substring(0, 32);
		}
		if (!StringUtils.hasText(title)) {
			title = target + "-" + externalId;
		}
		String agency = firstText(row, "소관부처명", "부처명", "기관명", "법원명", "자치단체명", "발령기관명");
		String category = firstText(row, "법령구분명", "행정규칙종류", "자치법규종류", "구분", "분류");
		String sourceDate = firstText(row, "시행일자", "공포일자", "발령일자", "선고일자", "의결일자", "자치법규시행일자");
		String detailLink = findDetailLink(row);
		if (!StringUtils.hasText(detailLink)) {
			detailLink = buildFallbackDetailLink(target, externalId);
		}
		return new SearchDocument(target, externalId, title, agency, category, sourceDate, detailLink, toJson(row));
	}

	/**
	 * target과 원본 ID로 기본 상세 링크를 만듭니다.
	 */
	private String buildFallbackDetailLink(String target, String externalId) {
		if ("admrul".equals(target)) {
			return "/DRF/lawService.do?OC=***&target=admrul&ID=" + externalId + "&type=HTML&mobileYn=";
		}
		return "/DRF/lawService.do?OC=***&target=law&MST=" + externalId + "&type=HTML&mobileYn=";
	}

	/**
	 * 상세 응답에서 제목, 본문 섹션, 첨부 자산을 조합합니다.
	 */
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

	/**
	 * 상세 응답에서 section 배열 또는 긴 텍스트 노드를 본문 섹션으로 추출합니다.
	 */
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

	/**
	 * JSON 전체를 재귀 탐색해 본문으로 볼 수 있는 긴 문자열을 섹션으로 추가합니다.
	 */
	private void collectLongTextSections(JsonNode node, String path, List<SyncDetailSection> sections) {
		if (node == null || node.isNull()) {
			return;
		}
		if (node.isTextual()) {
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
		node.properties().forEach(entry -> collectLongTextSections(entry.getValue(), entry.getKey(), sections));
	}

	/**
	 * 긴 텍스트를 검색 청크로 저장하기 좋은 크기의 섹션으로 나눕니다.
	 */
	private void addTextSections(List<SyncDetailSection> sections, String type, String no, String title, String text) {
		String normalized = normalizeText(text);
		if (normalized.length() < 80) {
			return;
		}
		int chunkSize = 4_000;
		for (int start = 0; start < normalized.length(); start += chunkSize) {
			int end = Math.min(start + chunkSize, normalized.length());
			int chunkNo = (start / chunkSize) + 1;
			sections.add(new SyncDetailSection(type, no, title + "#" + chunkNo, normalized.substring(start, end)));
		}
	}

	/**
	 * 상세 응답에서 첨부 이미지/파일 링크를 수집합니다.
	 */
	private List<SyncAsset> extractAssets(JsonNode payload) {
		Map<String, SyncAsset> assets = new LinkedHashMap<>();
		findAssetLinks(payload, assets);
		return List.copyOf(assets.values());
	}

	/**
	 * JSON 전체를 재귀 탐색해 다운로드 가능한 링크를 찾습니다.
	 */
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

	/**
	 * 파일 링크로 판단되는 URL을 첨부 자산 DTO로 추가합니다.
	 */
	private void addAsset(Map<String, SyncAsset> assets, String sourceUrl, String altText) {
		if (!StringUtils.hasText(sourceUrl) || !(sourceUrl.contains("flDownload.do") || sourceUrl.matches("(?i).+\\.(png|jpg|jpeg|gif|pdf|hwp|hwpx)$"))) {
			return;
		}
		String safeUrl = sourceUrl.startsWith("http") || sourceUrl.startsWith("/") ? sourceUrl : "/" + sourceUrl;
		String proxyUrl = "/api/law-data/proxy?link=" + safeUrl;
		assets.putIfAbsent(safeUrl, new SyncAsset("file", safeUrl, proxyUrl, fileName(safeUrl), fileExtension(safeUrl), altText));
	}

	/**
	 * 검색 row에서 상세 링크 계열 필드를 찾습니다.
	 */
	private String findDetailLink(JsonNode row) {
		for (var field : row.properties()) {
			String key = field.getKey().toLowerCase();
			if (key.contains("link") || field.getKey().contains("상세링크") || field.getKey().contains("파일링크")) {
				return field.getValue().isTextual() ? field.getValue().asText() : field.getValue().toString();
			}
		}
		return "";
	}

	/**
	 * 여러 후보 필드 중 첫 번째 텍스트 값을 반환합니다.
	 */
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

	/**
	 * 상세 JSON에서 가장 긴 텍스트 값을 찾아 본문 fallback으로 사용합니다.
	 */
	private String firstLongText(JsonNode node) {
		List<String> texts = new ArrayList<>();
		collectTexts(node, texts);
		return texts.stream().filter(text -> text.length() >= 80).findFirst().orElse("");
	}

	/**
	 * JSON 전체에서 문자열 값을 수집합니다.
	 */
	private void collectTexts(JsonNode node, List<String> texts) {
		if (node == null || node.isNull()) {
			return;
		}
		if (node.isTextual()) {
			texts.add(normalizeText(node.asText()));
			return;
		}
		if (node.isArray()) {
			node.valueStream().forEach(item -> collectTexts(item, texts));
			return;
		}
		node.properties().forEach(entry -> collectTexts(entry.getValue(), texts));
	}

	/**
	 * 화면과 검색 저장에 쓰기 좋게 공백을 정리합니다.
	 */
	private String normalizeText(String value) {
		return value == null ? "" : value.replaceAll("[\\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
	}

	/**
	 * 객체를 JSON 문자열로 직렬화합니다.
	 */
	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (Exception exception) {
			throw new IllegalStateException("JSON serialization failed.", exception);
		}
	}

	/**
	 * 변경 감지와 중복 식별에 사용할 SHA-256 해시를 생성합니다.
	 */
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

	/**
	 * URL 경로에서 파일명을 추출합니다.
	 */
	private String fileName(String sourceUrl) {
		int queryStart = sourceUrl.indexOf('?');
		String path = queryStart >= 0 ? sourceUrl.substring(0, queryStart) : sourceUrl;
		int slash = path.lastIndexOf('/');
		return slash >= 0 ? path.substring(slash + 1) : path;
	}

	/**
	 * 파일명에서 확장자를 추출합니다.
	 */
	private String fileExtension(String sourceUrl) {
		String fileName = fileName(sourceUrl);
		int dot = fileName.lastIndexOf('.');
		return dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "";
	}
}
