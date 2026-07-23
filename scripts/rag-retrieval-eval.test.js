const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

let caseParser = {};
let retrievalMetrics = {};
let retrievalRunner = {};
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

test('bundled answer-oracle sidecar merges exactly 85 explicit cases', () => {
  const repositoryRoot = path.resolve(__dirname, '..');
  const cases = caseParser.loadEvalCases([
    path.join(repositoryRoot, 'src', 'main', 'resources', 'rag-evaluation-cases.tsv'),
    path.join(repositoryRoot, 'src', 'main', 'resources', 'rag-evaluation-cases.generated.tsv'),
  ], {
    answerOraclePath: path.join(repositoryRoot, 'src', 'main', 'resources', 'rag-answer-evaluation-oracles.tsv'),
  });
  const explicit = cases.filter((item) => item.requiredPropositionGroups.length > 0);

  assert.equal(explicit.length, 85);
  assert.equal(explicit.every((item) => item.answerVerificationRequired === true), true);
  assert.equal(explicit.every((item) => item.forbiddenAnswerTerms.length > 0), true);
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
