const crypto = require('node:crypto');
const fs = require('node:fs');

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

module.exports = {
  loadTrainingManifest,
  sha256Bytes,
};
