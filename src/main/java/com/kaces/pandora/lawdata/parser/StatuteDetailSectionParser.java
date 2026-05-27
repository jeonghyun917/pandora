package com.kaces.pandora.lawdata.parser;


import com.kaces.pandora.common.json.LawJsonNodes;
import static com.kaces.pandora.common.json.LawJsonNodes.child;
import static com.kaces.pandora.common.json.LawJsonNodes.nodes;
import static com.kaces.pandora.common.json.LawJsonNodes.text;

import com.kaces.pandora.lawdata.detail.LawDetailSectionResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

@Component
public class StatuteDetailSectionParser implements StoredDetailSectionParser {
	@Override
	// 메소드 설명: supports 처리 흐름을 수행합니다.
	public boolean supports(JsonNode root) {
		return articleUnits(root) != null;
	}
	@Override
	// 메소드 설명: parse 처리 흐름을 수행합니다.
	public List<LawDetailSectionResponse> parse(JsonNode root) {
		return nodes(articleUnits(root)).stream()
			.map(this::toArticleSection)
			.filter(section -> StringUtils.hasText(section.title()) || StringUtils.hasText(section.body()))
			.toList();
	}
	// 메소드 설명: articleUnits 처리 흐름을 수행합니다.
	private JsonNode articleUnits(JsonNode root) {
		return child(child(child(root, "법령"), "조문"), "조문단위");
	}
	// 메소드 설명: toArticleSection 처리 흐름을 수행합니다.
	private LawDetailSectionResponse toArticleSection(JsonNode article) {
		String articleNo = text(article, "조문번호", "");
		String articleTitle = text(article, "조문제목", "");
		String articleContent = text(article, "조문내용", "");
		String title = formatArticleTitle(articleNo, articleTitle, articleContent);
		List<String> lines = new ArrayList<>();
		if (!isStructuralTitle(title, articleContent)) {
			addLine(lines, stripTitle(articleContent, title));
		}
		appendUnits(lines, unitNode(article, "항", "항단위"), "항내용");
		return new LawDetailSectionResponse(title, String.join("\n", lines));
	}
	// 메소드 설명: appendUnits 처리 흐름을 수행합니다.
	private void appendUnits(List<String> lines, JsonNode units, String contentKey) {
		for (JsonNode unit : nodes(units)) {
			addLine(lines, text(unit, contentKey, ""));
			appendUnits(lines, unitNode(unit, "호", "호단위"), "호내용");
			appendUnits(lines, unitNode(unit, "목", "목단위"), "목내용");
		}
	}
	// 메소드 설명: unitNode 처리 흐름을 수행합니다.
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
	// 메소드 설명: formatArticleTitle 처리 흐름을 수행합니다.
	private String formatArticleTitle(String articleNo, String articleTitle, String articleContent) {
		String normalizedContent = articleContent == null ? "" : articleContent.trim();
		if (!StringUtils.hasText(articleTitle) && normalizedContent.matches("^제\\d+조(의\\d+)?\\s+.*")) {
			return normalizedContent;
		}
		String formattedNo = articleNo != null && articleNo.matches("\\d+(의\\d+)?") ? "제" + articleNo + "조" : articleNo;
		if (StringUtils.hasText(formattedNo) && StringUtils.hasText(articleTitle)) {
			return formattedNo + "(" + articleTitle + ")";
		}
		return StringUtils.hasText(formattedNo) ? formattedNo : articleTitle;
	}
	// 메소드 설명: isStructuralTitle 처리 흐름을 수행합니다.
	private boolean isStructuralTitle(String title, String articleContent) {
		return StringUtils.hasText(title)
			&& title.equals(articleContent == null ? "" : articleContent.trim())
			&& title.matches("^제\\d+조(의\\d+)?\\s+.*");
	}
	// 메소드 설명: stripTitle 처리 흐름을 수행합니다.
	private String stripTitle(String body, String title) {
		if (!StringUtils.hasText(body) || !StringUtils.hasText(title)) {
			return body;
		}
		return body.startsWith(title) ? body.substring(title.length()).stripLeading() : body;
	}
	// 메소드 설명: addLine 처리 흐름을 수행합니다.
	private void addLine(List<String> lines, String value) {
		if (StringUtils.hasText(value)) {
			lines.add(value.trim());
		}
	}
}
