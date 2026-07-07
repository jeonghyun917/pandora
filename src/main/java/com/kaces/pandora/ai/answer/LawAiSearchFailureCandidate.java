package com.kaces.pandora.ai.answer;

import java.time.LocalDateTime;

public class LawAiSearchFailureCandidate {

	private String candidateKey;
	private Long latestFailureId;
	private String sampleQuestion;
	private String targets;
	private String intentTypes;
	private String entityIds;
	private String lexicalKeywords;
	private String expandedQueries;
	private String failureType;
	private String failureStage;
	private int occurrenceCount;
	private LocalDateTime firstCreatedAt;
	private LocalDateTime latestCreatedAt;

	public String getCandidateKey() {
		return candidateKey;
	}

	public void setCandidateKey(String candidateKey) {
		this.candidateKey = candidateKey;
	}

	public Long getLatestFailureId() {
		return latestFailureId;
	}

	public void setLatestFailureId(Long latestFailureId) {
		this.latestFailureId = latestFailureId;
	}

	public String getSampleQuestion() {
		return sampleQuestion;
	}

	public void setSampleQuestion(String sampleQuestion) {
		this.sampleQuestion = sampleQuestion;
	}

	public String getTargets() {
		return targets;
	}

	public void setTargets(String targets) {
		this.targets = targets;
	}

	public String getIntentTypes() {
		return intentTypes;
	}

	public void setIntentTypes(String intentTypes) {
		this.intentTypes = intentTypes;
	}

	public String getEntityIds() {
		return entityIds;
	}

	public void setEntityIds(String entityIds) {
		this.entityIds = entityIds;
	}

	public String getLexicalKeywords() {
		return lexicalKeywords;
	}

	public void setLexicalKeywords(String lexicalKeywords) {
		this.lexicalKeywords = lexicalKeywords;
	}

	public String getExpandedQueries() {
		return expandedQueries;
	}

	public void setExpandedQueries(String expandedQueries) {
		this.expandedQueries = expandedQueries;
	}

	public String getFailureType() {
		return failureType;
	}

	public void setFailureType(String failureType) {
		this.failureType = failureType;
	}

	public String getFailureStage() {
		return failureStage;
	}

	public void setFailureStage(String failureStage) {
		this.failureStage = failureStage;
	}

	public int getOccurrenceCount() {
		return occurrenceCount;
	}

	public void setOccurrenceCount(int occurrenceCount) {
		this.occurrenceCount = occurrenceCount;
	}

	public LocalDateTime getFirstCreatedAt() {
		return firstCreatedAt;
	}

	public void setFirstCreatedAt(LocalDateTime firstCreatedAt) {
		this.firstCreatedAt = firstCreatedAt;
	}

	public LocalDateTime getLatestCreatedAt() {
		return latestCreatedAt;
	}

	public void setLatestCreatedAt(LocalDateTime latestCreatedAt) {
		this.latestCreatedAt = latestCreatedAt;
	}
}
