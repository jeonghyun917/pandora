const PROVENANCE_FIELDS = Object.freeze([
  'trainingManifestHash', 'trainingSplitName', 'datasetHash', 'selectionHash', 'runtimeInstanceId',
  'runtimeArtifactSha256', 'runtimeConfigSha256', 'indexRevision', 'lexicalRevision',
  'qdrantReady', 'qdrantSearchFailureCount',
]);

function selectGroupBalancedBm25Policy({ manifest, run1, run2 }) {
  const fail = (status, reason) => ({ schemaVersion: 1, status, eligible: false, reason });
  const normalizedManifest = normalizeManifest(manifest);
  if (!normalizedManifest.valid) return fail('TRAINING_MANIFEST_MISMATCH', normalizedManifest.reason);
  const runs = validateRuns(normalizedManifest, run1, run2);
  if (!runs.valid) return fail(runs.status, runs.reason);

  const policy1 = normalizePolicy(run1.bm25VariantPolicy);
  const policy2 = normalizePolicy(run2.bm25VariantPolicy);
  if (!policy1 || !policy2 || JSON.stringify(policy1) !== JSON.stringify(policy2)
    || policy1.configHash !== run1.provenance.runtimeConfigSha256) {
    return fail('POLICY_MISMATCH', 'group-balanced BM25 policy is missing, drifting, or detached from runtime config');
  }

  let first;
  let second;
  try {
    first = normalizeResults(run1.results, normalizedManifest.ids);
    second = normalizeResults(run2.results, normalizedManifest.ids);
  } catch (error) {
    return fail('INVALID_CAPTURE', error.message);
  }
  const firstLoss = lostControlGroup(first);
  const secondLoss = lostControlGroup(second);
  if (firstLoss || secondLoss) {
    return fail('CONTROL_GROUP_REGRESSION', firstLoss ?? secondLoss);
  }
  if (JSON.stringify(first) !== JSON.stringify(second)) {
    return fail('NONDETERMINISTIC_CAPTURE', 'variant hashes, source presence, or candidate ordering differs between runs');
  }

  const addedGroups = first.flatMap((item) => {
    const control = new Set(item.control.indexes);
    const added = item.shadow.indexes.filter((index) => !control.has(index));
    return added.length > 0 ? [{ id: item.id, groupIndexes: added }] : [];
  });
  const summaries = { run1: summarize(first), run2: summarize(second) };
  if (addedGroups.length === 0) {
    return {
      schemaVersion: 1,
      status: 'NO_IMPROVEMENT',
      eligible: false,
      reason: 'shadow discovered no required group absent from control',
      policy: policy1,
      addedGroups: [],
      summaries,
    };
  }
  return {
    schemaVersion: 1,
    status: 'SELECTED',
    eligible: true,
    policy: policy1,
    addedGroups,
    summaries,
    provenance: Object.fromEntries(PROVENANCE_FIELDS.map((field) => [field, run1.provenance[field]])),
  };
}

function normalizeManifest(manifest) {
  const value = manifest?.manifest ?? manifest;
  const ids = value?.trainingCaseIds;
  if (!Array.isArray(ids) || value?.expectedTrainingCount !== 24 || ids.length !== 24) {
    return { valid: false, reason: 'training manifest must contain exactly 24 cases' };
  }
  const normalized = ids.map((id) => String(id ?? '').trim());
  if (normalized.some((id) => !id) || new Set(normalized).size !== normalized.length) {
    return { valid: false, reason: 'training manifest case IDs are invalid' };
  }
  return {
    valid: true,
    ids: normalized,
    manifestHash: manifest?.manifestHash ?? value?.manifestHash ?? null,
  };
}

function validateRuns(manifest, run1, run2) {
  for (const [label, run] of [['run1', run1], ['run2', run2]]) {
    if (!run || run.complete !== true || !Array.isArray(run.requestErrors) || run.requestErrors.length !== 0) {
      return { valid: false, status: 'REQUEST_ERRORS', reason: `${label} is not a complete error-free capture` };
    }
    if (run.selectedCases !== 24 || run.completedCases !== 24 || !sameIds(run.results, manifest.ids)) {
      return { valid: false, status: 'TRAINING_CAPTURE_MISMATCH', reason: `${label} does not match manifest order` };
    }
    if (PROVENANCE_FIELDS.filter((field) => !['qdrantReady', 'qdrantSearchFailureCount'].includes(field))
      .some((field) => typeof run.provenance?.[field] !== 'string' || !run.provenance[field].trim())) {
      return { valid: false, status: 'PROVENANCE_MISMATCH', reason: `${label} has incomplete provenance` };
    }
    if (run.provenance.qdrantReady !== true || run.provenance.qdrantSearchFailureCount !== 0) {
      return { valid: false, status: 'QDRANT_FAILURES', reason: `${label} has Qdrant failures` };
    }
    if (manifest.manifestHash && run.provenance.trainingManifestHash !== manifest.manifestHash) {
      return { valid: false, status: 'PROVENANCE_MISMATCH', reason: `${label} manifest hash differs` };
    }
  }
  for (const field of PROVENANCE_FIELDS) {
    if (run1.provenance[field] !== run2.provenance[field]) {
      return { valid: false, status: 'PROVENANCE_MISMATCH', reason: `immutable provenance differs: ${field}` };
    }
  }
  return { valid: true };
}

function normalizeResults(results, ids) {
  return results.map((result, index) => {
    const candidate = result?.bm25Variant;
    if (!['APPLIED', 'EMPTY'].includes(candidate?.status)) {
      throw new Error(`case ${ids[index]} has non-evaluable variant status`);
    }
    const control = normalizePresence(candidate.controlSourcePresence, ids[index], 'control');
    const shadow = normalizePresence(candidate.shadowSourcePresence, ids[index], 'shadow');
    if (control.requiredGroupCount !== shadow.requiredGroupCount) {
      throw new Error(`case ${ids[index]} required group count differs`);
    }
    const hashes = normalizeHashes(candidate.variantHashes, ids[index]);
    const capture = normalizeCapture(candidate.capture, ids[index]);
    if (JSON.stringify(hashes) !== JSON.stringify(capture.hashes)) {
      throw new Error(`case ${ids[index]} capture hash differs`);
    }
    return { id: ids[index], status: candidate.status, hashes, control, shadow, hits: capture.hits };
  });
}

function normalizePresence(value, id, label) {
  const count = value?.requiredGroupCount;
  const source = value?.matchedRequiredGroupIndexes;
  if (!Number.isSafeInteger(count) || count <= 0 || !Array.isArray(source)) {
    throw new Error(`case ${id} has invalid ${label} presence`);
  }
  const indexes = [...source].sort((left, right) => left - right);
  if (indexes.length !== new Set(indexes).size
    || indexes.some((item) => !Number.isSafeInteger(item) || item < 0 || item >= count)) {
    throw new Error(`case ${id} has invalid ${label} group indexes`);
  }
  return { requiredGroupCount: count, indexes };
}

function normalizeHashes(values, id) {
  if (!Array.isArray(values) || values.length > 4
    || values.some((value) => !/^[0-9a-f]{64}$/.test(String(value ?? '')))
    || new Set(values).size !== values.length) {
    throw new Error(`case ${id} has invalid variant hashes`);
  }
  return values.slice();
}

function normalizeCapture(capture, id) {
  if (!capture || !Array.isArray(capture.hits)) throw new Error(`case ${id} has no variant capture`);
  return {
    hashes: normalizeHashes(capture.variantHashes, id),
    hits: capture.hits.map((hit, index) => ({
      candidateKey: String(hit?.candidateKey ?? ''),
      documentId: hit?.documentId,
      rank: hit?.rank,
      variantRanks: Object.fromEntries(Object.entries(hit?.variantRanks ?? {}).sort(([a], [b]) => a.localeCompare(b))),
      matchedAuditGroupIndexes: [...(hit?.matchedAuditGroupIndexes ?? [])],
    })).map((hit, index) => {
      if (!/^[a-z_]+:[1-9]\d*$/.test(hit.candidateKey)
        || !Number.isSafeInteger(hit.documentId) || hit.documentId <= 0 || hit.rank !== index + 1) {
        throw new Error(`case ${id} has invalid captured hit ${index}`);
      }
      return hit;
    }),
  };
}

function lostControlGroup(results) {
  for (const item of results) {
    const shadow = new Set(item.shadow.indexes);
    const lost = item.control.indexes.filter((index) => !shadow.has(index));
    if (lost.length > 0) return `case ${item.id} lost control groups ${lost.join(',')}`;
  }
  return null;
}

function summarize(results) {
  const presence = (key) => ({
    caseCount: results.length,
    allRequired: results.filter((item) => item[key].indexes.length === item[key].requiredGroupCount).length,
    anyRequired: results.filter((item) => item[key].indexes.length > 0).length,
    matchedGroups: results.reduce((sum, item) => sum + item[key].indexes.length, 0),
  });
  return { control: presence('control'), shadow: presence('shadow') };
}

function normalizePolicy(value) {
  if (!value || typeof value !== 'object') return null;
  const id = String(value.id ?? '').trim();
  const configHash = String(value.configHash ?? '').trim();
  return id && configHash ? { id, configHash } : null;
}

function sameIds(results, ids) {
  return Array.isArray(results) && results.length === ids.length
    && ids.every((id, index) => String(results[index]?.id ?? '') === id);
}

module.exports = { selectGroupBalancedBm25Policy };
