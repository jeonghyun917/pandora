package com.kaces.pandora.law.search;

/**
 * 검색 결과 한 건을 프론트엔드의 기존 국가법령 API 호환 필드명으로 내려주는 응답 DTO입니다.
 */
public record LawSearchItemResponse(
	long 법령일련번호,
	String target,
	String 원본식별자,
	String 법령명한글,
	String 소관부처명,
	String 법령구분명,
	String 시행일자,
	String 법령상세링크,
	String source
) {
}
