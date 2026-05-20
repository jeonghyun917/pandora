package com.kaces.pandora.law.parser;

import static com.kaces.pandora.law.common.LawJsonNodes.text;

import com.kaces.pandora.law.detail.LawDetailSectionResponse;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class StoredDetailSectionReader {

	private final List<StoredDetailSectionParser> parsers;
	private final ObjectMapper objectMapper;

	/**
	 * 상세 원문 파서 전략 목록과 JSON 파서를 주입받습니다.
	 */
	public StoredDetailSectionReader(List<StoredDetailSectionParser> parsers, ObjectMapper objectMapper) {
		this.parsers = parsers;
		this.objectMapper = objectMapper;
	}

	/**
	 * 저장된 상세 원문을 우선 파싱하고 실패하면 저장된 sections_json을 사용합니다.
	 */
	public List<LawDetailSectionResponse> readSections(String rawJson, String sectionsJson) {
		// 법령/행정규칙별 전략 파서가 원문을 더 보기 좋은 조문 구조로 변환합니다.
		List<LawDetailSectionResponse> parsedSections = readParsedSections(rawJson);
		if (!parsedSections.isEmpty()) {
			return parsedSections;
		}
		// 원문 전략 파싱이 실패하면 동기화 시점에 저장해 둔 섹션 JSON을 사용합니다.
		return readFallbackSections(sectionsJson);
	}

	/**
	 * 원문 JSON을 읽고 지원 가능한 파서 전략으로 섹션을 추출합니다.
	 */
	private List<LawDetailSectionResponse> readParsedSections(String rawJson) {
		if (!StringUtils.hasText(rawJson)) {
			return List.of();
		}
		try {
			JsonNode root = objectMapper.readTree(rawJson);
			for (StoredDetailSectionParser parser : parsers) {
				if (parser.supports(root)) {
					return parser.parse(root);
				}
			}
		} catch (Exception ignored) {
			// 원문 파싱 실패 시 저장된 fallback 섹션을 사용합니다.
		}
		return List.of();
	}

	/**
	 * sections_json에 저장된 동기화 섹션을 화면 응답 섹션으로 변환합니다.
	 */
	private List<LawDetailSectionResponse> readFallbackSections(String sectionsJson) {
		if (!StringUtils.hasText(sectionsJson)) {
			return List.of(new LawDetailSectionResponse("상세 내용", "표시할 상세 내용이 없습니다."));
		}
		try {
			JsonNode root = objectMapper.readTree(sectionsJson);
			if (!root.isArray()) {
				return List.of(new LawDetailSectionResponse("상세 내용", root.toString()));
			}
			return root.valueStream()
				.map(section -> {
					String body = text(section, "body", "");
					return new LawDetailSectionResponse(cleanSectionTitle(text(section, "title", ""), body), body);
				})
				.filter(section -> StringUtils.hasText(section.title()) || StringUtils.hasText(section.body()))
				.toList();
		} catch (Exception exception) {
			throw new IllegalStateException("Stored detail sections JSON parsing failed.", exception);
		}
	}

	/**
	 * 저장 섹션 제목이 본문 전체와 같으면 중복 제목을 비웁니다.
	 */
	private String cleanSectionTitle(String storedTitle, String body) {
		if (!StringUtils.hasText(storedTitle)) {
			return "";
		}
		return storedTitle.equals(body) ? "" : storedTitle;
	}
}
