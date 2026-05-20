package com.kaces.pandora.law.search;
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
