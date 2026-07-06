package com.kaces.pandora.lawdata.parser;


import com.kaces.pandora.common.json.LawJsonNodes;
import static com.kaces.pandora.common.text.LawTextUtils.stripHtmlTags;
import static com.kaces.pandora.common.json.LawJsonNodes.text;

import com.kaces.pandora.lawdata.detail.LawDetailSectionResponse;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class StoredDetailSectionReader {

	private final List<StoredDetailSectionParser> parsers;
	private final ObjectMapper objectMapper;
	
	// 메소드 설명: StoredDetailSectionReader 처리 흐름을 수행합니다.
	public StoredDetailSectionReader(List<StoredDetailSectionParser> parsers, ObjectMapper objectMapper) {
		this.parsers = parsers;
		this.objectMapper = objectMapper;
	}
	
	// 메소드 설명: readSections 처리 흐름을 수행합니다.
	public List<LawDetailSectionResponse> readSections(String rawJson, String sectionsJson) {
		
		List<LawDetailSectionResponse> parsedSections = readParsedSections(rawJson);
		if (!parsedSections.isEmpty()) {
			return cleanSections(parsedSections);
		}
		return cleanSections(readFallbackSections(sectionsJson));
	}
	
	// 메소드 설명: readParsedSections 처리 흐름을 수행합니다.
	private List<LawDetailSectionResponse> readParsedSections(String rawJson) {
		if (!StringUtils.hasText(rawJson)) {
			return List.of();
		}
		try {
			
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			JsonNode root = objectMapper.readTree(rawJson);
			for (StoredDetailSectionParser parser : parsers) {
				if (parser.supports(root)) {
					return parser.parse(root);
				}
			}
		} catch (Exception ignored) {
		}
		return List.of();
	}
	
	// 메소드 설명: readFallbackSections 처리 흐름을 수행합니다.
	private List<LawDetailSectionResponse> readFallbackSections(String sectionsJson) {
		if (!StringUtils.hasText(sectionsJson)) {
			return List.of(new LawDetailSectionResponse("상세 내용", "표시할 상세 내용이 없습니다."));
		}
		try {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
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
	
	// 메소드 설명: cleanSectionTitle 처리 흐름을 수행합니다.
	private String cleanSectionTitle(String storedTitle, String body) {
		if (!StringUtils.hasText(storedTitle)) {
			return "";
		}
		return storedTitle.equals(body) ? "" : storedTitle;
	}

	private List<LawDetailSectionResponse> cleanSections(List<LawDetailSectionResponse> sections) {
		return sections.stream()
			.map(section -> new LawDetailSectionResponse(
				stripHtmlTags(section.title()),
				stripHtmlTags(section.body()),
				section.pageNo(),
				section.sourcePath(),
				section.chunkId()
			))
			.filter(section -> StringUtils.hasText(section.title()) || StringUtils.hasText(section.body()))
			.toList();
	}
}
