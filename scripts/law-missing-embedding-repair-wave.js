const fs = require("node:fs");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const workspace = path.resolve(__dirname, "..");
const integrityAuditPath = path.resolve(workspace, "logs", "law-index-integrity-audit-latest.json");
const parentChildAuditPath = path.resolve(workspace, "logs", "law-parent-child-chunk-audit-latest.json");
const shortChunkAuditPath = path.resolve(workspace, "logs", "rag-short-chunk-audit-latest.json");
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
    candidates.push({ chunkId: issue.chunkId, expectedChunkContentHash: issue.chunkContentHash });
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

function assertPostWaveInvariants({ beforeAudit, result, integrityAudit, parentChildAudit, shortChunkAudit, runtimeInfo }) {
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
  if (postBacklog !== beforeBacklog - repairedCount) {
    throw new Error("Post-wave missing-embedding backlog did not decrease by the verified repaired count.");
  }
  if (Object.entries(integrityAudit.causeCounts || {}).some(([cause, count]) => cause !== "MISSING_EMBEDDING_ROW" && integer(count) !== 0)) {
    throw new Error("Post-wave full integrity audit reported a non-missing integrity defect.");
  }
  if (!Array.isArray(integrityAudit.issues) || integrityAudit.issues.length !== postBacklog) {
    throw new Error("Post-wave full integrity audit issue list did not reconcile with its backlog.");
  }
  const quality = targetRow(parentChildAudit?.qualitySummary, "law");
  const metadata = targetRow(parentChildAudit?.metadataGaps, "law");
  const embeddingRows = Array.isArray(parentChildAudit?.embeddingStatus)
    ? parentChildAudit.embeddingStatus.filter((row) => row?.target === "law") : [];
  const currentChunks = integer(quality?.currentChunks);
  if (currentChunks == null || currentChunks !== integrityAudit.scannedRows || integer(metadata?.chunks) !== currentChunks
    || integer(metadata?.missingTitle) !== 0 || integer(metadata?.missingHash) !== 0
    || integer(metadata?.notIndexed) !== postBacklog) {
    throw new Error("Post-wave parent/child coverage or metadata invariants did not reconcile.");
  }
  const embeddingCounts = embeddingRows.map((row) => integer(row?.chunks));
  if (embeddingCounts.some((count) => count == null)) {
    throw new Error("Post-wave embedding coverage was malformed.");
  }
  const embeddingTotal = embeddingCounts.reduce((sum, count) => sum + count, 0);
  const noEmbed = embeddingRows.reduce((sum, row, index) => row?.status === "NO_EMBED" ? sum + embeddingCounts[index] : sum, 0);
  const indexed = embeddingRows.reduce((sum, row, index) => row?.status === "INDEXED" ? sum + embeddingCounts[index] : sum, 0);
  if (embeddingTotal !== currentChunks || noEmbed !== postBacklog || indexed !== currentChunks - postBacklog) {
    throw new Error("Post-wave embedding coverage did not reconcile with the full integrity audit.");
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
  if (runtimeInfo?.qdrantReady !== true || qdrantFailureCount !== 0
    || lawQdrantCount == null || lawDatabaseCount == null || ragQdrantCount == null || ragDatabaseCount == null
    || lawQdrantCount !== lawDatabaseCount || ragQdrantCount !== ragDatabaseCount
    || lawDatabaseCount !== currentChunks - postBacklog) {
    throw new Error("Post-wave DB-Qdrant invariants did not reconcile.");
  }
  return { integrityAudit, parentChildAudit, shortChunkAudit, runtimeInfo };
}

async function runPostWaveAudits({ beforeAudit, result, runIntegrityAudit, runParentChildAudit, runShortChunkAudit, loadRuntimeInfo }) {
  const integrityAudit = await runIntegrityAudit();
  const parentChildAudit = await runParentChildAudit();
  const shortChunkAudit = await runShortChunkAudit();
  const runtimeInfo = await loadRuntimeInfo();
  return assertPostWaveInvariants({ beforeAudit, result, integrityAudit, parentChildAudit, shortChunkAudit, runtimeInfo });
}

function integer(value) {
  const parsed = typeof value === "number" ? value
    : typeof value === "string" && /^\d+$/.test(value.trim()) ? Number(value) : NaN;
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : null;
}

function causeCount(audit, cause) {
  const count = audit?.causeCounts?.[cause];
  return Number.isSafeInteger(count) && count >= 0 ? count : -1;
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

async function loadRuntimeInfo() {
  const response = await fetch("http://127.0.0.1:8080/api/law-data/ai/debug/runtime-info", {
    signal: AbortSignal.timeout(30000),
  });
  if (!response.ok) {
    throw new Error(`Post-wave runtime info HTTP ${response.status}.`);
  }
  return response.json();
}

async function main() {
  const auditPath = path.resolve(workspace, args.audit || "logs/law-index-integrity-audit-latest.json");
  const audit = JSON.parse(fs.readFileSync(auditPath, "utf8"));
  const request = planRepairWave(audit, {
    maxDocuments: Number(args.maxDocuments || 50),
    maxCandidates: Number(args.maxCandidates || 1000),
    apply: args.apply === "true",
  });
  const response = await fetch("http://127.0.0.1:8080/api/admin/law-index-integrity/missing-embedding-repair", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
    signal: AbortSignal.timeout(600000),
  });
  const body = await response.text();
  if (!response.ok) {
    throw new Error(`Missing-embedding repair HTTP ${response.status}: ${body}`);
  }
	if (request.apply) {
		let result;
		try {
			result = JSON.parse(body);
		} catch {
			throw new Error("Missing-embedding repair returned malformed JSON.");
		}
		assertSuccessfulApply(result, request.candidates);
		await runPostWaveAudits({
			beforeAudit: audit,
			result,
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
	}
  process.stdout.write(`${body}\n`);
}

if (require.main === module) {
  main().catch((error) => {
    console.error(error.message || error);
    process.exitCode = 1;
  });
}

module.exports = { planRepairWave, assertSuccessfulApply, assertPostWaveInvariants, runPostWaveAudits };
