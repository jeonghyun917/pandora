package com.kaces.pandora.ai.answer;

import java.util.List;

public record LawAiFailureEvalCaseRequest(
	String id,
	List<String> targets,
	List<String> expectedTerms,
	Integer requiredMatches,
	List<String> expectedTitleTerms,
	List<String> expectedSectionTypes,
	List<String> forbiddenTerms,
	List<String> expectedDocumentTerms,
	List<String> expectedPageNumbers,
	List<String> expectedParentTerms,
	String answerDirection,
	List<String> expectedResultMsgs,
	Boolean answerVerificationRequired,
	List<String> expectedAnswerTerms,
	List<String> forbiddenAnswerTerms
) {
}
