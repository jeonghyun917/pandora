const assert = require('node:assert/strict');
const test = require('node:test');

let selection = {};
try {
  selection = require('./lib/document-expansion-selection');
} catch {
  // The first RED run intentionally exercises the not-yet-created selector.
}

test('document expansion summary keeps control and expansion recall separately', () => {
  assert.equal(typeof selection.summarizeDocumentExpansionRun, 'function');
  const summary = selection.summarizeDocumentExpansionRun(runWithMetrics([
    metric('case-a', [0], [0, 1]),
    metric('case-b', [], [0]),
  ]));

  assert.deepEqual(summary.control, {
    caseCount: 2,
    allRequired: 0,
    anyRequired: 1,
    matchedGroups: 1,
    passedCaseIds: [],
  });
  assert.deepEqual(summary.shadowFused, {
    caseCount: 2,
    allRequired: 1,
    anyRequired: 2,
    matchedGroups: 3,
    passedCaseIds: ['case-a'],
  });
});

test('document expansion selector allows only repeated bounded gains without baseline loss', () => {
  assert.equal(typeof selection.selectDocumentExpansionPolicy, 'function');
  const manifest = { expectedTrainingCount: 24, trainingCaseIds: caseIds(24), manifestHash: 'manifest-a' };
  const policies = [{ id: 'bounded-v1', configHash: 'policy-a' }];
  const run1 = completeRun(manifest, 'bounded-v1', controlMetrics(7, 14, 23), expansionMetrics(8, 14, 25));
  const run2 = structuredClone(run1);

  const result = selection.selectDocumentExpansionPolicy({ manifest, run1, run2, policies });

  assert.equal(result.status, 'ELIGIBLE_FOR_DIFFICULT_EVAL');
  assert.equal(result.policy.id, 'bounded-v1');

  const regression = structuredClone(run2);
  regression.results[0].documentExpansion.shadowFusedPresence.allRequired = false;
  regression.results[0].documentExpansion.shadowFusedPresence.matchedRequiredGroupIndexes = [0];
  assert.equal(
    selection.selectDocumentExpansionPolicy({ manifest, run1, run2: regression, policies }).status,
    'BASELINE_REGRESSION',
  );

  const mismatch = structuredClone(run2);
  mismatch.provenance.runtimeConfigSha256 = 'config-b';
  assert.equal(
    selection.selectDocumentExpansionPolicy({ manifest, run1, run2: mismatch, policies }).status,
    'PROVENANCE_MISMATCH',
  );

  const missingFenceRun1 = structuredClone(run1);
  const missingFenceRun2 = structuredClone(run2);
  delete missingFenceRun1.provenance.lexicalRevision;
  delete missingFenceRun2.provenance.lexicalRevision;
  assert.equal(
    selection.selectDocumentExpansionPolicy({
      manifest, run1: missingFenceRun1, run2: missingFenceRun2, policies,
    }).status,
    'PROVENANCE_MISMATCH',
  );

  const missingErrors = structuredClone(run1);
  delete missingErrors.requestErrors;
  assert.equal(
    selection.selectDocumentExpansionPolicy({ manifest, run1: missingErrors, run2, policies }).status,
    'REQUEST_ERRORS',
  );

  const noGain = completeRun(manifest, 'bounded-v1', controlMetrics(7, 14, 23), expansionMetrics(7, 14, 23));
  assert.equal(
    selection.selectDocumentExpansionPolicy({ manifest, run1: noGain, run2: structuredClone(noGain), policies }).status,
    'NO_DOCUMENT_EXPANSION_IMPROVEMENT',
  );

  const changedControl = completeRun(manifest, 'bounded-v1', controlMetrics(8, 14, 29), expansionMetrics(9, 14, 30));
  assert.equal(
    selection.selectDocumentExpansionPolicy({ manifest, run1: changedControl, run2: structuredClone(changedControl), policies }).status,
    'BASELINE_REGRESSION',
  );

  const lowGroups = completeRun(manifest, 'bounded-v1', controlMetrics(7, 14, 23), expansionMetrics(8, 13, 22));
  assert.equal(
    selection.selectDocumentExpansionPolicy({ manifest, run1: lowGroups, run2: structuredClone(lowGroups), policies }).status,
    'DOCUMENT_EXPANSION_QUALITY_REGRESSION',
  );
});

test('document expansion selector CLI requires immutable evidence paths', () => {
  let cli = {};
  try {
    cli = require('./document-expansion-select');
  } catch {
    // The first RED run intentionally exercises the not-yet-created CLI.
  }
  assert.equal(typeof cli.parseCliOptions, 'function');
  assert.deepEqual(cli.parseCliOptions([
    '--manifest', 'manifest.json',
    '--run-1', 'run1.json',
    '--run-2', 'run2.json',
    '--policies', 'policies.json',
    '--output', 'selection.json',
  ]), {
    manifestPath: 'manifest.json',
    run1Path: 'run1.json',
    run2Path: 'run2.json',
    policiesPath: 'policies.json',
    outputPath: 'selection.json',
  });
  assert.throws(() => cli.parseCliOptions(['--manifest', 'x']), /--run-1 is required/i);
});

function metric(id, controlIndexes, fusedIndexes, requiredGroupCount = 2) {
  return {
    id,
    documentExpansion: {
      control: { matchedRequiredGroupIndexes: controlIndexes, requiredGroupCount },
      expansionSourcePresence: { matchedRequiredGroupIndexes: fusedIndexes, requiredGroupCount },
      shadowFusedPresence: { matchedRequiredGroupIndexes: fusedIndexes, requiredGroupCount },
    },
  };
}

function caseIds(count) {
  return Array.from({ length: count }, (_, index) => `case-${index + 1}`);
}

function controlMetrics(allRequired, anyRequired, matchedGroups) {
  return metrics(allRequired, anyRequired, matchedGroups, false);
}

function expansionMetrics(allRequired, anyRequired, matchedGroups) {
  return metrics(allRequired, anyRequired, matchedGroups, true);
}

function metrics(allRequired, anyRequired, matchedGroups, expanded) {
  return { allRequired, anyRequired, matchedGroups, expanded };
}

function completeRun(manifest, policyId, control, expansion) {
  const ids = manifest.trainingCaseIds;
  const groupCounts = ids.map((_, index) => index < 7 ? 2 : 3);
  const results = ids.map((id, index) => {
    const controlIndexes = indexesFor(index, control, groupCounts);
    const expansionIndexes = indexesFor(index, expansion, groupCounts);
    return metric(id, controlIndexes, expansionIndexes, groupCounts[index]);
  });
  return {
    complete: true,
    selectedCases: ids.length,
    completedCases: ids.length,
    requestErrors: [],
    documentExpansionPolicy: { id: policyId, configHash: 'policy-a' },
    provenance: provenance(manifest),
    results,
  };
}

function indexesFor(index, metrics, groupCounts) {
  const groupCount = groupCounts[index];
  if (index < metrics.allRequired) return Array.from({ length: groupCount }, (_, value) => value);
  if (index < metrics.anyRequired) {
    const minimum = groupCounts.slice(0, metrics.allRequired).reduce((sum, value) => sum + value, 0)
      + (metrics.anyRequired - metrics.allRequired);
    const priorCapacity = groupCounts.slice(metrics.allRequired, index).reduce((sum, value) => sum + value - 2, 0);
    const extra = Math.max(0, Math.min(groupCount - 2, metrics.matchedGroups - minimum - priorCapacity));
    return Array.from({ length: 1 + extra }, (_, value) => value);
  }
  return [];
}

function provenance(manifest) {
  return {
    trainingManifestHash: manifest.manifestHash,
    trainingSplitName: 'training',
    datasetHash: 'dataset-a',
    selectionHash: 'selection-a',
    runtimeInstanceId: 'runtime-a',
    runtimeArtifactSha256: 'artifact-a',
    runtimeConfigSha256: 'config-a',
    indexRevision: 'index-a',
    lexicalRevision: 'lexical-a',
    qdrantReady: true,
    qdrantSearchFailureCount: 0,
  };
}

function runWithMetrics(results) {
  return { results };
}
