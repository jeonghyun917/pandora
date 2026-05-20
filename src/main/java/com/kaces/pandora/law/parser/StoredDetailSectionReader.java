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
	public StoredDetailSectionReader(List<StoredDetailSectionParser> parsers, ObjectMapper objectMapper) {
		this.parsers = parsers;
		this.objectMapper = objectMapper;
	}
	public List<LawDetailSectionResponse> readSections(String rawJson, String sectionsJson) {
		List<LawDetailSectionResponse> parsedSections = readParsedSections(rawJson);
		if (!parsedSections.isEmpty()) {
			return parsedSections;
		}
		return readFallbackSections(sectionsJson);
	}
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
		}
		return List.of();
	}
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
	private String cleanSectionTitle(String storedTitle, String body) {
		if (!StringUtils.hasText(storedTitle)) {
			return "";
		}
		return storedTitle.equals(body) ? "" : storedTitle;
	}
}
