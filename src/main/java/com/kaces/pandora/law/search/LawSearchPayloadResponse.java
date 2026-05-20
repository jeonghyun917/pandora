package com.kaces.pandora.law.search;

import java.util.List;

/**
 * 寃??API??LawSearch 猷⑦듃 ?덉뿉 ?ㅼ뼱媛???묐떟 DTO?낅땲??
 */
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
