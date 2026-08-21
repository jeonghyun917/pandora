const assert = require('node:assert/strict');
const test = require('node:test');

let reportBuilder = {};
let reportRunner = {};
try {
  reportBuilder = require('./lib/rag-failure-presence-report');
} catch {
  // The first RED run intentionally exercises the not-yet-created module.
}
try {
  reportRunner = require('./rag-failure-presence-report');
} catch {
  // The first RED run intentionally exercises the not-yet-created runner.
}

function evaluationResult(overrides) {
  return {
    id: 'case',
    passed: false,
    resultMsg: 'OK',
    answerVerificationRequired: true,
    answerVerified: false,
    missingAnswerTerms: [],
    forbiddenMatchedTerms: [],
    forbiddenAnswerMatchedTerms: [],
    unsupportedAnswerClaims: [],
    verifiedAnswer: 'answer',
    ...overrides,
  };
}

function retrievalResult(id, classification, firstLossStage = null, candidateLoss = null) {
  return {
    id,
    firstDropStage: 'selected',
    oraclePresence: {
      auditable: classification !== 'NO_EXPLICIT_ORACLE',
      classification,
      firstLossStage,
      stages: {},
    },
		candidateLoss,
  };
}

test('failure category classification preserves unsupported, missing, no-ground, forbidden, and empty-answer cases', () => {
  assert.equal(typeof reportBuilder.classifyEvaluationFailure, 'function');

  assert.equal(reportBuilder.classifyEvaluationFailure(evaluationResult({
    unsupportedAnswerClaims: ['unsupported'],
    missingAnswerTerms: ['also missing'],
  })), 'UNSUPPORTED_ANSWER');
  assert.equal(reportBuilder.classifyEvaluationFailure(evaluationResult({
    missingAnswerTerms: ['required conclusion'],
  })), 'MISSING_ANSWER');
  assert.equal(reportBuilder.classifyEvaluationFailure(evaluationResult({
    resultMsg: 'NO_GROUNDS',
    verifiedAnswer: '',
  })), 'NO_GROUNDS');
  assert.equal(reportBuilder.classifyEvaluationFailure(evaluationResult({
    forbiddenMatchedTerms: ['forbidden evidence'],
  })), 'FORBIDDEN_EVIDENCE');
  assert.equal(reportBuilder.classifyEvaluationFailure(evaluationResult({
    verifiedAnswer: '',
  })), 'TRANSIENT_EMPTY_ANSWER');
});

test('joined report keeps every failed ID and counts failure by proposition presence', () => {
  assert.equal(typeof reportBuilder.buildFailurePresenceReport, 'function');
  const evaluationReport = {
    provenance: { runtimeInstanceId: 'runtime-a', indexRevision: 'index-a' },
    results: [
      evaluationResult({
        id: 'unsupported',
        unsupportedAnswerClaims: ['unsupported'],
      }),
      evaluationResult({
        id: 'missing',
        missingAnswerTerms: ['required'],
      }),
      evaluationResult({
        id: 'no-ground',
        resultMsg: 'NO_GROUNDS',
        verifiedAnswer: '',
      }),
      { id: 'passed', passed: true },
    ],
  };
  const retrievalReport = {
    k: 30,
    provenance: { runtimeInstanceId: 'runtime-b', indexRevision: 'index-a' },
    results: [
      retrievalResult('unsupported', 'PRESENT_IN_SELECTED'),
      retrievalResult('missing', 'DROPPED_BEFORE_SELECTED', 'judged', {
			oracleCandidateTraces: [{
				candidateKey: 'law:10',
				firstLossStage: 'judge',
				reasonCodes: ['JUDGE_NOT_DIRECT'],
			}],
		}),
      retrievalResult('no-ground', 'NO_EXPLICIT_ORACLE'),
    ],
  };

  const report = reportBuilder.buildFailurePresenceReport(evaluationReport, retrievalReport);

  assert.equal(report.totalFailures, 3);
  assert.deepEqual(report.failureCategoryCounts, {
    UNSUPPORTED_ANSWER: 1,
    MISSING_ANSWER: 1,
    NO_GROUNDS: 1,
  });
  assert.deepEqual(report.presenceClassificationCounts, {
    PRESENT_IN_SELECTED: 1,
    DROPPED_BEFORE_SELECTED: 1,
    NO_EXPLICIT_ORACLE: 1,
  });
  assert.equal(report.results.find((row) => row.id === 'missing').firstLossStage, 'judged');
	assert.equal(report.results.find((row) => row.id === 'missing').candidateFirstLossStage, 'judge');
	assert.deepEqual(report.candidateFirstLossStageCounts, { judge: 1 });
	assert.deepEqual(report.candidateReasonCodeCounts, { JUDGE_NOT_DIRECT: 1 });
  assert.equal(report.results.some((row) => row.id === 'passed'), false);
});

test('joined report fails closed when a failed evaluation ID has no retrieval replay', () => {
  assert.throws(
    () => reportBuilder.buildFailurePresenceReport({
      results: [evaluationResult({ id: 'missing-replay' })],
    }, {
      k: 30,
      results: [],
    }),
    /missing retrieval replay.*missing-replay/i,
  );
});

test('report runner derives the Markdown sibling and renders classifications with IDs', () => {
  assert.equal(typeof reportRunner.parseOptions, 'function');
  assert.equal(typeof reportRunner.renderMarkdown, 'function');
  const options = reportRunner.parseOptions([
    '--evaluation', 'logs/evaluation.json',
    '--retrieval', 'logs/retrieval.json',
    '--output', 'logs/audit.json',
  ]);

  assert.deepEqual(options, {
    evaluationPath: 'logs/evaluation.json',
    retrievalPath: 'logs/retrieval.json',
    outputPath: 'logs/audit.json',
    reportPath: 'logs/audit.md',
  });

  const markdown = reportRunner.renderMarkdown({
    totalFailures: 1,
    k: 30,
    failureCategoryCounts: { UNSUPPORTED_ANSWER: 1 },
    presenceClassificationCounts: { PRESENT_IN_SELECTED: 1 },
    results: [{
      id: 'unsupported',
      failureCategory: 'UNSUPPORTED_ANSWER',
      presenceClassification: 'PRESENT_IN_SELECTED',
      firstLossStage: null,
      retrievalFirstDropStage: null,
    }],
  });

  assert.match(markdown, /UNSUPPORTED_ANSWER.*1/);
  assert.match(markdown, /PRESENT_IN_SELECTED.*1/);
  assert.match(markdown, /unsupported/);
});
