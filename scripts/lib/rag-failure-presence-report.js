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
    return {
      id: String(evaluation.id),
      question: evaluation.question ?? '',
      failureCategory: classifyEvaluationFailure(evaluation),
      resultMsg: evaluation.resultMsg ?? null,
      presenceClassification:
        retrieval?.oraclePresence?.classification ?? 'NO_EXPLICIT_ORACLE',
      firstLossStage: retrieval?.oraclePresence?.firstLossStage ?? null,
      retrievalFirstDropStage: retrieval?.firstDropStage ?? null,
      oraclePresence: retrieval?.oraclePresence ?? null,
    };
  });

  return {
    schemaVersion: 1,
    generatedAt:
      retrievalReport?.provenance?.generatedAt
        ?? evaluationReport?.provenance?.generatedAt
        ?? null,
    k: retrievalReport?.k ?? null,
    totalFailures: results.length,
    failureCategoryCounts: countBy(results, (row) => row.failureCategory),
    presenceClassificationCounts: countBy(results, (row) => row.presenceClassification),
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

module.exports = {
  buildFailurePresenceReport,
  classifyEvaluationFailure,
};
