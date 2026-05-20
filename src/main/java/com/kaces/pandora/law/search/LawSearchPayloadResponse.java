package com.kaces.pandora.law.search;

import java.util.List;

public record LawSearchPayloadResponse(
	String resultCode,
	String resultMsg,
	String target,
	String query,
	int page,
	int numOfRows,
	int totalCnt,
	List<LawSearchItemResponse> law
) {
}
