const assert = require('node:assert/strict');
const test = require('node:test');

const {
  COVERAGE_POLICY_GRID,
  rerankCoverage,
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
