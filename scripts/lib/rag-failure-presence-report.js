function classifyEvaluationFailure(result) {
  if (String(result?.resultMsg ?? '') === 'NO_GROUNDS') {
    return 'NO_GROUNDS';
  }
  if (hasValues(result?.forbiddenMatchedTerms) || hasValues(result?.forbiddenAnswerMatchedTerms)) {
    return 'FORBIDDEN_EVIDENCE';
  }
  if (hasValues(result?.unsupportedAnswerClaims)) {
    return 'UNSUPPORTED_ANSWER';
  }
  if (hasValues(result?.missingAnswerTerms)) {
    return 'MISSING_ANSWER';
  }
  if (result?.answerVerificationRequired === true
      && result?.answerVerified === false
      && !String(result?.verifiedAnswer ?? '').trim()) {
    return 'TRANSIENT_EMPTY_ANSWER';
  }
  return 'OTHER';
}

function buildFailurePresenceReport(evaluationReport, retrievalReport) {
  const failed = (evaluationReport?.results ?? []).filter((result) => result?.passed === false);
  const retrievalById = new Map(
    (retrievalReport?.results ?? []).map((result) => [String(result?.id ?? ''), result]),
  );
  const missing = failed
    .map((result) => String(result?.id ?? ''))
    .filter((id) => !retrievalById.has(id));
  if (missing.length > 0) {
    throw new Error(`missing retrieval replay for failed IDs: ${missing.join(', ')}`);
  }

  const results = failed.map((evaluation) => {
    const retrieval = retrievalById.get(String(evaluation.id));
		const candidateTrace = (retrieval?.candidateLoss?.oracleCandidateTraces ?? [])
			.find((trace) => trace?.firstLossStage != null) ?? null;
    return {
      id: String(evaluation.id),
      question: evaluation.question ?? '',
      failureCategory: classifyEvaluationFailure(evaluation),
      resultMsg: evaluation.resultMsg ?? null,
      presenceClassification:
        retrieval?.oraclePresence?.classification ?? 'NO_EXPLICIT_ORACLE',
      firstLossStage: retrieval?.oraclePresence?.firstLossStage ?? null,
		candidateFirstLossStage: candidateTrace?.firstLossStage ?? null,
		candidateReasonCodes: Array.isArray(candidateTrace?.reasonCodes)
			? candidateTrace.reasonCodes
			: [],
      retrievalFirstDropStage: retrieval?.firstDropStage ?? null,
      oraclePresence: retrieval?.oraclePresence ?? null,
    };
  });

  return {
    schemaVersion: 2,
    generatedAt:
      retrievalReport?.provenance?.generatedAt
        ?? evaluationReport?.provenance?.generatedAt
        ?? null,
    k: retrievalReport?.k ?? null,
    totalFailures: results.length,
    failureCategoryCounts: countBy(results, (row) => row.failureCategory),
    presenceClassificationCounts: countBy(results, (row) => row.presenceClassification),
	candidateFirstLossStageCounts: countBy(
		results.filter((row) => row.candidateFirstLossStage != null),
		(row) => row.candidateFirstLossStage,
	),
	candidateReasonCodeCounts: countValues(results.flatMap((row) => row.candidateReasonCodes)),
    byFailureCategory: Object.fromEntries(
      Array.from(new Set(results.map((row) => row.failureCategory))).map((category) => {
        const rows = results.filter((row) => row.failureCategory === category);
        return [category, {
          total: rows.length,
          presenceClassificationCounts: countBy(rows, (row) => row.presenceClassification),
          ids: rows.map((row) => row.id),
        }];
      }),
    ),
    evaluationProvenance: evaluationReport?.provenance ?? null,
    retrievalProvenance: retrievalReport?.provenance ?? null,
    results,
  };
}

function hasValues(value) {
  return Array.isArray(value) && value.length > 0;
}

function countBy(rows, valueProvider) {
  const counts = {};
  for (const row of rows) {
    const value = valueProvider(row);
    counts[value] = (counts[value] ?? 0) + 1;
  }
  return counts;
}

function countValues(values) {
	return countBy(values.map((value) => ({ value })), (row) => row.value);
}

module.exports = {
  buildFailurePresenceReport,
  classifyEvaluationFailure,
};
