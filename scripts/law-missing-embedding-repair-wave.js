const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { execFileSync } = require("node:child_process");

const workspace = path.resolve(__dirname, "..");
const integrityAuditPath = path.resolve(workspace, "logs", "law-index-integrity-audit-latest.json");
const parentChildAuditPath = path.resolve(workspace, "logs", "law-parent-child-chunk-audit-latest.json");
const shortChunkAuditPath = path.resolve(workspace, "logs", "rag-short-chunk-audit-latest.json");
const INTEGRITY_CAUSES = new Set([
  "MISSING_EMBEDDING_ROW",
  "RETRYABLE_EMBEDDING_FAILURE",
  "CONTENT_HASH_MISMATCH",
  "QDRANT_POINT_MISSING",
  "STALE_DATABASE_STATUS",
  "INACTIVE_CHUNK_COUNTED",
]);
const args = Object.fromEntries(process.argv.slice(2).map((arg) => {
  const [key, ...rest] = arg.replace(/^--/, "").split("=");
  return [key, rest.join("=") || "true"];
}));

function planRepairWave(audit, { maxDocuments = 50, maxCandidates = 1000, apply = false } = {}) {
  if (!audit || audit.target !== "law" || !Array.isArray(audit.issues)) {
    throw new Error("Repair planning requires a law integrity audit with issues.");
  }
  if (!nonBlank(audit.runtimeInstanceId) || !nonBlank(audit.indexRevision)) {
    throw new Error("Repair planning requires audit runtime and index revision fences.");
  }
  if (!Number.isSafeInteger(maxDocuments) || maxDocuments < 1 || maxDocuments > 50
    || !Number.isSafeInteger(maxCandidates) || maxCandidates < 1 || maxCandidates > 1000) {
    throw new Error("Repair planning bounds must be positive and within the server limits.");
  }
  const seenChunkIds = new Set();
  const issues = audit.issues.map((issue) => {
    if (issue?.cause !== "MISSING_EMBEDDING_ROW") {
      throw new Error("Repair planning accepts only MISSING_EMBEDDING_ROW issues.");
    }
    if (!Number.isSafeInteger(issue.chunkId) || issue.chunkId <= 0 || !Number.isSafeInteger(issue.documentId) || issue.documentId <= 0) {
      throw new Error("Repair planning requires positive chunkId and documentId values.");
    }
    if (!/^[0-9a-f]{64}$/i.test(String(issue.chunkContentHash || "")) || !seenChunkIds.add(issue.chunkId)) {
      throw new Error("Repair planning requires unique exact chunk content hashes.");
    }
    return issue;
  }).sort((left, right) => left.documentId - right.documentId || left.chunkId - right.chunkId);

  const documentIds = [];
  const candidates = [];
  for (const issue of issues) {
		if (candidates.length === maxCandidates) break;
    if (!documentIds.includes(issue.documentId)) {
      if (documentIds.length === maxDocuments) break;
      documentIds.push(issue.documentId);
    }
    candidates.push({ chunkId: issue.chunkId, expectedChunkContentHash: issue.chunkContentHash, expectedDocumentId: issue.documentId });
  }
  if (!candidates.length) {
    throw new Error("Repair planning selected no explicit candidates.");
  }
  return {
    target: "law",
    expectedRuntimeInstanceId: audit.runtimeInstanceId,
    expectedIndexRevision: audit.indexRevision,
    expectedDocumentIds: documentIds,
    candidates,
    apply: Boolean(apply),
  };
}

function nonBlank(value) {
  return typeof value === "string" && value.trim().length > 0;
}

function assertSuccessfulApply(result, expectedCandidates = null) {
  if (result?.operation) {
    const status = result.operation?.progress?.status;
    if (status !== "INDEXING_COMPLETE") {
      throw new Error("Repair apply was incomplete.");
    }
    const outcomes = Array.isArray(result.outcomes) ? result.outcomes : result.items;
    assertExactIndexedOutcomes(outcomes, expectedCandidates);
    return;
  }
  if (!result || result.applied !== true || result.complete !== true) {
    throw new Error("Repair apply was incomplete.");
  }
  if (!Array.isArray(result.outcomes) || result.outcomes.some((outcome) => outcome?.state !== "INDEXED")) {
    throw new Error("Repair apply reported a failed per-ID outcome.");
  }
  if (expectedCandidates && (!Array.isArray(expectedCandidates) || result.outcomes.length !== expectedCandidates.length
    || result.outcomes.some((outcome, index) => outcome?.chunkId !== expectedCandidates[index]?.chunkId))) {
    throw new Error("Repair apply did not report one successful outcome for every requested chunk.");
  }
}

function assertExactIndexedOutcomes(outcomes, expectedCandidates) {
  if (!Array.isArray(outcomes) || outcomes.some((outcome) => outcome?.state !== "INDEXED")) {
    throw new Error("Repair apply reported a failed per-ID outcome.");
  }
  if (!expectedCandidates) return;
  if (!Array.isArray(expectedCandidates) || outcomes.length !== expectedCandidates.length) {
    throw new Error("Repair apply did not report one successful outcome for every requested chunk.");
  }
  const expectedIds = new Set(expectedCandidates.map((candidate) => candidate?.chunkId));
  const actualIds = new Set(outcomes.map((outcome) => outcome?.chunkId));
  if (expectedIds.size !== expectedCandidates.length || actualIds.size !== outcomes.length
    || [...expectedIds].some((chunkId) => !actualIds.has(chunkId))) {
    throw new Error("Repair apply did not report one successful outcome for every requested chunk.");
  }
}

const OPERATION_ENDPOINT = "http://127.0.0.1:8080/api/admin/law-index-integrity/missing-embedding-repair-operations";
const LIVE_ITEM_STATES = new Set(["READY", "PROCESSING", "INDEXED", "FAILED", "NOT_ATTEMPTED"]);
const LIVE_OPERATION_STATES = new Set(["READY", "RUNNING", "INDEXING_COMPLETE", "FAILED"]);
const DEFAULT_STEP_TIMEOUT_MS = 660000; // 600s lease plus a 60s transport margin.
const DEFAULT_PROCESSING_BUDGET_MS = 1100000; // 380.2s max backend step + 600s lease + 119.8s safety margin.
const DEFAULT_PROCESSING_POLL_MS = 1000;

/**
 * Drives only one outstanding server mutation at a time. A post-step transport loss is always
 * reconciled by GET before another step can be issued, so the client never guesses whether an
 * item was committed.
 */
async function runDurableRepairOperation(request, options = {}) {
  const fetchImpl = options.fetch || global.fetch;
  const sleep = options.sleep || ((milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)));
  const timeoutSignal = options.timeoutSignal || ((milliseconds) => AbortSignal.timeout(milliseconds));
  const now = options.now || Date.now;
  const timeoutMs = positiveBoundedInteger(options.timeoutMs, DEFAULT_STEP_TIMEOUT_MS, "timeoutMs", 100, 900000);
  const processingBudgetMs = positiveBoundedInteger(options.processingBudgetMs, DEFAULT_PROCESSING_BUDGET_MS, "processingBudgetMs", 1000, 3600000);
  const processingPollMs = positiveBoundedInteger(options.processingPollMs, DEFAULT_PROCESSING_POLL_MS, "processingPollMs", 1, 60000);
  const maxPollsPerItem = positiveBoundedInteger(options.maxPollsPerItem ?? options.maxPolls,
    Math.ceil(processingBudgetMs / processingPollMs), "maxPollsPerItem", 1, 10000);
  if (typeof fetchImpl !== "function") throw new Error("Durable repair requires fetch.");
  const transportAttempts = [];
  const identity = durableRequestIdentity(request);
  const context = { operationId: null, transportAttempts, lastView: null };
  const waits = new Map();
  let view;
  try {
    view = observeDurableView(await registerDurableOperation(request, { fetchImpl, timeoutMs, timeoutSignal, transportAttempts }), request, identity, context);
    const maxTransitions = request.candidates.length * (maxPollsPerItem + 4);
    for (let transitions = 0; transitions < maxTransitions; transitions += 1) {
      const status = view.operation.progress.status;
      if (status === "INDEXING_COMPLETE") {
        const result = durableApplyResult(view, transportAttempts);
        assertSuccessfulApply(result, request.candidates);
        return result;
      }
      if (status === "FAILED") throw new Error("Durable repair operation failed.");
      const processing = processingItem(view);
      if (processing) {
        const currentTime = safeNow(now);
        const leaseExpiry = parseLeaseExpiry(processing.leaseExpiresAt);
        if (leaseExpiry > currentTime) {
          const wait = waits.get(processing.ordinal) || { startedAt: currentTime, polls: 0 };
          if (currentTime - wait.startedAt >= processingBudgetMs || wait.polls >= maxPollsPerItem) {
            throw new Error("Durable repair operation exceeded its per-item healthy processing budget.");
          }
          waits.set(processing.ordinal, { ...wait, polls: wait.polls + 1 });
          await sleep(Math.min(processingPollMs, leaseExpiry - currentTime));
          view = observeDurableView(await getDurableOperation(context.operationId, { fetchImpl, timeoutMs, timeoutSignal, transportAttempts }), request, identity, context);
          continue;
        }
      }
      try {
        view = observeDurableView(await postDurableStep(context.operationId, { fetchImpl, timeoutMs, timeoutSignal, transportAttempts }), request, identity, context);
      } catch (error) {
        if (!(error instanceof TransportError)) throw error;
        view = observeDurableView(await getDurableOperation(context.operationId, { fetchImpl, timeoutMs, timeoutSignal, transportAttempts }), request, identity, context);
      }
    }
    throw new Error("Durable repair operation exceeded its bounded transition limit.");
  } catch (error) {
    throw durableOperationError(error, context);
  }
}

async function registerDurableOperation(request, { fetchImpl, timeoutMs, timeoutSignal, transportAttempts }) {
  for (let attempt = 1; attempt <= 2; attempt += 1) {
    try {
      return await requestOperation("register", OPERATION_ENDPOINT, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(operationRegistrationPayload(request)),
      }, { fetchImpl, timeoutMs, timeoutSignal, transportAttempts });
    } catch (error) {
      if (!(error instanceof TransportError) || attempt === 2) throw error;
    }
  }
  throw new Error("Durable repair registration did not return a durable operation.");
}

async function getDurableOperation(operationId, { fetchImpl, timeoutMs, timeoutSignal, transportAttempts }) {
  return requestOperation("get", `${OPERATION_ENDPOINT}/${encodeURIComponent(requireOperationId(operationId))}`, {},
    { fetchImpl, timeoutMs, timeoutSignal, transportAttempts });
}

async function postDurableStep(operationId, { fetchImpl, timeoutMs, timeoutSignal, transportAttempts }) {
  return requestOperation("step", `${OPERATION_ENDPOINT}/${encodeURIComponent(requireOperationId(operationId))}/step`, { method: "POST" },
    { fetchImpl, timeoutMs, timeoutSignal, transportAttempts });
}

async function requestOperation(kind, url, init, { fetchImpl, timeoutMs, timeoutSignal, transportAttempts }) {
  try {
    const response = await fetchImpl(url, { ...init, signal: timeoutSignal(timeoutMs) });
    const body = await response.text();
    if (!response.ok) {
      transportAttempts.push({ kind, outcome: "http-error", status: response.status });
      throw new Error(`Durable repair ${kind} HTTP ${response.status}: ${body}`);
    }
    let view;
    try {
      view = JSON.parse(body);
    } catch {
      transportAttempts.push({ kind, outcome: "malformed-response", status: response.status });
      throw new Error(`Durable repair ${kind} returned malformed JSON.`);
    }
    transportAttempts.push({ kind, outcome: "response", status: response.status });
    return view;
  } catch (error) {
    if (error instanceof TransportError || /^Durable repair .* (HTTP|returned malformed JSON)/.test(String(error?.message || ""))) throw error;
    transportAttempts.push({ kind, outcome: "transport-error" });
    throw new TransportError(kind, error);
  }
}

class TransportError extends Error {
  constructor(kind, cause) {
    super(`Durable repair ${kind} transport failed.`);
    this.name = "TransportError";
    this.cause = cause;
  }
}

class DurableOperationError extends Error {
  constructor(message, evidence, cause = null) {
    super(message);
    this.name = "DurableOperationError";
    this.evidence = evidence;
    this.cause = cause;
  }
}

function durableOperationError(error, context) {
  if (error instanceof DurableOperationError) return error;
  return new DurableOperationError(error?.message || String(error), durableEvidence(context, error), error);
}

function durableEvidence(context, error = null) {
  return {
    operationId: context?.operationId || context?.lastView?.operation?.request?.operationId || null,
    transportAttempts: [...(context?.transportAttempts || [])],
    lastView: context?.lastView || null,
    baselineRuntimeInfo: context?.baselineRuntimeInfo || null,
    runtimeInfoAttempts: Array.isArray(context?.runtimeInfoAttempts) ? [...context.runtimeInfoAttempts]
      : runtimeInfoAttemptsFor(context?.baselineRuntimeInfo, error?.runtimeInfoAttempts),
    reason: error?.message || null,
  };
}

function observeDurableView(view, request, identity, context) {
  context.lastView = view;
  context.operationId = view?.operation?.request?.operationId || context.operationId;
  assertDurableOperationView(view, request, identity);
  return view;
}

function assertDurableOperationView(view, request, identity = durableRequestIdentity(request)) {
  const operation = view?.operation;
  const progress = operation?.progress;
  const operationRequest = operation?.request;
  if (!operationRequest || !progress || !LIVE_OPERATION_STATES.has(progress.status) || !Array.isArray(view?.items)
    || !nonBlank(operationRequest.operationId) || operationRequest.target !== "law"
    || operationRequest.runtimeInstanceId !== request?.expectedRuntimeInstanceId
    || operationRequest.candidateCount !== request.candidates.length || operationRequest.documentCount !== request.expectedDocumentIds.length
    || operationRequest.requestHash !== identity.hash || operationRequest.idempotencyKey !== identity.hash
    || operationRequest.normalizedRequest !== identity.normalized
    || view.items.some((item) => !Number.isSafeInteger(item?.chunkId) || !LIVE_ITEM_STATES.has(item?.state))) {
    throw new Error("Durable repair operation returned an unknown or malformed state.");
  }
  if (progress.status !== "FAILED" && view.items.some((item) => item.state === "FAILED" || item.state === "NOT_ATTEMPTED")) {
    throw new Error("Durable repair operation returned an item failure before a terminal operation state.");
  }
  if (view.items.length !== request.candidates.length || view.items.some((item, ordinal) => {
    const candidate = request.candidates[ordinal];
    return item.ordinal !== ordinal || item.chunkId !== candidate.chunkId
      || item.documentId !== candidate.expectedDocumentId
      || String(item.expectedContentHash || "").toLowerCase() !== candidate.expectedChunkContentHash.toLowerCase();
  })) {
    throw new Error("Durable repair operation no longer matches the immutable planned request identity.");
  }
}

function processingItem(view) {
  const processing = view.items.filter((item) => item.state === "PROCESSING");
  if (!processing.length) return null;
  if (processing.length !== 1) throw new Error("Durable repair operation reported more than one PROCESSING item.");
  parseLeaseExpiry(processing[0].leaseExpiresAt);
  return processing[0];
}

function parseLeaseExpiry(value) {
  if (!nonBlank(value) || !/^\d{4}-\d{2}-\d{2}T/.test(value)) {
    throw new Error("Durable repair operation reported an invalid PROCESSING lease expiry.");
  }
  const parsed = Date.parse(value);
  if (!Number.isSafeInteger(parsed)) throw new Error("Durable repair operation reported an invalid PROCESSING lease expiry.");
  return parsed;
}

function safeNow(now) {
  const value = now();
  if (!Number.isSafeInteger(value)) throw new Error("Durable repair clock returned an invalid time.");
  return value;
}

function operationRegistrationPayload(request) {
  return {
    target: request.target,
    expectedRuntimeInstanceId: request.expectedRuntimeInstanceId,
    expectedIndexRevision: request.expectedIndexRevision,
    apply: true,
    expectedDocumentIds: request.expectedDocumentIds,
    candidates: request.candidates.map((candidate) => ({
      chunkId: candidate.chunkId,
      expectedChunkContentHash: candidate.expectedChunkContentHash,
    })),
  };
}

function durableRequestIdentity(request) {
  if (!request || request.target !== "law" || !Array.isArray(request.candidates) || !Array.isArray(request.expectedDocumentIds)
    || request.candidates.some((candidate) => !Number.isSafeInteger(candidate?.expectedDocumentId))) {
    throw new Error("Durable repair requires immutable candidate document identity.");
  }
  let normalized = `target=law\nruntimeInstanceId=${String(request.expectedRuntimeInstanceId).toLowerCase()}\n`;
  normalized += `indexRevision=${String(request.expectedIndexRevision).toLowerCase()}\napply=true\n`;
  request.expectedDocumentIds.forEach((documentId, index) => { normalized += `expectedDocumentId[${index}]=${documentId}\n`; });
  request.candidates.forEach((candidate, index) => {
    normalized += `candidate[${index}]=${candidate.chunkId}:${String(candidate.expectedChunkContentHash).toLowerCase()}\n`;
  });
  return { normalized, hash: crypto.createHash("sha256").update(normalized, "utf8").digest("hex") };
}

function durableApplyResult(view, transportAttempts) {
  return {
    applied: true,
    complete: view.operation.progress.status === "INDEXING_COMPLETE",
    runtime: {
      runtimeInstanceId: view.operation.request.runtimeInstanceId,
      indexRevision: view.operation.progress.trustedIndexRevision,
    },
    outcomes: view.items.map((item) => ({ chunkId: item.chunkId, state: item.state, documentId: item.documentId })),
    operationId: view.operation.request.operationId,
    operationState: view.operation.progress.status,
    stateCounts: stateCounts(view.items),
    transportAttempts: [...transportAttempts],
    operation: view.operation,
    items: view.items,
  };
}

function stateCounts(items) {
  return items.reduce((counts, item) => {
    counts[item.state] = (counts[item.state] || 0) + 1;
    return counts;
  }, {});
}

function requireOperationId(operationId) {
  if (!nonBlank(operationId)) throw new Error("Durable repair operation ID was missing.");
  return operationId;
}

function positiveBoundedInteger(value, fallback, name, minimum, maximum) {
  const selected = value == null ? fallback : value;
  if (!Number.isSafeInteger(selected) || selected < minimum || selected > maximum) {
    throw new Error(`Durable repair ${name} must be between ${minimum} and ${maximum}.`);
  }
  return selected;
}

function assertPostWaveInvariants({ beforeAudit, request, baselineRuntimeInfo, result, integrityAudit, parentChildAudit, shortChunkAudit, runtimeInfo }) {
  assertRuntimeBaselineIdentity({ beforeAudit, request, baselineRuntimeInfo });
  assertSuccessfulApply(result, request.candidates);
  const plannedCount = request.candidates.length;
  const beforeBacklog = causeCount(beforeAudit, "MISSING_EMBEDDING_ROW");
  const repairedCount = result?.outcomes?.filter((outcome) => outcome?.state === "INDEXED").length;
  if (!Array.isArray(beforeAudit?.issues) || beforeAudit.issues.length !== beforeBacklog
    || !Number.isSafeInteger(repairedCount) || repairedCount < 1 || repairedCount > beforeBacklog) {
    throw new Error("Post-wave validation could not establish the repaired missing-embedding backlog.");
  }
  if (integrityAudit?.target !== "law" || !Number.isSafeInteger(integrityAudit.pages) || integrityAudit.pages < 1
    || !Number.isSafeInteger(integrityAudit.scannedRows) || integrityAudit.scannedRows < 0) {
    throw new Error("Post-wave full integrity audit was incomplete or malformed.");
  }
  const postBacklog = causeCount(integrityAudit, "MISSING_EMBEDDING_ROW");
  if (postBacklog == null) {
    throw new Error("Post-wave full integrity audit causeCounts were malformed.");
  }
  if (postBacklog !== beforeBacklog - repairedCount) {
    throw new Error("Post-wave missing-embedding backlog did not decrease by the verified repaired count.");
  }
  if (Object.entries(integrityAudit.causeCounts).some(([cause, count]) => cause !== "MISSING_EMBEDDING_ROW" && count !== 0)) {
    throw new Error("Post-wave full integrity audit reported a non-missing integrity defect.");
  }
  if (!Array.isArray(integrityAudit.issues) || integrityAudit.issues.length !== postBacklog) {
    throw new Error("Post-wave full integrity audit issue list did not reconcile with its backlog.");
  }
  const quality = targetRow(parentChildAudit?.qualitySummary, "law");
  const metadata = targetRow(parentChildAudit?.metadataGaps, "law");
  const lawCollection = nonBlank(runtimeInfo?.lawCollection) ? runtimeInfo.lawCollection : null;
  const embeddingModel = nonBlank(runtimeInfo?.embeddingModel) ? runtimeInfo.embeddingModel : null;
  const comparableMetric = parentChildAudit?.runtimeComparableIndexed;
  const comparableRows = comparableMetric?.embeddingModel === embeddingModel
    && comparableMetric?.vectorStore === lawCollection && Array.isArray(comparableMetric?.rows)
    ? comparableMetric.rows : null;
  const currentChunks = integer(quality?.currentChunks);
  if (currentChunks == null || currentChunks !== integrityAudit.scannedRows || integer(metadata?.chunks) !== currentChunks
    || integer(metadata?.missingTitle) !== 0 || integer(metadata?.missingHash) !== 0
    || integer(metadata?.notIndexed) !== postBacklog) {
    throw new Error("Post-wave parent/child coverage or metadata invariants did not reconcile.");
  }
  const comparableTargets = comparableRows == null ? null : new Set(comparableRows.map((row) => row?.target));
  if (comparableRows == null || comparableRows.some((row) => !nonBlank(row?.target) || integer(row?.chunks) == null)
    || comparableTargets.size !== comparableRows.length) {
    throw new Error("Post-wave runtime-comparable indexed metric was malformed or mismatched the runtime.");
  }
  const targetComparableIndexed = comparableRows.filter((row) => row.target === "law")
    .reduce((sum, row) => sum + integer(row.chunks), 0);
  if (targetComparableIndexed !== currentChunks - postBacklog) {
    throw new Error("Post-wave runtime-comparable target coverage did not reconcile with the full integrity audit.");
  }
  if (!shortChunkAudit || shortChunkAudit.applyRequested !== false || shortChunkAudit.applyCompleted !== false
    || !Number.isSafeInteger(shortChunkAudit.total) || shortChunkAudit.total < 0 || !Array.isArray(shortChunkAudit.summary)) {
    throw new Error("Post-wave short-chunk audit was incomplete, malformed, or mutated corpus state.");
  }
  if (!sameRuntime(integrityAudit, result?.runtime) || !sameRuntime(integrityAudit, runtimeInfo)) {
    throw new Error("Runtime drifted between repair completion and the post-wave audits.");
  }
  const qdrantFailureCount = integer(runtimeInfo?.qdrantSearchFailureCount);
  const lawQdrantCount = integer(runtimeInfo?.lawQdrantExactPointCount);
  const lawDatabaseCount = integer(runtimeInfo?.lawDatabaseIndexedCount);
  const ragQdrantCount = integer(runtimeInfo?.ragQdrantExactPointCount);
  const ragDatabaseCount = integer(runtimeInfo?.ragDatabaseIndexedCount);
  const baselineLawQdrantCount = integer(baselineRuntimeInfo?.lawQdrantExactPointCount);
  const baselineLawDatabaseCount = integer(baselineRuntimeInfo?.lawDatabaseIndexedCount);
  const baselineRagQdrantCount = integer(baselineRuntimeInfo?.ragQdrantExactPointCount);
  const baselineRagDatabaseCount = integer(baselineRuntimeInfo?.ragDatabaseIndexedCount);
  const collectionComparableIndexed = comparableRows.reduce((sum, row) => sum + integer(row.chunks), 0);
  if (runtimeInfo?.qdrantReady !== true || qdrantFailureCount !== 0 || lawCollection == null || embeddingModel == null
    || lawQdrantCount == null || lawDatabaseCount == null || ragQdrantCount == null || ragDatabaseCount == null
    || lawQdrantCount !== lawDatabaseCount || ragQdrantCount !== ragDatabaseCount) {
    throw new Error("Post-wave DB-Qdrant invariants did not reconcile.");
  }
  if (baselineRuntimeInfo.qdrantReady !== true || integer(baselineRuntimeInfo.qdrantSearchFailureCount) !== 0
    || baselineRuntimeInfo.lawCollection !== lawCollection || baselineRuntimeInfo.embeddingModel !== embeddingModel
    || baselineLawQdrantCount == null || baselineLawDatabaseCount == null
    || baselineRagQdrantCount == null || baselineRagDatabaseCount == null
    || baselineLawQdrantCount !== baselineLawDatabaseCount || baselineRagQdrantCount !== baselineRagDatabaseCount) {
    throw new Error("Post-wave runtime baseline counts or collection identity were malformed.");
  }
  if (lawQdrantCount !== baselineLawQdrantCount + plannedCount
    || lawDatabaseCount !== baselineLawDatabaseCount + plannedCount) {
    throw new Error("Post-wave law runtime baseline delta was not exactly the planned candidate count.");
  }
  if (ragQdrantCount !== baselineRagQdrantCount || ragDatabaseCount !== baselineRagDatabaseCount) {
    throw new Error("Post-wave rag runtime baseline delta was not exactly unchanged.");
  }
  if (collectionComparableIndexed !== lawDatabaseCount) {
    throw new Error("Post-wave law collection coverage did not reconcile.");
  }
  return { integrityAudit, parentChildAudit, shortChunkAudit, runtimeInfo };
}

function assertRuntimeBaselineIdentity({ beforeAudit, request, baselineRuntimeInfo }) {
  const requestFence = {
    runtimeInstanceId: request?.expectedRuntimeInstanceId,
    indexRevision: request?.expectedIndexRevision,
  };
  if (request?.target !== "law" || !Array.isArray(request?.candidates) || request.candidates.length < 1
    || !sameRuntime(beforeAudit, requestFence) || !sameRuntime(baselineRuntimeInfo, requestFence)) {
    throw new Error("Pre-wave runtime baseline identity did not match the request and integrity audit.");
  }
}

async function runPostWaveAudits({ beforeAudit, request, baselineRuntimeInfo, result, runIntegrityAudit, runParentChildAudit, runShortChunkAudit, loadRuntimeInfo }) {
  const integrityAudit = await runIntegrityAudit();
  const parentChildAudit = await runParentChildAudit();
  const shortChunkAudit = await runShortChunkAudit();
  const runtimeInfo = await loadRuntimeInfo({ phase: "post-wave-runtime-info" });
  try {
    return assertPostWaveInvariants({ beforeAudit, request, baselineRuntimeInfo, result, integrityAudit, parentChildAudit, shortChunkAudit, runtimeInfo });
  } catch (error) {
    error.runtimeInfoAttempts = runtimeInfoAttemptsFor(baselineRuntimeInfo, runtimeInfo.runtimeInfoAttempts);
    throw error;
  }
}

async function runDurableRepairWithPostWaveAudits({
  request, beforeAudit, runner = runDurableRepairOperation, runIntegrityAudit, runParentChildAudit, runShortChunkAudit, loadRuntimeInfo, runnerOptions,
}) {
  let baselineRuntimeInfo = null;
  try {
    baselineRuntimeInfo = await loadRuntimeInfo({ phase: "pre-wave-runtime-info" });
    assertRuntimeBaselineIdentity({ beforeAudit, request, baselineRuntimeInfo });
  } catch (error) {
    throw durableOperationError(new Error(`Durable repair pre-wave runtime baseline failed: ${error.message || error}`), {
      baselineRuntimeInfo,
      runtimeInfoAttempts: runtimeInfoAttemptsFor(baselineRuntimeInfo, error?.runtimeInfoAttempts),
    });
  }
  let result;
  try {
    result = await runner(request, runnerOptions);
  } catch (error) {
    const durable = error instanceof DurableOperationError ? error : durableOperationError(error, error?.evidence || {});
    throw new DurableOperationError(durable.message, { ...durable.evidence, baselineRuntimeInfo }, durable);
  }
  try {
    const postWaveAudits = await runPostWaveAudits({ beforeAudit, request, baselineRuntimeInfo, result,
      runIntegrityAudit, runParentChildAudit, runShortChunkAudit, loadRuntimeInfo });
    return { ...result, runtimeInfoAttempts: runtimeInfoAttemptsFor(baselineRuntimeInfo, postWaveAudits.runtimeInfo) };
  } catch (error) {
    const context = result ? {
      operationId: result.operationId,
      transportAttempts: result.transportAttempts,
      lastView: result.operation ? { operation: result.operation, items: result.items } : null,
      baselineRuntimeInfo,
      runtimeInfoAttempts: runtimeInfoAttemptsFor(baselineRuntimeInfo, error?.runtimeInfoAttempts),
    } : error?.evidence || {};
    throw durableOperationError(new Error(`Durable repair post-wave verification failed: ${error.message || error}`), context);
  }
}

function durableFailureArtifact(error) {
  const durable = error instanceof DurableOperationError ? error : durableOperationError(error, {});
  return {
    kind: "DURABLE_REPAIR_FAILURE",
    qualityResult: false,
    message: durable.message,
    evidence: durable.evidence,
  };
}

function integer(value) {
  const parsed = typeof value === "number" ? value
    : typeof value === "string" && /^\d+$/.test(value.trim()) ? Number(value) : NaN;
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : null;
}

function causeCount(audit, cause) {
  const causeCounts = audit?.causeCounts;
  if (!validCauseCounts(causeCounts)) return null;
  return Object.hasOwn(causeCounts, cause) ? causeCounts[cause] : 0;
}

function validCauseCounts(causeCounts) {
  return causeCounts != null && typeof causeCounts === "object" && !Array.isArray(causeCounts)
    && Object.entries(causeCounts).every(([cause, count]) => INTEGRITY_CAUSES.has(cause)
      && Number.isSafeInteger(count) && count >= 0);
}

function targetRow(rows, target) {
  return Array.isArray(rows) ? rows.find((row) => row?.target === target) : null;
}

function sameRuntime(left, right) {
  return nonBlank(left?.runtimeInstanceId) && nonBlank(left?.indexRevision)
    && left.runtimeInstanceId === right?.runtimeInstanceId && left.indexRevision === right?.indexRevision;
}

function runScript(relativePath, argumentsList = []) {
  execFileSync(process.execPath, [path.resolve(workspace, relativePath), ...argumentsList], {
    cwd: workspace,
    encoding: "utf8",
    stdio: "inherit",
    windowsHide: true,
  });
}

function readJson(artifactPath) {
  return JSON.parse(fs.readFileSync(artifactPath, "utf8"));
}

async function loadRuntimeInfo({ phase = "runtime-info", fetch: fetchImpl = global.fetch } = {}) {
  const runtimeInfoAttempts = [];
  for (let attempt = 1; attempt <= 2; attempt += 1) {
    let response;
    try {
      response = await fetchImpl("http://127.0.0.1:8080/api/law-data/ai/debug/runtime-info", {
        signal: AbortSignal.timeout(30000),
      });
    } catch (error) {
      runtimeInfoAttempts.push({ phase, attempt, outcome: "transport-error" });
      if (attempt === 2) throw runtimeInfoError(error, runtimeInfoAttempts);
      continue;
    }
    if (!response.ok) {
      runtimeInfoAttempts.push({ phase, attempt, outcome: "http-error", status: response.status });
      throw runtimeInfoError(new Error(`Runtime info HTTP ${response.status}.`), runtimeInfoAttempts);
    }
    try {
      const runtimeInfo = JSON.parse(await response.text());
      runtimeInfoAttempts.push({ phase, attempt, outcome: "response", status: response.status });
      return { ...runtimeInfo, runtimeInfoAttempts };
    } catch (error) {
      runtimeInfoAttempts.push({ phase, attempt, outcome: "malformed-json", status: response.status });
      throw runtimeInfoError(error, runtimeInfoAttempts);
    }
  }
  throw runtimeInfoError(new Error("Runtime info did not return a response."), runtimeInfoAttempts);
}

function runtimeInfoError(error, runtimeInfoAttempts) {
  error.runtimeInfoAttempts = [...runtimeInfoAttempts];
  return error;
}

function runtimeInfoAttemptsFor(...runtimeInfos) {
  return runtimeInfos.flatMap((runtimeInfo) => Array.isArray(runtimeInfo?.runtimeInfoAttempts)
    ? runtimeInfo.runtimeInfoAttempts : []);
}

async function main() {
  const auditPath = path.resolve(workspace, args.audit || "logs/law-index-integrity-audit-latest.json");
  const audit = JSON.parse(fs.readFileSync(auditPath, "utf8"));
  const request = planRepairWave(audit, {
    maxDocuments: Number(args.maxDocuments || 50),
    maxCandidates: Number(args.maxCandidates || 1000),
    apply: args.apply === "true",
  });
  if (request.apply) {
    try {
      const result = await runDurableRepairWithPostWaveAudits({
        request,
        beforeAudit: audit,
        runIntegrityAudit: async () => {
          runScript("scripts/law-index-integrity-audit.js", ["--target=law", "--limit=10000"]);
          return readJson(integrityAuditPath);
        },
        runParentChildAudit: async () => {
          runScript("scripts/law-parent-child-chunk-audit.js");
          return readJson(parentChildAuditPath);
        },
        runShortChunkAudit: async () => {
          runScript("scripts/rag-short-chunk-audit.js");
          return readJson(shortChunkAuditPath);
        },
        loadRuntimeInfo,
      });
      process.stdout.write(`${JSON.stringify(result)}\n`);
      return;
    } catch (error) {
      process.stdout.write(`${JSON.stringify(durableFailureArtifact(error))}\n`);
      throw error;
    }
  }
  const response = await fetch("http://127.0.0.1:8080/api/admin/law-index-integrity/missing-embedding-repair", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ...operationRegistrationPayload(request), apply: false }),
    signal: AbortSignal.timeout(600000),
  });
  const body = await response.text();
  if (!response.ok) {
    throw new Error(`Missing-embedding preview HTTP ${response.status}: ${body}`);
  }
  process.stdout.write(`${body}\n`);
}

if (require.main === module) {
  main().catch((error) => {
    console.error(error.message || error);
    process.exitCode = 1;
  });
}

module.exports = {
  planRepairWave,
  assertSuccessfulApply,
  assertPostWaveInvariants,
  runPostWaveAudits,
  runDurableRepairOperation,
  registerDurableOperation,
  getDurableOperation,
  postDurableStep,
  runDurableRepairWithPostWaveAudits,
  DurableOperationError,
  durableFailureArtifact,
  loadRuntimeInfo,
};
