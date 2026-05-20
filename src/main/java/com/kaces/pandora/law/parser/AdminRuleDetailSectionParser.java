package com.kaces.pandora.law.parser;

import static com.kaces.pandora.law.common.LawJsonNodes.child;
import static com.kaces.pandora.law.common.LawJsonNodes.nodeText;
import static com.kaces.pandora.law.common.LawJsonNodes.nodes;

import com.kaces.pandora.law.detail.LawDetailSectionResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

@Component
public class AdminRuleDetailSectionParser implements StoredDetailSectionParser {

	private static final Pattern ARTICLE_TITLE_PATTERN = Pattern.compile("^(제\\d+조(의\\d+)?(?:\\([^)]*\\))?|제\\d+조(의\\d+)?)");

	/**
	 * 행정규칙 원문 JSON에 조문 내용이 있는지 확인합니다.
	 */
	@Override
	public boolean supports(JsonNode root) {
		// 행정규칙 JSON은 AdmRulService 하위에 조문내용을 담는 경우가 많습니다.
		return articleContents(root) != null;
	}

	/**
	 * 행정규칙 조문 내용을 상세 화면 섹션 목록으로 변환합니다.
	 */
	@Override
	public List<LawDetailSectionResponse> parse(JsonNode root) {
		JsonNode articleRoot = child(child(root, "AdmRulService"), "조문");
		List<JsonNode> contentNodes = nodes(articleContents(root));
		List<JsonNode> numbers = nodes(child(articleRoot, "조문번호"));
		List<JsonNode> titles = nodes(child(articleRoot, "조문제목"));
		List<LawDetailSectionResponse> sections = new ArrayList<>();
		for (int i = 0; i < contentNodes.size(); i++) {
			String body = nodeText(contentNodes.get(i));
			if (!StringUtils.hasText(body)) {
				continue;
			}
			// 본문 첫머리에서 조문 제목을 우선 추출합니다.
			String title = articleTitle(body);
			if (!StringUtils.hasText(title)) {
				// 본문에 제목이 없으면 조문번호/조문제목 배열에서 같은 인덱스의 값을 사용합니다.
				title = formatArticleTitle(nodeTextAt(numbers, i), nodeTextAt(titles, i));
			}
			sections.add(new LawDetailSectionResponse(title, stripTitle(body, title)));
		}
		return sections;
	}

	/**
	 * 행정규칙 JSON에서 조문내용 노드를 찾습니다.
	 */
	private JsonNode articleContents(JsonNode root) {
		JsonNode serviceRoot = child(root, "AdmRulService");
		JsonNode articleRoot = child(serviceRoot, "조문");
		JsonNode contents = child(articleRoot, "조문내용");
		return contents == null ? child(serviceRoot, "조문내용") : contents;
	}

	/**
	 * 본문 첫머리에 적힌 조문 제목을 추출합니다.
	 */
	private String articleTitle(String body) {
		Matcher matcher = ARTICLE_TITLE_PATTERN.matcher(body.stripLeading());
		return matcher.find() ? matcher.group(1) : "";
	}

	/**
	 * 조문번호와 조문제목을 조합해 제목 문자열을 만듭니다.
	 */
	private String formatArticleTitle(String articleNo, String articleTitle) {
		String normalizedNo = articleNo == null ? "" : articleNo.replaceFirst("^0+", "");
		String title = StringUtils.hasText(normalizedNo) ? "제" + normalizedNo + "조" : "";
		if (StringUtils.hasText(articleTitle)) {
			return title + "(" + articleTitle + ")";
		}
		return title;
	}

	/**
	 * 지정한 인덱스의 JsonNode 텍스트를 안전하게 읽습니다.
	 */
	private String nodeTextAt(List<JsonNode> nodes, int index) {
		return index >= 0 && index < nodes.size() ? nodeText(nodes.get(index)) : "";
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
}
