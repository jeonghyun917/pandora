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

test('retrieval runner accepts explicit case IDs, case limit, K, and output path', () => {
  assert.equal(typeof retrievalRunner.parseOptions, 'function');

  const options = retrievalRunner.parseOptions([
    '--case-ids', 'guide-a,guide-b',
    '--limit', '12',
    '--k', '7',
    '--output', 'logs/custom-retrieval.json',
  ], {});

  assert.deepEqual(options.caseIds, ['guide-a', 'guide-b']);
  assert.equal(options.caseLimit, 12);
  assert.equal(options.k, 7);
  assert.equal(options.outputPath, 'logs/custom-retrieval.json');
  assert.equal(options.reportPath, 'logs/custom-retrieval.md');
});

test('debug response validator requires resultMsg and every retrieval stage array', () => {
  assert.equal(typeof retrievalRunner.assertDebugResponse, 'function');
  const valid = Object.fromEntries(retrievalMetrics.STAGE_NAMES.map((stage) => [stage, []]));
  valid.resultMsg = 'OK';

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
