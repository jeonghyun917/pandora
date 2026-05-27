package com.kaces.pandora.semantic.search;

import java.util.List;

public record LawSemanticSearchResponse(
	String resultCode,
	String resultMsg,
	String target,
	String query,
	int totalCnt,
	List<LawSemanticSearchItem> items
) {
}
