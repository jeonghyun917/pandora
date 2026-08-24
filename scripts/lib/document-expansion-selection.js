const TRAINING_BASELINE = Object.freeze({ allRequired: 7, anyRequired: 14, matchedGroups: 22, caseCount: 24 });
const PROVENANCE_FIELDS = Object.freeze([
  'trainingManifestHash', 'trainingSplitName', 'datasetHash', 'selectionHash', 'runtimeInstanceId',
  'runtimeArtifactSha256', 'runtimeConfigSha256', 'indexRevision', 'lexicalRevision',
  'qdrantReady', 'qdrantSearchFailureCount',
]);

function summarizeDocumentExpansionRun(run) {
  const results = Array.isArray(run?.results) ? run.results : [];
  return {
    control: summarizePresence(results, 'controlFusedPresence'),
    expansionSource: summarizePresence(results, 'expansionSourcePresence'),
    shadowFused: summarizePresence(results, 'shadowFusedPresence'),
  };
}

function summarizePresence(results, key) {
  const perCase = results.map((result) => {
    const presence = result?.documentExpansion?.[key];
    const requiredGroupCount = Number(presence?.requiredGroupCount);
    if (!Number.isSafeInteger(requiredGroupCount) || requiredGroupCount <= 0) {
      throw new Error(`invalid ${key} required group count: ${result?.id ?? '<unknown>'}`);
    }
    const source = presence?.matchedRequiredGroupIndexes;
    const indexes = Array.from(new Set(Array.isArray(source) ? source : []))
      .filter((value) => Number.isSafeInteger(value) && value >= 0 && value < requiredGroupCount)
      .sort((left, right) => left - right);
    if (!Array.isArray(source) || indexes.length !== new Set(source).size) {
      throw new Error(`invalid ${key} matched groups: ${result?.id ?? '<unknown>'}`);
    }
    return { id: String(result?.id ?? ''), indexes, allRequired: indexes.length === requiredGroupCount, anyRequired: indexes.length > 0 };
  });
  if (perCase.some((item) => !item.id)) throw new Error('document expansion result id is required');
  return {
    caseCount: perCase.length,
    allRequired: perCase.filter((item) => item.allRequired).length,
    anyRequired: perCase.filter((item) => item.anyRequired).length,
    matchedGroups: perCase.reduce((sum, item) => sum + item.indexes.length, 0),
    passedCaseIds: perCase.filter((item) => item.allRequired).map((item) => item.id),
  };
}

function selectDocumentExpansionPolicy({ manifest, run1, run2, policies }) {
  const fail = (status, reason) => ({ schemaVersion: 1, status, eligible: false, reason, trainingBaseline: { ...TRAINING_BASELINE } });
  const normalizedManifest = normalizeManifest(manifest);
  if (!normalizedManifest.valid) return fail('TRAINING_MANIFEST_MISMATCH', normalizedManifest.reason);
  const runs = validateRuns(normalizedManifest, run1, run2);
  if (!runs.valid) return fail(runs.status, runs.reason);
  const policy1 = normalizePolicy(run1?.documentExpansionPolicy ?? run1?.provenance?.documentExpansionPolicy);
  const policy2 = normalizePolicy(run2?.documentExpansionPolicy ?? run2?.provenance?.documentExpansionPolicy);
  if (!policy1 || !policy2 || policy1.id !== policy2.id || policy1.configHash !== policy2.configHash) {
    return fail('POLICY_MISMATCH', 'document expansion policy differs between runs');
  }
  const policy = new Map(normalizePolicies(policies).map((entry) => [entry.id, entry])).get(policy1.id);
  if (!policy || policy.configHash !== policy1.configHash) {
    return fail('POLICY_MISMATCH', 'document expansion policy is not an immutable supplied policy');
  }
  let summary1;
  let summary2;
  try {
    summary1 = summarizeDocumentExpansionRun(run1);
    summary2 = summarizeDocumentExpansionRun(run2);
  } catch (error) {
    return fail('INVALID_CAPTURE', error.message);
  }
  if ([summary1.control, summary2.control].some((metrics) => !matchesBaseline(metrics))) {
    return fail('BASELINE_REGRESSION', 'control recall does not match the frozen training baseline');
  }
  if ([summary1, summary2].some((summary) => summary.control.passedCaseIds
    .some((id) => !summary.shadowFused.passedCaseIds.includes(id)))) {
    return fail('BASELINE_REGRESSION', 'shadow fused recall lost a baseline-passing case');
  }
  if ([summary1.shadowFused, summary2.shadowFused].some((metrics) => metrics.allRequired <= TRAINING_BASELINE.allRequired)) {
    return fail('NO_DOCUMENT_EXPANSION_IMPROVEMENT', 'shadow fused all-required recall did not exceed 7/24');
  }
  if ([summary1, summary2].some((summary) => summary.shadowFused.allRequired <= summary.control.allRequired)) {
    return fail('NO_DOCUMENT_EXPANSION_IMPROVEMENT', 'shadow fused all-required recall did not improve its control');
  }
  if ([summary1.shadowFused, summary2.shadowFused].some((metrics) => metrics.anyRequired < TRAINING_BASELINE.anyRequired
    || metrics.matchedGroups < TRAINING_BASELINE.matchedGroups)) {
    return fail('DOCUMENT_EXPANSION_QUALITY_REGRESSION', 'shadow fused recall is below the frozen quality floors');
  }
  return {
    schemaVersion: 1,
    status: 'ELIGIBLE_FOR_DIFFICULT_EVAL',
    eligible: true,
    policy,
    trainingBaseline: { ...TRAINING_BASELINE },
    summaries: { run1: summary1, run2: summary2 },
    provenance: Object.fromEntries(PROVENANCE_FIELDS.map((field) => [field, run1.provenance[field]])),
  };
}

function matchesBaseline(metrics) {
  return metrics.caseCount === TRAINING_BASELINE.caseCount && metrics.allRequired === TRAINING_BASELINE.allRequired
    && metrics.anyRequired === TRAINING_BASELINE.anyRequired && metrics.matchedGroups === TRAINING_BASELINE.matchedGroups;
}

function normalizeManifest(manifest) {
  const value = manifest?.manifest ?? manifest;
  const ids = value?.trainingCaseIds;
  if (!Array.isArray(ids) || ids.length !== TRAINING_BASELINE.caseCount || value?.expectedTrainingCount !== TRAINING_BASELINE.caseCount) {
    return { valid: false, reason: 'training manifest must contain exactly 24 cases' };
  }
  const normalizedIds = ids.map((id) => String(id ?? '').trim());
  if (normalizedIds.some((id) => !id) || new Set(normalizedIds).size !== normalizedIds.length) {
    return { valid: false, reason: 'training manifest case ids are invalid' };
  }
  return { valid: true, ids: normalizedIds, manifestHash: manifest?.manifestHash ?? value?.manifestHash ?? null };
}

function validateRuns(manifest, run1, run2) {
  for (const [label, run] of [['run1', run1], ['run2', run2]]) {
    if (!run || run.complete !== true || !Array.isArray(run.requestErrors) || run.requestErrors.length !== 0) {
      return { valid: false, status: 'REQUEST_ERRORS', reason: `${label} is not a complete error-free capture` };
    }
    if (PROVENANCE_FIELDS.filter((field) => field !== 'qdrantReady' && field !== 'qdrantSearchFailureCount')
      .some((field) => typeof run.provenance?.[field] !== 'string' || !run.provenance[field].trim())) {
      return { valid: false, status: 'PROVENANCE_MISMATCH', reason: `${label} has incomplete immutable provenance` };
    }
    if (run.provenance?.qdrantReady !== true || run.provenance?.qdrantSearchFailureCount !== 0) {
      return { valid: false, status: 'QDRANT_FAILURES', reason: `${label} has Qdrant failures` };
    }
    if (run.selectedCases !== TRAINING_BASELINE.caseCount || run.completedCases !== TRAINING_BASELINE.caseCount
      || !sameIds(run.results, manifest.ids)) {
      return { valid: false, status: 'TRAINING_CAPTURE_MISMATCH', reason: `${label} does not match the training manifest` };
    }
    if (manifest.manifestHash && run.provenance?.trainingManifestHash !== manifest.manifestHash) {
      return { valid: false, status: 'PROVENANCE_MISMATCH', reason: `${label} has a different manifest hash` };
    }
  }
  for (const field of PROVENANCE_FIELDS) {
    if (run1.provenance?.[field] !== run2.provenance?.[field]) {
      return { valid: false, status: 'PROVENANCE_MISMATCH', reason: `immutable provenance differs: ${field}` };
    }
  }
  return { valid: true };
}

function sameIds(results, ids) {
  return Array.isArray(results) && results.length === ids.length && ids.every((id, index) => String(results[index]?.id ?? '') === id);
}

function normalizePolicies(policies) {
  const values = Array.isArray(policies) ? policies : Object.values(policies ?? {});
  return values.map(normalizePolicy).filter(Boolean);
}

function normalizePolicy(value) {
  if (!value || typeof value !== 'object') return null;
  const id = String(value.id ?? value.policyId ?? '').trim();
  const configHash = String(value.configHash ?? value.policyHash ?? '').trim();
  return id && configHash ? { id, configHash } : null;
}

module.exports = { TRAINING_BASELINE, selectDocumentExpansionPolicy, summarizeDocumentExpansionRun };
