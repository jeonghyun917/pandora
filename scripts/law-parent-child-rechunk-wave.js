const { execFileSync, spawnSync } = require("node:child_process");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const workspace = path.resolve(__dirname, "..");
const mysql = process.env.MARIADB_EXE || "C:\\Program Files\\MariaDB 12.2\\bin\\mariadb.exe";
const baseUrl = process.env.PANDORA_APP_URL || "http://127.0.0.1:8080";
const qdrantUrl = process.env.QDRANT_URL || "http://127.0.0.1:6333";
const model = process.env.PANDORA_EMBEDDING_MODEL || "text-embedding-3-small";
const vectorStore = process.env.PANDORA_LAW_VECTOR_STORE || "law_chunks";
const outPath = path.resolve(workspace, "logs", "law-parent-child-rechunk-wave-latest.md");
const jsonPath = path.resolve(workspace, "logs", "law-parent-child-rechunk-wave-latest.json");

if (process.argv.includes("--help") || process.argv.includes("-h")) {
  console.log(`
Usage: node scripts/law-parent-child-rechunk-wave.js [options]

Fail-closed workflow:
  preview -> create-candidate -> index -> verify -> activate

--apply=false is preview-only and never writes to MariaDB, Qdrant, or the app API.
--apply=true creates a CANDIDATE version; it never deletes ACTIVE chunks before activation.
  `.trim());
  process.exit(0);
}

const args = parseArgs(process.argv.slice(2));
const apply = boolArg("apply", false);
const targetArg = String(args.target || "all").trim().toLowerCase();
const targets = targetArg === "all" ? ["law", "admrul"] : targetArg.split(",").map((value) => value.trim()).filter(Boolean);
const maxDocs = numberArg("max-docs", 4);
const candidatePool = numberArg("candidate-pool", 20);
const maxProjectedChunks = numberArg("max-projected-chunks", 1000);
const maxProjectedChunksPerDoc = numberArg("max-projected-chunks-per-doc", 0);
const minTinyChunks = numberArg("min-tiny", 100);
const minLongChunks = numberArg("min-long", 1);
const candidateMode = String(args.candidate || "tiny").trim().toLowerCase();
const maxProjectedLength = numberArg("max-projected-length", 2500);
const indexMode = String(args.index || "none").trim().toLowerCase();
const runEval = boolArg("eval", false);
const allowProjectedTiny = boolArg("allow-projected-tiny", false);
const allowNetTinyReduction = boolArg("allow-net-tiny-reduction", true);
const allowProjectedLong = boolArg("allow-projected-long", false);
const requireQdrantGreen = boolArg("require-qdrant-green", true);
const compactOutput = boolArg("compact", false);
const missingChunkNoMode = ["missing-chunk-no", "missing_chunk_no", "missingchunkno"].includes(candidateMode);
const previewRequestChunkSize = numberArg("preview-request-chunk-size", 250);
const applyRequestChunkSize = numberArg("apply-request-chunk-size", 250);
const postApplySettleAttempts = numberArg("post-apply-settle-attempts", 5);
const postApplySettleDelayMs = numberArg("post-apply-settle-delay-ms", 2000);

function parseArgs(argv) {
  return Object.fromEntries(argv.map((arg) => {
    const [key, ...rest] = arg.replace(/^--/, "").split("=");
    return [key, rest.length ? rest.join("=") : "true"];
  }));
}

function boolArg(name, fallback) {
  if (!(name in args)) return fallback;
  return String(args[name]).toLowerCase() === "true";
}

function numberArg(name, fallback) {
  const parsed = Number(args[name]);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : fallback;
}

function q(value) {
  return String(value ?? "").replace(/\\/g, "\\\\").replace(/'/g, "''");
}

function db(sql) {
  const output = execFileSync(mysql, [
    "--ssl=0",
    "-h", "localhost",
    "-P", "3306",
    "-upandora",
    "-ppandora",
    "--batch",
    "--raw",
    "--skip-column-names",
    "--default-character-set=utf8mb4",
    "pandora",
    "-e",
    sql,
  ], {
    cwd: workspace,
    encoding: "utf8",
    windowsHide: true,
    maxBuffer: 256 * 1024 * 1024,
  });
  return output.trim().split(/\r?\n/).filter(Boolean).map((line) => line.split("\t"));
}

function table(sql, columns) {
  return db(sql).map((row) => Object.fromEntries(columns.map((name, index) => [name, row[index] ?? ""])));
}

function number(value) {
  const parsed = Number(String(value ?? "0").replace(/,/g, ""));
  return Number.isFinite(parsed) ? parsed : 0;
}

function chunksOf(items, size) {
  const chunkSize = Math.max(1, number(size) || 1);
  const chunks = [];
  for (let index = 0; index < items.length; index += chunkSize) {
    chunks.push(items.slice(index, index + chunkSize));
  }
  return chunks;
}

function fmt(value) {
  return number(value).toLocaleString("ko-KR");
}

function summarizeRows(rows, keys) {
  const summary = { rows: rows.length };
  for (const key of keys) {
    summary[key] = rows.reduce((sum, row) => sum + number(row[key]), 0);
  }
  return summary;
}

function summarizeResult(result) {
  const selected = result.selected || [];
  const selectedSummary = summarizeRows(selected, [
    "currentChunks",
    "currentTinyChunks",
    "projectedChunks",
    "projectedTinyChunks",
    "projectedShortChunks",
  ]);
  selectedSummary.targets = selected.reduce((counts, item) => {
    counts[item.target] = (counts[item.target] || 0) + 1;
    return counts;
  }, {});
  selectedSummary.maxProjectedLength = selected.reduce(
    (max, item) => Math.max(max, number(item.maxProjectedLength)),
    0,
  );

  const qdrantRows = result.qdrantRows || [];
  const qdrantMismatches = qdrantRows.filter((row) => number(row.dbChunks) !== number(row.qdrantPoints));

  return {
    generatedAt: result.generatedAt,
    mode: result.mode,
    status: result.status,
    options: result.options,
    active: result.active || [],
    runnerStatus: result.runnerStatus,
    qdrantHealth: result.qdrantHealth,
    blockReason: result.blockReason,
    selectedSummary,
    selectedPreview: selected.slice(0, 10).map((item) => ({
      documentId: item.documentId,
      target: item.target,
      title: item.title,
      currentTinyChunks: item.currentTinyChunks,
      projectedChunks: item.projectedChunks,
      projectedTinyChunks: item.projectedTinyChunks,
      maxProjectedLength: item.maxProjectedLength,
    })),
    postApplySummary: summarizeRows(result.statusRows || [], [
      "chunks",
      "tinyChunks",
      "shortChunks",
      "indexed",
      "pending",
      "missingTitle",
      "missingChunkNo",
      "embeddingPending",
    ]),
    qdrantSummary: {
      rows: qdrantRows.length,
      mismatches: qdrantMismatches.length,
      mismatchPreview: qdrantMismatches.slice(0, 5),
    },
    indexFailures: result.indexFailures || [],
    evalResult: result.evalResult,
    postApplyIssues: result.postApplyIssues || [],
    actionCount: (result.actions || []).length,
    actionsPreview: (result.actions || []).slice(0, 10),
  };
}

function printResult(result) {
  console.log(JSON.stringify(compactOutput ? summarizeResult(result) : result, null, 2));
}

function mdTable(rows, columns) {
  if (!rows.length) return "_none_";
  const header = `| ${columns.map((column) => column.label).join(" | ")} |`;
  const sep = `| ${columns.map((column) => column.align === "right" ? "---:" : "---").join(" | ")} |`;
  const body = rows.map((row) => `| ${columns.map((column) => {
    const raw = row[column.key] ?? "";
    const value = column.format === "number" ? fmt(raw) : String(raw);
    return value.replace(/\r?\n/g, " ").replace(/\|/g, "\\|");
  }).join(" | ")} |`);
  return [header, sep, ...body].join("\n");
}

async function postJson(apiPath, params = {}, timeoutMs = 180000) {
  const url = new URL(apiPath, baseUrl);
  for (const [key, value] of Object.entries(params)) {
    if (Array.isArray(value)) {
      for (const item of value) url.searchParams.append(key, String(item));
    } else {
      url.searchParams.set(key, String(value));
    }
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { method: "POST", signal: controller.signal });
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`${response.status} ${response.statusText}: ${text.slice(0, 500)}`);
    }
    return text.trim() ? JSON.parse(text) : {};
  } finally {
    clearTimeout(timer);
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function postJsonWithRetry(apiPath, params = {}, timeoutMs = 180000, attempts = 2) {
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      return await postJson(apiPath, params, timeoutMs);
    } catch (error) {
      lastError = error;
      if (attempt < attempts) {
        await sleep(1000 * attempt);
      }
    }
  }
  throw lastError;
}

async function qdrantCount(documentId, target, collection = vectorStore, activationStatus = "ACTIVE") {
  const response = await fetch(`${qdrantUrl}/collections/${encodeURIComponent(collection)}/points/count`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    signal: AbortSignal.timeout(30000),
    body: JSON.stringify({
      exact: true,
      filter: {
        must: [
          { key: "documentId", match: { value: Number(documentId) } },
          { key: "target", match: { value: target } },
          { key: "activationStatus", match: { value: activationStatus } },
        ],
      },
    }),
  });
  if (!response.ok) {
    throw new Error(`Qdrant count HTTP ${response.status}`);
  }
  const json = await response.json();
  return Number(json.result?.count ?? 0);
}

async function qdrantCollectionInfo(name) {
  try {
    const response = await fetch(`${qdrantUrl}/collections/${encodeURIComponent(name)}`, {
      signal: AbortSignal.timeout(10000),
    });
    const text = await response.text();
    if (!response.ok) {
      return {
        ok: false,
        collection: name,
        status: "HTTP_" + response.status,
        error: text.slice(0, 500),
      };
    }
    const json = text ? JSON.parse(text) : {};
    return {
      ok: true,
      collection: name,
      status: String(json.result?.status || ""),
      optimizerStatus: json.result?.optimizer_status || "",
      pointsCount: Number(json.result?.points_count || 0),
      updateQueueLength: Number(json.result?.update_queue?.length || 0),
    };
  } catch (error) {
    return {
      ok: false,
      collection: name,
      status: "UNREACHABLE",
      error: error.message,
    };
  }
}

function isQdrantGreen(info) {
  return info && info.ok && String(info.status || "").toLowerCase() === "green";
}

function qdrantBlockReason(info) {
  if (!info) {
    return "Qdrant collection status is unknown.";
  }
  if (!info.ok) {
    return `Qdrant collection ${info.collection} is not reachable: ${info.error || info.status}`;
  }
  return `Qdrant collection ${info.collection} is ${info.status}: ${JSON.stringify(info.optimizerStatus || "")}`;
}

async function ensureRunner() {
  const response = await fetch(`${baseUrl}/api/law-data/semantic/batches/scheduler-status`, {
    signal: AbortSignal.timeout(8000),
  });
  if (!response.ok) {
    throw new Error(`batch runner health HTTP ${response.status}`);
  }
  return response.json();
}

function activeBatchJobs() {
  return table(`
SELECT batch_job_id, target, status, query_text, submitted_count, completed_count,
       TIMESTAMPDIFF(MINUTE, created_at, NOW()) AS age_min
FROM semantic_batch_jobs
WHERE target IN ('law','admrul')
  AND status IN ('validating','in_progress','finalizing')
ORDER BY batch_job_id;
`, ["batchJobId", "target", "status", "queryText", "submitted", "completed", "ageMin"]);
}

function candidateRows(target) {
  const longMode = candidateMode === "long";
  const having = longMode
    ? `long_chunks >= ${Number(minLongChunks)}`
    : missingChunkNoMode
      ? "missing_chunk_no > 0"
      : `tiny_chunks >= ${Number(minTinyChunks)}`;
  const order = longMode
    ? "long_chunks DESC, max_len DESC, current_chunks DESC"
    : missingChunkNoMode
      ? "missing_chunk_no DESC, current_chunks DESC"
      : "tiny_chunks DESC, current_chunks DESC";
  return table(`
SELECT d.document_id, d.target, d.title,
       COUNT(*) AS current_chunks,
       SUM(CHAR_LENGTH(c.chunk_text) < 80) AS tiny_chunks,
       SUM(CHAR_LENGTH(c.chunk_text) < 800) AS short_chunks,
       SUM(CHAR_LENGTH(c.chunk_text) > 2500) AS long_chunks,
       SUM(c.chunk_no IS NULL OR TRIM(c.chunk_no)='') AS missing_chunk_no,
       ROUND(AVG(CHAR_LENGTH(c.chunk_text)),1) AS avg_len,
       MAX(CHAR_LENGTH(c.chunk_text)) AS max_len,
       SUM(c.index_status != 'INDEXED') AS not_indexed
FROM law_api_documents d
JOIN law_api_document_chunks c ON c.document_id=d.document_id
WHERE d.target='${q(target)}'
  AND d.use_yn='Y'
  AND c.use_yn='Y'
GROUP BY d.document_id, d.target, d.title
HAVING ${having}
ORDER BY ${order}
LIMIT ${Number(candidatePool)};
`, ["documentId", "target", "title", "currentChunks", "tinyChunks", "shortChunks", "longChunks", "missingChunkNo", "avgLen", "maxLen", "notIndexed"]);
}

function comparePreviewItems(left, right) {
  if (candidateMode === "long") {
    return number(right.currentLongChunks) - number(left.currentLongChunks)
      || number(right.maxProjectedLength) - number(left.maxProjectedLength)
      || number(right.currentTinyChunks) - number(left.currentTinyChunks);
  }
  if (missingChunkNoMode) {
    return number(right.currentMissingChunkNo) - number(left.currentMissingChunkNo)
      || number(right.currentTinyChunks) - number(left.currentTinyChunks)
      || number(right.currentLongChunks) - number(left.currentLongChunks);
  }
  return number(right.currentTinyChunks) - number(left.currentTinyChunks)
    || number(right.currentLongChunks) - number(left.currentLongChunks);
}

function postStatusRows(documentIds, activationStatus = "ACTIVE") {
  if (!documentIds.length) return [];
  if (!["ACTIVE", "CANDIDATE"].includes(activationStatus)) {
    throw new Error(`unsupported chunk activation status: ${activationStatus}`);
  }
  const ids = documentIds.map((id) => Number(id)).filter((id) => id > 0).join(",");
  return table(`
SELECT d.document_id, d.target, d.title,
       COUNT(*) AS chunks,
       SUM(CHAR_LENGTH(c.chunk_text) < 80) AS tiny_chunks,
       SUM(CHAR_LENGTH(c.chunk_text) < 800) AS short_chunks,
       ROUND(AVG(CHAR_LENGTH(c.chunk_text)),1) AS avg_len,
       MIN(CHAR_LENGTH(c.chunk_text)) AS min_len,
       MAX(CHAR_LENGTH(c.chunk_text)) AS max_len,
       SUM(c.index_status='INDEXED') AS indexed,
       SUM(c.index_status='PENDING') AS pending,
       SUM(c.chunk_title IS NULL OR c.chunk_title='') AS missing_title,
       SUM(c.chunk_no IS NULL OR TRIM(c.chunk_no)='') AS missing_chunk_no,
       SUM(e.chunk_id IS NULL OR e.status != 'INDEXED' OR COALESCE(e.content_hash, '') != COALESCE(c.content_hash, '')) AS embedding_pending
FROM law_api_documents d
JOIN law_api_document_chunks c ON c.document_id=d.document_id
LEFT JOIN law_api_chunk_embeddings e
  ON e.chunk_id=c.chunk_id
 AND e.embedding_model='${q(model)}'
 AND e.vector_store='${q(vectorStore)}'
WHERE d.document_id IN (${ids})
  AND d.use_yn='Y'
  AND c.use_yn='Y'
  AND c.activation_status='${activationStatus}'
GROUP BY d.document_id, d.target, d.title
ORDER BY d.target, d.document_id;
`, ["documentId", "target", "title", "chunks", "tinyChunks", "shortChunks", "avgLen", "minLen", "maxLen", "indexed", "pending", "missingTitle", "missingChunkNo", "embeddingPending"]);
}

function activeVersionPointIds(documentId) {
  const rows = table(`
SELECT c.chunk_version, c.chunk_id
FROM law_api_document_chunks c
WHERE c.document_id=${Number(documentId)}
  AND c.use_yn='Y'
  AND c.activation_status='ACTIVE'
ORDER BY c.chunk_version, c.chunk_id;
`, ["chunkVersion", "chunkId"]);
  const version = rows.length ? number(rows[0].chunkVersion) : 0;
  if (rows.some((row) => number(row.chunkVersion) !== version)) {
    throw new Error(`document ${documentId} has multiple active chunk versions`);
  }
  return { version, pointIds: rows.map((row) => number(row.chunkId)).filter((id) => id > 0) };
}

function manifestIdentityForSelection(selected) {
  const stableSelection = (selected || []).map((item) => ({
    documentId: number(item.documentId),
    target: String(item.target || ""),
    projectedChunks: number(item.projectedChunks),
  })).sort((left, right) => left.documentId - right.documentId || left.target.localeCompare(right.target));
  return `selection-fingerprint:${crypto.createHash("sha256").update(JSON.stringify(stableSelection)).digest("hex")}`;
}

function candidateArtifact(candidate, previous, manifestIdentity) {
  const documentId = number(candidate.documentId);
  const previousVersion = number(previous?.version);
  const rollbackPath = previousVersion > 0
    ? `/api/law-data/chunks/rollback-version?documentId=${documentId}&retiredVersion=${previousVersion}`
    : null;
  return {
    documentId,
    target: String(candidate.target || ""),
    manifestIdentity,
    oldChunkVersion: previousVersion || null,
    oldPointIds: previous?.pointIds || [],
    newChunkVersion: number(candidate.chunkVersion),
    newPointIds: (candidate.chunkIds || []).map(number).filter((id) => id > 0),
    rollbackApiPath: rollbackPath,
    rollbackCommand: rollbackPath ? `Invoke-RestMethod -Method Post -Uri '${baseUrl}${rollbackPath}'` : null,
  };
}

async function previewTarget(target, candidates) {
  if (!candidates.length) return { target, items: [] };
  const chunkSize = Math.max(1, previewRequestChunkSize);
  const items = [];
  const summaries = [];
  for (let index = 0; index < candidates.length; index += chunkSize) {
    const slice = candidates.slice(index, index + chunkSize);
    const documentIds = slice.map((row) => row.documentId);
    const preview = await postJson("/api/law-data/chunks/rebuild-preview-by-document-ids", {
      target,
      documentIds,
    }, 180000);
    items.push(...(preview.items || []));
    summaries.push({
      offset: index,
      requestedDocuments: documentIds.length,
      items: (preview.items || []).length,
    });
  }
  return { target, items, requestChunks: summaries };
}

function pickWave(previewItems) {
  const selected = [];
  let projected = 0;
  for (const item of previewItems) {
    const itemProjectedChunks = number(item.projectedChunks);
    if (itemProjectedChunks <= 0) {
      continue;
    }
    if (candidateMode === "long"
      && !allowProjectedLong
      && number(item.maxProjectedLength) > maxProjectedLength) {
      continue;
    }
    if (!isProjectedTinyAllowed(item)) {
      continue;
    }
    if (maxProjectedChunksPerDoc > 0 && itemProjectedChunks > maxProjectedChunksPerDoc) {
      continue;
    }
    const nextProjected = projected + itemProjectedChunks;
    if (selected.length >= maxDocs) break;
    if (nextProjected > maxProjectedChunks) continue;
    selected.push(item);
    projected = nextProjected;
  }
  return selected;
}

function isProjectedTinyAllowed(item) {
  const projectedTiny = number(item.projectedTinyChunks);
  if (allowProjectedTiny || projectedTiny <= 0) {
    return true;
  }
  if (!allowNetTinyReduction) {
    return false;
  }
  return projectedTiny < number(item.currentTinyChunks ?? item.tinyChunks)
    && number(item.projectedChunks) < number(item.currentChunks);
}

async function main() {
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  const generatedAt = new Date().toISOString();
  const runnerStatus = await ensureRunner();
  const qdrantHealth = await qdrantCollectionInfo(vectorStore);
  const active = activeBatchJobs();
	const baselineManifestId = String(process.env.RAG_BASELINE_MANIFEST_ID || "").trim();
	if (apply && !baselineManifestId) {
		const result = { generatedAt, mode: "apply", status: "BLOCKED_BASELINE_MANIFEST_REQUIRED", runnerStatus, active, qdrantHealth };
		writeReports(result);
		printResult(result);
		process.exitCode = 5;
		return;
	}
  if (apply && requireQdrantGreen && !isQdrantGreen(qdrantHealth)) {
    const result = {
      generatedAt,
      mode: "apply",
      status: "BLOCKED_QDRANT_NOT_GREEN",
      qdrantHealth,
      blockReason: qdrantBlockReason(qdrantHealth),
      runnerStatus,
      active,
    };
    writeReports(result);
    printResult(result);
    process.exitCode = 4;
    return;
  }
  const actions = [];
  if (active.length) {
    const result = { generatedAt, mode: apply ? "apply" : "dry-run", status: "BLOCKED_ACTIVE_BATCH", active, runnerStatus, qdrantHealth };
    writeReports(result);
    printResult(result);
    process.exitCode = 2;
    return;
  }

  const targetResults = [];
  const previewItems = [];
  for (const target of targets) {
    if (!["law", "admrul"].includes(target)) {
      throw new Error(`unsupported target: ${target}`);
    }
    const candidates = candidateRows(target);
    const candidateByDocumentId = new Map(candidates.map((row) => [String(row.documentId), row]));
    const preview = await previewTarget(target, candidates);
    const items = (preview.items || [])
      .map((item) => {
        const candidate = candidateByDocumentId.get(String(item.documentId)) || {};
        return {
          ...item,
          currentLongChunks: candidate.longChunks ?? "",
          currentMissingChunkNo: candidate.missingChunkNo ?? "",
        };
      })
      .sort(comparePreviewItems);
    targetResults.push({ target, candidates, preview, selected: [] });
    for (const item of items) previewItems.push(item);
  }

  const allSelected = pickWave(previewItems.sort(comparePreviewItems));
  for (const targetResult of targetResults) {
    targetResult.selected = allSelected.filter((item) => item.target === targetResult.target);
  }

  if (!allSelected.length) {
    const result = { generatedAt, mode: apply ? "apply" : "dry-run", status: "NO_CANDIDATES", targetResults, runnerStatus, active, qdrantHealth };
    writeReports(result);
    printResult(result);
    return;
  }

  const projectedTiny = allSelected.reduce((sum, item) => sum + number(item.projectedTinyChunks), 0);
  if (projectedTiny > 0 && !allowProjectedTiny && allSelected.some((item) => !isProjectedTinyAllowed(item))) {
    const result = { generatedAt, mode: apply ? "apply" : "dry-run", status: "BLOCKED_PROJECTED_TINY", projectedTiny, targetResults, runnerStatus, active, qdrantHealth };
    writeReports(result);
    printResult(result);
    process.exitCode = 3;
    return;
  }

  const documentIds = allSelected.map((item) => Number(item.documentId));
	const selectionFingerprint = manifestIdentityForSelection(allSelected);
	const manifestIdentity = apply ? baselineManifestId : null;
  let rebuildResults = [];
  let candidateResults = [];
  let candidateArtifacts = [];
  let indexResults = [];
  const indexFailures = [];
  let statusRows = [];
  let qdrantRows = [];
  let evalResult = null;
  const postApplyIssues = [];

  if (apply) {
    const previousPointsByDocumentId = new Map(allSelected.map((item) => [
      `${item.target}:${item.documentId}`,
      activeVersionPointIds(item.documentId),
    ]));
    for (const target of targets) {
      const ids = allSelected.filter((item) => item.target === target).map((item) => item.documentId);
      if (!ids.length) continue;
      for (const documentId of ids) {
        const candidate = await postJson("/api/law-data/chunks/create-candidate", {
          target,
          documentId,
			previewApprovalToken: allSelected.find((item) => item.target === target && String(item.documentId) === String(documentId))?.approvalToken || "",
        }, 300000);
        const candidateWithTarget = { ...candidate, target };
        candidateResults.push(candidateWithTarget);
        candidateArtifacts.push(candidateArtifact(
          candidateWithTarget,
          previousPointsByDocumentId.get(`${target}:${documentId}`),
          manifestIdentity,
        ));
        actions.push(`create-candidate:${target}:${documentId}:v${candidate.chunkVersion}`);
      }
    }

    statusRows = postStatusRows(documentIds, "CANDIDATE");

    if (indexMode === "direct") {
      for (const target of targets) {
        const ids = allSelected.filter((item) => item.target === target).map((item) => item.documentId);
        if (!ids.length) continue;
        for (const id of ids) {
          const candidate = candidateResults.find((item) => item.target === target && String(item.documentId) === String(id));
          const limit = number(candidate?.expectedChunkCount);
          if (limit <= 0) {
            actions.push(`index-direct-skip:${target}:${id}`);
            continue;
          }
          try {
            const indexed = await postJsonWithRetry("/api/law-data/semantic/index-candidate", {
              target,
              documentId: id,
              candidateVersion: candidate.chunkVersion,
              limit,
            }, 600000, 2);
            indexResults.push({ ...indexed, target, documentId: id });
            actions.push(`index-direct:${target}:${id}`);
          } catch (error) {
            const failure = { target, documentId: id, error: error.message };
            indexFailures.push(failure);
            indexResults.push({ target, documentId: id, status: "FAILED", error: error.message });
            actions.push(`index-direct-failed:${target}:${id}`);
          }
        }
      }
      statusRows = postStatusRows(documentIds, "CANDIDATE");
    } else if (indexMode === "batch") {
      throw new Error("Candidate activation requires direct index and verification; batch mode is not supported.");
    } else if (!["none", ""].includes(indexMode)) {
      throw new Error(`unsupported index mode: ${indexMode}`);
    }

    if (indexMode !== "batch") {
      let embeddingPending = 0;
      let qdrantMismatches = [];
      const settleAttempts = Math.max(1, postApplySettleAttempts);
      for (let settleAttempt = 1; settleAttempt <= settleAttempts; settleAttempt += 1) {
        qdrantRows = [];
        for (const row of statusRows) {
          qdrantRows.push({
            documentId: row.documentId,
            target: row.target,
            dbChunks: row.chunks,
            qdrantPoints: await qdrantCount(row.documentId, row.target, `${vectorStore}_candidate`, "CANDIDATE"),
            collection: `${vectorStore}_candidate`,
            activationStatus: "CANDIDATE",
          });
        }
        embeddingPending = statusRows.reduce((sum, row) => sum + number(row.embeddingPending), 0);
        qdrantMismatches = qdrantRows.filter((row) => number(row.dbChunks) !== number(row.qdrantPoints));
        if (embeddingPending === 0 && qdrantMismatches.length === 0) {
          break;
        }
        if (settleAttempt < settleAttempts) {
          actions.push(`post-apply-settle-wait:${settleAttempt}:embeddingPending=${embeddingPending}:qdrantMismatches=${qdrantMismatches.length}`);
          await sleep(Math.max(250, postApplySettleDelayMs));
          statusRows = postStatusRows(documentIds, "CANDIDATE");
        }
      }
      if (embeddingPending > 0) {
        postApplyIssues.push(`embedding-pending:${embeddingPending}`);
      }
      if (qdrantMismatches.length > 0) {
        postApplyIssues.push(`qdrant-mismatch:${qdrantMismatches.length}`);
      }
      if (indexFailures.length === 0 && postApplyIssues.length === 0) {
        for (const candidate of candidateResults) {
          const activated = await postJson("/api/law-data/chunks/activate-candidate", {
            documentId: candidate.documentId,
            candidateVersion: candidate.chunkVersion,
          }, 300000);
          rebuildResults.push(activated);
          actions.push(`activate:${candidate.target}:${candidate.documentId}:v${candidate.chunkVersion}:${activated.activated}`);
          if (!activated.activated) {
            postApplyIssues.push(`candidate-activation-blocked:${candidate.target}:${candidate.documentId}:v${candidate.chunkVersion}`);
          }
        }
		if (postApplyIssues.length === 0) {
			statusRows = postStatusRows(documentIds, "ACTIVE");
			qdrantRows = [];
			for (const row of statusRows) {
				qdrantRows.push({
					documentId: row.documentId,
					target: row.target,
					dbChunks: row.chunks,
					qdrantPoints: await qdrantCount(row.documentId, row.target, vectorStore, "ACTIVE"),
					collection: vectorStore,
					activationStatus: "ACTIVE",
				});
			}
			if (qdrantRows.some((row) => number(row.dbChunks) !== number(row.qdrantPoints))) {
				postApplyIssues.push("active-qdrant-mismatch-after-activation");
			}
		}
      }
    }

    if (runEval) {
      if (indexMode === "batch") {
        evalResult = {
          status: "SKIPPED",
          stdout: "Skipped because Batch-submitted chunks are not searchable until poll/ingest completes.",
          stderr: "",
        };
        actions.push("rag-eval-gate-skipped-batch-pending");
      } else {
      const evalRun = spawnSync(process.execPath, ["scripts\\rag-eval-gate.js"], {
        cwd: workspace,
        encoding: "utf8",
        windowsHide: true,
        timeout: 360000,
      });
      evalResult = {
        status: evalRun.status,
        stdout: evalRun.stdout.trim(),
        stderr: evalRun.stderr.trim(),
      };
      if (evalRun.status !== 0) {
        throw new Error(`rag eval failed: ${evalResult.stderr || evalResult.stdout}`);
      }
      actions.push("rag-eval-gate");
      }
    }
  }

  const finalStatus = !apply
    ? "DRY_RUN"
    : indexFailures.length
      ? "APPLIED_WITH_INDEX_FAILURES"
      : postApplyIssues.length
        ? "APPLIED_WITH_ISSUES"
        : "APPLIED";
  const result = {
    generatedAt,
    manifestIdentity,
		selectionFingerprint,
    mode: apply ? "apply" : "dry-run",
    status: finalStatus,
    options: {
      targets,
      maxDocs,
      candidatePool,
      maxProjectedChunks,
      maxProjectedChunksPerDoc,
      minTinyChunks,
      minLongChunks,
      candidateMode,
      maxProjectedLength,
      allowProjectedTiny,
      allowNetTinyReduction,
      allowProjectedLong,
      indexMode,
      runEval,
      previewRequestChunkSize,
      applyRequestChunkSize,
      requireQdrantGreen,
      postApplySettleAttempts,
      postApplySettleDelayMs,
    },
    runnerStatus,
    active,
    qdrantHealth,
    targetResults,
    selected: allSelected,
    rebuildResults,
    candidateResults,
    candidateArtifacts,
    indexResults,
    indexFailures,
    statusRows,
    qdrantRows,
    evalResult,
    actions,
    postApplyIssues,
  };
  writeReports(result);
  printResult(result);
  if (indexFailures.length || postApplyIssues.length) {
    process.exitCode = 1;
  }
}

function writeReports(result) {
  fs.writeFileSync(jsonPath, JSON.stringify(result, null, 2), "utf8");
  const selectedRows = (result.selected || []).map((item) => ({
    documentId: item.documentId,
    target: item.target,
    title: item.title,
    currentChunks: item.currentChunks,
    projectedChunks: item.projectedChunks,
    currentTinyChunks: item.currentTinyChunks,
    currentLongChunks: item.currentLongChunks,
    projectedTinyChunks: item.projectedTinyChunks,
    projectedShortChunks: item.projectedShortChunks,
    maxProjectedLength: item.maxProjectedLength,
  }));
  const candidateRows = (result.targetResults || []).flatMap((row) => (row.candidates || []).slice(0, 10));
  const lines = [
    "# Law Parent-Child Rechunk Wave",
    "",
    `- Generated at: ${result.generatedAt}`,
    `- Status: ${result.status}`,
    `- Mode: ${result.mode}`,
    result.blockReason ? `- Block reason: ${result.blockReason}` : "",
    result.qdrantHealth
      ? `- Qdrant ${result.qdrantHealth.collection}: ${result.qdrantHealth.status}${result.qdrantHealth.ok ? "" : ` (${result.qdrantHealth.error || "error"})`}`
      : "",
    `- Base URL: ${baseUrl}`,
    `- Vector store: ${vectorStore}`,
    `- Candidate mode: ${candidateMode}`,
    `- Max projected length: ${maxProjectedLength}`,
    `- Max projected chunks per document: ${maxProjectedChunksPerDoc > 0 ? maxProjectedChunksPerDoc : "none"}`,
    "",
    "## Selected Wave",
    "",
    mdTable(selectedRows, [
      { key: "documentId", label: "Document", align: "right" },
      { key: "target", label: "Target" },
      { key: "title", label: "Title" },
      { key: "currentChunks", label: "Current", align: "right", format: "number" },
      { key: "projectedChunks", label: "Projected", align: "right", format: "number" },
      { key: "currentTinyChunks", label: "Micro<80 Now", align: "right", format: "number" },
      { key: "currentLongChunks", label: "Long>2500 Now", align: "right", format: "number" },
      { key: "projectedTinyChunks", label: "Micro<80 After", align: "right", format: "number" },
      { key: "projectedShortChunks", label: "Short<800 After", align: "right", format: "number" },
      { key: "maxProjectedLength", label: "Max Len", align: "right", format: "number" },
    ]),
    "",
    "## Candidate Pool",
    "",
    mdTable(candidateRows, [
      { key: "documentId", label: "Document", align: "right" },
      { key: "target", label: "Target" },
      { key: "title", label: "Title" },
      { key: "currentChunks", label: "Chunks", align: "right", format: "number" },
      { key: "tinyChunks", label: "Micro<80", align: "right", format: "number" },
      { key: "shortChunks", label: "Short<800", align: "right", format: "number" },
      { key: "longChunks", label: "Long>2500", align: "right", format: "number" },
      { key: "avgLen", label: "Avg Len", align: "right", format: "number" },
      { key: "maxLen", label: "Max Len", align: "right", format: "number" },
    ]),
    "",
    "## Post Apply Status",
    "",
    mdTable(result.statusRows || [], [
      { key: "documentId", label: "Document", align: "right" },
      { key: "target", label: "Target" },
      { key: "title", label: "Title" },
      { key: "chunks", label: "Chunks", align: "right", format: "number" },
      { key: "tinyChunks", label: "Micro<80", align: "right", format: "number" },
      { key: "shortChunks", label: "Short<800", align: "right", format: "number" },
      { key: "minLen", label: "Min Len", align: "right", format: "number" },
      { key: "missingTitle", label: "No Title", align: "right", format: "number" },
      { key: "indexed", label: "Indexed", align: "right", format: "number" },
      { key: "pending", label: "Pending", align: "right", format: "number" },
      { key: "embeddingPending", label: "Embed Pending", align: "right", format: "number" },
    ]),
    "",
    "## Qdrant Counts",
    "",
    mdTable(result.qdrantRows || [], [
      { key: "documentId", label: "Document", align: "right" },
      { key: "target", label: "Target" },
      { key: "dbChunks", label: "DB Chunks", align: "right", format: "number" },
      { key: "qdrantPoints", label: "Qdrant", align: "right", format: "number" },
    ]),
    "",
    "## Index Failures",
    "",
    mdTable(result.indexFailures || [], [
      { key: "documentId", label: "Document", align: "right" },
      { key: "target", label: "Target" },
      { key: "error", label: "Error" },
    ]),
    "",
    "## Eval",
    "",
    result.evalResult ? `- status=${result.evalResult.status}\n- stdout=${result.evalResult.stdout}` : "_not run_",
    "",
    "## Actions",
    "",
    (result.actions || []).map((action) => `- ${action}`).join("\n") || "_none_",
  ];
  fs.writeFileSync(outPath, lines.join("\n"), "utf8");
}

if (require.main === module) main().catch((error) => {
  const result = {
    generatedAt: new Date().toISOString(),
    mode: apply ? "apply" : "dry-run",
    status: "ERROR",
    error: error.stack || error.message,
  };
  writeReports(result);
  console.error(error.stack || error.message);
  process.exitCode = 1;
});

module.exports = { candidateArtifact, manifestIdentityForSelection };
