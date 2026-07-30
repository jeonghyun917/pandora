const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const {
  loadEvalCases,
  selectEvalCases,
  splitCaseIds,
} = require('./lib/rag-eval-cases');
const {
  archivePaths,
  assertEvaluationRuntimeReady,
  buildCheckpointIdentity,
  buildProvenance,
  datasetHash,
  determineRunScope,
  evaluationBreakdown,
  isCheckpointCompatible,
  isRuntimeStable,
  resolveReportPaths,
  selectionHash,
} = require('./lib/rag-eval-provenance');
const {
  assertSameManifest,
  buildBaselineManifest,
} = require('./lib/rag-baseline-manifest');
const { buildBlockingGates } = require('./lib/rag-eval-gates');

const baseUrl = process.env.RAG_EVAL_BASE_URL || 'http://127.0.0.1:8080';
const endpoint = `${baseUrl.replace(/\/$/, '')}/api/law-data/ai/debug/evaluate/gate`;
const runtimeInfoEndpoint = `${baseUrl.replace(/\/$/, '')}/api/law-data/ai/debug/runtime-info`;
const casePaths = [
  path.resolve('src/main/resources/rag-evaluation-cases.tsv'),
  path.resolve('src/main/resources/rag-evaluation-cases.generated.tsv'),
];
const answerOraclePath = path.resolve('src/main/resources/rag-answer-evaluation-oracles.tsv');
const datasetPaths = [...casePaths, answerOraclePath];
const maxEvaluationErrorRetries = Number(process.env.RAG_EVAL_ERROR_RETRIES || 3);
const caseBatchSize = Number(process.env.RAG_EVAL_CASE_BATCH_SIZE || 10);
const requestTimeoutMs = Number(process.env.RAG_EVAL_REQUEST_TIMEOUT_MS || 180000);
const interBatchSleepMs = Number(process.env.RAG_EVAL_INTER_BATCH_SLEEP_MS || 300);
const caseLimit = Number(process.env.RAG_EVAL_CASE_LIMIT || 0);
const caseIds = splitCaseIds(process.env.RAG_EVAL_CASE_IDS || '');
const gateProfile = resolveGateProfile(process.env.RAG_EVAL_GATE_PROFILE);
let checkpointPath = process.env.RAG_EVAL_CHECKPOINT || 'logs/rag-eval-gate-targeted-checkpoint.json';
const resumeFromCheckpoint = ['1', 'true', 'yes', 'y'].includes(
  String(process.env.RAG_EVAL_RESUME || '').trim().toLowerCase(),
);
const baselineManifestPath = String(process.env.RAG_EVAL_BASELINE_MANIFEST || '').trim();

async function main() {
  const allCases = loadCases();
  const cases = selectCases(allCases);
  const scope = determineRunScope(cases, allCases, caseIds, caseLimit);
  const reportPaths = resolveReportPaths(scope);
  checkpointPath = reportPaths.checkpointPath;
  const suppliedBaselineManifest = loadBaselineManifest();
  const runtimeInfo = await loadRuntimeInfo();
  assertEvaluationRuntimeReady(runtimeInfo, scope);
  const datasetHashValue = datasetHash(datasetPaths.filter((casePath) => fs.existsSync(casePath)));
  const selectionHashValue = selectionHash(cases);
  if (suppliedBaselineManifest) {
    assertSameManifest(suppliedBaselineManifest, buildCurrentBaselineManifest(
      runtimeInfo,
      datasetHashValue,
      selectionHashValue,
    ));
  }
  const checkpointIdentity = buildCheckpointIdentity({
    scope,
    baseUrl,
    datasetHashValue,
    selectionHashValue,
    selectedCount: cases.length,
    gateProfile,
    runtimeInfo,
  });
  if (resumeFromCheckpoint && !checkpointIdentity.indexRevision) {
    console.warn('[rag-eval-gate] resume disabled: server index revision is unavailable');
  }
  const usesBatchCheckpoint = caseBatchSize > 0 && cases.length > caseBatchSize;
  let body = await runEvaluationForCases(cases, checkpointIdentity);
  body = await retryEvaluationErrors(body);
  const finalRuntimeInfo = await loadRuntimeInfo();
  if (!isRuntimeStable(runtimeInfo, finalRuntimeInfo)) {
    throw new Error('[rag-eval-gate] runtime identity changed or became unavailable during evaluation');
  }
  if (suppliedBaselineManifest) {
    assertSameManifest(suppliedBaselineManifest, buildCurrentBaselineManifest(
      finalRuntimeInfo,
      datasetHashValue,
      selectionHashValue,
    ));
  }
  if (usesBatchCheckpoint) {
    writeJson(checkpointPath, recomputeGate({
      ...body,
      checkpoint: true,
      checkpointIdentity,
      checkpointUpdatedAt: new Date().toISOString(),
    }));
  }
  body = {
    ...body,
    provenance: buildProvenance({
      scope,
      baseUrl,
      gitCommit: gitOutput(['rev-parse', 'HEAD']),
      gitDirty: Boolean(gitOutput(['status', '--porcelain'])),
      datasetHashValue,
      selectionHashValue,
      selectedCount: cases.length,
      totalCaseCount: allCases.length,
      gateProfile,
      runtimeInfo,
      baselineManifestId: suppliedBaselineManifest?.manifestId || null,
    }),
    breakdown: evaluationBreakdown(body.results ?? []),
  };
  writeJson(reportPaths.outputPath, body);
  writeReport(reportPaths.reportPath, body, baseUrl);
  if (archiveEnabled()) {
    const archive = archivePaths(scope, runId(body.provenance.generatedAt));
    writeJson(archive.outputPath, body);
    writeReport(archive.reportPath, body, baseUrl);
  }
  if (!body?.gatePassed) {
    const passed = body?.passed ?? 0;
    const total = body?.total ?? 0;
    const blocking = body?.blockingFailureIds ?? [];
    console.error(`[rag-eval-gate] FAIL ${passed}/${total}`);
    for (const id of blocking) {
      console.error(`- ${id}`);
    }
    process.exit(1);
  }
  const percent = Math.round((body.passRate ?? 0) * 100);
  console.log(`[rag-eval-gate] PASS ${body.passed}/${body.total} (${percent}%)`);
}

async function loadRuntimeInfo() {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 5000);
  try {
    const response = await fetch(runtimeInfoEndpoint, { signal: controller.signal });
    if (response.ok) {
      return { ...(await response.json()), source: 'server' };
    }
  } catch (error) {
    console.warn(`[rag-eval-gate] runtime provenance unavailable: ${error?.message ?? error}`);
  } finally {
    clearTimeout(timer);
  }
  const environmentInfo = {
    indexVersion: process.env.RAG_EVAL_INDEX_VERSION || null,
    embeddingModel: process.env.RAG_EVAL_EMBEDDING_MODEL || null,
    answerModel: process.env.RAG_EVAL_ANSWER_MODEL || null,
    lawCollection: process.env.RAG_EVAL_LAW_COLLECTION || null,
    ragCollection: process.env.RAG_EVAL_RAG_COLLECTION || null,
    runtimeArtifactKind: process.env.RAG_EVAL_RUNTIME_ARTIFACT_KIND || null,
    runtimeArtifactSha256: process.env.RAG_EVAL_RUNTIME_ARTIFACT_SHA256 || null,
    runtimeArtifactSize: process.env.RAG_EVAL_RUNTIME_ARTIFACT_SIZE || null,
    runtimeInstanceId: process.env.RAG_EVAL_RUNTIME_INSTANCE_ID || null,
    runtimeConfigSha256: process.env.RAG_EVAL_RUNTIME_CONFIG_SHA256 || null,
    indexRevision: process.env.RAG_EVAL_INDEX_REVISION || null,
    lexicalRevision: process.env.RAG_EVAL_LEXICAL_REVISION || null,
    qdrantReady: false,
    qdrantSearchFailureCount: null,
  };
  return {
    ...environmentInfo,
    source: Object.values(environmentInfo).some(Boolean) ? 'environment' : 'unavailable',
  };
}

function gitOutput(args) {
  try {
    return execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
  } catch {
    return '';
  }
}

function archiveEnabled() {
  return !['0', 'false', 'no', 'n'].includes(String(process.env.RAG_EVAL_ARCHIVE ?? 'true').trim().toLowerCase());
}

function runId(isoTimestamp) {
  return String(isoTimestamp)
    .replace(/[-:]/g, '')
    .replace('T', '-')
    .replace(/\.(\d{3})Z$/, '-$1Z');
}

async function runEvaluationForCases(cases, expectedCheckpointIdentity = null) {
  if (!cases.length) {
    return recomputeGate({ results: [] });
  }
  if (caseBatchSize <= 0 || cases.length <= caseBatchSize) {
    return runEvaluation({ cases }, "all");
  }
  const batches = chunk(cases, caseBatchSize);
  const selectedIds = new Set(cases.map((item) => item.id));
  const checkpoint = resumeFromCheckpoint && expectedCheckpointIdentity ? readJson(checkpointPath) : null;
  const checkpointCompatible = isCheckpointCompatible(checkpoint, expectedCheckpointIdentity);
  if (checkpoint && !checkpointCompatible) {
    console.warn('[rag-eval-gate] ignoring incompatible checkpoint');
  }
  if (checkpointCompatible) {
    assertResultIds(Array.from(selectedIds), checkpoint?.results, 'checkpoint', { allowMissing: true });
  }
  const results = checkpointCompatible
    ? restoreCaseClassification(checkpoint.results, cases)
    : [];
  const completedIds = new Set(results.map((result) => result.id));
  const attempts = [];
  for (let index = 0; index < batches.length; index += 1) {
    const batch = batches[index].filter((item) => !completedIds.has(item.id));
    const batchLabel = `batch ${index + 1}/${batches.length}`;
    if (batch.length === 0) {
      console.log(`[rag-eval-gate] skipping ${batchLabel} from checkpoint`);
      continue;
    }
    console.log(`[rag-eval-gate] running ${batchLabel} (${batch.length} cases)`);
    const body = await runEvaluation({ cases: batch }, batchLabel);
    attempts.push({
      batch: index + 1,
      total: body.total ?? batch.length,
      passed: body.passed ?? 0,
      failed: body.failed ?? Math.max(0, (body.total ?? batch.length) - (body.passed ?? 0)),
    });
    for (const result of body.results ?? []) {
      results.push(result);
      completedIds.add(result.id);
    }
    if (expectedCheckpointIdentity) {
      writeJson(checkpointPath, recomputeGate({
        results,
        batchAttempts: attempts,
        batchSize: caseBatchSize,
        selectedCaseLimit: caseLimit > 0 ? caseLimit : null,
        selectedCaseIds: caseIds,
        checkpoint: true,
        checkpointIdentity: expectedCheckpointIdentity,
        checkpointUpdatedAt: new Date().toISOString(),
      }));
    }
    if (index < batches.length - 1 && interBatchSleepMs > 0) {
      await sleep(interBatchSleepMs);
    }
  }
  const combined = {
    results,
    batchAttempts: attempts,
    batchSize: caseBatchSize,
    selectedCaseLimit: caseLimit > 0 ? caseLimit : null,
    selectedCaseIds: caseIds,
  };
  assertResultIds(cases.map((item) => item.id), combined.results, 'combined evaluation');
  return recomputeGate(combined);
}

async function runEvaluation(payload, label = "all") {
  const requestedCases = Array.isArray(payload?.cases) ? payload.cases : loadCases();
  const requestedIds = requestedCases.map((item) => item.id);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), requestTimeoutMs);
  try {
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(payload),
      signal: controller.signal,
    });
    const text = await response.text();
    const body = parseJson(text);
    if (!response.ok) {
      if (response.status === 409 && isCompletedFailingEvaluationResponse(body)) {
        assertResultIds(requestedIds, body.results, label);
        return { ...body, results: restoreCaseClassification(body.results, requestedCases) };
      }
      const details = body
        ? JSON.stringify({
          status: body.status ?? response.status,
          error: body.error,
          message: body.message,
          path: body.path,
        })
        : text.slice(0, 300);
      throw new Error(`evaluation ${label} HTTP ${response.status} ${response.statusText}: ${details}`);
    }
    if (!body) {
      throw new Error(`evaluation ${label} HTTP ${response.status} ${response.statusText}: ${text.slice(0, 300)}`);
    }
    assertResultIds(requestedIds, body.results, label);
    return { ...body, results: restoreCaseClassification(body.results, requestedCases) };
  } catch (error) {
    if (error?.name === "AbortError") {
      throw new Error(`evaluation ${label} timed out after ${requestTimeoutMs}ms`);
    }
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

function loadBaselineManifest() {
  if (!baselineManifestPath) {
    return null;
  }
  let parsed;
  try {
    parsed = JSON.parse(fs.readFileSync(baselineManifestPath, 'utf8'));
  } catch (error) {
    throw new Error(`[rag-eval-gate] unable to load baseline manifest: ${error?.message ?? error}`);
  }
  return parsed;
}

function buildCurrentBaselineManifest(runtimeInfo, datasetHashValue, selectionHashValue) {
  return buildBaselineManifest({
    gitCommit: gitOutput(['rev-parse', 'HEAD']),
    gitDirty: Boolean(gitOutput(['status', '--porcelain'])),
    runtimeInfo,
    datasetHash: datasetHashValue,
    selectionHash: selectionHashValue,
  });
}

function restoreCaseClassification(results, cases) {
  const casesById = new Map((cases ?? []).map((item) => [item.id, item]));
  return (results ?? []).map((result) => {
    const evalCase = casesById.get(result.id);
    return {
      ...result,
      expectedResultMsgs: [...(evalCase?.expectedResultMsgs ?? [])],
      answerVerificationRequired: evalCase?.answerVerificationRequired,
    };
  });
}

function assertResultIds(requestedIds, results, label, { allowMissing = false } = {}) {
  if (!Array.isArray(results)) {
    throw new Error(`evaluation ${label} response ID mismatch: results is not an array`);
  }
  const invalidRequestIndexes = invalidIdIndexes(requestedIds);
  const responseIds = results.map((result) => result?.id);
  const invalidResponseIndexes = invalidIdIndexes(responseIds);
  const validRequestedIds = requestedIds.filter(isValidId);
  const validResponseIds = responseIds.filter(isValidId);
  const requestedSet = new Set(validRequestedIds);
  const responseSet = new Set(validResponseIds);
  const duplicateRequestIds = duplicateIds(validRequestedIds);
  const duplicateResponseIds = duplicateIds(validResponseIds);
  const missingResponseIds = validRequestedIds.filter((id) => !responseSet.has(id));
  const unexpectedResponseIds = validResponseIds.filter((id) => !requestedSet.has(id));
  const problems = [];
  if (invalidRequestIndexes.length > 0) {
    problems.push(`invalid request ID indexes=[${invalidRequestIndexes.join(', ')}]`);
  }
  if (duplicateRequestIds.length > 0) {
    problems.push(`duplicate request IDs=[${duplicateRequestIds.join(', ')}]`);
  }
  if (invalidResponseIndexes.length > 0) {
    problems.push(`invalid response ID indexes=[${invalidResponseIndexes.join(', ')}]`);
  }
  if (duplicateResponseIds.length > 0) {
    problems.push(`duplicate response IDs=[${duplicateResponseIds.join(', ')}]`);
  }
  if (!allowMissing && missingResponseIds.length > 0) {
    problems.push(`missing response IDs=[${missingResponseIds.join(', ')}]`);
  }
  if (unexpectedResponseIds.length > 0) {
    problems.push(`unexpected response IDs=[${unexpectedResponseIds.join(', ')}]`);
  }
  if (problems.length > 0) {
    throw new Error(`evaluation ${label} response ID mismatch: ${problems.join('; ')}`);
  }
}

function isValidId(id) {
  return typeof id === 'string' && id.length > 0;
}

function invalidIdIndexes(ids) {
  return ids.flatMap((id, index) => (isValidId(id) ? [] : [index]));
}

function duplicateIds(ids) {
  const seen = new Set();
  const duplicates = new Set();
  for (const id of ids) {
    if (seen.has(id)) {
      duplicates.add(id);
    }
    seen.add(id);
  }
  return Array.from(duplicates);
}

async function retryEvaluationErrors(body) {
  if (!body?.results?.length || maxEvaluationErrorRetries <= 0) {
    return recomputeGate(body);
  }
  const casesById = new Map(loadCases().map((row) => [row.id, row]));
  let merged = body;
  const attempts = [];
  for (let attempt = 1; attempt <= maxEvaluationErrorRetries; attempt += 1) {
    const retryIds = (merged.results ?? [])
      .filter((result) => !result.passed && result.resultMsg === 'EVALUATION_ERROR')
      .map((result) => result.id);
    if (retryIds.length === 0) {
      break;
    }
    const retryCases = retryIds.map((id) => casesById.get(id)).filter(Boolean);
    if (retryCases.length === 0) {
      break;
    }
    await sleep(500 * attempt);
    const retryBody = await runEvaluationForCases(retryCases);
    attempts.push({
      attempt,
      ids: retryIds,
      passed: retryBody.passed ?? 0,
      total: retryBody.total ?? retryCases.length,
    });
    merged = mergeResults(merged, retryBody.results ?? []);
  }
  return recomputeGate({ ...merged, retryAttempts: attempts });
}

function mergeResults(body, retryResults) {
  const retryById = new Map(retryResults.map((result) => [result.id, result]));
  const results = (body.results ?? []).map((result) => retryById.get(result.id) ?? result);
  return { ...body, results };
}

function recomputeGate(body) {
  const results = body?.results ?? [];
  const total = results.length;
  const passed = results.filter((result) => result.passed === true).length;
  const failed = total - passed;
  const blockingGates = buildBlockingGates(results);
  const namedGatesPassed = Object.values(blockingGates)
    .filter((gate) => gate.total > 0)
    .every((gate) => gate.gatePassed);
  return {
    ...body,
    total,
    passed,
    failed,
    passRate: total === 0 ? 0 : passed / total,
    gatePassed: total > 0 && failed === 0 && namedGatesPassed,
    minimumPassed: total,
    blockingFailureIds: results.filter((result) => result.passed !== true).map((result) => result.id),
    blockingGates,
  };
}

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function readJson(filePath) {
  if (!fs.existsSync(filePath)) {
    return null;
  }
  return parseJson(fs.readFileSync(filePath, 'utf8'));
}

function isEvaluationResponse(body) {
  return Boolean(
    body
    && Array.isArray(body.results)
    && typeof body.total === 'number'
    && typeof body.passed === 'number'
    && typeof body.failed === 'number'
    && typeof body.gatePassed === 'boolean'
  );
}

function isCompletedFailingEvaluationResponse(body) {
  if (!isEvaluationResponse(body) || body.gatePassed !== false) {
    return false;
  }
  return body.results.some((result) => result.passed !== true);
}

function loadCases() {
  return loadEvalCases(casePaths, { answerOraclePath });
}

function selectCases(cases) {
  return selectEvalCases(filterCasesByGateProfile(cases, gateProfile), { caseIds, caseLimit });
}

function resolveGateProfile(value) {
  const profile = String(value ?? 'release').trim().toLowerCase() || 'release';
  if (!['release', 'curated', 'answer-oracle', 'no-grounds'].includes(profile)) {
    throw new Error(`unsupported RAG_EVAL_GATE_PROFILE: ${profile}`);
  }
  return profile;
}

function filterCasesByGateProfile(cases, profile) {
  if (profile === 'release') {
    return cases;
  }
  if (profile === 'curated') {
    return cases.filter((item) => !String(item.id ?? '').startsWith('gen-'));
  }
  if (profile === 'answer-oracle') {
    return cases.filter((item) => item.answerVerificationRequired === true);
  }
  return cases.filter((item) =>
    (item.expectedResultMsgs ?? []).includes('NO_GROUNDS')
      || String(item.id ?? '').startsWith('no-'));
}

function chunk(values, size) {
  const result = [];
  for (let index = 0; index < values.length; index += size) {
    result.push(values.slice(index, index + size));
  }
  return result;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function writeJson(filePath, body) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(body, null, 2)}\n`, 'utf8');
}

function writeReport(filePath, body, baseUrl) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  const failures = (body.results ?? []).filter((result) => !result.passed);
  const diagnostics = failures.map((result) => ({
    ...diagnoseFailureReadable(result),
    id: result.id,
  }));
  const diagnosticCounts = diagnostics.reduce((counts, item) => {
    counts[item.cause] = (counts[item.cause] ?? 0) + 1;
    return counts;
  }, {});
  const rows = failures.map((result) => {
    const diagnosis = diagnoseFailureReadable(result);
    return [
      result.id,
      result.resultMsg,
      diagnosis.label,
      nextActionForCause(diagnosis.cause),
      list(result.missingTerms),
      list(result.missingTitleTerms),
      list(result.missingSectionTypes),
      list(result.missingDocumentTerms),
      list(result.missingParentTerms),
      list(result.forbiddenMatchedTerms),
      list(result.missingAnswerTerms),
      list(result.unsupportedAnswerClaims),
      selectedSummary(result),
    ];
  });
  const lines = [
    '# RAG Eval Gate',
    '',
    `- Scope: ${body.provenance?.runScope ?? 'unknown'}`,
    `- Generated at: ${body.provenance?.generatedAt ?? '-'}`,
    `- Workspace Git commit: ${body.provenance?.gitCommit ?? '-'}`,
    `- Workspace Git dirty: ${Boolean(body.provenance?.gitDirty)}`,
    `- Dataset hash: ${body.provenance?.datasetHash ?? '-'}`,
    `- Selection hash: ${body.provenance?.selectionHash ?? '-'}`,
    `- Index version: ${body.provenance?.indexVersion ?? '-'}`,
    `- Embedding model: ${body.provenance?.embeddingModel ?? '-'}`,
    `- Answer model: ${body.provenance?.answerModel ?? '-'}`,
    `- Runtime artifact: ${body.provenance?.runtimeArtifactKind ?? '-'}`,
    `- Runtime artifact SHA-256: ${body.provenance?.runtimeArtifactSha256 ?? '-'}`,
    `- Runtime artifact size: ${body.provenance?.runtimeArtifactSize ?? '-'}`,
    `- Runtime instance ID: ${body.provenance?.runtimeInstanceId ?? '-'}`,
    `- Runtime config SHA-256: ${body.provenance?.runtimeConfigSha256 ?? '-'}`,
    `- Index revision: ${body.provenance?.indexRevision ?? '-'}`,
    `- Lexical revision: ${body.provenance?.lexicalRevision ?? '-'}`,
    `- Baseline manifest ID: ${body.provenance?.baselineManifestId ?? '-'}`,
    `- Qdrant ready: ${body.provenance?.qdrantReady === true}`,
    `- Qdrant search failures at start: ${body.provenance?.qdrantSearchFailureCount ?? '-'}`,
    `- Execution port: ${body.provenance?.executionPort ?? '-'}`,
    `- Base URL: ${baseUrl}`,
    `- Total: ${body.total ?? 0}`,
    `- Passed: ${body.passed ?? 0}`,
    `- Failed: ${body.failed ?? 0}`,
    `- Pass rate: ${Math.round((body.passRate ?? 0) * 100)}%`,
    `- Gate passed: ${Boolean(body.gatePassed)}`,
    `- Batch size: ${body.batchSize ?? 'single request'}`,
    `- Failure causes: ${Object.keys(diagnosticCounts).length ? Object.entries(diagnosticCounts).map(([cause, count]) => `${cause}=${count}`).join(', ') : '-'}`,
    `- Curated: ${body.breakdown?.curated?.passed ?? 0}/${body.breakdown?.curated?.total ?? 0}`,
    `- Generated: ${body.breakdown?.generated?.passed ?? 0}/${body.breakdown?.generated?.total ?? 0}`,
    `- Answer verification: ${body.breakdown?.answerVerification?.passed ?? 0}/${body.breakdown?.answerVerification?.required ?? 0}`,
    '',
    '| ID | Result | Likely Cause | Next Action | Missing Terms | Missing Title | Missing Section | Missing Doc | Missing Parent | Forbidden | Missing Answer | Unsupported Claims | Top Selected |',
    '|---|---:|---|---|---|---|---|---|---|---|---|---|---|',
    ...rows.map((row) => `| ${row.map(escapeCell).join(' | ')} |`),
    '',
  ];
  fs.writeFileSync(filePath, `${lines.join('\n')}\n`, 'utf8');
}

function diagnoseFailureReadable(result) {
  const selected = result?.selected ?? [];
  const resultMsg = result?.resultMsg ?? '';
  if (!result) {
    return { cause: 'invalid_result', label: 'Invalid result' };
  }
  if (!selected.length && resultMsg === 'NO_GROUNDS') {
    return { cause: 'no_grounds', label: 'No direct grounds' };
  }
  if (!selected.length) {
    return { cause: 'retrieval_empty', label: 'No selected candidates' };
  }
  if (hasValues(result.forbiddenMatchedTerms)) {
    return { cause: 'forbidden_evidence', label: 'Forbidden evidence selected' };
  }
  if (result.answerVerificationRequired && !result.answerVerified) {
    return { cause: 'answer_verification', label: 'Answer not grounded' };
  }
  if (hasValues(result.missingDocumentTerms) || hasValues(result.missingTitleTerms)) {
    return { cause: 'wrong_document', label: 'Wrong document or title' };
  }
  if (hasValues(result.missingSectionTypes) || hasValues(result.missingParentTerms)) {
    return { cause: 'wrong_section', label: 'Wrong section or parent context' };
  }
  if (hasValues(result.missingPageNumbers)) {
    return { cause: 'wrong_page', label: 'Wrong page' };
  }
  if (hasValues(result.missingTerms)) {
    return { cause: 'partial_evidence', label: 'Partial direct evidence' };
  }
  if (resultMsg && resultMsg !== 'OK') {
    return { cause: 'result_status', label: `Status=${resultMsg}` };
  }
  return { cause: 'unknown_gate_condition', label: 'Unknown gate condition' };
}

/*
function diagnoseFailure(result) {
  const selected = result?.selected ?? [];
  const resultMsg = result?.resultMsg ?? '';
  if (!result) {
    return { cause: 'invalid_result', label: '결과 없음' };
  }
  if (!selected.length && resultMsg === 'NO_GROUNDS') {
    return { cause: 'no_grounds', label: '근거 없음' };
  }
  if (!selected.length) {
    return { cause: 'retrieval_empty', label: '검색 후보 없음' };
  }
  if (hasValues(result.forbiddenMatchedTerms)) {
    return { cause: 'forbidden_evidence', label: '금지 근거 혼입' };
  }
  if (hasValues(result.missingDocumentTerms) || hasValues(result.missingTitleTerms)) {
    return { cause: 'wrong_document', label: '문서/제목 불일치' };
  }
  if (hasValues(result.missingSectionTypes) || hasValues(result.missingParentTerms)) {
    return { cause: 'wrong_section', label: '섹션/상위문맥 불일치' };
  }
  if (hasValues(result.missingPageNumbers)) {
    return { cause: 'wrong_page', label: '페이지 불일치' };
  }
  if (hasValues(result.missingTerms)) {
    return { cause: 'partial_evidence', label: '직접근거 일부 부족' };
  }
  if (resultMsg && resultMsg !== 'OK') {
    return { cause: 'result_status', label: `상태=${resultMsg}` };
  }
  return { cause: 'unknown_gate_condition', label: '게이트 조건 확인 필요' };
}

*/
function nextActionForCause(cause) {
  switch (cause) {
    case 'no_grounds':
      return 'Check retrieval recall first; if candidates exist, inspect EvidenceJudge rejection.';
    case 'retrieval_empty':
      return 'Check query planner synonyms, lexical fallback, and collection filters.';
    case 'forbidden_evidence':
      return 'Strengthen rerank/evidence exclusion rules for the forbidden domain.';
    case 'wrong_document':
      return 'Tune title/source_org boost, expected document aliases, or document lookup routing.';
    case 'wrong_section':
      return 'Tune section metadata, parent-child context expansion, or section-type scoring.';
    case 'wrong_page':
      return 'Check parser page mapping and citation extraction.';
    case 'partial_evidence':
      return 'Check chunk granularity and direct-evidence term coverage.';
    case 'answer_verification':
      return 'Inspect generated answer, ClaimVerifier unsupported claims, and answer-level expected terms.';
    case 'result_status':
      return 'Inspect answer service resultMsg path and fallback policy.';
    default:
      return 'Inspect raw selected chunks and case expectations.';
  }
}

function hasValues(values) {
  return Array.isArray(values) && values.length > 0;
}

function selectedSummary(result) {
  const item = (result?.selected ?? [])[0];
  if (!item) {
    return '-';
  }
  const parts = [
    item.target,
    item.title,
    item.chunkTitle,
    item.pageNo == null ? '' : `p.${item.pageNo}`,
  ].filter(Boolean);
  return parts.join(' / ');
}

function list(values) {
  return Array.isArray(values) && values.length > 0 ? values.join(', ') : '-';
}

function escapeCell(value) {
  return String(value ?? '-').replace(/\|/g, '\\|').replace(/\n/g, '<br>');
}

main().catch((error) => {
  console.error(`[rag-eval-gate] ERROR ${error?.message ?? error}`);
  process.exit(1);
});
