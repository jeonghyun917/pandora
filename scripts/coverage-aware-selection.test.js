const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const {
  COVERAGE_POLICY_GRID,
  coverageEligibility,
  rerankCoverage,
  selectCoveragePolicy,
  validateDocumentIdentitySnapshot,
} = require('./lib/coverage-aware-selection');

test('declares the fixed coverage policy grid in review order', () => {
  assert.deepEqual(COVERAGE_POLICY_GRID, [
    { enabled: false, maxRescues: 0, maxRescuesPerDocument: 1, sourceRankLimit: 30 },
    { enabled: true, maxRescues: 1, maxRescuesPerDocument: 1, sourceRankLimit: 20 },
    { enabled: true, maxRescues: 1, maxRescuesPerDocument: 1, sourceRankLimit: 30 },
    { enabled: true, maxRescues: 2, maxRescuesPerDocument: 1, sourceRankLimit: 20 },
    { enabled: true, maxRescues: 2, maxRescuesPerDocument: 1, sourceRankLimit: 30 },
  ]);
});

test('mirrors the bounded Java sibling rescue order', () => {
  const fixture = rankingFixture();

  const result = rerankCoverage({
    ranking: fixture.ranking,
    documentIdByCandidate: fixture.documentIds,
    policy: COVERAGE_POLICY_GRID[2],
    topK: 30,
  });

  assert.equal(result.status, 'APPLIED');
  assert.equal(result.ranking.length, 30);
  assert.equal(result.ranking.some((item) => item.candidateKey === 'law:31'), true);
  assert.equal(result.ranking.some((item) => item.candidateKey === 'law:30'), false);
  assert.equal(new Set(result.ranking.map((item) => item.candidateKey)).size, 30);
  assert.deepEqual(result.rescues, [{
    candidateKey: 'law:31',
    documentKey: 'law:100',
    anchorCandidateKey: 'law:1',
    baselineRank: 31,
    rescuedRank: 30,
    reason: 'DOCUMENT_SIBLING_RESCUE',
  }]);
});

test('source rank 28 is ineligible under the 20 boundary', () => {
  const fixture = rankingFixture();

  const result = rerankCoverage({
    ranking: fixture.ranking,
    documentIdByCandidate: fixture.documentIds,
    policy: COVERAGE_POLICY_GRID[1],
    topK: 30,
  });

  assert.equal(result.status, 'NO_ELIGIBLE_SIBLING');
  assert.deepEqual(result.ranking, fixture.ranking);
});

test('does not cross target boundaries for the same numeric document id', () => {
  const fixture = rankingFixture();
  fixture.ranking[30] = ranked('admrul', 31, 28, null);
  delete fixture.documentIds['law:31'];
  fixture.documentIds['admrul:31'] = 100;

  const result = rerankCoverage({
    ranking: fixture.ranking,
    documentIdByCandidate: fixture.documentIds,
    policy: COVERAGE_POLICY_GRID[2],
    topK: 30,
  });

  assert.equal(result.status, 'NO_ELIGIBLE_SIBLING');
});

test('applies at most one rescue per document', () => {
  const fixture = rankingFixture();
  fixture.ranking[31] = ranked('law', 32, 29, null);
  fixture.documentIds['law:32'] = 100;

  const result = rerankCoverage({
    ranking: fixture.ranking,
    documentIdByCandidate: fixture.documentIds,
    policy: COVERAGE_POLICY_GRID[4],
    topK: 30,
  });

  assert.deepEqual(result.rescues.map((item) => item.candidateKey), ['law:31']);
});

test('document identity validation rejects missing invalid and conflicting ids', () => {
  assert.throws(
    () => validateDocumentIdentitySnapshot({
      vector: [{ candidateKey: 'law:1', documentId: 0, rank: 1 }],
      bm25: [],
    }),
    /invalid document id.*law:1/i,
  );
  assert.throws(
    () => validateDocumentIdentitySnapshot({
      vector: [{ candidateKey: 'law:1', documentId: 10, rank: 1 }],
      bm25: [{ candidateKey: 'law:1', documentId: 11, rank: 1 }],
    }),
    /conflicting document id.*law:1/i,
  );
});

test('selects the same guarded coverage policy across two independent runs', () => {
  const manifestInfo = selectorManifestInfo();
  const run1 = selectorRun(improvingSelectorSnapshots());
  const run2 = structuredClone(run1);

  const selection = selectCoveragePolicy({ manifestInfo, run1, run2, topK: 2, rrfK: 60 });

  const expectedPolicy = COVERAGE_POLICY_GRID[1];
  assert.equal(selection.schemaVersion, 1);
  assert.equal(selection.status, 'RECOMMENDED');
  assert.deepEqual(selection.winnersByRun.run1.policy, expectedPolicy);
  assert.deepEqual(selection.winnersByRun.run2.policy, expectedPolicy);
  assert.deepEqual(selection.recommendation.policy, expectedPolicy);
  assert.equal(selection.baseline.metrics.allRequiredCount, 1);
  assert.equal(selection.recommendation.metrics.allRequiredCount, 2);
});

test('falls back when only one run improves or independent winners diverge', () => {
  const manifestInfo = selectorManifestInfo();
  const improving = selectorRun(improvingSelectorSnapshots());
  const baselineOnly = selectorRun(baselineOnlySelectorSnapshots());

  const oneRun = selectCoveragePolicy({
    manifestInfo,
    run1: improving,
    run2: baselineOnly,
    topK: 2,
    rrfK: 60,
  });
  assert.equal(oneRun.status, 'NO_STABLE_COVERAGE_IMPROVEMENT');
  assert.equal(oneRun.recommendation.policy.enabled, false);

  const boundary30 = selectorRun(improvingSelectorSnapshots({ siblingRank: 25 }));
  const divergent = selectCoveragePolicy({
    manifestInfo,
    run1: improving,
    run2: boundary30,
    topK: 2,
    rrfK: 60,
  });
  assert.equal(divergent.status, 'NO_STABLE_COVERAGE_IMPROVEMENT');
  assert.deepEqual(divergent.winnersByRun.run1.policy, COVERAGE_POLICY_GRID[1]);
  assert.deepEqual(divergent.winnersByRun.run2.policy, COVERAGE_POLICY_GRID[2]);
  assert.equal(divergent.recommendation.policy.enabled, false);
});

test('rejects coverage candidates that regress baseline, any-required, or total groups', () => {
  const baseline = {
    allRequiredCount: 2,
    anyRequiredCount: 3,
    totalMatchedGroupCount: 5,
    passedCaseIds: ['case-a', 'case-b'],
  };
  assert.deepEqual(coverageEligibility({
    allRequiredCount: 3,
    anyRequiredCount: 2,
    totalMatchedGroupCount: 4,
    passedCaseIds: ['case-a', 'case-c', 'case-d'],
  }, baseline), {
    eligible: false,
    reasons: [
      'BASELINE_CASE_REGRESSION:case-b',
      'ANY_REQUIRED_REGRESSION:2/3',
      'TOTAL_GROUP_REGRESSION:4/5',
    ],
  });
});

test('fails closed on provenance, manifest, order, completeness, and document identity drift', () => {
  const manifestInfo = selectorManifestInfo();
  const run1 = selectorRun(improvingSelectorSnapshots());

  const provenance = structuredClone(run1);
  provenance.provenance.runtimeConfigSha256 = 'config-b';
  assert.throws(
    () => selectCoveragePolicy({ manifestInfo, run1, run2: provenance, topK: 2, rrfK: 60 }),
    /training provenance mismatch: runtimeConfigSha256/i,
  );

  const manifestMismatch = structuredClone(run1);
  manifestMismatch.provenance.trainingManifestHash = 'other-manifest';
  assert.throws(
    () => selectCoveragePolicy({ manifestInfo, run1: manifestMismatch, run2: manifestMismatch, topK: 2, rrfK: 60 }),
    /training manifest hash mismatch/i,
  );

  const reordered = structuredClone(run1);
  reordered.results.reverse();
  assert.throws(
    () => selectCoveragePolicy({ manifestInfo, run1, run2: reordered, topK: 2, rrfK: 60 }),
    /training result order does not match manifest/i,
  );

  const incomplete = structuredClone(run1);
  incomplete.complete = false;
  assert.throws(
    () => selectCoveragePolicy({ manifestInfo, run1: incomplete, run2: incomplete, topK: 2, rrfK: 60 }),
    /not a complete error-free training capture/i,
  );

  const conflict = structuredClone(run1);
  conflict.results[0].sourceRankSnapshot.bm25[0].documentId = 999;
  assert.throws(
    () => selectCoveragePolicy({ manifestInfo, run1: conflict, run2: conflict, topK: 2, rrfK: 60 }),
    /conflicting document id.*law:1/i,
  );
});

test('coverage selector CLI requires only the four exact paths', () => {
  const cli = require('./coverage-aware-select');
  assert.deepEqual(cli.parseCliOptions([
    '--manifest', 'manifest.json',
    '--run1', 'run1.json',
    '--run2', 'run2.json',
    '--output', 'selection.json',
  ]), {
    manifestPath: 'manifest.json',
    run1Path: 'run1.json',
    run2Path: 'run2.json',
    outputPath: 'selection.json',
  });
  assert.throws(() => cli.parseCliOptions(['--run-1', 'x']), /unknown option: --run-1/i);
  assert.throws(() => cli.parseCliOptions(['--manifest', 'x']), /--run1 is required/i);
});

test('coverage selector output has a terminal newline and rejects different existing evidence', () => {
  const cli = require('./coverage-aware-select');
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pandora-coverage-output-'));
  const file = path.join(directory, 'selection.json');
  try {
    cli.writeJsonAtomic(file, { status: 'RECOMMENDED' });
    assert.equal(fs.readFileSync(file, 'utf8'), '{\n  "status": "RECOMMENDED"\n}\n');
    assert.equal(cli.writeJsonAtomic(file, { status: 'RECOMMENDED' }), false);
    assert.throws(
      () => cli.writeJsonAtomic(file, { status: 'NO_COVERAGE_IMPROVEMENT' }),
      /refusing to overwrite different selection evidence/i,
    );
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

function rankingFixture() {
  const ranking = [];
  const documentIds = {};
  for (let index = 1; index <= 32; index += 1) {
    const item = index === 1
      ? ranked('law', index, 1, 1)
      : index === 31
        ? ranked('law', index, 28, null)
        : ranked('law', index, index, null);
    ranking.push(item);
    documentIds[item.candidateKey] = index === 1 || index === 31 ? 100 : 1_000 + index;
  }
  return { ranking, documentIds };
}

function ranked(target, chunkId, vectorRank, bm25Rank) {
  return {
    candidateKey: `${target}:${chunkId}`,
    target,
    chunkId,
    vectorRank,
    bm25Rank,
    bestSourceRank: Math.min(vectorRank ?? Infinity, bm25Rank ?? Infinity),
    matchedAuditGroupIndexes: [],
  };
}

function selectorManifestInfo() {
  return {
    manifestHash: 'manifest-hash',
    manifest: { splitName: 'fixture-training', trainingCaseIds: ['case-a', 'case-b'] },
    trainingCases: [
      { id: 'case-a', requiredPropositionGroups: [['a0'], ['a1']], requiredConditionGroups: [] },
      { id: 'case-b', requiredPropositionGroups: [['b0'], ['b1']], requiredConditionGroups: [] },
    ],
  };
}

function improvingSelectorSnapshots({ siblingRank = 3 } = {}) {
  const fillerItems = [];
  for (let rank = 2; rank < siblingRank; rank += 1) {
    fillerItems.push(snapshotItem(`law:${rank}`, rank, [], 1_000 + rank));
  }
  return {
    'case-a': {
      vector: [
        snapshotItem('law:1', 1, [0], 100),
        ...fillerItems,
        snapshotItem('law:31', siblingRank, [1], 100),
      ],
      bm25: [snapshotItem('law:1', 1, [0], 100)],
    },
    'case-b': completeSelectorSnapshot('law:40', 400),
  };
}

function baselineOnlySelectorSnapshots() {
  return {
    'case-a': completeSelectorSnapshot('law:1', 100),
    'case-b': completeSelectorSnapshot('law:40', 400),
  };
}

function completeSelectorSnapshot(candidateKey, documentId) {
  return {
    vector: [snapshotItem(candidateKey, 1, [0, 1], documentId)],
    bm25: [snapshotItem(candidateKey, 1, [0, 1], documentId)],
  };
}

function snapshotItem(candidateKey, rank, matchedAuditGroupIndexes, documentId) {
  return { candidateKey, documentId, rank, matchedAuditGroupIndexes };
}

function selectorRun(snapshots) {
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
    },
    results: ids.map((id) => ({
      id,
      oraclePresence: { totalGroupCount: 2 },
      sourceRankSnapshot: snapshots[id],
    })),
  };
}
