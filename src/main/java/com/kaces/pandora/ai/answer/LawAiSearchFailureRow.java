package com.kaces.pandora.ai.answer;

import java.time.LocalDateTime;

public class LawAiSearchFailureRow {

	private Long failureId;
	private String question;
	private String targets;
	private String intentTypes;
	private String entityIds;
	private String lexicalKeywords;
	private String expandedQueries;
	private String failureType;
	private String failureStage;
	private boolean retryable;
	private boolean evalCandidate;
	private int qdrantHitCount;
	private int vectorChunkCount;
	private int lexicalChunkCount;
	private int mergedCount;
	private int rankedCount;
	private int intentFilteredCount;
	private int judgeCandidateCount;
	private int judgedCount;
	private int finalGroundCount;
	private String resultMsg;
	private String publicMessage;
	private String diagnosticMessage;
	private String reviewStatus;
	private String promotedEvalCaseId;
	private LocalDateTime createdAt;

	public Long getFailureId() {
		return failureId;
	}

	public void setFailureId(Long failureId) {
		this.failureId = failureId;
	}

	public String getQuestion() {
		return question;
	}

	public void setQuestion(String question) {
		this.question = question;
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

	public boolean isRetryable() {
		return retryable;
	}

	public void setRetryable(boolean retryable) {
		this.retryable = retryable;
	}

	public boolean isEvalCandidate() {
		return evalCandidate;
	}

	public void setEvalCandidate(boolean evalCandidate) {
		this.evalCandidate = evalCandidate;
	}

	public int getQdrantHitCount() {
		return qdrantHitCount;
	}

	public void setQdrantHitCount(int qdrantHitCount) {
		this.qdrantHitCount = qdrantHitCount;
	}

	public int getVectorChunkCount() {
		return vectorChunkCount;
	}

	public void setVectorChunkCount(int vectorChunkCount) {
		this.vectorChunkCount = vectorChunkCount;
	}

	public int getLexicalChunkCount() {
		return lexicalChunkCount;
	}

	public void setLexicalChunkCount(int lexicalChunkCount) {
		this.lexicalChunkCount = lexicalChunkCount;
	}

	public int getMergedCount() {
		return mergedCount;
	}

	public void setMergedCount(int mergedCount) {
		this.mergedCount = mergedCount;
	}

	public int getRankedCount() {
		return rankedCount;
	}

	public void setRankedCount(int rankedCount) {
		this.rankedCount = rankedCount;
	}

	public int getIntentFilteredCount() {
		return intentFilteredCount;
	}

	public void setIntentFilteredCount(int intentFilteredCount) {
		this.intentFilteredCount = intentFilteredCount;
	}

	public int getJudgeCandidateCount() {
		return judgeCandidateCount;
	}

	public void setJudgeCandidateCount(int judgeCandidateCount) {
		this.judgeCandidateCount = judgeCandidateCount;
	}

	public int getJudgedCount() {
		return judgedCount;
	}

	public void setJudgedCount(int judgedCount) {
		this.judgedCount = judgedCount;
	}

	public int getFinalGroundCount() {
		return finalGroundCount;
	}

	public void setFinalGroundCount(int finalGroundCount) {
		this.finalGroundCount = finalGroundCount;
	}

	public String getResultMsg() {
		return resultMsg;
	}

	public void setResultMsg(String resultMsg) {
		this.resultMsg = resultMsg;
	}

	public String getPublicMessage() {
		return publicMessage;
	}

	public void setPublicMessage(String publicMessage) {
		this.publicMessage = publicMessage;
	}

	public String getDiagnosticMessage() {
		return diagnosticMessage;
	}

	public void setDiagnosticMessage(String diagnosticMessage) {
		this.diagnosticMessage = diagnosticMessage;
	}

	public String getReviewStatus() {
		return reviewStatus;
	}

	public void setReviewStatus(String reviewStatus) {
		this.reviewStatus = reviewStatus;
	}

	public String getPromotedEvalCaseId() {
		return promotedEvalCaseId;
	}

	public void setPromotedEvalCaseId(String promotedEvalCaseId) {
		this.promotedEvalCaseId = promotedEvalCaseId;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}
}
