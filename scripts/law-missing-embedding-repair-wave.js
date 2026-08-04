const fs = require("node:fs");
const path = require("node:path");

const workspace = path.resolve(__dirname, "..");
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

function assertSuccessfulApply(result) {
  if (!result || result.applied !== true || result.complete !== true) {
    throw new Error("Repair apply was incomplete.");
  }
  if (!Array.isArray(result.outcomes) || result.outcomes.some((outcome) => outcome?.state !== "INDEXED")) {
    throw new Error("Repair apply reported a failed per-ID outcome.");
  }
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
		assertSuccessfulApply(result);
	}
  process.stdout.write(`${body}\n`);
}

if (require.main === module) {
  main().catch((error) => {
    console.error(error.message || error);
    process.exitCode = 1;
  });
}

module.exports = { planRepairWave, assertSuccessfulApply };
