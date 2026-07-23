const assert = require('node:assert/strict');
const fs = require('node:fs');
const test = require('node:test');
const {
  GRAMMATICAL_NEGATIONS,
  LOCAL_POLARITY_BRIDGES,
  matchOracleGroup,
  parseJavaListConstant,
} = require('./lib/rag-explicit-oracle-matcher');
const evidenceCoverage = require('./lib/rag-evidence-coverage');
const retrievalMetrics = require('./lib/rag-retrieval-metrics');

function canonicalJavaMarkers(name) {
  const source = fs.readFileSync(
    `${__dirname}/../src/main/java/com/kaces/pandora/ai/answer/ExplicitOracleTermMatcher.java`,
    'utf8',
  );
  return parseJavaListConstant(source, name);
}

function assertMarkerParity(name, source, target) {
  const missing = source.filter((marker) => !target.includes(marker));
  const extra = target.filter((marker) => !source.includes(marker));
  if (source.length !== target.length || missing.length > 0 || extra.length > 0) {
    throw new Error(
      `${name} marker parity mismatch: sourceCount=${source.length} targetCount=${target.length} `
      + `missing=${JSON.stringify(missing)} extra=${JSON.stringify(extra)}`,
    );
  }
}

test('matches one OR alias within an AND group', () => {
  const aliases = ['등록 요청을 받는 경우', '등록 요청이 있으면'];

  assert.equal(matchOracleGroup('등록 요청이 있으면 처리해야 합니다.', aliases), aliases[1]);
});

test('requires every material token in one text', () => {
  const alias = '개인정보 처리 목적 보유 기간';

  assert.equal(
    matchOracleGroup('개인정보는 처리 목적에 따라 보유 기간을 정합니다.', [alias]),
    alias,
  );
});

test('does not synthesize an alias from separate items or sentences', () => {
  const alias = '개인정보 처리 목적 보유 기간';
  const items = ['개인정보 처리 목적입니다.', '보유 기간을 정합니다.'];

  assert.equal(items.some((item) => matchOracleGroup(item, [alias]) !== null), false);
  assert.equal(matchOracleGroup(items.join(' '), [alias]), null);
});

test('does not match opposite positive and negative local polarity', () => {
  const positive = '개인정보를 처리할 수 있습니다';
  const negative = '개인정보를 처리할 수 없습니다';

  assert.equal(matchOracleGroup(negative, [positive]), null);
  assert.equal(matchOracleGroup(positive, [negative]), null);
});

test('normalizes Korean punctuation and spacing before matching', () => {
  const alias = '개인정보 처리 목적에 따른 보유 기간';

  assert.equal(
    matchOracleGroup('개인정보 처리목적에 따른, 보유기간입니다.', [alias]),
    alias,
  );
});

test('preserves dotted dates while splitting sentences like AnswerOracleMatcher', () => {
  const alias = '2025. 12. 17 ~ 2026. 10. 31';

  assert.equal(
    matchOracleGroup('IRM 측정기간은 2025. 12. 17 ~ 2026. 10. 31입니다.', [alias]),
    alias,
  );
});

test('does not match a positive alias before grammatical 안', () => {
  const alias = '공개장소에 자유롭게 설치';

  assert.equal(matchOracleGroup('공개장소에 자유롭게 설치해서는 안 됩니다.', [alias]), null);
});

test('does not match a positive alias through a conditional bridge before 안', () => {
  const alias = '공개장소에 자유롭게 설치';

  assert.equal(matchOracleGroup('공개장소에 자유롭게 설치하면 안 됩니다.', [alias]), null);
});

test('does not match a proposition superseded by a contrast final assertion', () => {
  const alias = '비대상';

  assert.equal(
    matchOracleGroup('비대상이라는 견해도 있지만 실제로는 과업심의 대상입니다.', [alias]),
    null,
  );
});

test('does not match a positive alias through a 할 경우 bridge before 안 됨', () => {
  const alias = '공개장소에 자유롭게 설치';

  assert.equal(matchOracleGroup('공개장소에 자유롭게 설치할 경우 안 됨.', [alias]), null);
});

test('does not match a positive alias through a 했을 경우 bridge before 안 됩니다', () => {
  const alias = '공개장소에 자유롭게 설치';

  assert.equal(matchOracleGroup('공개장소에 자유롭게 설치했을 경우 안 됩니다.', [alias]), null);
});

test('does not match a positive alias before direct 안 됨', () => {
  const alias = '공개장소에 자유롭게 설치';

  assert.equal(matchOracleGroup('공개장소에 자유롭게 설치 안 됨.', [alias]), null);
});

test('does not match a proposition superseded by 실제로 without 는', () => {
  const alias = '비대상';

  assert.equal(
    matchOracleGroup('비대상이라는 견해도 있지만 실제로 과업심의 대상입니다.', [alias]),
    null,
  );
});

test('does not match a proposition superseded by 오히려', () => {
  const alias = '비대상';

  assert.equal(
    matchOracleGroup('비대상이라는 견해도 있지만 오히려 과업심의 대상입니다.', [alias]),
    null,
  );
});

test('does not match through every Java grammatical-negation marker', () => {
  const alias = '공개장소에 자유롭게 설치';
  assert.equal(typeof parseJavaListConstant, 'function');

  for (const marker of canonicalJavaMarkers('GRAMMATICAL_NEGATIONS')) {
    assert.equal(matchOracleGroup(`${alias}${marker}.`, [alias]), null, marker);
  }
  assert.equal(matchOracleGroup(`${alias}아닙니다.`, [alias]), null, '아닙니다');
});

test('does not match through every Java local-polarity bridge', () => {
  const alias = '공개장소에 자유롭게 설치';
  assert.equal(typeof parseJavaListConstant, 'function');

  for (const bridge of canonicalJavaMarkers('LOCAL_POLARITY_BRIDGES')) {
    assert.equal(matchOracleGroup(`${alias}${bridge}안됨.`, [alias]), null, bridge);
  }
});

test('parses Java string escapes in marker constants', () => {
  assert.equal(typeof parseJavaListConstant, 'function');
  assert.deepEqual(
    parseJavaListConstant('private static final List<String> EXAMPLE = List.of("a\\n", "\\uAC00");', 'EXAMPLE'),
    ['a\n', '가'],
  );
});

test('reports explicit marker differences for a mismatched Node fixture', () => {
  assert.throws(
    () => assertMarkerParity('fixture', ['canonical'], ['node']),
    /fixture marker parity mismatch:.*missing=\["canonical"\].*extra=\["node"\]/,
  );
});

test('ports the complete canonical Java marker tables without set differences', () => {
  assert.equal(typeof parseJavaListConstant, 'function');
  assertMarkerParity(
    'GRAMMATICAL_NEGATIONS',
    canonicalJavaMarkers('GRAMMATICAL_NEGATIONS'),
    GRAMMATICAL_NEGATIONS,
  );
  assertMarkerParity(
    'LOCAL_POLARITY_BRIDGES',
    canonicalJavaMarkers('LOCAL_POLARITY_BRIDGES'),
    LOCAL_POLARITY_BRIDGES,
  );
});

test('measures proposition and condition group coverage at every evidence stage', () => {
  const evalCase = {
    id: 'coverage-stages',
    requiredPropositionGroups: [['must retain'], ['publish record', 'release record']],
    requiredConditionGroups: [['before approval']],
  };
  const retained = {
    target: 'law', chunkId: 1, title: 'must', snippet: 'retain',
  };
  const condition = {
    target: 'law', chunkId: 2, chunkTitle: 'before approval',
  };
  const released = {
    target: 'law', chunkId: 3, matchedChildText: 'release record',
  };
  const response = {
    vectorHits: [retained, condition],
    lexicalHits: [{ ...retained, snippet: 'different duplicate' }, released],
    merged: [retained, condition, released],
    reranked: [retained, released],
    intentFiltered: [retained, released],
    judgeCandidates: [retained, released],
    judged: [retained],
    selected: [retained],
  };

  const coverage = retrievalMetrics.measureRetrievalCase(evalCase, response, 10).evidenceCoverage;

  assert.deepEqual(coverage.stages, {
    candidateSources: { items: 3 },
    merged: { items: 3 },
    reranked: { items: 2 },
    intentFiltered: { items: 2 },
    judgeCandidates: { items: 2 },
    judged: { items: 1 },
    selected: { items: 1 },
  });
  assert.deepEqual(coverage.propositionGroups, [
    {
      id: 'proposition:1',
      aliases: ['must retain'],
      coverage: {
        candidateSources: true, merged: true, reranked: true, intentFiltered: true,
        judgeCandidates: true, judged: true, selected: true,
      },
      firstLossStage: 'survived',
    },
    {
      id: 'proposition:2',
      aliases: ['publish record', 'release record'],
      coverage: {
        candidateSources: true, merged: true, reranked: true, intentFiltered: true,
        judgeCandidates: true, judged: false, selected: false,
      },
      firstLossStage: 'judged',
    },
  ]);
  assert.deepEqual(coverage.conditionGroups, [
    {
      id: 'condition:1',
      aliases: ['before approval'],
      coverage: {
        candidateSources: true, merged: true, reranked: false, intentFiltered: false,
        judgeCandidates: false, judged: false, selected: false,
      },
      firstLossStage: 'reranked',
    },
  ]);
});

test('classifies groups missing from candidate sources and aggregates group summaries by type', () => {
  const entered = {
    id: 'entered',
    requiredPropositionGroups: [['retain record']],
    requiredConditionGroups: [['before approval']],
  };
  const enteredItem = { target: 'law', chunkId: 1, snippet: 'retain record before approval' };
  const enteredResponse = {
    vectorHits: [enteredItem], lexicalHits: [], merged: [enteredItem], reranked: [enteredItem],
    intentFiltered: [enteredItem], judgeCandidates: [enteredItem], judged: [enteredItem], selected: [enteredItem],
  };
  const absent = {
    id: 'absent',
    requiredPropositionGroups: [['publish record']],
    requiredConditionGroups: [],
  };
  const lateItem = { target: 'law', chunkId: 2, snippet: 'publish record' };
  const absentResponse = {
    vectorHits: [], lexicalHits: [], merged: [lateItem], reranked: [lateItem],
    intentFiltered: [lateItem], judgeCandidates: [lateItem], judged: [lateItem], selected: [lateItem],
  };

  const enteredMeasured = retrievalMetrics.measureRetrievalCase(entered, enteredResponse, 10);
  const absentMeasured = retrievalMetrics.measureRetrievalCase(absent, absentResponse, 10);
  const summary = retrievalMetrics.summarizeRetrievalCases([enteredMeasured, absentMeasured], 10);

  assert.equal(absentMeasured.evidenceCoverage.propositionGroups[0].firstLossStage, 'candidateSources');
  assert.deepEqual(summary.evidenceCoverage.proposition, {
    totalGroups: 2,
    stages: {
      candidateSources: { coveredGroups: 1, rate: 0.5 },
      merged: { coveredGroups: 2, rate: 1 },
      reranked: { coveredGroups: 2, rate: 1 },
      intentFiltered: { coveredGroups: 2, rate: 1 },
      judgeCandidates: { coveredGroups: 2, rate: 1 },
      judged: { coveredGroups: 2, rate: 1 },
      selected: { coveredGroups: 2, rate: 1 },
    },
    firstLossCounts: { survived: 1, candidateSources: 1 },
  });
  assert.deepEqual(summary.evidenceCoverage.condition, {
    totalGroups: 1,
    stages: {
      candidateSources: { coveredGroups: 1, rate: 1 },
      merged: { coveredGroups: 1, rate: 1 },
      reranked: { coveredGroups: 1, rate: 1 },
      intentFiltered: { coveredGroups: 1, rate: 1 },
      judgeCandidates: { coveredGroups: 1, rate: 1 },
      judged: { coveredGroups: 1, rate: 1 },
      selected: { coveredGroups: 1, rate: 1 },
    },
    firstLossCounts: { survived: 1 },
  });
});

test('measures supported evidence per sentence and the final verified answer independently', () => {
  assert.equal(typeof evidenceCoverage.extendEvidenceCoverage, 'function');
  const evalCase = {
    id: 'answer-stages',
    requiredPropositionGroups: [['direct rule'], ['split evidence']],
    requiredConditionGroups: [['when filed']],
  };
  const retrievalItem = {
    target: 'law',
    chunkId: 1,
    matchedChildText: 'direct rule and split evidence apply when filed',
  };
  const response = {
    vectorHits: [retrievalItem],
    lexicalHits: [],
    merged: [retrievalItem],
    reranked: [retrievalItem],
    intentFiltered: [retrievalItem],
    judgeCandidates: [retrievalItem],
    judged: [retrievalItem],
    selected: [retrievalItem],
  };
  const retrievalCoverage = evidenceCoverage.measureEvidenceCoverage(evalCase, response, 10);
  const answerResult = {
    claimEvidenceLinks: [
      { relation: 'SUPPORTED', evidenceSentence: 'The direct rule applies when filed.' },
      { relation: 'UNSUPPORTED', evidenceSentence: 'split evidence' },
      { relation: 'SUPPORTED', evidenceSentence: 'split' },
      { relation: 'SUPPORTED', evidenceSentence: 'evidence' },
    ],
    verifiedAnswer: 'The direct rule applies.',
  };

  const measured = evidenceCoverage.extendEvidenceCoverage(evalCase, retrievalCoverage, answerResult);

  assert.deepEqual(measured.stages.supportedEvidence, { status: 'measured', items: 3 });
  assert.deepEqual(measured.stages.verifiedAnswer, { status: 'measured', items: 1 });
  assert.equal(measured.propositionGroups[0].coverage.supportedEvidence, true);
  assert.equal(measured.propositionGroups[0].coverage.verifiedAnswer, true);
  assert.equal(measured.propositionGroups[0].firstLossStage, 'survived');
  assert.equal(measured.propositionGroups[1].coverage.supportedEvidence, false);
  assert.equal(measured.propositionGroups[1].coverage.verifiedAnswer, false);
  assert.equal(measured.propositionGroups[1].firstLossStage, 'supportedEvidence');
  assert.equal(measured.conditionGroups[0].coverage.supportedEvidence, true);
  assert.equal(measured.conditionGroups[0].coverage.verifiedAnswer, false);
  assert.equal(measured.conditionGroups[0].firstLossStage, 'verifiedAnswer');
  assert.deepEqual(measured.missingGroups.supportedEvidence, {
    proposition: ['proposition:2'],
    condition: [],
  });
  assert.deepEqual(measured.missingGroups.verifiedAnswer, {
    proposition: ['proposition:2'],
    condition: ['condition:1'],
  });
});

test('marks answer stages not measured without an answer evaluation and summarizes all nine stages', () => {
  assert.equal(typeof evidenceCoverage.summarizeEndToEndEvidenceCoverage, 'function');
  const evalCase = {
    id: 'retrieval-only',
    requiredPropositionGroups: [['direct rule']],
    requiredConditionGroups: [],
  };
  const retrievalItem = { target: 'law', chunkId: 1, matchedChildText: 'direct rule' };
  const response = {
    vectorHits: [retrievalItem],
    lexicalHits: [],
    merged: [retrievalItem],
    reranked: [retrievalItem],
    intentFiltered: [retrievalItem],
    judgeCandidates: [retrievalItem],
    judged: [retrievalItem],
    selected: [retrievalItem],
  };
  const retrievalCoverage = evidenceCoverage.measureEvidenceCoverage(evalCase, response, 10);

  const notMeasured = evidenceCoverage.extendEvidenceCoverage(evalCase, retrievalCoverage);

  assert.deepEqual(notMeasured.stages.supportedEvidence, { status: 'not_measured', items: null });
  assert.deepEqual(notMeasured.stages.verifiedAnswer, { status: 'not_measured', items: null });
  assert.equal(notMeasured.propositionGroups[0].coverage.supportedEvidence, 'not_measured');
  assert.equal(notMeasured.propositionGroups[0].coverage.verifiedAnswer, 'not_measured');
  assert.equal(notMeasured.propositionGroups[0].firstLossStage, 'not_measured');

  const measured = evidenceCoverage.extendEvidenceCoverage(evalCase, retrievalCoverage, {
    claimEvidenceLinks: [{ relation: 'SUPPORTED', evidenceSentence: 'direct rule' }],
    verifiedAnswer: 'direct rule',
  });
  const summary = evidenceCoverage.summarizeEndToEndEvidenceCoverage([
    { endToEndEvidenceCoverage: measured },
  ]);
  assert.deepEqual(summary.proposition.stages.supportedEvidence, {
    status: 'measured',
    coveredGroups: 1,
    rate: 1,
  });
  assert.deepEqual(summary.proposition.stages.verifiedAnswer, {
    status: 'measured',
    coveredGroups: 1,
    rate: 1,
  });
  assert.deepEqual(summary.proposition.firstLossCounts, { survived: 1 });
});
