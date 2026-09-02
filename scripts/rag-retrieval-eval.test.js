const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { buildBlockingGates } = require('./lib/rag-eval-gates');

let caseParser = {};
let retrievalMetrics = {};
let retrievalRunner = {};
let coverageReporter = {};
let supplementGenerator = {};
try {
  caseParser = require('./lib/rag-eval-cases');
} catch {
  // The first RED run intentionally exercises the not-yet-created module.
}
try {
  retrievalMetrics = require('./lib/rag-retrieval-metrics');
} catch {
  // The first RED run intentionally exercises the not-yet-created module.
}
try {
  retrievalRunner = require('./rag-retrieval-eval');
} catch {
  // The runner is added only after its command-line contract has failed RED.
}
try {
  coverageReporter = require('./rag-eval-coverage-report');
} catch {
  // The coverage reporter is added only after its named-gate contract has failed RED.
}
try {
  supplementGenerator = require('./generate-rag-eval-supplement');
} catch {
  // The generator is loaded only after it becomes safe to import without querying the database.
}

test('blocking gates classify curated, answer-oracle, and no-grounds failures independently', () => {
  const rows = [
    { id: 'curated-pass-one', passed: true },
    { id: 'curated-pass-two', passed: true },
    { id: 'curated-fail', passed: false },
    { id: 'gen-oracle-pass', passed: true, answerVerificationRequired: true },
    { id: 'gen-oracle-fail', passed: false, answerVerificationRequired: true },
    { id: 'gen-no-ground-fail', passed: false, expectedResultMsgs: ['NO_GROUNDS'] },
  ];

  assert.deepEqual(buildBlockingGates(rows), {
    curated: {
      total: 3,
      passed: 2,
      failed: 1,
      passRate: 2 / 3,
      gatePassed: false,
      blockingFailureIds: ['curated-fail'],
    },
    answerOracle: {
      total: 2,
      passed: 1,
      failed: 1,
      passRate: 1 / 2,
      gatePassed: false,
      blockingFailureIds: ['gen-oracle-fail'],
    },
    noGrounds: {
      total: 1,
      passed: 0,
      failed: 1,
      passRate: 0,
      gatePassed: false,
      blockingFailureIds: ['gen-no-ground-fail'],
    },
  });
});

test('TSV parser preserves UTF-8 and quoted tabs, newlines, and escaped quotes', () => {
  assert.equal(typeof caseParser.parseEvalCasesTsv, 'function');
  const tsv = [
    '\uFEFFid\tquestion\ttargets\texpectedTerms\trequiredMatches\texpectedTitleTerms\texpectedSectionTypes\tforbiddenTerms\texpectedDocumentTerms\texpectedPageNumbers\texpectedParentTerms\tanswerDirection\texpectedResultMsgs\tanswerVerificationRequired\texpectedAnswerTerms\tforbiddenAnswerTerms',
    'quoted-korean\t"개인정보\t질문에 ""인용""과\n줄바꿈"\tofficial_doc|law\t-\t0\t생성형 AI|안내서\trequirement\t-\t개인정보 처리\t-\t처리 기준\t"첫 줄\n둘째 줄"\tOK\ttrue\t직접 근거\t추측',
  ].join('\r\n');

  const rows = caseParser.parseEvalCasesTsv(tsv);

  assert.equal(rows.length, 1);
  assert.equal(rows[0].question, '개인정보\t질문에 "인용"과\n줄바꿈');
  assert.deepEqual(rows[0].targets, ['official_doc', 'law']);
  assert.deepEqual(rows[0].expectedTitleTerms, ['생성형 AI', '안내서']);
  assert.equal(rows[0].answerDirection, '첫 줄\n둘째 줄');
  assert.equal(rows[0].answerVerificationRequired, true);
});

test('TSV parser keeps optional answer oracle empty and defaults no-* cases to NO_GROUNDS', () => {
  assert.equal(typeof caseParser.parseEvalCasesTsv, 'function');
  const tsv = [
    '# id\tquestion\ttargets\texpectedTerms\trequiredMatches\texpectedTitleTerms\texpectedSectionTypes\tforbiddenTerms',
    'ordinary\t일반 질문\tlaw\t용어\t1\t법률\t-\t-',
    'no-runtime\t런타임 질문\tofficial_doc\t-\t0\t-\t-\tQdrant',
  ].join('\n');

  const rows = caseParser.parseEvalCasesTsv(tsv);

  assert.deepEqual(rows[0].expectedResultMsgs, []);
  assert.equal(rows[0].answerVerificationRequired, null);
  assert.deepEqual(rows[0].expectedAnswerTerms, []);
  assert.deepEqual(rows[1].expectedResultMsgs, ['NO_GROUNDS']);
});

test('TSV parser rejects short data rows with physical line context', () => {
  const tsv = [
    'id\tquestion\ttargets\texpectedTerms\trequiredMatches\texpectedTitleTerms\texpectedSectionTypes\tforbiddenTerms',
    'short-row\tquestion\tlaw',
  ].join('\n');

  assert.throws(
    () => caseParser.parseEvalCasesTsv(tsv),
    /line 2.*at least 8 columns/i,
  );
});

test('TSV parser rejects unclosed quotes and empty IDs with physical line context', () => {
  const header = 'id\tquestion\ttargets\texpectedTerms\trequiredMatches\texpectedTitleTerms\texpectedSectionTypes\tforbiddenTerms';
  assert.throws(
    () => caseParser.parseEvalCasesTsv(`${header}\nopen\t"unclosed\tlaw\t-\t0\t-\t-\t-`),
    /unclosed quoted field.*line 2/i,
  );
  assert.throws(
    () => caseParser.parseEvalCasesTsv(`${header}\n\tquestion\tlaw\t-\t0\t-\t-\t-`),
    /line 2.*empty id/i,
  );
});

test('case loading rejects duplicate IDs across files instead of overwriting them', (t) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'rag-eval-cases-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const firstPath = path.join(directory, 'first.tsv');
  const secondPath = path.join(directory, 'second.tsv');
  fs.writeFileSync(firstPath, 'duplicate\tfirst question\tlaw\t-\t0\t-\t-\t-\n', 'utf8');
  fs.writeFileSync(secondPath, 'duplicate\tsecond question\tlaw\t-\t0\t-\t-\t-\n', 'utf8');

  assert.throws(
    () => caseParser.loadEvalCases([firstPath, secondPath]),
    /duplicate evaluation case id.*duplicate/i,
  );
});

test('answer-oracle parser preserves AND groups and aliases', () => {
  assert.equal(typeof caseParser.parseAnswerOraclesTsv, 'function');
  const rows = caseParser.parseAnswerOraclesTsv([
    'id\trequiredPropositionGroups\trequiredConditionGroups\tforbiddenAnswerExpressions',
    'period\tnot fixed|not mandatory;purpose based|based on purpose\tinstallation purpose|stated purpose\talways 30 days|fixed at 30 days',
  ].join('\n'));

  assert.deepEqual(rows, [{
    id: 'period',
    requiredPropositionGroups: [
      ['not fixed', 'not mandatory'],
      ['purpose based', 'based on purpose'],
    ],
    requiredConditionGroups: [['installation purpose', 'stated purpose']],
    forbiddenAnswerExpressions: ['always 30 days', 'fixed at 30 days'],
  }]);
});

test('answer-oracle parser fails closed for quoted fields including quoted tabs and newlines', () => {
  const parse = (row) => caseParser.parseAnswerOraclesTsv([
    'id\trequiredPropositionGroups\trequiredConditionGroups\tforbiddenAnswerExpressions',
    row,
  ].join('\n'));

  assert.throws(() => parse('"a"\tanswer\t-\twrong'), /quoted fields are not supported/i);
  assert.throws(() => parse('a\t"answer\talias"\t-\twrong'), /quoted fields are not supported/i);
  assert.throws(() => parse('a\t"answer\ncontinued"\t-\twrong'), /quoted fields are not supported/i);
});

test('answer-oracle parser ignores comments with leading spaces', () => {
  const rows = caseParser.parseAnswerOraclesTsv([
    'id\trequiredPropositionGroups\trequiredConditionGroups\tforbiddenAnswerExpressions',
    '  # comment with leading spaces',
    'a\tanswer\t-\twrong',
  ].join('\n'));

  assert.deepEqual(rows.map((row) => row.id), ['a']);
});

test('answer-oracle merge rejects duplicate, orphan, missing, and malformed rows', () => {
  const baseCases = [{ id: 'a' }, { id: 'b' }];
  const requiredOracleIds = new Set(['a', 'b']);
  const parse = (rows) => caseParser.parseAnswerOraclesTsv([
    'id\trequiredPropositionGroups\trequiredConditionGroups\tforbiddenAnswerExpressions',
    ...rows,
  ].join('\n'));
  const merge = (rows) => caseParser.mergeAnswerOracles(baseCases, parse(rows), requiredOracleIds);

  assert.throws(
    () => merge(['a\tanswer\t-\twrong', 'a\tanswer\t-\twrong', 'b\tanswer\t-\twrong']),
    /duplicate oracle id.*a/i,
  );
  assert.throws(
    () => merge(['a\tanswer\t-\twrong', 'b\tanswer\t-\twrong', 'orphan\tanswer\t-\twrong']),
    /orphan oracle id.*orphan/i,
  );
  assert.throws(
    () => merge(['a\tanswer\t-\twrong']),
    /missing oracle ids.*b/i,
  );
  assert.throws(
    () => merge(['a\tanswer||alias\t-\twrong', 'b\tanswer\t-\twrong']),
    /malformed proposition group.*a/i,
  );
  assert.throws(
    () => merge(['a\tanswer\t-;condition\twrong', 'b\tanswer\t-\twrong']),
    /malformed condition groups.*a/i,
  );
  assert.throws(
    () => merge(['a\tanswer\t-\t-', 'b\tanswer\t-\twrong']),
    /forbidden answer expression.*a/i,
  );
});

test('bundled answer-oracle sidecar merges exactly 89 explicit cases', () => {
  const repositoryRoot = path.resolve(__dirname, '..');
  const cases = caseParser.loadEvalCases([
    path.join(repositoryRoot, 'src', 'main', 'resources', 'rag-evaluation-cases.tsv'),
    path.join(repositoryRoot, 'src', 'main', 'resources', 'rag-evaluation-cases.generated.tsv'),
  ], {
    answerOraclePath: path.join(repositoryRoot, 'src', 'main', 'resources', 'rag-answer-evaluation-oracles.tsv'),
  });
  const explicit = cases.filter((item) => item.requiredPropositionGroups.length > 0);

  assert.equal(cases.length, 1003);
  assert.equal(explicit.length, 89);
  assert.equal(explicit.every((item) => item.answerVerificationRequired === true), true);
  assert.equal(explicit.every((item) => item.forbiddenAnswerTerms.length > 0), true);
  assert.deepEqual(
    explicit
      .filter((item) => item.id.startsWith('contract-completion-'))
      .map((item) => item.id)
      .sort(),
    [
      'contract-completion-actual-finished',
      'contract-completion-before-period',
      'contract-completion-before-period-paraphrase',
      'contract-completion-work-remaining-control',
    ],
  );
});

test('case selection rejects every requested ID that is missing', () => {
  const cases = [{ id: 'present' }];

  assert.throws(
    () => caseParser.selectEvalCases(cases, { caseIds: ['present', 'missing-a', 'missing-b'] }),
    /unknown evaluation case ids.*missing-a.*missing-b/i,
  );
});

test('retrieval metrics find a first downstream drop after vector or lexical entry', () => {
  assert.equal(typeof retrievalMetrics.measureRetrievalCase, 'function');
  const evalCase = {
    id: 'guide',
    expectedTitleTerms: ['생성형 AI', '안내서'],
    expectedDocumentTerms: ['개인정보 처리'],
    expectedSectionTypes: ['requirement'],
    expectedParentTerms: ['처리 기준'],
    expectedResultMsgs: ['OK'],
  };
  const direct = {
    chunkId: 7,
    target: 'official_doc',
    title: '생성형 AI 개인정보 처리 안내서',
    chunkTitle: '처리 기준',
    parentSectionTitle: '처리 기준',
    sectionType: 'requirement',
    snippet: '처리 기준에 따라 개인정보 보호조치를 하여야 한다.',
  };
  const wrong = {
    chunkId: 8,
    target: 'official_doc',
    title: '무관한 문서',
    chunkTitle: '개요',
    snippet: '관련 없는 본문',
  };
  const response = {
    resultMsg: 'NO_GROUNDS',
    vectorHits: [direct],
    lexicalHits: [wrong],
    merged: [direct, wrong],
    reranked: [direct],
    intentFiltered: [wrong],
    judgeCandidates: [],
    selected: [],
  };

  const measured = retrievalMetrics.measureRetrievalCase(evalCase, response, 2);

  assert.equal(measured.stages.vectorHits.documentHit, true);
  assert.equal(measured.stages.vectorHits.sectionParentHit, true);
  assert.equal(measured.stages.lexicalHits.directHit, false);
  assert.equal(measured.candidateEntryHit, true);
  assert.equal(measured.firstDropStage, 'intentFiltered');
  assert.equal(measured.stages.intentFiltered.directHit, false);
});

test('retrieval metrics preserve BM25, fused, and coverage shadow ranks for deterministic acceptance', () => {
  const evalCase = {
    id: 'shadow-rank',
    expectedTitleTerms: ['정답 문서'],
    expectedDocumentTerms: [],
    expectedSectionTypes: [],
    expectedParentTerms: [],
    expectedResultMsgs: ['OK'],
  };
  const direct = { chunkId: 7, target: 'law', title: '정답 문서' };
  const wrong = { chunkId: 8, target: 'law', title: '오답 문서' };
  const response = Object.fromEntries(retrievalMetrics.STAGE_NAMES.map((stage) => [stage, [direct]]));
  response.resultMsg = 'OK';
  response.bm25Hits = [wrong, direct];
  response.fused = [direct, wrong];
  response.coverageFused = [wrong, direct];

  const measured = retrievalMetrics.measureRetrievalCase(evalCase, response, 30);
  const summary = retrievalMetrics.summarizeRetrievalCases([measured], 30);

  assert.equal(measured.stages.bm25Hits.directHit, true);
  assert.equal(measured.stages.fused.directHit, true);
  assert.equal(measured.stages.coverageFused.directHit, true);
  assert.deepEqual(measured.shadowRanks, {
    bm25Hits: ['law:8', 'law:7'],
    bm25VariantHits: [],
    fused: ['law:7', 'law:8'],
    coverageFused: ['law:8', 'law:7'],
  });
  assert.equal(summary.shadowStages.bm25Hits.directHitRate, 1);
  assert.equal(summary.shadowStages.fused.directHitRate, 1);
  assert.equal(summary.shadowStages.coverageFused.directHitRate, 1);
});

test('retrieval metrics honor K and report no-ground false grounds outside recall denominators', () => {
  assert.equal(typeof retrievalMetrics.measureRetrievalCase, 'function');
  assert.equal(typeof retrievalMetrics.summarizeRetrievalCases, 'function');
  const evalCase = {
    id: 'doc',
    expectedTitleTerms: ['정답 문서'],
    expectedDocumentTerms: [],
    expectedSectionTypes: [],
    expectedParentTerms: [],
    expectedResultMsgs: ['OK'],
  };
  const wrong = { chunkId: 1, target: 'official_doc', title: '오답 문서' };
  const direct = { chunkId: 2, target: 'official_doc', title: '정답 문서' };
  const response = Object.fromEntries(retrievalMetrics.STAGE_NAMES.map((stage) => [stage, [wrong, direct]]));
  response.resultMsg = 'OK';

  const topOne = retrievalMetrics.measureRetrievalCase(evalCase, response, 1);
  const topTwo = retrievalMetrics.measureRetrievalCase(evalCase, response, 2);
  assert.equal(topOne.stages.vectorHits.documentHit, false);
  assert.equal(topTwo.stages.vectorHits.documentHit, true);

  const noGroundCase = {
    id: 'no-runtime',
    expectedTitleTerms: [],
    expectedDocumentTerms: [],
    expectedSectionTypes: [],
    expectedParentTerms: [],
    expectedResultMsgs: ['NO_GROUNDS'],
  };
  const noGroundResponse = Object.fromEntries(retrievalMetrics.STAGE_NAMES.map((stage) => [stage, []]));
  noGroundResponse.resultMsg = 'OK';
  noGroundResponse.selected = [{ chunkId: 9, target: 'official_doc', title: '엉뚱한 근거' }];
  const falseGround = retrievalMetrics.measureRetrievalCase(noGroundCase, noGroundResponse, 2);
  const summary = retrievalMetrics.summarizeRetrievalCases([topTwo, falseGround], 2);

  assert.equal(falseGround.noGroundExpected, true);
  assert.equal(falseGround.falseGround, true);
  assert.equal(summary.recallEligibleCases, 1);
  assert.deepEqual(summary.falseGround, {
    cases: 1,
    falseGrounds: 1,
    rate: 1,
    ids: ['no-runtime'],
  });
  assert.equal(summary.stages.vectorHits.documentHitRate, 1);
});

test('document hit is case-level while alias coverage remains partial term coverage', () => {
  const evalCase = {
    id: 'document-alias',
    expectedTitleTerms: ['정식 문서명', '통용 별칭'],
    expectedDocumentTerms: [],
    expectedSectionTypes: [],
    expectedParentTerms: [],
    expectedResultMsgs: ['OK'],
  };
  const direct = { chunkId: 1, target: 'official_doc', title: '정식 문서명' };
  const response = Object.fromEntries(retrievalMetrics.STAGE_NAMES.map((stage) => [stage, [direct]]));
  response.resultMsg = 'OK';

  const measured = retrievalMetrics.measureRetrievalCase(evalCase, response, 10);
  const summary = retrievalMetrics.summarizeRetrievalCases([measured], 10);

  assert.equal(measured.stages.vectorHits.documentHit, true);
  assert.equal(measured.stages.vectorHits.documentTermCoverage, 0.5);
  assert.equal(summary.stages.vectorHits.documentHitRate, 1);
  assert.equal(summary.stages.vectorHits.documentTermCoverageAtK, 0.5);
  assert.equal('documentRecallAtK' in summary.stages.vectorHits, false);
});

test('document identity matching ignores expected terms found only in chunk headings', () => {
  const evalCase = {
    id: 'wrong-document-heading',
    expectedTitleTerms: ['정답 문서'],
    expectedDocumentTerms: [],
    expectedSectionTypes: [],
    expectedParentTerms: [],
    expectedResultMsgs: ['OK'],
  };
  const wrongDocument = {
    chunkId: 1,
    target: 'official_doc',
    title: '오답 문서',
    chunkTitle: '정답 문서',
    parentSectionTitle: '정답 문서 개요',
  };
  const response = Object.fromEntries(retrievalMetrics.STAGE_NAMES.map((stage) => [stage, [wrongDocument]]));
  response.resultMsg = 'OK';

  const measured = retrievalMetrics.measureRetrievalCase(evalCase, response, 10);

  assert.equal(measured.stages.vectorHits.documentHit, false);
  assert.equal(measured.stages.vectorHits.documentTermCoverage, 0);
});

test('expected parent matching ignores terms found only in body text', () => {
  const evalCase = {
    id: 'body-only-parent',
    expectedTitleTerms: ['정답 문서'],
    expectedDocumentTerms: [],
    expectedSectionTypes: [],
    expectedParentTerms: ['권한 관리'],
    expectedResultMsgs: ['OK'],
  };
  const bodyOnly = {
    chunkId: 1,
    target: 'official_doc',
    title: '정답 문서',
    chunkTitle: '일반 안내',
    parentSectionTitle: '사용 방법',
    snippet: '본문에서 권한 관리라는 표현만 언급한다.',
  };
  const response = Object.fromEntries(retrievalMetrics.STAGE_NAMES.map((stage) => [stage, [bodyOnly]]));
  response.resultMsg = 'OK';

  const measured = retrievalMetrics.measureRetrievalCase(evalCase, response, 10);

  assert.equal(measured.stages.vectorHits.documentHit, true);
  assert.equal(measured.stages.vectorHits.sectionParentHit, false);
  assert.equal(measured.stages.vectorHits.sectionParentTermCoverage, 0);
  assert.equal(measured.stages.vectorHits.directHit, false);
});

test('section type matching uses the indexed section type instead of body inference', () => {
  const evalCase = {
    id: 'body-only-section-type',
    expectedTitleTerms: ['정답 문서'],
    expectedDocumentTerms: [],
    expectedSectionTypes: ['requirement'],
    expectedParentTerms: [],
    expectedResultMsgs: ['OK'],
  };
  const bodyOnly = {
    chunkId: 1,
    target: 'official_doc',
    title: '정답 문서',
    chunkTitle: '일반 설명',
    parentSectionTitle: '개요',
    sectionType: 'body',
    snippet: '본문에 반드시 준수해야 한다는 말이 있지만 이 청크의 구조 유형은 본문이다.',
  };
  const response = Object.fromEntries(retrievalMetrics.STAGE_NAMES.map((stage) => [stage, [bodyOnly]]));
  response.resultMsg = 'OK';

  const measured = retrievalMetrics.measureRetrievalCase(evalCase, response, 10);

  assert.equal(measured.stages.vectorHits.sectionParentHit, false);
  assert.equal(measured.stages.vectorHits.directHit, false);
});

test('retrieval runner accepts explicit case IDs, case limit, K, output path, and bounded rank capture', () => {
  assert.equal(typeof retrievalRunner.parseOptions, 'function');

  const options = retrievalRunner.parseOptions([
    '--case-ids', 'guide-a,guide-b',
    '--limit', '12',
    '--k', '7',
    '--capture-rank-limit', '3',
    '--training-manifest', 'src/main/resources/training.json',
    '--output', 'logs/custom-retrieval.json',
  ], {});

  assert.deepEqual(options.caseIds, ['guide-a', 'guide-b']);
  assert.equal(options.caseLimit, 12);
  assert.equal(options.k, 7);
  assert.equal(options.captureRankLimit, 3);
  assert.equal(options.trainingManifestPath, 'src/main/resources/training.json');
  assert.equal(options.outputPath, 'logs/custom-retrieval.json');
  assert.equal(options.reportPath, 'logs/custom-retrieval.md');
});

test('training capture requires the exact manifest order', () => {
  const manifestInfo = {
    trainingCases: [{ id: 'case-a' }, { id: 'case-b' }],
  };

  assert.doesNotThrow(() => retrievalRunner.assertTrainingSelection(
    manifestInfo,
    [{ id: 'case-a' }, { id: 'case-b' }],
  ));
  assert.throws(
    () => retrievalRunner.assertTrainingSelection(
      manifestInfo,
      [{ id: 'case-b' }, { id: 'case-a' }],
    ),
    /training selection does not match manifest order/i,
  );
});

test('retrieval rank capture defaults off and rejects limits above 100', () => {
  assert.equal(retrievalRunner.parseOptions([], {}).captureRankLimit, 0);
  assert.throws(
    () => retrievalRunner.parseOptions(['--capture-rank-limit', '101'], {}),
    /capture rank limit must be between 0 and 100/i,
  );
});

test('source rank snapshot is bounded, ordered, audit-only, and excludes candidate text', () => {
  const response = {
    vectorHits: [
      {
        target: 'law',
        chunkId: 9,
        documentId: 109,
        matchedAuditGroupIndexes: [2, 0, 2, -1, '1'],
        chunkText: 'secret chunk',
        body: 'secret body',
        snippet: 'secret snippet',
      },
      { candidateKey: 'official_doc:7', documentId: 207, matchedAuditGroupIndexes: [1] },
      { candidateKey: 'law:8', documentId: 108, matchedAuditGroupIndexes: [] },
    ],
    bm25Hits: [
      { candidateKey: 'internal_doc:4', documentId: 304, matchedAuditGroupIndexes: [3, 1] },
    ],
  };

  const snapshot = retrievalRunner.captureSourceRankSnapshot(response, 2);

  assert.deepEqual(snapshot, {
    vector: [
      { candidateKey: 'law:9', documentId: 109, rank: 1, matchedAuditGroupIndexes: [0, 2] },
      { candidateKey: 'official_doc:7', documentId: 207, rank: 2, matchedAuditGroupIndexes: [1] },
    ],
    bm25: [
      { candidateKey: 'internal_doc:4', documentId: 304, rank: 1, matchedAuditGroupIndexes: [1, 3] },
    ],
  });
  const serialized = JSON.stringify(snapshot);
  assert.equal(serialized.includes('secret'), false);
  assert.equal(serialized.includes('chunkText'), false);
  assert.equal(serialized.includes('body'), false);
  assert.equal(serialized.includes('snippet'), false);
});

test('runtime verification retries one read-only transport timeout and preserves attempt count', async () => {
  assert.equal(typeof retrievalRunner.loadRuntimeInfo, 'function');
  let calls = 0;
  const runtime = await retrievalRunner.loadRuntimeInfo('http://runtime.test', 50, {
    fetchImpl: async () => {
      calls += 1;
      if (calls === 1) {
        const error = new Error('timed out');
        error.name = 'AbortError';
        throw error;
      }
      return {
        ok: true,
        json: async () => ({ runtimeInstanceId: 'runtime-a', indexRevision: 'revision-a' }),
      };
    },
    delayImpl: async () => {},
  });

  assert.equal(calls, 2);
  assert.equal(runtime.readAttempts, 2);
  assert.equal(runtime.runtimeInstanceId, 'runtime-a');
});

test('runtime verification never retries a completed HTTP rejection', async () => {
  assert.equal(typeof retrievalRunner.loadRuntimeInfo, 'function');
  let calls = 0;
  await assert.rejects(
    retrievalRunner.loadRuntimeInfo('http://runtime.test', 50, {
      fetchImpl: async () => {
        calls += 1;
        return { ok: false, status: 503 };
      },
      delayImpl: async () => {},
    }),
    /HTTP 503/,
  );
  assert.equal(calls, 1);
});

test('debug response validator requires resultMsg and every retrieval stage array', () => {
  assert.equal(typeof retrievalRunner.assertDebugResponse, 'function');
  const valid = Object.fromEntries([
    ...retrievalMetrics.STAGE_NAMES,
    ...retrievalMetrics.SHADOW_STAGE_NAMES,
  ].map((stage) => [stage, []]));
  valid.resultMsg = 'OK';
  valid.documentExpansionStatus = 'NO_STRONG_ANCHOR';
  valid.documentExpansionReasonCodes = ['DOCUMENT_NOT_ANCHORED'];
  valid.documentExpansionHits = [];
  valid.documentExpansionFused = [];
  valid.bm25VariantStatus = 'DISABLED';
  valid.bm25VariantReasonCodes = [];
  valid.bm25VariantHashes = [];
  valid.bm25VariantPlanningMs = 0;
  valid.bm25VariantSearchMs = 0;
  valid.bm25VariantFusionMs = 0;
  valid.bm25VariantHits = [];

  assert.equal(retrievalRunner.assertDebugResponse(valid), valid);
  assert.throws(
    () => retrievalRunner.assertDebugResponse({ ...valid, resultMsg: undefined }),
    /resultMsg.*string/i,
  );
  assert.throws(
    () => retrievalRunner.assertDebugResponse({ ...valid, judged: undefined }),
    /judged.*array/i,
  );
  assert.throws(
    () => retrievalRunner.assertDebugResponse({ ...valid, selected: {} }),
    /selected.*array/i,
  );
  assert.throws(
    () => retrievalRunner.assertDebugResponse({ ...valid, coverageFused: undefined }),
    /coverageFused.*array/i,
  );
  assert.throws(
    () => retrievalRunner.assertDebugResponse({ ...valid, documentExpansionHits: undefined }),
    /documentExpansionHits.*array/i,
  );
  assert.throws(
    () => retrievalRunner.assertDebugResponse({ ...valid, documentExpansionStatus: undefined }),
    /documentExpansionStatus.*string/i,
  );
  assert.throws(
    () => retrievalRunner.assertDebugResponse({ ...valid, documentExpansionReasonCodes: undefined }),
    /documentExpansionReasonCodes.*array/i,
  );
  assert.throws(
    () => retrievalRunner.assertDebugResponse({
      ...valid,
      vectorHits: [{ title: '문서', chunkTitle: '제목' }],
    }),
    /vectorHits.*parentSectionTitle.*sectionType/i,
  );
	assert.throws(
		() => retrievalRunner.assertDebugResponse({
			...valid,
			candidateTraces: [{ candidateKey: 'law:1', chunkText: 'must not escape' }],
		}),
		/candidateTraces.*chunkText/i,
	);
});

test('document expansion capture is bounded, audit-only, and rejects malformed expansion payloads', () => {
  assert.equal(typeof retrievalRunner.assertDocumentExpansionCapture, 'function');
  const response = {
    documentExpansionHits: [{
      target: 'official_doc',
      chunkId: 7,
      documentId: 207,
      documentExpansionRank: 1,
      documentExpansionAnchorType: 'EXPLICIT_TITLE',
      documentExpansionReason: 'EXACT_PROVISION',
      documentExpansionOverlap: false,
      matchedAuditGroupIndexes: [1, 0, 1],
      title: 'must not be captured',
      snippet: 'must not be captured',
    }],
    documentExpansionFused: [
      {
        target: 'official_doc',
        chunkId: 7,
        documentId: 207,
        documentExpansionRank: 1,
        documentExpansionAnchorType: 'EXPLICIT_TITLE',
        documentExpansionReason: 'EXACT_PROVISION',
        documentExpansionOverlap: false,
        matchedAuditGroupIndexes: [0, 1],
      },
      {
        target: 'law',
        chunkId: 8,
        documentId: 108,
        matchedAuditGroupIndexes: [],
      },
    ],
  };

  const captured = retrievalRunner.assertDocumentExpansionCapture(response);

  assert.deepEqual(captured, {
    documentExpansionHits: [{
      candidateKey: 'official_doc:7',
      documentId: 207,
      rank: 1,
      anchorType: 'EXPLICIT_TITLE',
      reason: 'EXACT_PROVISION',
      overlapsExistingSource: false,
      matchedAuditGroupIndexes: [0, 1],
    }],
    documentExpansionFused: [{
      candidateKey: 'official_doc:7',
      documentId: 207,
      rank: 1,
      anchorType: 'EXPLICIT_TITLE',
      reason: 'EXACT_PROVISION',
      overlapsExistingSource: false,
      matchedAuditGroupIndexes: [0, 1],
    }],
  });
  assert.equal(JSON.stringify(captured).includes('must not be captured'), false);
  assert.throws(
    () => retrievalRunner.assertDocumentExpansionCapture({ ...response, documentExpansionHits: undefined }),
    /documentExpansionHits.*array/i,
  );
  assert.throws(
    () => retrievalRunner.assertDocumentExpansionCapture({
      ...response,
      documentExpansionHits: [...response.documentExpansionHits, { ...response.documentExpansionHits[0] }],
    }),
    /duplicate.*candidate/i,
  );
  assert.throws(
    () => retrievalRunner.assertDocumentExpansionCapture({
      ...response,
      documentExpansionHits: [{ ...response.documentExpansionHits[0], documentExpansionReason: 'UNKNOWN_REASON' }],
    }),
    /unknown.*reason/i,
  );
  assert.throws(
    () => retrievalRunner.assertDocumentExpansionCapture({
      ...response,
      documentExpansionHits: [{ ...response.documentExpansionHits[0], documentId: 0 }],
    }),
    /invalid documentId/i,
  );
  assert.throws(
    () => retrievalRunner.assertDocumentExpansionCapture({
      ...response,
      documentExpansionHits: [{ ...response.documentExpansionHits[0], documentExpansionRank: 0 }],
    }),
    /invalid document expansion rank/i,
  );
  assert.throws(
    () => retrievalRunner.assertDocumentExpansionCapture({
      ...response,
      documentExpansionHits: [{ ...response.documentExpansionHits[0], candidateKey: '   ' }],
      documentExpansionFused: [],
    }),
    /candidate key/i,
  );
  assert.throws(
    () => retrievalRunner.assertDocumentExpansionCapture({
      ...response,
      documentExpansionHits: [{ ...response.documentExpansionHits[0], documentId: '207' }],
    }),
    /invalid documentId/i,
  );
  assert.throws(
    () => retrievalRunner.assertDocumentExpansionCapture({
      ...response,
      documentExpansionHits: [{ ...response.documentExpansionHits[0], documentExpansionRank: '1' }],
    }),
    /invalid document expansion rank/i,
  );
  assert.throws(
    () => retrievalRunner.assertDocumentExpansionCapture({
      ...response,
      documentExpansionHits: Array.from({ length: 25 }, (_, index) => ({
        ...response.documentExpansionHits[0], chunkId: index + 1, documentExpansionRank: index + 1,
      })),
    }),
    /at most 24/i,
  );
  assert.throws(
    () => retrievalRunner.assertDocumentExpansionCapture({
      ...response,
      documentExpansionHits: Array.from({ length: 9 }, (_, index) => ({
        ...response.documentExpansionHits[0], chunkId: index + 1, documentExpansionRank: index + 1,
      })),
    }),
    /at most 8.*document/i,
  );
});

test('retrieval runner accepts a retrieval-only evaluation manifest', () => {
  const options = retrievalRunner.parseOptions([
    '--case-ids', 'guide-a,guide-b',
    '--evaluation-manifest', 'src/main/resources/difficult.json',
  ], {});

  assert.equal(options.evaluationManifestPath, 'src/main/resources/difficult.json');
  assert.equal(options.trainingManifestPath, null);
});

test('group-balanced BM25 capture is bounded, audit-only, and validates variant ranks', () => {
  const response = {
    bm25VariantStatus: 'APPLIED',
    bm25VariantReasonCodes: [],
    bm25VariantHashes: ['aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'],
    bm25VariantPlanningMs: 3,
    bm25VariantSearchMs: 12,
    bm25VariantFusionMs: 1,
    bm25VariantHits: [{
      target: 'law',
      chunkId: 7,
      documentId: 207,
      bm25VariantRanks: { 'direct-evidence': 1, 'entity-intent': 2 },
      matchedAuditGroupIndexes: [1, 0, 1],
      title: 'must not be captured',
      snippet: 'must not be captured',
    }],
  };

  assert.deepEqual(retrievalRunner.assertBm25VariantCapture(response), {
    status: 'APPLIED',
    reasonCodes: [],
    variantHashes: response.bm25VariantHashes,
    timings: { planningMs: 3, searchMs: 12, fusionMs: 1 },
    hits: [{
      candidateKey: 'law:7',
      documentId: 207,
      rank: 1,
      variantRanks: { 'direct-evidence': 1, 'entity-intent': 2 },
      matchedAuditGroupIndexes: [0, 1],
    }],
  });
  assert.equal(JSON.stringify(retrievalRunner.assertBm25VariantCapture(response)).includes('must not be captured'), false);

  assert.throws(
    () => retrievalRunner.assertBm25VariantCapture({ ...response, bm25VariantStatus: 'UNKNOWN' }),
    /bm25VariantStatus.*known/i,
  );
  assert.throws(
    () => retrievalRunner.assertBm25VariantCapture({
      ...response,
      bm25VariantHits: [{ ...response.bm25VariantHits[0], bm25VariantRanks: { 'direct-evidence': 0 } }],
    }),
    /variant rank/i,
  );
  assert.throws(
    () => retrievalRunner.assertBm25VariantCapture({
      ...response,
      bm25VariantHashes: [...response.bm25VariantHashes, response.bm25VariantHashes[0]],
    }),
    /hash/i,
  );
  assert.throws(
    () => retrievalRunner.assertBm25VariantCapture({ ...response, bm25VariantSearchMs: -1 }),
    /timing/i,
  );
});

test('group-balanced BM25 measurement reports only evaluation-time required-group presence', () => {
  const evalCase = {
    requiredPropositionGroups: [['의무']],
    requiredConditionGroups: [['예외']],
  };
  const item = (target, chunkId, groups, variantRanks = {}) => ({
    target, chunkId, documentId: 100 + chunkId,
    matchedAuditGroupIndexes: groups,
    bm25VariantRanks: variantRanks,
  });
  const response = {
    vectorHits: [item('law', 1, [0])],
    lexicalHits: [],
    bm25Hits: [],
    bm25VariantStatus: 'APPLIED',
    bm25VariantReasonCodes: [],
    bm25VariantHashes: ['bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'],
    bm25VariantPlanningMs: 1,
    bm25VariantSearchMs: 2,
    bm25VariantFusionMs: 1,
    bm25VariantHits: [item('law', 2, [1], { 'direct-evidence': 1 })],
  };

  const measured = retrievalRunner.measureBm25VariantCase(evalCase, response, 10);

  assert.deepEqual(measured.controlSourcePresence.matchedRequiredGroupIndexes, [0]);
  assert.deepEqual(measured.variantPresence.matchedRequiredGroupIndexes, [1]);
  assert.deepEqual(measured.shadowSourcePresence.matchedRequiredGroupIndexes, [0, 1]);
  assert.equal(measured.shadowSourcePresence.allRequired, true);
});

test('BM25 title expansion capture requires bounded seed metadata and known outcomes', () => {
  const bm25Title = {
    target: 'law',
    chunkId: 101,
    documentId: 10,
    documentExpansionRank: 1,
    documentExpansionAnchorType: 'BM25_TITLE',
    documentExpansionReason: 'BM25_TITLE_SEED',
    documentExpansionOverlap: false,
    documentExpansionSeedTermCount: 2,
    documentExpansionSeedBm25Score: 9.5,
    documentExpansionSeedBm25Rank: 4,
    matchedAuditGroupIndexes: [0],
    title: 'must not be captured',
  };
  const response = {
    documentExpansionHits: [bm25Title],
    documentExpansionFused: [bm25Title],
  };

  assert.deepEqual(retrievalRunner.assertDocumentExpansionCapture(response).documentExpansionHits[0], {
    candidateKey: 'law:101',
    documentId: 10,
    rank: 1,
    anchorType: 'BM25_TITLE',
    reason: 'BM25_TITLE_SEED',
    overlapsExistingSource: false,
    seedTermCount: 2,
    seedBm25Score: 9.5,
    seedBm25Rank: 4,
    matchedAuditGroupIndexes: [0],
  });

  for (const malformed of [
    { documentExpansionSeedTermCount: undefined },
    { documentExpansionSeedBm25Score: undefined },
    { documentExpansionSeedBm25Rank: undefined },
    { documentExpansionSeedTermCount: 1 },
    { documentExpansionSeedTermCount: 7 },
    { documentExpansionSeedBm25Score: Number.POSITIVE_INFINITY },
    { documentExpansionSeedBm25Score: 0 },
    { documentExpansionSeedBm25Rank: 0 },
    { documentExpansionSeedBm25Rank: 101 },
  ]) {
    assert.throws(
      () => retrievalRunner.assertDocumentExpansionCapture({
        ...response,
        documentExpansionHits: [{ ...bm25Title, ...malformed }],
      }),
      /seed|BM25/i,
    );
  }

  assert.throws(
    () => retrievalRunner.assertDocumentExpansionCapture({
      ...response,
      documentExpansionHits: [{
        ...bm25Title,
        documentExpansionAnchorType: 'EXPLICIT_TITLE',
        documentExpansionReason: 'EXACT_PROVISION',
      }],
    }),
    /seed|legacy/i,
  );

  assert.throws(
    () => retrievalRunner.assertDocumentExpansionCapture({
      documentExpansionHits: Array.from({ length: 4 }, (_, index) => ({
        ...bm25Title,
        chunkId: index + 1,
        documentId: index + 1,
        documentExpansionRank: index + 1,
      })),
      documentExpansionFused: [],
    }),
    /at most 3.*document/i,
  );

  const validDebug = validDebugResponse();
  assert.doesNotThrow(() => retrievalRunner.assertDebugResponse({
    ...validDebug,
    documentExpansionStatus: 'BM25_TITLE_APPLIED',
    documentExpansionReasonCodes: [],
    bm25VariantStatus: 'DISABLED',
    bm25VariantReasonCodes: [],
    bm25VariantHashes: [],
    bm25VariantPlanningMs: 0,
    bm25VariantSearchMs: 0,
    bm25VariantFusionMs: 0,
    bm25VariantHits: [],
    documentExpansionHits: [bm25Title],
    documentExpansionFused: [bm25Title],
  }));
  assert.throws(
    () => retrievalRunner.assertDebugResponse({
      ...validDebug,
      documentExpansionStatus: 'UNKNOWN_BM25_STATUS',
    }),
    /documentExpansionStatus.*known/i,
  );
  assert.throws(
    () => retrievalRunner.assertDebugResponse({
      ...validDebug,
      documentExpansionStatus: 'BM25_TITLE_NO_MATCH',
      documentExpansionReasonCodes: ['UNKNOWN_BM25_REASON'],
    }),
    /ReasonCodes.*unknown/i,
  );
  assert.doesNotThrow(() => retrievalRunner.assertDebugResponse({
    ...validDebug,
    documentExpansionStatus: 'BM25_TITLE_DB_FALLBACK',
    documentExpansionReasonCodes: ['BM25_TITLE_EXPANSION_DB_FAILURE'],
  }));
  assert.doesNotThrow(() => retrievalRunner.assertDebugResponse({
    ...validDebug,
    documentExpansionStatus: 'BM25_TITLE_NO_MATCH',
    documentExpansionReasonCodes: ['DOCUMENT_DUPLICATE_OVERLAP', 'BM25_TITLE_NO_NOVEL_CHUNK'],
  }));
  assert.doesNotThrow(() => retrievalRunner.assertDebugResponse({
    ...validDebug,
    documentExpansionStatus: 'BM25_TITLE_NO_MATCH',
    documentExpansionReasonCodes: [
      'BM25_TITLE_NO_MATCH',
      'BM25_TITLE_DIAGNOSTIC_REASON_TITLE_MISMATCH',
      'BM25_TITLE_PLANNED_TERM_COUNT_3',
      'BM25_TITLE_INSPECTED_CANDIDATE_COUNT_24',
      'BM25_TITLE_HYDRATED_CANDIDATE_COUNT_22',
      'BM25_TITLE_MAX_MATCHED_TITLE_TERM_COUNT_1',
    ],
  }));
  assert.throws(
    () => retrievalRunner.assertDebugResponse({
      ...validDebug,
      documentExpansionStatus: 'BM25_TITLE_NO_MATCH',
      documentExpansionReasonCodes: ['BM25_TITLE_PLANNED_TERM_COUNT_NOT_A_NUMBER'],
    }),
    /ReasonCodes.*unknown/i,
  );
});

test('document expansion shadow-fused presence includes retained control and expansion candidates', () => {
  const expansion = {
    target: 'official_doc',
    chunkId: 7,
    documentId: 207,
    documentExpansionRank: 1,
    documentExpansionAnchorType: 'EXPLICIT_TITLE',
    documentExpansionReason: 'EXACT_PROVISION',
    documentExpansionOverlap: false,
    matchedAuditGroupIndexes: [1],
  };
  const result = retrievalRunner.measureDocumentExpansionCase({
    requiredPropositionGroups: [['control'], ['expansion']],
    requiredConditionGroups: [],
  }, {
    vectorHits: [{ matchedAuditGroupIndexes: [0] }],
    lexicalHits: [],
    bm25Hits: [],
    fused: [{ matchedAuditGroupIndexes: [0] }],
    documentExpansionHits: [expansion],
    documentExpansionFused: [{ matchedAuditGroupIndexes: [0] }, expansion],
    documentExpansionStatus: 'APPLIED',
    documentExpansionReasonCodes: [],
  });

  assert.deepEqual(result.controlFusedPresence.matchedRequiredGroupIndexes, [0]);
  assert.deepEqual(result.shadowFusedPresence.matchedRequiredGroupIndexes, [0, 1]);
  assert.equal(result.shadowFusedPresence.allRequired, true);
  assert.equal(result.status, 'APPLIED');
  assert.deepEqual(result.reasonCodes, []);
  assert.deepEqual(result.capture.documentExpansionFused.map((item) => item.candidateKey), ['official_doc:7']);
});

test('document expansion compares top-k fused control to top-k fused shadow without inventing a regression', () => {
  const result = retrievalRunner.measureDocumentExpansionCase({
    requiredPropositionGroups: [['first'], ['second']],
    requiredConditionGroups: [],
  }, {
    vectorHits: [{ matchedAuditGroupIndexes: [0, 1] }],
    lexicalHits: [],
    bm25Hits: [],
    fused: [{ matchedAuditGroupIndexes: [0] }],
    documentExpansionHits: [],
    documentExpansionFused: [{ matchedAuditGroupIndexes: [0] }],
    documentExpansionStatus: 'NO_STRONG_ANCHOR',
    documentExpansionReasonCodes: ['DOCUMENT_NOT_ANCHORED'],
  }, 10);

  assert.deepEqual(result.candidateSourcePresence.matchedRequiredGroupIndexes, [0, 1]);
  assert.deepEqual(result.controlFusedPresence.matchedRequiredGroupIndexes, [0]);
  assert.deepEqual(result.shadowFusedPresence.matchedRequiredGroupIndexes, [0]);
  assert.equal(result.firstDropStage, 'controlFused');
  assert.equal(result.status, 'NO_STRONG_ANCHOR');
  assert.deepEqual(result.reasonCodes, ['DOCUMENT_NOT_ANCHORED']);
});

test('document expansion capture ignores nullable fused control metadata but rejects partial expansion metadata', () => {
  const expansion = {
    target: 'official_doc',
    chunkId: 7,
    documentId: 207,
    documentExpansionRank: 1,
    documentExpansionAnchorType: 'EXPLICIT_TITLE',
    documentExpansionReason: 'EXACT_PROVISION',
    documentExpansionOverlap: false,
    matchedAuditGroupIndexes: [0],
  };
  const controls = Array.from({ length: 29 }, (_, index) => ({
    target: 'law',
    chunkId: index + 1,
    documentId: index + 1,
    documentExpansionRank: null,
    documentExpansionAnchorType: null,
    documentExpansionReason: null,
    documentExpansionOverlap: null,
    matchedAuditGroupIndexes: [],
  }));

  assert.deepEqual(retrievalRunner.assertDocumentExpansionCapture({
    documentExpansionHits: [expansion],
    documentExpansionFused: [...controls, expansion],
  }).documentExpansionFused, [{
    candidateKey: 'official_doc:7',
    documentId: 207,
    rank: 1,
    anchorType: 'EXPLICIT_TITLE',
    reason: 'EXACT_PROVISION',
    overlapsExistingSource: false,
    matchedAuditGroupIndexes: [0],
  }]);

  assert.throws(
    () => retrievalRunner.assertDocumentExpansionCapture({
      documentExpansionHits: [expansion],
      documentExpansionFused: [{ ...expansion, documentExpansionAnchorType: null }],
    }),
    /invalid document expansion anchor type/i,
  );

  for (const malformed of [
    { documentExpansionAnchorType: 42 },
    { documentExpansionReason: {} },
    { documentExpansionOverlap: 'false' },
  ]) {
    assert.throws(
      () => retrievalRunner.assertDocumentExpansionCapture({
        documentExpansionHits: [expansion],
        documentExpansionFused: [{
          target: 'law',
          chunkId: 99,
          documentId: 99,
          documentExpansionRank: null,
          documentExpansionAnchorType: null,
          documentExpansionReason: null,
          documentExpansionOverlap: null,
          matchedAuditGroupIndexes: [],
          ...malformed,
        }],
      }),
      /invalid document expansion rank/i,
    );
  }
});

test('release coverage rejects no-ground controls without distractors and too few controls', () => {
  const cases = [{
    id: 'no-weak',
    question: 'unsupported',
    expectedResultMsgs: ['NO_GROUNDS'],
    forbiddenTerms: [],
  }];

  assert.throws(
    () => caseParser.assertReleaseCoverage(cases, { minimumNoGround: 2 }),
    /NO_GROUNDS_DISTRACTOR_MISSING.*NO_GROUNDS_MINIMUM/i,
  );
});

test('release coverage rejects answer-required rows without proposition groups', () => {
  assert.throws(
    () => caseParser.assertReleaseCoverage([{
      id: 'answer-without-proposition',
      question: 'answer me',
      answerVerificationRequired: true,
      requiredPropositionGroups: [],
    }], { minimumNoGround: 0 }),
    /ANSWER_PROPOSITION_MISSING/i,
  );
});

test('release coverage rejects duplicate normalized question and oracle combinations', () => {
  const oracle = {
    requiredPropositionGroups: [['must notify']],
    requiredConditionGroups: [],
    forbiddenAnswerTerms: ['no notice'],
  };

  assert.throws(
    () => caseParser.assertReleaseCoverage([
      { id: 'one', question: 'Must notify?', ...oracle },
      { id: 'two', question: 'must-notify!', ...oracle },
    ], { minimumNoGround: 0 }),
    /DUPLICATE_QUESTION_ORACLE/i,
  );
});

test('release coverage permits the same generated question with different retrieval oracles', () => {
  const coverage = caseParser.assertReleaseCoverage([
    {
      id: 'gen-one',
      question: '이 공식문서를 찾아줘',
      targets: ['official_doc'],
      expectedDocumentTerms: ['문서 A'],
    },
    {
      id: 'gen-two',
      question: '이 공식문서를 찾아줘',
      targets: ['official_doc'],
      expectedDocumentTerms: ['문서 B'],
    },
  ], { minimumNoGround: 0 });

  assert.equal(coverage.passed, true);
});

test('release coverage rejects reviewed failures classified only as generic other', () => {
  assert.throws(
    () => caseParser.assertReleaseCoverage([{
      id: 'reviewed-failure-generic',
      question: 'failure',
      failureTaxonomy: '기타',
    }], { minimumNoGround: 0 }),
    /GENERIC_FAILURE_TAXONOMY/i,
  );
});

test('coverage report exposes named gates, unsafe semantic disagreement, and first-loss coverage', () => {
  const cases = [
    {
      id: 'curated-answer',
      question: 'answer',
      answerVerificationRequired: true,
      requiredPropositionGroups: [['must notify']],
      requiredConditionGroups: [['within 30 days']],
      forbiddenAnswerTerms: ['never notify'],
      forbiddenTerms: [],
      expectedResultMsgs: [],
    },
    {
      id: 'gen-control',
      question: 'unsupported',
      answerVerificationRequired: false,
      requiredPropositionGroups: [],
      requiredConditionGroups: [],
      forbiddenAnswerTerms: [],
      forbiddenTerms: ['unrelated title'],
      expectedResultMsgs: ['NO_GROUNDS'],
      failureTaxonomy: 'unrelated-domain',
    },
  ];
  const gate = {
    unsafeSemanticShadowDisagreementCount: 2,
    results: [],
  };
  const failurePresence = {
    results: [
      {
        id: 'lost',
        presenceClassification: 'DROPPED_BEFORE_SELECTED',
        candidateFirstLossStage: 'judgeCandidates',
      },
      {
        id: 'selected',
        presenceClassification: 'PRESENT_IN_SELECTED',
        candidateFirstLossStage: null,
      },
    ],
  };

  const report = coverageReporter.buildCoverageReport(cases, gate, failurePresence, {
    minimumNoGround: 1,
    gateFile: 'logs/gate.json',
    failurePresenceFile: 'logs/presence.json',
  });

  assert.equal(report.namedGates.releaseTotal, 2);
  assert.equal(report.namedGates.curatedTotal, 1);
  assert.equal(report.namedGates.answerOracleTotal, 1);
  assert.equal(report.namedGates.noGroundTotal, 1);
  assert.equal(report.namedGates.explicitConditionTotal, 1);
  assert.equal(report.namedGates.unsafeSemanticDisagreementCount, 2);
  assert.deepEqual(report.namedGates.candidatePresentFirstLossCoverage, {
    candidatePresentFailures: 2,
    firstLossRecorded: 1,
    selectedWithoutLoss: 1,
    covered: 2,
    rate: 1,
    uncoveredIds: [],
  });
});

test('generated supplement rejects duplicate question and retrieval oracle rows', () => {
  const keys = new Set();
  const row = {
    id: 'gen-one',
    question: '문서에서 근거를 찾아줘',
    targets: 'official_doc',
    expectedTerms: '근거|문서',
    requiredMatches: 2,
    expectedTitleTerms: '문서',
    expectedSectionTypes: '',
    forbiddenTerms: '',
    expectedDocumentTerms: '문서',
    expectedPageNumbers: '',
    expectedParentTerms: '',
    answerDirection: 'generated',
    expectedResultMsgs: '',
  };

  assert.equal(supplementGenerator.rememberUniqueCase(row, keys), true);
  assert.equal(supplementGenerator.rememberUniqueCase({ ...row, id: 'gen-two' }, keys), false);
  assert.equal(supplementGenerator.rememberUniqueCase({
    ...row,
    id: 'gen-three',
    expectedDocumentTerms: '다른 문서',
  }, keys), true);
});

test('candidate loss analysis joins audit-matched candidates to their first server-side loss', () => {
	assert.equal(typeof retrievalRunner.extractCandidateLossAnalysis, 'function');
	const response = Object.fromEntries(retrievalMetrics.STAGE_NAMES.map((stage) => [stage, []]));
	response.resultMsg = 'NO_GROUNDS';
	response.vectorHits = [{
		candidateKey: 'law:10',
		target: 'law',
		chunkId: 10,
		matchedAuditGroupIndexes: [0, 2],
	}];
	response.candidateTraces = [{
		candidateKey: 'law:10',
		target: 'law',
		chunkId: 10,
		sourceRanks: { vector: 3, bm25: 1 },
		enteredStages: ['loaded', 'merged', 'reranked', 'intent'],
		firstLossStage: 'judgeCandidates',
		reasonCodes: ['JUDGE_CANDIDATE_LIMIT'],
		selected: false,
	}];

	const analysis = retrievalRunner.extractCandidateLossAnalysis(response);

	assert.deepEqual(analysis.firstLossStageCounts, { judgeCandidates: 1 });
	assert.deepEqual(analysis.reasonCodeCounts, { JUDGE_CANDIDATE_LIMIT: 1 });
	assert.deepEqual(analysis.oracleCandidateTraces, [{
		candidateKey: 'law:10',
		oraclePresenceStage: 'intent',
		matchedAuditGroupIndexes: [0, 2],
		firstLossStage: 'judgeCandidates',
		reasonCodes: ['JUDGE_CANDIDATE_LIMIT'],
	}]);
});

test('candidate loss analysis includes audit matches found only by shadow retrieval', () => {
	const response = Object.fromEntries(retrievalMetrics.STAGE_NAMES.map((stage) => [stage, []]));
	response.resultMsg = 'NO_GROUNDS';
	response.bm25Hits = [{
		candidateKey: 'official_doc:23',
		target: 'official_doc',
		chunkId: 23,
		matchedAuditGroupIndexes: [0],
	}];
	response.fused = [];
	response.candidateTraces = [{
		candidateKey: 'official_doc:23',
		target: 'official_doc',
		chunkId: 23,
		sourceRanks: { bm25: 23 },
		enteredStages: ['loaded'],
		firstLossStage: 'merged',
		reasonCodes: ['MERGE_NOT_SELECTED'],
		selected: false,
	}];

	const analysis = retrievalRunner.extractCandidateLossAnalysis(response);

	assert.deepEqual(analysis.oracleCandidateTraces, [{
		candidateKey: 'official_doc:23',
		oraclePresenceStage: 'loaded',
		matchedAuditGroupIndexes: [0],
		firstLossStage: 'merged',
		reasonCodes: ['MERGE_NOT_SELECTED'],
	}]);
});

test('judged and selected stages distinguish judge rejection from ground rejection', () => {
  assert.equal(retrievalMetrics.STAGE_NAMES.includes('judged'), true);
  const evalCase = {
    id: 'judge-drop',
    expectedTitleTerms: ['정답 문서'],
    expectedDocumentTerms: [],
    expectedSectionTypes: [],
    expectedParentTerms: [],
    expectedResultMsgs: ['OK'],
  };
  const direct = { chunkId: 1, target: 'official_doc', title: '정답 문서' };
  const judgeRejected = Object.fromEntries(retrievalMetrics.STAGE_NAMES.map((stage) => [stage, [direct]]));
  judgeRejected.resultMsg = 'NO_GROUNDS';
  judgeRejected.judged = [];
  judgeRejected.selected = [];
  const groundRejected = Object.fromEntries(retrievalMetrics.STAGE_NAMES.map((stage) => [stage, [direct]]));
  groundRejected.resultMsg = 'NO_GROUNDS';
  groundRejected.selected = [];

  assert.equal(retrievalMetrics.measureRetrievalCase(evalCase, judgeRejected, 10).firstDropStage, 'judged');
  assert.equal(retrievalMetrics.measureRetrievalCase(evalCase, groundRejected, 10).firstDropStage, 'selected');
});

test('oracle presence aggregates server-confirmed body groups and records first loss', () => {
  const evalCase = {
    id: 'oracle-drop',
    expectedTitleTerms: ['정답 문서'],
    expectedDocumentTerms: [],
    expectedSectionTypes: [],
    expectedParentTerms: [],
    expectedResultMsgs: ['OK'],
    requiredPropositionGroups: [
      ['직접 결론', '결론 별칭'],
      ['절차 결과'],
    ],
    requiredConditionGroups: [
      ['적용 조건'],
    ],
  };
  const complete = {
    chunkId: 1,
    target: 'official_doc',
    title: '정답 문서',
    parentSectionTitle: '',
    sectionType: '',
    matchedAuditGroupIndexes: [0, 1, 2],
    matchedAuditAliases: ['직접 결론', '절차 결과', '적용 조건'],
  };
  const partial = {
    ...complete,
    matchedAuditGroupIndexes: [0, 2],
    matchedAuditAliases: ['직접 결론', '적용 조건'],
  };
  const response = Object.fromEntries(retrievalMetrics.STAGE_NAMES.map((stage) => [stage, [complete]]));
  response.resultMsg = 'OK';
  response.intentFiltered = [partial];
  response.judgeCandidates = [partial];
  response.judged = [partial];
  response.selected = [partial];

  const measured = retrievalMetrics.measureRetrievalCase(evalCase, response, 10);

  assert.equal(measured.oraclePresence.auditable, true);
  assert.equal(measured.oraclePresence.propositionGroupCount, 2);
  assert.equal(measured.oraclePresence.conditionGroupCount, 1);
  assert.deepEqual(measured.oraclePresence.stages.candidateSources.matchedGroupIndexes, [0, 1, 2]);
  assert.deepEqual(measured.oraclePresence.stages.selected.missingGroupIndexes, [1]);
  assert.equal(measured.oraclePresence.firstLossStage, 'intentFiltered');
  assert.equal(measured.oraclePresence.classification, 'DROPPED_BEFORE_SELECTED');
});

test('oracle presence distinguishes partial, absent, selected, and no-oracle cases', () => {
  const baseCase = {
    id: 'presence',
    expectedTitleTerms: [],
    expectedDocumentTerms: [],
    expectedSectionTypes: [],
    expectedParentTerms: [],
    expectedResultMsgs: ['OK'],
    requiredPropositionGroups: [['first'], ['second']],
    requiredConditionGroups: [],
  };
  const item = (indexes) => ({
    chunkId: indexes.join('-') || 0,
    target: 'law',
    title: 'law',
    parentSectionTitle: '',
    sectionType: '',
    matchedAuditGroupIndexes: indexes,
    matchedAuditAliases: indexes.map((index) => index === 0 ? 'first' : 'second'),
  });
  const response = (candidate, selected) => {
    const value = Object.fromEntries(retrievalMetrics.STAGE_NAMES.map((stage) => [stage, selected]));
    value.resultMsg = 'OK';
    value.vectorHits = candidate;
    value.lexicalHits = [];
    return value;
  };

  assert.equal(
    retrievalMetrics.measureRetrievalCase(baseCase, response([item([0])], []), 10)
      .oraclePresence.classification,
    'PARTIAL_IN_CANDIDATES',
  );
  assert.equal(
    retrievalMetrics.measureRetrievalCase(baseCase, response([item([])], []), 10)
      .oraclePresence.classification,
    'ABSENT_FROM_TOP_K_CANDIDATES',
  );
  assert.equal(
    retrievalMetrics.measureRetrievalCase(baseCase, response([item([0, 1])], [item([0, 1])]), 10)
      .oraclePresence.classification,
    'PRESENT_IN_SELECTED',
  );
  assert.equal(
    retrievalMetrics.measureRetrievalCase({
      ...baseCase,
      requiredPropositionGroups: [],
    }, response([], []), 10).oraclePresence.classification,
    'NO_EXPLICIT_ORACLE',
  );
});

test('debug request sends proposition groups before condition groups', () => {
  assert.equal(typeof retrievalRunner.buildDebugRequest, 'function');
  const request = retrievalRunner.buildDebugRequest({
    targets: ['law'],
    question: 'question',
    requiredPropositionGroups: [['p1', 'p1 alias'], ['p2']],
    requiredConditionGroups: [['c1']],
  }, 30);

  assert.deepEqual(request, {
    targets: ['law'],
    question: 'question',
    limit: 30,
    includeFuture: true,
    auditTermGroups: [['p1', 'p1 alias'], ['p2'], ['c1']],
  });
});

test('downstream survival excludes cases that were absent from candidate sources', () => {
  const evalCase = {
    id: 'late-hit',
    expectedTitleTerms: ['정답'],
    expectedDocumentTerms: [],
    expectedSectionTypes: [],
    expectedParentTerms: [],
    expectedResultMsgs: ['OK'],
  };
  const response = Object.fromEntries(retrievalMetrics.STAGE_NAMES.map((stage) => [stage, []]));
  response.merged = [{ chunkId: 10, target: 'law', title: '정답' }];

  const measured = retrievalMetrics.measureRetrievalCase(evalCase, response, 3);
  const summary = retrievalMetrics.summarizeRetrievalCases([measured], 3);

  assert.equal(measured.firstDropStage, 'candidateSources');
  assert.deepEqual(summary.stageSurvival.merged, {
    denominator: 0,
    survived: 0,
    rate: null,
  });
});

function validDebugResponse() {
  return {
    ...Object.fromEntries([
      ...retrievalMetrics.STAGE_NAMES,
      ...retrievalMetrics.SHADOW_STAGE_NAMES,
    ].map((stage) => [stage, []])),
    resultMsg: 'OK',
    documentExpansionStatus: 'NO_STRONG_ANCHOR',
    documentExpansionReasonCodes: ['DOCUMENT_NOT_ANCHORED'],
    documentExpansionHits: [],
    documentExpansionFused: [],
    bm25VariantStatus: 'DISABLED',
    bm25VariantReasonCodes: [],
    bm25VariantHashes: [],
    bm25VariantPlanningMs: 0,
    bm25VariantSearchMs: 0,
    bm25VariantFusionMs: 0,
    bm25VariantHits: [],
  };
}
