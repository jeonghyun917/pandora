package com.kaces.pandora.lawdata.parser;


import com.kaces.pandora.common.json.LawJsonNodes;
import static com.kaces.pandora.common.json.LawJsonNodes.child;
import static com.kaces.pandora.common.json.LawJsonNodes.nodeText;
import static com.kaces.pandora.common.json.LawJsonNodes.nodes;

import com.kaces.pandora.lawdata.detail.LawDetailSectionResponse;
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
	@Override
	// 메소드 설명: supports 처리 흐름을 수행합니다.
	public boolean supports(JsonNode root) {
		return articleContents(root) != null;
	}
	@Override
	// 메소드 설명: parse 처리 흐름을 수행합니다.
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
			String title = articleTitle(body);
			if (!StringUtils.hasText(title)) {
				title = formatArticleTitle(nodeTextAt(numbers, i), nodeTextAt(titles, i));
			}
			sections.add(new LawDetailSectionResponse(title, stripTitle(body, title)));
		}
		return sections;
	}
	// 메소드 설명: articleContents 처리 흐름을 수행합니다.
	private JsonNode articleContents(JsonNode root) {
		JsonNode serviceRoot = child(root, "AdmRulService");
		JsonNode articleRoot = child(serviceRoot, "조문");
		JsonNode contents = child(articleRoot, "조문내용");
		return contents == null ? child(serviceRoot, "조문내용") : contents;
	}
	// 메소드 설명: articleTitle 처리 흐름을 수행합니다.
	private String articleTitle(String body) {
		Matcher matcher = ARTICLE_TITLE_PATTERN.matcher(body.stripLeading());
		return matcher.find() ? matcher.group(1) : "";
	}
	// 메소드 설명: formatArticleTitle 처리 흐름을 수행합니다.
	private String formatArticleTitle(String articleNo, String articleTitle) {
		String normalizedNo = articleNo == null ? "" : articleNo.replaceFirst("^0+", "");
		String title = StringUtils.hasText(normalizedNo) ? "제" + normalizedNo + "조" : "";
		if (StringUtils.hasText(articleTitle)) {
			return title + "(" + articleTitle + ")";
		}
		return title;
	}
	// 메소드 설명: nodeTextAt 처리 흐름을 수행합니다.
	private String nodeTextAt(List<JsonNode> nodes, int index) {
		return index >= 0 && index < nodes.size() ? nodeText(nodes.get(index)) : "";
	}
	// 메소드 설명: stripTitle 처리 흐름을 수행합니다.
	private String stripTitle(String body, String title) {
		if (!StringUtils.hasText(body) || !StringUtils.hasText(title)) {
			return body;
		}
		return body.startsWith(title) ? body.substring(title.length()).stripLeading() : body;
	}
}
