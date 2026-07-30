const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

function determineRunScope(selectedCases, allCases, caseIds, caseLimit) {
  const explicitlyTargeted = (caseIds?.length ?? 0) > 0 || Number(caseLimit ?? 0) > 0;
  return !explicitlyTargeted && selectedCases.length === allCases.length ? 'full' : 'targeted';
}

function resolveReportPaths(scope, env = process.env) {
  const safeScope = scope === 'full' ? 'full' : 'targeted';
  const reportPaths = {
    outputPath: env.RAG_EVAL_OUTPUT || `logs/rag-eval-gate-${safeScope}-latest.json`,
    reportPath: env.RAG_EVAL_REPORT || `logs/rag-eval-gate-${safeScope}-latest.md`,
    checkpointPath: env.RAG_EVAL_CHECKPOINT || `logs/rag-eval-gate-${safeScope}-checkpoint.json`,
  };
  if (safeScope === 'targeted') {
    const reservedFullNames = new Set([
      'rag-eval-gate-latest.json',
      'rag-eval-gate-latest.md',
      'rag-eval-gate-checkpoint.json',
      'rag-eval-gate-full-latest.json',
      'rag-eval-gate-full-latest.md',
      'rag-eval-gate-full-checkpoint.json',
      'rag-eval-gate-full-latest.checkpoint.json',
    ]);
    for (const reportPath of Object.values(reportPaths)) {
      if (reservedFullNames.has(path.basename(path.normalize(reportPath)).toLowerCase())) {
        throw new Error(`targeted run cannot use reserved full-run path: ${reportPath}`);
      }
    }
  }
  return reportPaths;
}

function datasetHash(filePaths) {
  const hash = crypto.createHash('sha256');
  for (const filePath of [...filePaths].sort()) {
    hash.update(path.basename(filePath));
    hash.update('\0');
    hash.update(fs.readFileSync(filePath));
    hash.update('\0');
  }
  return hash.digest('hex');
}

function selectionHash(cases) {
  const hash = crypto.createHash('sha256');
  for (const item of cases) {
    hash.update(String(item.id ?? ''));
    hash.update('\0');
    hash.update(String(item.question ?? ''));
    hash.update('\0');
  }
  return hash.digest('hex');
}

function buildCheckpointIdentity({
  scope,
  baseUrl,
  datasetHashValue,
  selectionHashValue,
  selectedCount,
  runtimeInfo,
}) {
  return {
    schemaVersion: 2,
    runScope: scope,
    baseUrl: String(baseUrl ?? '').replace(/\/$/, ''),
    datasetHash: datasetHashValue || null,
    selectionHash: selectionHashValue || null,
    selectedCaseCount: Number(selectedCount ?? 0),
    indexVersion: runtimeInfo?.indexVersion || null,
    embeddingModel: runtimeInfo?.embeddingModel || null,
    answerModel: runtimeInfo?.answerModel || null,
    lawCollection: runtimeInfo?.lawCollection || null,
    ragCollection: runtimeInfo?.ragCollection || null,
    runtimeInfoSource: runtimeInfo?.source || 'unavailable',
    runtimeArtifactSha256: runtimeInfo?.runtimeArtifactSha256
      ? String(runtimeInfo.runtimeArtifactSha256).toLowerCase()
      : null,
    runtimeInstanceId: runtimeInfo?.runtimeInstanceId || null,
    runtimeConfigSha256: runtimeInfo?.runtimeConfigSha256 || null,
    indexRevision: runtimeInfo?.indexRevision || null,
    qdrantReady: runtimeInfo?.qdrantReady === true,
    qdrantSearchFailureCount: normalizeFailureCount(runtimeInfo?.qdrantSearchFailureCount),
  };
}

function normalizeFailureCount(value) {
  if (value == null) {
    return null;
  }
  const count = Number(value);
  return Number.isSafeInteger(count) && count >= 0 ? count : null;
}

function isCheckpointCompatible(checkpoint, expectedIdentity) {
  const actualIdentity = checkpoint?.checkpointIdentity;
  if (!actualIdentity || !expectedIdentity) {
    return false;
  }
  const requiredValues = [
    'runtimeArtifactSha256',
    'runtimeInstanceId',
    'runtimeConfigSha256',
    'indexRevision',
  ];
  if (actualIdentity.runtimeInfoSource !== 'server'
    || expectedIdentity.runtimeInfoSource !== 'server'
    || actualIdentity.qdrantReady !== true
    || expectedIdentity.qdrantReady !== true
    || requiredValues.some((key) => !actualIdentity[key] || !expectedIdentity[key])) {
    return false;
  }
  if (normalizeFailureCount(actualIdentity.qdrantSearchFailureCount) == null
    || normalizeFailureCount(expectedIdentity.qdrantSearchFailureCount) == null) {
    return false;
  }
  const requiredKeys = [
    'schemaVersion',
    'runScope',
    'baseUrl',
    'datasetHash',
    'selectionHash',
    'selectedCaseCount',
    'indexVersion',
    'embeddingModel',
    'answerModel',
    'lawCollection',
    'ragCollection',
    'runtimeInfoSource',
    'runtimeArtifactSha256',
    'runtimeInstanceId',
    'runtimeConfigSha256',
    'indexRevision',
    'qdrantReady',
    'qdrantSearchFailureCount',
  ];
  return requiredKeys.every((key) => actualIdentity[key] === expectedIdentity[key]);
}

function isRuntimeStable(startRuntimeInfo, endRuntimeInfo) {
  if (startRuntimeInfo?.source !== 'server' || endRuntimeInfo?.source !== 'server') {
    return false;
  }
  const requiredValues = ['runtimeArtifactSha256', 'runtimeInstanceId', 'runtimeConfigSha256'];
  if (requiredValues.some((key) => !startRuntimeInfo[key] || !endRuntimeInfo[key])) {
    return false;
  }
  if (startRuntimeInfo.qdrantReady !== true || endRuntimeInfo.qdrantReady !== true) {
    return false;
  }
  if (normalizeFailureCount(startRuntimeInfo.qdrantSearchFailureCount) == null
    || normalizeFailureCount(endRuntimeInfo.qdrantSearchFailureCount) == null) {
    return false;
  }
  const stableKeys = [
    'indexVersion',
    'embeddingModel',
    'answerModel',
    'lawCollection',
    'ragCollection',
    'runtimeArtifactKind',
    'runtimeArtifactSha256',
    'runtimeArtifactSize',
    'runtimeInstanceId',
    'runtimeConfigSha256',
    'indexRevision',
    'qdrantReady',
    'qdrantSearchFailureCount',
  ];
  return stableKeys.every((key) => startRuntimeInfo[key] === endRuntimeInfo[key]);
}

function assertEvaluationRuntimeReady(runtimeInfo, scope = 'targeted') {
  if (runtimeInfo?.source !== 'server') {
    throw new Error('server runtime information is unavailable');
  }
  if (runtimeInfo.qdrantReady !== true) {
    throw new Error('Qdrant search is not ready');
  }
  if (normalizeFailureCount(runtimeInfo.qdrantSearchFailureCount) == null) {
    throw new Error('Qdrant search failure counter is unavailable');
  }
  if (scope === 'full'
    && (typeof runtimeInfo.indexRevision !== 'string' || !runtimeInfo.indexRevision.trim())) {
    throw new Error('full gate requires a dynamic index revision');
  }
}

function evaluationBreakdown(results = []) {
  const summarize = (rows) => ({
    total: rows.length,
    passed: rows.filter((row) => row.passed).length,
    failed: rows.filter((row) => !row.passed).length,
    passRate: rows.length === 0 ? 0 : rows.filter((row) => row.passed).length / rows.length,
  });
  const curated = results.filter((row) => !String(row.id ?? '').startsWith('gen-'));
  const generated = results.filter((row) => String(row.id ?? '').startsWith('gen-'));
  const answerRequired = results.filter((row) => row.answerVerificationRequired === true);
  return {
    curated: summarize(curated),
    generated: summarize(generated),
    answerVerification: {
      required: answerRequired.length,
      passed: answerRequired.filter((row) => row.answerVerified === true).length,
      failed: answerRequired.filter((row) => row.answerVerified !== true).length,
      unsupportedClaimCases: answerRequired.filter((row) => (row.unsupportedAnswerClaims ?? []).length > 0).length,
    },
  };
}

function buildProvenance({
  scope,
  baseUrl,
  gitCommit,
  gitDirty,
  datasetHashValue,
  selectionHashValue,
  selectedCount,
  totalCaseCount,
  runtimeInfo,
  generatedAt = new Date().toISOString(),
}) {
  const parsedUrl = new URL(baseUrl);
  const defaultPort = parsedUrl.protocol === 'https:' ? '443' : '80';
  return {
    schemaVersion: 3,
    runScope: scope,
    generatedAt,
    baseUrl,
    executionPort: Number(parsedUrl.port || defaultPort),
    gitCommit: gitCommit || null,
    gitDirty: Boolean(gitDirty),
    datasetHash: datasetHashValue,
    selectionHash: selectionHashValue,
    selectedCaseCount: selectedCount,
    totalCaseCount,
    indexVersion: runtimeInfo?.indexVersion || null,
    embeddingModel: runtimeInfo?.embeddingModel || null,
    answerModel: runtimeInfo?.answerModel || null,
    lawCollection: runtimeInfo?.lawCollection || null,
    ragCollection: runtimeInfo?.ragCollection || null,
    runtimeArtifactKind: runtimeInfo?.runtimeArtifactKind || null,
    runtimeArtifactSha256: runtimeInfo?.runtimeArtifactSha256 || null,
    runtimeArtifactSize: runtimeInfo?.runtimeArtifactSize != null
      && Number.isFinite(Number(runtimeInfo.runtimeArtifactSize))
      ? Number(runtimeInfo.runtimeArtifactSize)
      : null,
    runtimeInstanceId: runtimeInfo?.runtimeInstanceId || null,
    runtimeConfigSha256: runtimeInfo?.runtimeConfigSha256 || null,
    indexRevision: runtimeInfo?.indexRevision || null,
    qdrantReady: runtimeInfo?.qdrantReady === true,
    qdrantSearchFailureCount: normalizeFailureCount(runtimeInfo?.qdrantSearchFailureCount),
    runtimeInfoSource: runtimeInfo?.source || 'unavailable',
  };
}

function archivePaths(scope, runId) {
  return {
    outputPath: `logs/rag-eval-gate-${scope}-${runId}.json`,
    reportPath: `logs/rag-eval-gate-${scope}-${runId}.md`,
  };
}

module.exports = {
  assertEvaluationRuntimeReady,
  archivePaths,
  buildCheckpointIdentity,
  buildProvenance,
  datasetHash,
  determineRunScope,
  evaluationBreakdown,
  isCheckpointCompatible,
  isRuntimeStable,
  resolveReportPaths,
  selectionHash,
};
