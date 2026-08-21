const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const { loadEvalCases } = require('./lib/rag-eval-cases');
const {
  loadTrainingManifest,
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
