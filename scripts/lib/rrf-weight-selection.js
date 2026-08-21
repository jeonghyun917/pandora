const crypto = require('node:crypto');
const fs = require('node:fs');

const WEIGHT_GRID = Object.freeze([
  Object.freeze({ vectorWeight: 1, lexicalWeight: 0.5 }),
  Object.freeze({ vectorWeight: 1, lexicalWeight: 0.75 }),
  Object.freeze({ vectorWeight: 1, lexicalWeight: 1 }),
  Object.freeze({ vectorWeight: 0.75, lexicalWeight: 1 }),
  Object.freeze({ vectorWeight: 0.5, lexicalWeight: 1 }),
]);
const BASELINE_WEIGHTS = WEIGHT_GRID[2];
const PROVENANCE_FIELDS = Object.freeze([
  'trainingManifestHash',
  'trainingSplitName',
  'datasetHash',
  'selectionHash',
  'runtimeInstanceId',
  'runtimeArtifactSha256',
  'runtimeConfigSha256',
  'indexRevision',
  'lexicalRevision',
  'qdrantReady',
  'qdrantSearchFailureCount',
]);
const STRING_PROVENANCE_FIELDS = Object.freeze(PROVENANCE_FIELDS.filter((field) =>
  field !== 'qdrantReady' && field !== 'qdrantSearchFailureCount'));

function sha256Bytes(bytes) {
  return crypto.createHash('sha256').update(bytes).digest('hex');
}

function loadTrainingManifest(manifestPath, allCases) {
  const bytes = fs.readFileSync(manifestPath);
  let manifest;
  try {
    manifest = JSON.parse(bytes.toString('utf8'));
  } catch (error) {
    throw new Error(`invalid training manifest JSON: ${error.message}`);
  }
  validateManifestShape(manifest);

  const caseById = new Map((allCases ?? []).map((item) => [String(item?.id ?? '').trim(), item]));
  const difficultIds = uniqueIds(manifest.excludedDifficultCaseIds, 'difficult');
  for (const id of difficultIds) {
    if (!caseById.has(id)) {
      throw new Error(`unknown difficult case id: ${id}`);
    }
  }

  const trainingIds = uniqueIds(manifest.trainingCaseIds, 'training');
  if (trainingIds.length !== manifest.expectedTrainingCount) {
    throw new Error(`training case count mismatch: ${trainingIds.length}/${manifest.expectedTrainingCount}`);
  }
  const excluded = new Set(difficultIds);
  const trainingCases = trainingIds.map((id) => {
    if (excluded.has(id)) {
      throw new Error(`training case is excluded as difficult: ${id}`);
    }
    const item = caseById.get(id);
    if (!item) {
      throw new Error(`unknown training case id: ${id}`);
    }
    if (!hasExplicitOracle(item)) {
      throw new Error(`training case lacks explicit oracle: ${id}`);
    }
    return item;
  });
  const trainingSet = new Set(trainingIds);
  const holdoutCases = (allCases ?? []).filter((item) => {
    const id = String(item?.id ?? '').trim();
    return hasExplicitOracle(item) && !trainingSet.has(id) && !excluded.has(id);
  });

  return {
    manifest,
    manifestHash: sha256Bytes(bytes),
    trainingCases,
    holdoutCases,
  };
}

function validateManifestShape(manifest) {
  if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) {
    throw new Error('training manifest must be an object');
  }
  if (manifest.schemaVersion !== 1) {
    throw new Error(`unsupported training manifest schema: ${manifest.schemaVersion}`);
  }
  if (typeof manifest.splitName !== 'string' || !manifest.splitName.trim()) {
    throw new Error('training manifest splitName is required');
  }
  if (!Number.isSafeInteger(manifest.expectedTrainingCount) || manifest.expectedTrainingCount <= 0) {
    throw new Error('training manifest expectedTrainingCount must be a positive integer');
  }
  if (typeof manifest.selectionBasis !== 'string' || !manifest.selectionBasis.trim()) {
    throw new Error('training manifest selectionBasis is required');
  }
  if (!Array.isArray(manifest.trainingCaseIds)) {
    throw new Error('training manifest trainingCaseIds must be an array');
  }
  if (!Array.isArray(manifest.excludedDifficultCaseIds)) {
    throw new Error('training manifest excludedDifficultCaseIds must be an array');
  }
}

function uniqueIds(values, label) {
  const seen = new Set();
  return values.map((value) => {
    const id = String(value ?? '').trim();
    if (!id) {
      throw new Error(`${label} case id must not be empty`);
    }
    if (seen.has(id)) {
      throw new Error(`duplicate ${label} case id: ${id}`);
    }
    seen.add(id);
    return id;
  });
}

function hasExplicitOracle(item) {
  return (item?.requiredPropositionGroups?.length ?? 0)
    + (item?.requiredConditionGroups?.length ?? 0) > 0;
}

function fuseRanks(snapshot, weights, rrfK = 60) {
  const vectorWeight = positiveNumber(weights?.vectorWeight, 'vector weight');
  const lexicalWeight = positiveNumber(weights?.lexicalWeight, 'lexical weight');
  const safeK = positiveInteger(rrfK, 'RRF k');
  const candidates = new Map();
  addRankedSource(candidates, snapshot?.vector, 'vectorRank', vectorWeight, safeK);
  addRankedSource(candidates, snapshot?.bm25, 'bm25Rank', lexicalWeight, safeK);
  return Array.from(candidates.values())
    .map((candidate) => ({
      candidateKey: candidate.candidateKey,
      target: candidate.target,
      chunkId: candidate.chunkId,
      vectorRank: candidate.vectorRank ?? null,
      bm25Rank: candidate.bm25Rank ?? null,
      bestSourceRank: Math.min(candidate.vectorRank ?? Infinity, candidate.bm25Rank ?? Infinity),
      score: candidate.score,
      matchedAuditGroupIndexes: Array.from(candidate.matchedAuditGroupIndexes).sort((left, right) => left - right),
    }))
    .sort(compareFusedCandidates);
}

function addRankedSource(candidates, items, rankField, weight, rrfK) {
  for (const item of Array.isArray(items) ? items : []) {
    const key = String(item?.candidateKey ?? '').trim();
    const identity = parseCandidateKey(key);
    const rank = positiveInteger(item?.rank, `${rankField} rank`);
    let candidate = candidates.get(key);
    if (!candidate) {
      candidate = {
        candidateKey: key,
        ...identity,
        score: 0,
        matchedAuditGroupIndexes: new Set(),
      };
      candidates.set(key, candidate);
    }
    const previousRank = candidate[rankField];
    if (previousRank == null || rank < previousRank) {
      if (previousRank != null) {
        candidate.score -= weight / (rrfK + previousRank);
      }
      candidate[rankField] = rank;
      candidate.score += weight / (rrfK + rank);
    }
    for (const index of Array.isArray(item?.matchedAuditGroupIndexes) ? item.matchedAuditGroupIndexes : []) {
      if (Number.isSafeInteger(index) && index >= 0) {
        candidate.matchedAuditGroupIndexes.add(index);
      }
    }
  }
}

function compareFusedCandidates(left, right) {
  const scoreOrder = right.score - left.score;
  if (scoreOrder !== 0) {
    return scoreOrder;
  }
  if (left.bestSourceRank !== right.bestSourceRank) {
    return left.bestSourceRank - right.bestSourceRank;
  }
  const targetOrder = left.target < right.target ? -1 : (left.target > right.target ? 1 : 0);
  if (targetOrder !== 0) {
    return targetOrder;
  }
  if (left.chunkId !== right.chunkId) {
    return left.chunkId - right.chunkId;
  }
  return left.candidateKey.localeCompare(right.candidateKey, 'en');
}

function measureFused(ranking, requiredGroupCount, topK = 30) {
  const safeRequiredCount = positiveInteger(requiredGroupCount, 'required group count');
  const safeTopK = positiveInteger(topK, 'top K');
  const matched = new Set();
  for (const item of (Array.isArray(ranking) ? ranking : []).slice(0, safeTopK)) {
    for (const index of Array.isArray(item?.matchedAuditGroupIndexes) ? item.matchedAuditGroupIndexes : []) {
      if (Number.isSafeInteger(index) && index >= 0 && index < safeRequiredCount) {
        matched.add(index);
      }
    }
  }
  const matchedGroupIndexes = Array.from(matched).sort((left, right) => left - right);
  return {
    matchedGroupIndexes,
    matchedGroupCount: matchedGroupIndexes.length,
    requiredGroupCount: safeRequiredCount,
    anyRequiredPresent: matchedGroupIndexes.length > 0,
    allRequiredPresent: matchedGroupIndexes.length === safeRequiredCount,
  };
}

function parseCandidateKey(candidateKey) {
  const separator = candidateKey.lastIndexOf(':');
  const target = candidateKey.slice(0, separator);
  const chunkId = Number(candidateKey.slice(separator + 1));
  if (separator <= 0 || !target || !Number.isSafeInteger(chunkId) || chunkId < 0) {
    throw new Error(`invalid candidate key: ${candidateKey || '<empty>'}`);
  }
  return { target, chunkId };
}

function positiveNumber(value, label) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    throw new Error(`${label} must be positive`);
  }
  return parsed;
}

function positiveInteger(value, label) {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw new Error(`${label} must be a positive integer`);
  }
  return parsed;
}

function selectionEligibility(candidate, baseline) {
  const reasons = [];
  if (candidate.allRequiredCount <= baseline.allRequiredCount) {
    reasons.push(`ALL_REQUIRED_NOT_IMPROVED:${candidate.allRequiredCount}/${baseline.allRequiredCount}`);
  }
  const candidatePassed = new Set(candidate.passedCaseIds ?? []);
  for (const id of baseline.passedCaseIds ?? []) {
    if (!candidatePassed.has(id)) {
      reasons.push(`BASELINE_CASE_REGRESSION:${id}`);
    }
  }
  if (candidate.anyRequiredCount < baseline.anyRequiredCount) {
    reasons.push(`ANY_REQUIRED_REGRESSION:${candidate.anyRequiredCount}/${baseline.anyRequiredCount}`);
  }
  return { eligible: reasons.length === 0, reasons };
}

function selectWeights({ manifestInfo, run1, run2, topK = 30, rrfK = 60 }) {
  const trainingCases = validateTrainingRun(manifestInfo, run1, 'run1');
  validateTrainingRun(manifestInfo, run2, 'run2');
  assertMatchingProvenance(run1.provenance, run2.provenance);
  for (let index = 0; index < trainingCases.length; index += 1) {
    const left = run1.results[index];
    const right = run2.results[index];
    if (JSON.stringify(left.sourceRankSnapshot) !== JSON.stringify(right.sourceRankSnapshot)) {
      throw new Error(`training rank snapshot mismatch: ${left.id}`);
    }
  }

  const evaluated = WEIGHT_GRID.map((weights) => ({
    weights: { ...weights },
    metrics: evaluateTrainingCases(trainingCases, run1.results, weights, topK, rrfK),
    distanceFromBaseline: Math.abs(Math.log(weights.vectorWeight / weights.lexicalWeight)),
  }));
  const baseline = evaluated.find((item) => sameWeights(item.weights, BASELINE_WEIGHTS));
  const candidates = evaluated.map((item) => {
    if (sameWeights(item.weights, BASELINE_WEIGHTS)) {
      return { ...item, eligible: false, reasons: ['BASELINE'] };
    }
    return { ...item, ...selectionEligibility(item.metrics, baseline.metrics) };
  });
  const eligible = candidates.filter((item) => item.eligible).sort(compareRecommendations);
  const recommendation = eligible[0] ?? baseline;
  return {
    schemaVersion: 1,
    status: eligible.length > 0 ? 'RECOMMENDED' : 'NO_TRAINING_IMPROVEMENT',
    manifest: {
      splitName: manifestInfo.manifest.splitName,
      manifestHash: manifestInfo.manifestHash,
      trainingCaseCount: trainingCases.length,
    },
    parameters: { topK: positiveInteger(topK, 'top K'), rrfK: positiveInteger(rrfK, 'RRF k') },
    provenance: Object.fromEntries(PROVENANCE_FIELDS.map((field) => [field, run1.provenance[field]])),
    baseline,
    recommendation,
    candidates,
  };
}

function validateTrainingRun(manifestInfo, run, label) {
  if (!run || run.complete !== true || (run.requestErrors?.length ?? 0) !== 0) {
    throw new Error(`${label} is not a complete error-free training capture`);
  }
  assertCompleteProvenance(run.provenance, label);
  const expectedCases = manifestInfo?.trainingCases ?? [];
  if (run.selectedCases !== expectedCases.length || run.completedCases !== expectedCases.length) {
    throw new Error(`${label} training count mismatch`);
  }
  if (run.provenance?.trainingManifestHash !== manifestInfo.manifestHash) {
    throw new Error(`${label} training manifest hash mismatch`);
  }
  if (run.provenance?.trainingSplitName !== manifestInfo.manifest?.splitName) {
    throw new Error(`${label} training split mismatch`);
  }
  const actualIds = (run.results ?? []).map((item) => item?.id);
  const expectedIds = expectedCases.map((item) => item.id);
  if (actualIds.length !== expectedIds.length || expectedIds.some((id, index) => actualIds[index] !== id)) {
    throw new Error(`${label} training result order does not match manifest`);
  }
  for (let index = 0; index < expectedCases.length; index += 1) {
    const requiredGroupCount = explicitOracleGroupCount(expectedCases[index]);
    const result = run.results[index];
    if (result?.oraclePresence?.totalGroupCount !== requiredGroupCount) {
      throw new Error(`${label} explicit oracle group mismatch: ${expectedCases[index].id}`);
    }
    validateSourceRankSnapshot(result.sourceRankSnapshot, requiredGroupCount, expectedCases[index].id, label);
  }
  return expectedCases;
}

function assertCompleteProvenance(provenance, label) {
  for (const field of STRING_PROVENANCE_FIELDS) {
    if (typeof provenance?.[field] !== 'string' || !provenance[field].trim()) {
      throw new Error(`missing training provenance: ${field} (${label})`);
    }
  }
  if (provenance?.qdrantReady !== true) {
    throw new Error(`training Qdrant is not ready (${label})`);
  }
  if (provenance?.qdrantSearchFailureCount !== 0) {
    throw new Error(`training Qdrant search failures are nonzero (${label})`);
  }
}

function validateSourceRankSnapshot(snapshot, requiredGroupCount, caseId, label) {
  if (!snapshot || !Array.isArray(snapshot.vector) || !Array.isArray(snapshot.bm25) || snapshot.vector.length === 0) {
    throw new Error(`${label} incomplete source rank snapshot: ${caseId}`);
  }
  for (const source of ['vector', 'bm25']) {
    const seen = new Set();
    snapshot[source].forEach((item, index) => {
      if (item?.rank !== index + 1) {
        throw new Error(`${label} non-contiguous ${source} rank: ${caseId}`);
      }
      parseCandidateKey(String(item?.candidateKey ?? ''));
      if (seen.has(item.candidateKey)) {
        throw new Error(`${label} duplicate ${source} candidate: ${caseId}:${item.candidateKey}`);
      }
      seen.add(item.candidateKey);
      if (!Array.isArray(item.matchedAuditGroupIndexes)
        || item.matchedAuditGroupIndexes.some((value) => !Number.isSafeInteger(value)
          || value < 0 || value >= requiredGroupCount)) {
        throw new Error(`${label} invalid audit group index: ${caseId}:${item.candidateKey}`);
      }
    });
  }
}

function assertMatchingProvenance(left, right) {
  for (const field of PROVENANCE_FIELDS) {
    if (left?.[field] !== right?.[field]) {
      throw new Error(`training provenance mismatch: ${field}`);
    }
  }
}

function evaluateTrainingCases(trainingCases, results, weights, topK, rrfK) {
  const perCase = trainingCases.map((evalCase, index) => {
    const ranking = fuseRanks(results[index].sourceRankSnapshot, weights, rrfK);
    return {
      id: evalCase.id,
      ...measureFused(ranking, explicitOracleGroupCount(evalCase), topK),
      fusedCandidateKeys: ranking.slice(0, topK).map((item) => item.candidateKey),
    };
  });
  return {
    caseCount: perCase.length,
    allRequiredCount: perCase.filter((item) => item.allRequiredPresent).length,
    anyRequiredCount: perCase.filter((item) => item.anyRequiredPresent).length,
    totalMatchedGroupCount: perCase.reduce((sum, item) => sum + item.matchedGroupCount, 0),
    passedCaseIds: perCase.filter((item) => item.allRequiredPresent).map((item) => item.id),
    anyRequiredCaseIds: perCase.filter((item) => item.anyRequiredPresent).map((item) => item.id),
    perCase,
  };
}

function explicitOracleGroupCount(item) {
  const count = (item?.requiredPropositionGroups?.length ?? 0)
    + (item?.requiredConditionGroups?.length ?? 0);
  return positiveInteger(count, `explicit oracle group count for ${item?.id ?? '<unknown>'}`);
}

function compareRecommendations(left, right) {
  return right.metrics.allRequiredCount - left.metrics.allRequiredCount
    || right.metrics.anyRequiredCount - left.metrics.anyRequiredCount
    || right.metrics.totalMatchedGroupCount - left.metrics.totalMatchedGroupCount
    || left.distanceFromBaseline - right.distanceFromBaseline
    || left.weights.vectorWeight - right.weights.vectorWeight
    || left.weights.lexicalWeight - right.weights.lexicalWeight;
}

function sameWeights(left, right) {
  return left.vectorWeight === right.vectorWeight && left.lexicalWeight === right.lexicalWeight;
}

module.exports = {
  fuseRanks,
  loadTrainingManifest,
  measureFused,
  selectWeights,
  selectionEligibility,
  sha256Bytes,
};
