package com.kaces.pandora.law.common;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 援??踰뺣졊 JSON??諛곗뿴/?⑥씪 媛앹껜 ?쇱슜 援ъ“瑜??덉쟾?섍쾶 ?쎄린 ?꾪븳 ?묒? helper?낅땲??
 */
public final class LawJsonNodes {

	/**
	 * ?뺤쟻 ?좏떥由ы떚 ?대옒?ㅼ씠誘濡??몃??먯꽌 ?몄뒪?댁뒪瑜?留뚮뱾 ???녾쾶 留됱뒿?덈떎.
	 */
	private LawJsonNodes() {
	}

	/**
	 * null-safe 諛⑹떇?쇰줈 諛붾줈 ?꾨옒 ?꾨뱶瑜?媛?몄샃?덈떎.
	 */
	public static JsonNode child(JsonNode node, String fieldName) {
		return node == null || node.isNull() ? null : node.get(fieldName);
	}

	/**
	 * 諛곗뿴?대㈃ ?먯냼 紐⑸줉?? ?⑥씪 媛믪씠硫???媛쒖쭨由?紐⑸줉??諛섑솚?⑸땲??
	 */
	public static List<JsonNode> nodes(JsonNode node) {
		if (node == null || node.isNull()) {
			return List.of();
		}
		if (node.isArray()) {
			return node.valueStream().toList();
		}
		return List.of(node);
	}

	/**
	 * 吏???꾨뱶???띿뒪??媛믪쓣 ?쎄퀬 ?놁쑝硫?湲곕낯媛믪쓣 諛섑솚?⑸땲??
	 */
	public static String text(JsonNode node, String fieldName, String defaultValue) {
		JsonNode value = child(node, fieldName);
		if (value == null || value.isNull()) {
			return defaultValue;
		}
		return value.isTextual() ? value.asText() : value.toString();
	}

	/**
	 * ?몃뱶 ?먯껜瑜??띿뒪?몃줈 ?쎌뒿?덈떎.
	 */
	public static String nodeText(JsonNode node) {
		if (node == null || node.isNull()) {
			return "";
		}
		return node.isTextual() ? node.asText() : node.toString();
	}
}
