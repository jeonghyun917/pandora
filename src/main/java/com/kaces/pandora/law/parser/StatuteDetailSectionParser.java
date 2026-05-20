package com.kaces.pandora.law.parser;

import static com.kaces.pandora.law.common.LawJsonNodes.child;
import static com.kaces.pandora.law.common.LawJsonNodes.nodes;
import static com.kaces.pandora.law.common.LawJsonNodes.text;

import com.kaces.pandora.law.detail.LawDetailSectionResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

@Component
public class StatuteDetailSectionParser implements StoredDetailSectionParser {

	/**
	 * 법령 원문 JSON에 조문 단위 목록이 있는지 확인합니다.
	 */
	@Override
	public boolean supports(JsonNode root) {
		// 법령 JSON은 보통 법령 > 조문 > 조문단위 경로에 본문 조문을 담습니다.
		return articleUnits(root) != null;
	}

	/**
	 * 법령 조문 단위 목록을 상세 화면 섹션 목록으로 변환합니다.
	 */
	@Override
	public List<LawDetailSectionResponse> parse(JsonNode root) {
		// 조문 단위 배열을 순회하며 각 조문을 제목과 본문으로 나눕니다.
		return nodes(articleUnits(root)).stream()
			.map(this::toArticleSection)
			.filter(section -> StringUtils.hasText(section.title()) || StringUtils.hasText(section.body()))
			.toList();
	}

	/**
	 * 법령 JSON에서 조문 단위 배열 노드를 찾습니다.
	 */
	private JsonNode articleUnits(JsonNode root) {
		return child(child(child(root, "법령"), "조문"), "조문단위");
	}

	/**
	 * 조문 단위 노드를 화면 섹션 하나로 변환합니다.
	 */
	private LawDetailSectionResponse toArticleSection(JsonNode article) {
		String articleNo = text(article, "조문번호", "");
		String articleTitle = text(article, "조문제목", "");
		String articleContent = text(article, "조문내용", "");
		String title = formatArticleTitle(articleNo, articleTitle, articleContent);
		List<String> lines = new ArrayList<>();
		if (!isStructuralTitle(title, articleContent)) {
			// 조문내용 첫 줄에 제목이 반복되면 본문에서 제거합니다.
			addLine(lines, stripTitle(articleContent, title));
		}
		// 항/호/목 하위 단위의 본문을 순서대로 이어 붙입니다.
		appendUnits(lines, unitNode(article, "항", "항단위"), "항내용");
		return new LawDetailSectionResponse(title, String.join("\n", lines));
	}

	/**
	 * 하위 조문 단위를 재귀적으로 펼쳐 본문 줄에 추가합니다.
	 */
	private void appendUnits(List<String> lines, JsonNode units, String contentKey) {
		for (JsonNode unit : nodes(units)) {
			addLine(lines, text(unit, contentKey, ""));
			appendUnits(lines, unitNode(unit, "호", "호단위"), "호내용");
			appendUnits(lines, unitNode(unit, "목", "목단위"), "목내용");
		}
	}

	/**
	 * 컨테이너 노드 안의 실제 단위 노드를 찾고, 구조가 다르면 가능한 값을 찾아 반환합니다.
	 */
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

	/**
	 * 조문번호와 조문제목을 조합해 화면 제목을 만듭니다.
	 */
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

	/**
	 * 조문 내용 전체가 제목만 담은 구조인지 확인합니다.
	 */
	private boolean isStructuralTitle(String title, String articleContent) {
		return StringUtils.hasText(title)
			&& title.equals(articleContent == null ? "" : articleContent.trim())
			&& title.matches("^제\\d+조(의\\d+)?\\s+.*");
	}

	/**
	 * 본문 앞에 반복된 제목을 제거합니다.
	 */
	private String stripTitle(String body, String title) {
		if (!StringUtils.hasText(body) || !StringUtils.hasText(title)) {
			return body;
		}
		return body.startsWith(title) ? body.substring(title.length()).stripLeading() : body;
	}

	/**
	 * 비어 있지 않은 본문 줄만 목록에 추가합니다.
	 */
	private void addLine(List<String> lines, String value) {
		if (StringUtils.hasText(value)) {
			lines.add(value.trim());
		}
	}
}
