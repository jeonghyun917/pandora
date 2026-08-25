const COVERAGE_POLICY_GRID = Object.freeze([
  Object.freeze({ enabled: false, maxRescues: 0, maxRescuesPerDocument: 1, sourceRankLimit: 30 }),
  Object.freeze({ enabled: true, maxRescues: 1, maxRescuesPerDocument: 1, sourceRankLimit: 20 }),
  Object.freeze({ enabled: true, maxRescues: 1, maxRescuesPerDocument: 1, sourceRankLimit: 30 }),
  Object.freeze({ enabled: true, maxRescues: 2, maxRescuesPerDocument: 1, sourceRankLimit: 20 }),
  Object.freeze({ enabled: true, maxRescues: 2, maxRescuesPerDocument: 1, sourceRankLimit: 30 }),
]);

const MAX_CANDIDATE_LIMIT = 100;
const RESCUE_REASON = 'DOCUMENT_SIBLING_RESCUE';
const BASELINE_WEIGHTS = Object.freeze({ vectorWeight: 1, lexicalWeight: 1 });
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

const { fuseRanks, measureFused } = require('./rrf-weight-selection');

function rerankCoverage({ ranking, documentIdByCandidate, policy, topK = 30 }) {
  const baseline = Array.isArray(ranking) ? [...ranking] : [];
  if (!policy?.enabled || policy.maxRescues === 0) {
    return result(baseline, baseline, [], 'DISABLED');
  }
  if (!validInputs(baseline, documentIdByCandidate, policy, topK)) {
    return result(baseline, baseline, [], 'FALLBACK_BASELINE');
  }

  const anchors = eligibleAnchors(baseline, documentIdByCandidate, topK);
  const proposals = eligibleProposals(baseline, documentIdByCandidate, anchors, policy, topK);
  const selected = selectWithinBudgets(proposals, policy);
  if (selected.length === 0) {
    return result(baseline, baseline, [], 'NO_ELIGIBLE_SIBLING');
  }
  return replaceTail(baseline, anchors, selected, topK);
}

function validInputs(baseline, documentIds, policy, topK) {
  if (!Number.isSafeInteger(topK) || topK <= 0 || baseline.length < topK
    || !documentIds || typeof documentIds !== 'object'
    || !Number.isSafeInteger(policy.maxRescues) || policy.maxRescues < 0
    || !Number.isSafeInteger(policy.maxRescuesPerDocument) || policy.maxRescuesPerDocument <= 0
    || policy.maxRescuesPerDocument > policy.maxRescues
    || !Number.isSafeInteger(policy.sourceRankLimit) || policy.sourceRankLimit <= 0) {
    return false;
  }
  const seen = new Set();
  return baseline.slice(0, MAX_CANDIDATE_LIMIT).every((item) => {
    const key = String(item?.candidateKey ?? '');
    if (key !== `${item?.target}:${item?.chunkId}` || seen.has(key)) {
      return false;
    }
    seen.add(key);
    return Number.isSafeInteger(documentIds[key]) && documentIds[key] > 0;
  });
}

function eligibleAnchors(baseline, documentIds, topK) {
  const anchors = new Map();
  baseline.slice(0, topK).forEach((item, index) => {
    if (!crossSource(item)) {
      return;
    }
    const key = documentKey(item, documentIds[item.candidateKey]);
    if (!anchors.has(key)) {
      anchors.set(key, { hit: item, rank: index + 1, documentKey: key });
    }
  });
  return anchors;
}

function eligibleProposals(baseline, documentIds, anchors, policy, topK) {
  const proposals = [];
  baseline.slice(topK, MAX_CANDIDATE_LIMIT).forEach((sibling, offset) => {
    if (crossSource(sibling) || sibling.bestSourceRank > policy.sourceRankLimit) {
      return;
    }
    const documentId = documentIds[sibling.candidateKey];
    const key = documentKey(sibling, documentId);
    const anchor = anchors.get(key);
    if (anchor) {
      proposals.push({
        sibling,
        anchor: anchor.hit,
        documentKey: key,
        documentId,
        anchorRank: anchor.rank,
        baselineRank: topK + offset + 1,
        bestSourceRank: sibling.bestSourceRank,
      });
    }
  });
  return proposals.sort((left, right) => left.anchorRank - right.anchorRank
    || left.bestSourceRank - right.bestSourceRank
    || left.baselineRank - right.baselineRank
    || left.sibling.target.localeCompare(right.sibling.target, 'en')
    || left.documentId - right.documentId
    || left.sibling.chunkId - right.sibling.chunkId);
}

function selectWithinBudgets(proposals, policy) {
  const selected = [];
  const counts = new Map();
  for (const proposal of proposals) {
    if (selected.length >= policy.maxRescues) {
      break;
    }
    const count = counts.get(proposal.documentKey) ?? 0;
    if (count >= policy.maxRescuesPerDocument) {
      continue;
    }
    selected.push(proposal);
    counts.set(proposal.documentKey, count + 1);
  }
  return selected;
}

function replaceTail(baseline, anchors, selected, topK) {
  const ranking = baseline.slice(0, topK);
  const protectedKeys = new Set(Array.from(anchors.values(), (anchor) => anchor.hit.candidateKey));
  for (let rescueIndex = 0; rescueIndex < selected.length; rescueIndex += 1) {
    let replacementIndex = -1;
    for (let index = ranking.length - 1; index >= 0; index -= 1) {
      if (!protectedKeys.has(ranking[index].candidateKey)) {
        replacementIndex = index;
        break;
      }
    }
    if (replacementIndex < 0) {
      return result(baseline, baseline, [], 'FALLBACK_BASELINE');
    }
    ranking.splice(replacementIndex, 1);
  }
  const rescues = selected.map((proposal) => {
    ranking.push(proposal.sibling);
    return {
      candidateKey: proposal.sibling.candidateKey,
      documentKey: proposal.documentKey,
      anchorCandidateKey: proposal.anchor.candidateKey,
      baselineRank: proposal.baselineRank,
      rescuedRank: ranking.length,
      reason: RESCUE_REASON,
    };
  });
  if (ranking.length !== topK || new Set(ranking.map((item) => item.candidateKey)).size !== topK) {
    return result(baseline, baseline, [], 'FALLBACK_BASELINE');
  }
  return result(baseline, ranking, rescues, 'APPLIED');
}

function validateDocumentIdentitySnapshot(snapshot) {
  const documentIds = {};
  for (const source of ['vector', 'bm25']) {
    for (const item of Array.isArray(snapshot?.[source]) ? snapshot[source] : []) {
      const key = String(item?.candidateKey ?? '').trim();
      const documentId = Number(item?.documentId);
      if (!key || !Number.isSafeInteger(documentId) || documentId <= 0) {
        throw new Error(`invalid document id for candidate: ${key || '<empty>'}`);
      }
      if (documentIds[key] != null && documentIds[key] !== documentId) {
        throw new Error(`conflicting document id for candidate: ${key}`);
      }
      documentIds[key] = documentId;
    }
  }
  return documentIds;
}

function selectCoveragePolicy({ manifestInfo, run1, run2, topK = 30, rrfK = 60 }) {
  const safeTopK = positiveInteger(topK, 'top K');
  const safeRrfK = positiveInteger(rrfK, 'RRF k');
  const trainingCases = validateTrainingRun(manifestInfo, run1, 'run1');
  validateTrainingRun(manifestInfo, run2, 'run2');
  assertMatchingProvenance(run1.provenance, run2.provenance);

  const evaluatedByRun = {
    run1: evaluatePolicies(trainingCases, run1.results, safeTopK, safeRrfK),
    run2: evaluatePolicies(trainingCases, run2.results, safeTopK, safeRrfK),
  };
  const baselines = {
    run1: evaluatedByRun.run1[0],
    run2: evaluatedByRun.run2[0],
  };
  const guardedByRun = Object.fromEntries(Object.entries(evaluatedByRun).map(([label, evaluated]) => [
    label,
    evaluated.map((item, index) => index === 0
      ? { ...item, eligible: false, reasons: ['BASELINE'] }
      : { ...item, ...coverageEligibility(item.metrics, baselines[label].metrics) }),
  ]));
  const improvedByRun = Object.fromEntries(Object.entries(guardedByRun).map(([label, evaluated]) => [
    label,
    evaluated.some((item) => item.eligible),
  ]));
  const winners = Object.fromEntries(Object.entries(guardedByRun).map(([label, evaluated]) => [
    label,
    evaluated.filter((item) => item.eligible).sort(compareCoverageRecommendations)[0] ?? baselines[label],
  ]));
  const candidates = COVERAGE_POLICY_GRID.map((policy, index) => {
    const left = guardedByRun.run1[index];
    const right = guardedByRun.run2[index];
    return {
      policy: { ...policy },
      metrics: conservativeMetrics(left.metrics, right.metrics),
      metricsByRun: { run1: left.metrics, run2: right.metrics },
      eligible: left.eligible && right.eligible,
      eligibilityByRun: {
        run1: { eligible: left.eligible, reasons: left.reasons },
        run2: { eligible: right.eligible, reasons: right.reasons },
      },
      reasons: [
        ...left.reasons.map((reason) => `RUN1:${reason}`),
        ...right.reasons.map((reason) => `RUN2:${reason}`),
      ],
      gridIndex: index,
    };
  });
  const stableImprovement = improvedByRun.run1 && improvedByRun.run2
    && samePolicy(winners.run1.policy, winners.run2.policy);
  const baseline = candidates[0];
  const recommendation = stableImprovement
    ? candidates.find((item) => samePolicy(item.policy, winners.run1.policy))
    : baseline;
  const status = stableImprovement
    ? 'RECOMMENDED'
    : (improvedByRun.run1 || improvedByRun.run2)
      ? 'NO_STABLE_COVERAGE_IMPROVEMENT'
      : 'NO_COVERAGE_IMPROVEMENT';
  return {
    schemaVersion: 1,
    status,
    manifest: {
      splitName: manifestInfo.manifest.splitName,
      manifestHash: manifestInfo.manifestHash,
      trainingCaseCount: trainingCases.length,
    },
    parameters: { topK: safeTopK, rrfK: safeRrfK },
    provenance: Object.fromEntries(PROVENANCE_FIELDS.map((field) => [field, run1.provenance[field]])),
    rankSnapshotsIdentical: trainingCases.every((_, index) =>
      JSON.stringify(run1.results[index].sourceRankSnapshot)
        === JSON.stringify(run2.results[index].sourceRankSnapshot)),
    winnersByRun: {
      run1: { policy: { ...winners.run1.policy }, improved: improvedByRun.run1 },
      run2: { policy: { ...winners.run2.policy }, improved: improvedByRun.run2 },
    },
    baseline,
    candidates,
    recommendation,
  };
}

function evaluatePolicies(trainingCases, results, topK, rrfK) {
  return COVERAGE_POLICY_GRID.map((policy, gridIndex) => {
    const perCase = trainingCases.map((evalCase, index) => {
      const snapshot = results[index].sourceRankSnapshot;
      const ranking = fuseRanks(snapshot, BASELINE_WEIGHTS, rrfK);
      const documentIds = validateDocumentIdentitySnapshot(snapshot);
      const coverage = rerankCoverage({
        ranking,
        documentIdByCandidate: documentIds,
        policy,
        topK,
      });
      return {
        id: evalCase.id,
        ...measureFused(coverage.ranking, explicitOracleGroupCount(evalCase), topK),
        rescueCount: coverage.rescues.length,
        coverageStatus: coverage.status,
        rescuedCandidateKeys: coverage.rescues.map((item) => item.candidateKey),
      };
    });
    return {
      policy: { ...policy },
      gridIndex,
      metrics: aggregateMetrics(perCase),
    };
  });
}

function aggregateMetrics(perCase) {
  return {
    caseCount: perCase.length,
    allRequiredCount: perCase.filter((item) => item.allRequiredPresent).length,
    anyRequiredCount: perCase.filter((item) => item.anyRequiredPresent).length,
    totalMatchedGroupCount: perCase.reduce((sum, item) => sum + item.matchedGroupCount, 0),
    totalRescueCount: perCase.reduce((sum, item) => sum + item.rescueCount, 0),
    passedCaseIds: perCase.filter((item) => item.allRequiredPresent).map((item) => item.id),
    anyRequiredCaseIds: perCase.filter((item) => item.anyRequiredPresent).map((item) => item.id),
    perCase,
  };
}

function coverageEligibility(candidate, baseline) {
  const reasons = [];
  if (candidate.allRequiredCount <= baseline.allRequiredCount) {
    reasons.push(`ALL_REQUIRED_NOT_IMPROVED:${candidate.allRequiredCount}/${baseline.allRequiredCount}`);
  }
  const candidatePassed = new Set(candidate.passedCaseIds);
  for (const id of baseline.passedCaseIds) {
    if (!candidatePassed.has(id)) {
      reasons.push(`BASELINE_CASE_REGRESSION:${id}`);
    }
  }
  if (candidate.anyRequiredCount < baseline.anyRequiredCount) {
    reasons.push(`ANY_REQUIRED_REGRESSION:${candidate.anyRequiredCount}/${baseline.anyRequiredCount}`);
  }
  if (candidate.totalMatchedGroupCount < baseline.totalMatchedGroupCount) {
    reasons.push(`TOTAL_GROUP_REGRESSION:${candidate.totalMatchedGroupCount}/${baseline.totalMatchedGroupCount}`);
  }
  return { eligible: reasons.length === 0, reasons };
}

function compareCoverageRecommendations(left, right) {
  return right.metrics.allRequiredCount - left.metrics.allRequiredCount
    || right.metrics.anyRequiredCount - left.metrics.anyRequiredCount
    || right.metrics.totalMatchedGroupCount - left.metrics.totalMatchedGroupCount
    || left.metrics.totalRescueCount - right.metrics.totalRescueCount
    || left.policy.sourceRankLimit - right.policy.sourceRankLimit
    || left.gridIndex - right.gridIndex;
}

function conservativeMetrics(left, right) {
  const passedCaseIds = left.passedCaseIds.filter((id) => right.passedCaseIds.includes(id));
  const anyRequiredCaseIds = left.anyRequiredCaseIds.filter((id) => right.anyRequiredCaseIds.includes(id));
  return {
    caseCount: Math.min(left.caseCount, right.caseCount),
    allRequiredCount: passedCaseIds.length,
    anyRequiredCount: anyRequiredCaseIds.length,
    totalMatchedGroupCount: Math.min(left.totalMatchedGroupCount, right.totalMatchedGroupCount),
    totalRescueCount: Math.max(left.totalRescueCount, right.totalRescueCount),
    passedCaseIds,
    anyRequiredCaseIds,
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
  if (run.provenance.trainingManifestHash !== manifestInfo?.manifestHash) {
    throw new Error(`${label} training manifest hash mismatch`);
  }
  if (run.provenance.trainingSplitName !== manifestInfo?.manifest?.splitName) {
    throw new Error(`${label} training split mismatch`);
  }
  const expectedIds = expectedCases.map((item) => item.id);
  const actualIds = (run.results ?? []).map((item) => item?.id);
  if (actualIds.length !== expectedIds.length || expectedIds.some((id, index) => actualIds[index] !== id)) {
    throw new Error(`${label} training result order does not match manifest`);
  }
  expectedCases.forEach((evalCase, index) => {
    const requiredCount = explicitOracleGroupCount(evalCase);
    const result = run.results[index];
    if (result?.oraclePresence?.totalGroupCount !== requiredCount) {
      throw new Error(`${label} explicit oracle group mismatch: ${evalCase.id}`);
    }
    validateSourceRankSnapshot(result.sourceRankSnapshot, requiredCount, evalCase.id, label);
    validateDocumentIdentitySnapshot(result.sourceRankSnapshot);
  });
  return expectedCases;
}

function validateSourceRankSnapshot(snapshot, requiredCount, caseId, label) {
  if (!snapshot || !Array.isArray(snapshot.vector) || !Array.isArray(snapshot.bm25) || snapshot.vector.length === 0) {
    throw new Error(`${label} incomplete source rank snapshot: ${caseId}`);
  }
  for (const source of ['vector', 'bm25']) {
    const seen = new Set();
    snapshot[source].forEach((item, index) => {
      if (item?.rank !== index + 1) {
        throw new Error(`${label} non-contiguous ${source} rank: ${caseId}`);
      }
      if (seen.has(item.candidateKey)) {
        throw new Error(`${label} duplicate ${source} candidate: ${caseId}:${item.candidateKey}`);
      }
      seen.add(item.candidateKey);
      if (!Array.isArray(item.matchedAuditGroupIndexes)
        || item.matchedAuditGroupIndexes.some((value) => !Number.isSafeInteger(value)
          || value < 0 || value >= requiredCount)) {
        throw new Error(`${label} invalid audit group index: ${caseId}:${item.candidateKey}`);
      }
    });
  }
}

function assertCompleteProvenance(provenance, label) {
  for (const field of PROVENANCE_FIELDS.filter((name) => name !== 'qdrantReady'
    && name !== 'qdrantSearchFailureCount')) {
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

function assertMatchingProvenance(left, right) {
  for (const field of PROVENANCE_FIELDS) {
    if (left?.[field] !== right?.[field]) {
      throw new Error(`training provenance mismatch: ${field}`);
    }
  }
}

function explicitOracleGroupCount(item) {
  return positiveInteger(
    (item?.requiredPropositionGroups?.length ?? 0) + (item?.requiredConditionGroups?.length ?? 0),
    `explicit oracle group count for ${item?.id ?? '<unknown>'}`,
  );
}

function positiveInteger(value, label) {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw new Error(`${label} must be a positive integer`);
  }
  return parsed;
}

function samePolicy(left, right) {
  return left.enabled === right.enabled
    && left.maxRescues === right.maxRescues
    && left.maxRescuesPerDocument === right.maxRescuesPerDocument
    && left.sourceRankLimit === right.sourceRankLimit;
}

function crossSource(item) {
  return item?.vectorRank != null && item?.bm25Rank != null;
}

function documentKey(item, documentId) {
  return `${item.target}:${documentId}`;
}

function result(baseline, ranking, rescues, status) {
  return {
    baseline: [...baseline],
    ranking: [...ranking],
    rescues: [...rescues],
    status,
  };
}

module.exports = {
  COVERAGE_POLICY_GRID,
  coverageEligibility,
  rerankCoverage,
  selectCoveragePolicy,
  validateDocumentIdentitySnapshot,
};
