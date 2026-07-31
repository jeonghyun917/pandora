const crypto = require('crypto');

const REQUIRED_STRING_FIELDS = [
  'gitCommit',
  'runtimeArtifactSha256',
  'runtimeInstanceId',
  'runtimeConfigSha256',
  'indexRevision',
  'lexicalRevision',
  'datasetHash',
  'selectionHash',
];

function buildBaselineManifest({
  gitCommit,
  gitDirty,
  runtimeInfo,
  datasetHash,
  selectionHash,
  selectionCaseIds,
}) {
  const manifest = {
    schemaVersion: 1,
    gitCommit,
    gitDirty,
    runtimeArtifactSha256: runtimeInfo?.runtimeArtifactSha256,
    runtimeArtifactSize: runtimeInfo?.runtimeArtifactSize,
    runtimeInstanceId: runtimeInfo?.runtimeInstanceId,
    runtimeConfigSha256: runtimeInfo?.runtimeConfigSha256,
    indexRevision: runtimeInfo?.indexRevision,
    lexicalRevision: runtimeInfo?.lexicalRevision,
    datasetHash,
    selectionHash,
    selectionCaseIds,
    qdrantReady: runtimeInfo?.qdrantReady,
    qdrantSearchFailureCount: runtimeInfo?.qdrantSearchFailureCount,
  };
  validateBaselineManifest(manifest);
  return {
    ...manifest,
    manifestId: manifestId(manifest),
  };
}

function assertSameManifest(expected, actual) {
  assertManifestIntegrity(expected, 'expected');
  const difference = firstDifference(withoutManifestId(expected), withoutManifestId(actual));
  if (difference) {
    throw new Error(`baseline manifest mismatch: ${difference}`);
  }
  assertManifestIntegrity(actual, 'actual');
  return true;
}

function assertManifestIntegrity(manifest, label) {
  validateBaselineManifest(withoutManifestId(manifest));
  if (typeof manifest?.manifestId !== 'string' || !/^[0-9a-f]{64}$/.test(manifest.manifestId)) {
    throw new Error(`${label} baseline manifest has an invalid manifestId`);
  }
  if (manifest.manifestId !== manifestId(manifest)) {
    throw new Error(`${label} baseline manifest manifestId does not match its content`);
  }
}

function validateBaselineManifest(manifest) {
  for (const field of REQUIRED_STRING_FIELDS) {
    if (typeof manifest?.[field] !== 'string' || !manifest[field].trim()) {
      throw new Error(`baseline manifest requires ${field}`);
    }
  }
  if (typeof manifest.gitDirty !== 'boolean') {
    throw new Error('baseline manifest requires gitDirty');
  }
  if (!Number.isSafeInteger(Number(manifest.runtimeArtifactSize)) || Number(manifest.runtimeArtifactSize) <= 0) {
    throw new Error('baseline manifest requires a positive runtimeArtifactSize');
  }
  if (manifest.qdrantReady !== true) {
    throw new Error('baseline manifest requires qdrantReady');
  }
  if (!Number.isSafeInteger(Number(manifest.qdrantSearchFailureCount))
    || Number(manifest.qdrantSearchFailureCount) < 0) {
    throw new Error('baseline manifest requires qdrantSearchFailureCount');
  }
  if (!Array.isArray(manifest.selectionCaseIds) || manifest.selectionCaseIds.length === 0
    || manifest.selectionCaseIds.some((id) => typeof id !== 'string' || !id.trim())
    || new Set(manifest.selectionCaseIds).size !== manifest.selectionCaseIds.length) {
    throw new Error('baseline manifest requires unique selectionCaseIds');
  }
}

function assertManifestSelection(manifest, selectedCaseIds) {
  assertManifestIntegrity(manifest, 'expected');
  if (!Array.isArray(selectedCaseIds) || selectedCaseIds.length === 0
    || selectedCaseIds.some((id) => typeof id !== 'string' || !id.trim())) {
    throw new Error('evaluation requires selected case IDs');
  }
  const baselineUniverse = new Set(manifest.selectionCaseIds);
  const outside = selectedCaseIds.filter((id) => !baselineUniverse.has(id));
  if (outside.length > 0) {
    throw new Error(`evaluation selection is outside the baseline manifest universe: ${outside.join(', ')}`);
  }
  return true;
}

function manifestId(manifest) {
  return crypto.createHash('sha256').update(canonicalJson(withoutManifestId(manifest)), 'utf8').digest('hex');
}

function canonicalJson(value) {
  return JSON.stringify(sortKeys(value));
}

function sortKeys(value) {
  if (Array.isArray(value)) {
    return value.map(sortKeys);
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, sortKeys(value[key])]));
  }
  return value;
}

function withoutManifestId(manifest) {
  if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) {
    return manifest;
  }
  const { manifestId: ignoredManifestId, ...rest } = manifest;
  return rest;
}

function firstDifference(expected, actual, path = '') {
  if (Object.is(expected, actual)) {
    return null;
  }
  if (Array.isArray(expected) && Array.isArray(actual)) {
    if (expected.length !== actual.length) {
      return `${path || 'root'}.length`;
    }
    for (let index = 0; index < expected.length; index += 1) {
      const difference = firstDifference(expected[index], actual[index], `${path}[${index}]`);
      if (difference) {
        return difference;
      }
    }
    return null;
  }
  if (isPlainObject(expected) && isPlainObject(actual)) {
    const keys = Array.from(new Set([...Object.keys(expected), ...Object.keys(actual)])).sort();
    for (const key of keys) {
      if (!Object.hasOwn(expected, key) || !Object.hasOwn(actual, key)) {
        return path ? `${path}.${key}` : key;
      }
      const difference = firstDifference(expected[key], actual[key], path ? `${path}.${key}` : key);
      if (difference) {
        return difference;
      }
    }
    return null;
  }
  return path || 'root';
}

function isPlainObject(value) {
  return value && typeof value === 'object' && !Array.isArray(value);
}

module.exports = {
  assertManifestSelection,
  assertSameManifest,
  buildBaselineManifest,
  canonicalJson,
};
