const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const { loadEvalCases } = require('./lib/rag-eval-cases');
const {
  fuseRanks,
  loadEvaluationManifest,
  loadTrainingManifest,
  measureFused,
  selectWeights,
  selectionEligibility,
  sha256Bytes,
} = require('./lib/rrf-weight-selection');

const CASE_PATHS = [
  path.resolve('src/main/resources/rag-evaluation-cases.tsv'),
  path.resolve('src/main/resources/rag-evaluation-cases.generated.tsv'),
];
const ORACLE_PATH = path.resolve('src/main/resources/rag-answer-evaluation-oracles.tsv');
const MANIFEST_PATH = path.resolve('src/main/resources/rag-retrieval-training-manifest.json');

test('sha256 uses the exact manifest bytes', () => {
  assert.equal(
    sha256Bytes(Buffer.from('abc', 'utf8')),
    'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
  );
});

test('bundled training manifest resolves exactly 24 explicit-oracle cases and a disjoint holdout', () => {
  const allCases = loadEvalCases(CASE_PATHS, { answerOraclePath: ORACLE_PATH });

  const result = loadTrainingManifest(MANIFEST_PATH, allCases);

  assert.equal(result.trainingCases.length, 24);
  assert.deepEqual(
    result.trainingCases.slice(0, 3).map((item) => item.id),
    ['project-review-target', 'project-review-simple-software', 'project-review-hardware-exclusion'],
  );
  assert.equal(result.manifestHash.length, 64);
  assert.equal(result.holdoutCases.length > 0, true);
  const trainingIds = new Set(result.trainingCases.map((item) => item.id));
  const difficultIds = new Set(result.manifest.excludedDifficultCaseIds);
  assert.equal(result.holdoutCases.some((item) => trainingIds.has(item.id)), false);
  assert.equal(result.holdoutCases.some((item) => difficultIds.has(item.id)), false);
  assert.equal(result.trainingCases.every(hasExplicitOracle), true);
  assert.equal(result.holdoutCases.every(hasExplicitOracle), true);
});

test('training manifest rejects duplicates and difficult-set overlap', () => {
  const allCases = fixtureCases(['train-a', 'train-b', 'difficult-a']);
  const duplicate = manifest(['train-a', 'train-a'], ['difficult-a'], 2);
  const overlap = manifest(['train-a', 'difficult-a'], ['difficult-a'], 2);

  assert.throws(
    () => withManifest(duplicate, (file) => loadTrainingManifest(file, allCases)),
    /duplicate training case id: train-a/i,
  );
  assert.throws(
    () => withManifest(overlap, (file) => loadTrainingManifest(file, allCases)),
    /training case is excluded as difficult: difficult-a/i,
  );
});

test('training manifest rejects count mismatch, unknown cases, and cases without explicit oracle', () => {
  const allCases = [
    ...fixtureCases(['train-a']),
    { id: 'no-oracle', requiredPropositionGroups: [], requiredConditionGroups: [] },
  ];

  assert.throws(
    () => withManifest(manifest(['train-a'], [], 2), (file) => loadTrainingManifest(file, allCases)),
    /training case count mismatch: 1\/2/i,
  );
  assert.throws(
    () => withManifest(manifest(['missing'], [], 1), (file) => loadTrainingManifest(file, allCases)),
    /unknown training case id: missing/i,
  );
  assert.throws(
    () => withManifest(manifest(['no-oracle'], [], 1), (file) => loadTrainingManifest(file, allCases)),
    /training case lacks explicit oracle: no-oracle/i,
  );
});

test('retrieval evaluation manifest accepts known cases without an explicit answer oracle', () => {
  const allCases = [
    ...fixtureCases(['oracle-case']),
    { id: 'retrieval-only', requiredPropositionGroups: [], requiredConditionGroups: [] },
  ];
  const value = manifest(['retrieval-only', 'oracle-case'], [], 2);

  const result = withManifest(value, (file) => loadEvaluationManifest(file, allCases));

  assert.deepEqual(result.trainingCases.map((item) => item.id), ['retrieval-only', 'oracle-case']);
  assert.equal(result.manifestHash.length, 64);
});

test('pure RRF replay uses hand-derived scores and merges audit groups across sources', () => {
  const ranking = fuseRanks({
    vector: [
      ranked('law:20', 1, [0]),
      ranked('official_doc:3', 2, []),
      ranked('law:10', 3, []),
    ],
    bm25: [
      ranked('official_doc:3', 1, [1]),
      ranked('law:10', 2, []),
      ranked('law:20', 3, [0]),
    ],
  }, { vectorWeight: 1, lexicalWeight: 1 }, 60);

  assert.deepEqual(ranking.map((item) => item.candidateKey), [
    'official_doc:3',
    'law:20',
    'law:10',
  ]);
  assert.equal(Math.abs(ranking[0].score - ((1 / 62) + (1 / 61))) < 1e-12, true);
  assert.deepEqual(ranking[0].matchedAuditGroupIndexes, [1]);
  assert.deepEqual(ranking[1].matchedAuditGroupIndexes, [0]);
});

test('pure RRF replay breaks exact ties by target and numeric chunk ID', () => {
  const ranking = fuseRanks({
    vector: [ranked('law:10', 1, [])],
    bm25: [ranked('law:2', 1, []), ranked('official_doc:1', 2, [])],
  }, { vectorWeight: 1, lexicalWeight: 1 }, 60);

  assert.deepEqual(ranking.slice(0, 2).map((item) => item.candidateKey), ['law:2', 'law:10']);
});

test('fused measurement aggregates distinct required groups only inside top K', () => {
  const result = measureFused([
    { candidateKey: 'law:1', matchedAuditGroupIndexes: [0, 0] },
    { candidateKey: 'law:2', matchedAuditGroupIndexes: [1] },
    { candidateKey: 'law:3', matchedAuditGroupIndexes: [2] },
  ], 3, 2);

  assert.deepEqual(result, {
    matchedGroupIndexes: [0, 1],
    matchedGroupCount: 2,
    requiredGroupCount: 3,
    anyRequiredPresent: true,
    allRequiredPresent: false,
  });
});

test('selection guardrails reject baseline case loss and any-required regression', () => {
  const baseline = {
    allRequiredCount: 2,
    anyRequiredCount: 3,
    passedCaseIds: ['case-a', 'case-b'],
  };

  assert.deepEqual(selectionEligibility({
    allRequiredCount: 3,
    anyRequiredCount: 3,
    passedCaseIds: ['case-a', 'case-c', 'case-d'],
  }, baseline), {
    eligible: false,
    reasons: ['BASELINE_CASE_REGRESSION:case-b'],
  });
  assert.deepEqual(selectionEligibility({
    allRequiredCount: 3,
    anyRequiredCount: 2,
    passedCaseIds: ['case-a', 'case-b', 'case-c'],
  }, baseline), {
    eligible: false,
    reasons: ['ANY_REQUIRED_REGRESSION:2/3'],
  });
});

test('weight selection chooses the nearest improving pair without baseline regression', () => {
  const manifestInfo = selectionManifestInfo();
  const run1 = selectionRun(improvementSnapshots());
  const run2 = structuredClone(run1);

  const result = selectWeights({ manifestInfo, run1, run2, topK: 2, rrfK: 60 });

  assert.equal(result.status, 'RECOMMENDED');
  assert.deepEqual(result.baseline.weights, { vectorWeight: 1, lexicalWeight: 1 });
  assert.deepEqual(result.recommendation.weights, { vectorWeight: 0.75, lexicalWeight: 1 });
  assert.equal(result.baseline.metrics.allRequiredCount, 1);
  assert.equal(result.recommendation.metrics.allRequiredCount, 2);
  assert.deepEqual(result.recommendation.metrics.passedCaseIds, ['case-a', 'case-b']);
});

test('weight selection preserves baseline when no grid pair improves training', () => {
  const manifestInfo = selectionManifestInfo();
  const snapshots = {
    'case-a': singleCompleteSnapshot('law:1'),
    'case-b': singleCompleteSnapshot('law:2'),
  };
  const run1 = selectionRun(snapshots);

  const result = selectWeights({
    manifestInfo,
    run1,
    run2: structuredClone(run1),
    topK: 2,
    rrfK: 60,
  });

  assert.equal(result.status, 'NO_TRAINING_IMPROVEMENT');
  assert.deepEqual(result.recommendation.weights, { vectorWeight: 1, lexicalWeight: 1 });
});

test('weight selection accepts bounded rank drift when both runs select the same guarded weights', () => {
  const manifestInfo = selectionManifestInfo();
  const run1 = selectionRun(improvementSnapshots());
  const run2 = structuredClone(run1);
  run2.results[1].sourceRankSnapshot.vector.push(ranked('law:99', 2, []));

  const result = selectWeights({ manifestInfo, run1, run2, topK: 2, rrfK: 60 });

  assert.equal(result.status, 'RECOMMENDED');
  assert.deepEqual(result.recommendation.weights, { vectorWeight: 0.75, lexicalWeight: 1 });
  assert.equal(result.rankSnapshotsIdentical, false);
});

test('weight selection preserves baseline when rank drift produces different guarded winners', () => {
  const manifestInfo = selectionManifestInfo();
  const run1 = selectionRun(improvementSnapshots());
  const inverted = improvementSnapshots();
  [inverted['case-a'].vector, inverted['case-a'].bm25] = [
    inverted['case-a'].bm25,
    inverted['case-a'].vector,
  ];
  const run2 = selectionRun(inverted);

  const result = selectWeights({ manifestInfo, run1, run2, topK: 2, rrfK: 60 });

  assert.equal(result.status, 'NO_STABLE_TRAINING_IMPROVEMENT');
  assert.deepEqual(result.recommendation.weights, { vectorWeight: 1, lexicalWeight: 1 });
});

test('weight selection fails closed on provenance or order drift', () => {
  const manifestInfo = selectionManifestInfo();
  const run1 = selectionRun(improvementSnapshots());
  const provenanceDrift = structuredClone(run1);
  provenanceDrift.provenance.runtimeConfigSha256 = 'config-b';
  assert.throws(
    () => selectWeights({ manifestInfo, run1, run2: provenanceDrift, topK: 2, rrfK: 60 }),
    /training provenance mismatch: runtimeConfigSha256/i,
  );

  const reordered = structuredClone(run1);
  reordered.results.reverse();
  assert.throws(
    () => selectWeights({ manifestInfo, run1, run2: reordered, topK: 2, rrfK: 60 }),
    /training result order does not match manifest/i,
  );

});

test('weight selection rejects captures with missing provenance fences', () => {
  const manifestInfo = selectionManifestInfo();
  const run1 = selectionRun(improvementSnapshots());
  const run2 = structuredClone(run1);
  delete run1.provenance.lexicalRevision;
  delete run2.provenance.lexicalRevision;

  assert.throws(
    () => selectWeights({ manifestInfo, run1, run2, topK: 2, rrfK: 60 }),
    /missing training provenance: lexicalRevision/i,
  );
});

test('selector CLI parses exact required paths and rejects unknown options', () => {
  const selectorCli = require('./rrf-weight-select');
  assert.deepEqual(selectorCli.parseCliOptions([
    '--manifest', 'manifest.json',
    '--run-1', 'run1.json',
    '--run-2', 'run2.json',
    '--output', 'selection.json',
  ]), {
    manifestPath: 'manifest.json',
    run1Path: 'run1.json',
    run2Path: 'run2.json',
    outputPath: 'selection.json',
  });
  assert.throws(() => selectorCli.parseCliOptions(['--unknown', 'x']), /unknown option: --unknown/i);
  assert.throws(() => selectorCli.parseCliOptions(['--manifest', 'x']), /--run-1 is required/i);
});

test('selector output writer is atomic, idempotent for exact bytes, and rejects different existing evidence', () => {
  const selectorCli = require('./rrf-weight-select');
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pandora-rrf-output-'));
  const file = path.join(directory, 'selection.json');
  try {
    selectorCli.writeJsonAtomic(file, { status: 'RECOMMENDED', value: 1 });
    const first = fs.readFileSync(file, 'utf8');
    assert.equal(first, '{\n  "status": "RECOMMENDED",\n  "value": 1\n}\n');
    selectorCli.writeJsonAtomic(file, { status: 'RECOMMENDED', value: 1 });
    assert.equal(fs.readFileSync(file, 'utf8'), first);
    assert.throws(
      () => selectorCli.writeJsonAtomic(file, { status: 'RECOMMENDED', value: 2 }),
      /refusing to overwrite different selection evidence/i,
    );
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

function hasExplicitOracle(item) {
  return (item.requiredPropositionGroups?.length ?? 0)
    + (item.requiredConditionGroups?.length ?? 0) > 0;
}

function fixtureCases(ids) {
  return ids.map((id) => ({
    id,
    requiredPropositionGroups: [[`${id}-proposition`]],
    requiredConditionGroups: [],
  }));
}

function ranked(candidateKey, rank, matchedAuditGroupIndexes) {
  return { candidateKey, rank, matchedAuditGroupIndexes };
}

function selectionManifestInfo() {
  return {
    manifestHash: 'manifest-hash',
    manifest: {
      splitName: 'fixture-training',
      trainingCaseIds: ['case-a', 'case-b'],
    },
    trainingCases: [
      { id: 'case-a', requiredPropositionGroups: [['a0'], ['a1']], requiredConditionGroups: [] },
      { id: 'case-b', requiredPropositionGroups: [['b0'], ['b1']], requiredConditionGroups: [] },
    ],
  };
}

function improvementSnapshots() {
  return {
    'case-a': {
      vector: [ranked('law:1', 1, [0]), ranked('official_doc:2', 2, [0])],
      bm25: [ranked('law:3', 1, [1]), ranked('official_doc:2', 2, [0])],
    },
    'case-b': singleCompleteSnapshot('law:4'),
  };
}

function singleCompleteSnapshot(candidateKey) {
  return {
    vector: [ranked(candidateKey, 1, [0, 1])],
    bm25: [ranked(candidateKey, 1, [0, 1])],
  };
}

function selectionRun(snapshots, provenanceOverrides = {}) {
  const ids = ['case-a', 'case-b'];
  return {
    complete: true,
    selectedCases: 2,
    completedCases: 2,
    requestErrors: [],
    provenance: {
      trainingManifestHash: 'manifest-hash',
      trainingSplitName: 'fixture-training',
      datasetHash: 'dataset-a',
      selectionHash: 'selection-a',
      runtimeInstanceId: 'runtime-a',
      runtimeArtifactSha256: 'jar-a',
      runtimeConfigSha256: 'config-a',
      indexRevision: 'index-a',
      lexicalRevision: 'lexical-a',
      qdrantReady: true,
      qdrantSearchFailureCount: 0,
      ...provenanceOverrides,
    },
    results: ids.map((id) => ({
      id,
      oraclePresence: { totalGroupCount: 2 },
      sourceRankSnapshot: snapshots[id],
    })),
  };
}

function manifest(trainingCaseIds, excludedDifficultCaseIds, expectedTrainingCount) {
  return {
    schemaVersion: 1,
    splitName: 'fixture',
    expectedTrainingCount,
    selectionBasis: 'fixture metadata',
    trainingCaseIds,
    excludedDifficultCaseIds,
  };
}

function withManifest(value, callback) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pandora-rrf-manifest-'));
  const file = path.join(directory, 'manifest.json');
  try {
    fs.writeFileSync(file, JSON.stringify(value), 'utf8');
    return callback(file);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
}
