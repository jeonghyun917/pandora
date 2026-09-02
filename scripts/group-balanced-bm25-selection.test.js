const test = require('node:test');
const assert = require('node:assert/strict');

const { selectGroupBalancedBm25Policy } = require('./lib/group-balanced-bm25-selection');
const selectorCli = require('./group-balanced-bm25-select');

test('selector CLI requires immutable manifest, two runs, and a new output path', () => {
  assert.deepEqual(selectorCli.parseCliOptions([
    '--manifest', 'manifest.json', '--run-1', 'run1.json', '--run-2', 'run2.json', '--output', 'selection.json',
  ]), {
    manifestPath: 'manifest.json', run1Path: 'run1.json', run2Path: 'run2.json', outputPath: 'selection.json',
  });
  assert.throws(() => selectorCli.parseCliOptions([]), /--manifest.*required/i);
});

test('selects deterministic candidate that adds a missing required group without loss', () => {
  const manifest = trainingManifest();
  const run1 = capture(manifest.trainingCaseIds, { improveCase: 'case-8' });
  const run2 = structuredClone(run1);

  const selection = selectGroupBalancedBm25Policy({ manifest, run1, run2 });

  assert.equal(selection.status, 'SELECTED');
  assert.equal(selection.eligible, true);
  assert.deepEqual(selection.addedGroups, [{ id: 'case-8', groupIndexes: [1] }]);
  assert.equal(selection.summaries.run1.shadow.allRequired, selection.summaries.run1.control.allRequired + 1);
});

test('returns no improvement when shadow discovers no new required group', () => {
  const manifest = trainingManifest();
  const run1 = capture(manifest.trainingCaseIds);
  const selection = selectGroupBalancedBm25Policy({ manifest, run1, run2: structuredClone(run1) });

  assert.equal(selection.status, 'NO_IMPROVEMENT');
  assert.equal(selection.eligible, false);
});

test('rejects any lost control group or nondeterministic second capture', () => {
  const manifest = trainingManifest();
  const run1 = capture(manifest.trainingCaseIds, { improveCase: 'case-8' });
  const regression = structuredClone(run1);
  regression.results[0].bm25Variant.shadowSourcePresence.matchedRequiredGroupIndexes = [];
  regression.results[0].bm25Variant.shadowSourcePresence.matchedRequiredGroupCount = 0;

  assert.equal(
    selectGroupBalancedBm25Policy({ manifest, run1: regression, run2: structuredClone(regression) }).status,
    'CONTROL_GROUP_REGRESSION',
  );

  const nondeterministic = structuredClone(run1);
  nondeterministic.results[7].bm25Variant.capture.hits[0].candidateKey = 'law:999';
  assert.equal(
    selectGroupBalancedBm25Policy({ manifest, run1, run2: nondeterministic }).status,
    'NONDETERMINISTIC_CAPTURE',
  );
});

test('rejects incomplete requests, qdrant failures, and provenance drift', () => {
  const manifest = trainingManifest();
  const run1 = capture(manifest.trainingCaseIds, { improveCase: 'case-8' });

  assert.equal(selectGroupBalancedBm25Policy({
    manifest,
    run1: { ...run1, complete: false },
    run2: structuredClone(run1),
  }).status, 'REQUEST_ERRORS');

  const qdrant = structuredClone(run1);
  qdrant.provenance.qdrantSearchFailureCount = 1;
  assert.equal(selectGroupBalancedBm25Policy({ manifest, run1: qdrant, run2: qdrant }).status, 'QDRANT_FAILURES');

  const drift = structuredClone(run1);
  drift.provenance.indexRevision = 'revision-b';
  assert.equal(selectGroupBalancedBm25Policy({ manifest, run1, run2: drift }).status, 'PROVENANCE_MISMATCH');
});

function trainingManifest() {
  return {
    schemaVersion: 1,
    expectedTrainingCount: 24,
    manifestHash: 'manifest-hash',
    trainingCaseIds: Array.from({ length: 24 }, (_, index) => `case-${index + 1}`),
  };
}

function capture(ids, options = {}) {
  const provenance = {
    trainingManifestHash: 'manifest-hash',
    trainingSplitName: 'training-v1',
    datasetHash: 'dataset-hash',
    selectionHash: 'selection-hash',
    runtimeInstanceId: 'runtime-a',
    runtimeArtifactSha256: 'artifact-hash',
    runtimeConfigSha256: 'config-hash',
    indexRevision: 'revision-a',
    lexicalRevision: 'lexical-a',
    qdrantReady: true,
    qdrantSearchFailureCount: 0,
  };
  return {
    complete: true,
    selectedCases: 24,
    completedCases: 24,
    requestErrors: [],
    provenance,
    bm25VariantPolicy: { id: 'group-balanced-bm25-shadow-v1', configHash: 'config-hash' },
    results: ids.map((id, index) => result(id, index, options.improveCase === id)),
  };
}

function result(id, index, improves) {
  const controlIndexes = index < 7 ? [0, 1] : (index < 14 ? [0] : []);
  const shadowIndexes = improves ? [0, 1] : controlIndexes;
  const presence = (indexes) => ({
    requiredGroupCount: 2,
    matchedRequiredGroupIndexes: indexes,
    matchedRequiredGroupCount: indexes.length,
    anyRequired: indexes.length > 0,
    allRequired: indexes.length === 2,
  });
  return {
    id,
    bm25Variant: {
      status: 'APPLIED',
      reasonCodes: [],
      variantHashes: ['aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'],
      controlSourcePresence: presence(controlIndexes),
      variantPresence: presence(improves ? [1] : []),
      shadowSourcePresence: presence(shadowIndexes),
      capture: {
        status: 'APPLIED',
        reasonCodes: [],
        variantHashes: ['aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'],
        hits: [{
          candidateKey: `law:${index + 1}`,
          documentId: index + 101,
          rank: 1,
          variantRanks: { 'direct-evidence': 1 },
          matchedAuditGroupIndexes: improves ? [1] : [],
        }],
      },
    },
  };
}
