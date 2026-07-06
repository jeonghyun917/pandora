const { spawnSync, execFileSync } = require("node:child_process");
const path = require("node:path");

const workspace = path.resolve(__dirname, "..");
const nodeExe = process.execPath;
const waveScript = path.join(__dirname, "law-parent-child-rechunk-wave.js");
const evalScript = path.join(__dirname, "rag-eval-gate.js");
const mysql = process.env.MARIADB_EXE || "C:\\Program Files\\MariaDB 12.2\\bin\\mariadb.exe";

if (process.argv.includes("--help") || process.argv.includes("-h")) {
  console.log(`
Usage:
  node scripts/law-parent-child-rechunk-bulk.js [options]

Common options:
  --apply=true|false                 Execute the rechunk/index wave. Defaults to true for existing automation compatibility.
  --targets=law,admrul               Comma-separated targets.
  --waves=1                          Number of wave iterations.
  --candidate=tiny|long|missing...    Candidate selection mode.
  --max-docs=30                      Documents per target/wave.
  --candidate-pool=80                Candidate pool size per target/wave.
  --max-projected-chunks=5000        Safety cap for projected chunks per target/wave.
  --min-tiny=100                     Minimum tiny chunks for tiny mode.
  --index=direct|batch|none          Indexing mode after rechunk.
  --eval-every=0                     Run rag-eval-gate every N waves when > 0.
  --compact=true|false               Compact selected-document output.
  --base-url=http://127.0.0.1:18080  Batch runner base URL.

Examples:
  node scripts/law-parent-child-rechunk-bulk.js --apply=false --targets=law --candidate=tiny
  node scripts/law-parent-child-rechunk-bulk.js --apply=true --targets=law,admrul --waves=1 --candidate=tiny --max-docs=50 --index=direct
`.trim());
  process.exit(0);
}

function arg(name, fallback = "") {
  const prefix = `--${name}=`;
  const found = process.argv.slice(2).find((item) => item.startsWith(prefix));
  return found ? found.slice(prefix.length) : fallback;
}

function flag(name, fallback = false) {
  const value = arg(name, "");
  if (!value) return fallback;
  return ["1", "true", "yes", "y"].includes(value.toLowerCase());
}

const options = {
  targets: arg("targets", "law,admrul").split(",").map((item) => item.trim()).filter(Boolean),
  waves: Number(arg("waves", "1")),
  candidate: arg("candidate", "tiny"),
  maxDocs: Number(arg("max-docs", "30")),
  candidatePool: Number(arg("candidate-pool", "80")),
  maxProjectedChunks: Number(arg("max-projected-chunks", "5000")),
  minTiny: Number(arg("min-tiny", "100")),
  minLong: Number(arg("min-long", "1")),
  maxProjectedLength: Number(arg("max-projected-length", "2500")),
  index: arg("index", "direct"),
  apply: flag("apply", true),
  retryFailures: flag("retry-failures", true),
  retryAttempts: Number(arg("retry-attempts", "3")),
  retryDelayMs: Number(arg("retry-delay-ms", "5000")),
  evalEvery: Number(arg("eval-every", "0")),
  baseUrl: arg("base-url", process.env.LAW_BATCH_BASE_URL || "http://127.0.0.1:18080"),
  compact: flag("compact", false),
  finalizeWaitMinutes: Number(arg("finalize-wait-minutes", "10")),
  finalizePollMs: Number(arg("finalize-poll-ms", "10000")),
};

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function runNode(script, args, env = {}) {
  const result = spawnSync(nodeExe, [script, ...args], {
    cwd: workspace,
    encoding: "utf8",
    windowsHide: true,
    env: { ...process.env, ...env },
    maxBuffer: 256 * 1024 * 1024,
  });
  return result;
}

function parseJson(output) {
  const trimmed = String(output || "").trim();
  if (!trimmed) return null;
  const start = trimmed.indexOf("{");
  const end = trimmed.lastIndexOf("}");
  if (start < 0 || end < start) return null;
  return JSON.parse(trimmed.slice(start, end + 1));
}

function q(value) {
  return String(value ?? "").replace(/\\/g, "\\\\").replace(/'/g, "''");
}

function db(sql) {
  const output = execFileSync(mysql, [
    "--ssl=0",
    "-upandora",
    "-ppandora",
    "--batch",
    "--raw",
    "--default-character-set=utf8mb4",
    "pandora",
    "-e",
    sql,
  ], {
    cwd: workspace,
    encoding: "utf8",
    windowsHide: true,
    maxBuffer: 128 * 1024 * 1024,
  });
  return output.trim();
}

function dbRows(sql, columns) {
  const output = execFileSync(mysql, [
    "--ssl=0",
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
    maxBuffer: 128 * 1024 * 1024,
  }).trim();
  if (!output) return [];
  return output.split(/\r?\n/).filter(Boolean).map((line) => {
    const values = line.split("\t");
    return Object.fromEntries(columns.map((name, index) => [name, values[index] ?? ""]));
  });
}

function aggregateStatus() {
  return db(`
SELECT target,
       COUNT(*) total_chunks,
       SUM(index_status='INDEXED') indexed,
       SUM(index_status<>'INDEXED') pending,
       SUM(CHAR_LENGTH(chunk_text)>2500) long_gt_2500,
       SUM(CHAR_LENGTH(chunk_text)<80) tiny_lt_80,
       SUM(chunk_title IS NULL OR TRIM(chunk_title)='') missing_title,
       SUM(chunk_no IS NULL OR TRIM(chunk_no)='') missing_chunk_no,
       MAX(CHAR_LENGTH(chunk_text)) max_len
FROM law_api_document_chunks c
JOIN law_api_documents d ON d.document_id=c.document_id
WHERE c.use_yn='Y' AND d.use_yn='Y'
GROUP BY target;
SELECT COUNT(*) active_jobs
FROM semantic_batch_jobs
WHERE target IN ('law','admrul')
  AND status IN ('validating','in_progress','finalizing');
`);
}

function globalCounters() {
  const rows = dbRows(`
SELECT SUM(c.index_status<>'INDEXED') pending_chunks,
       SUM(CHAR_LENGTH(c.chunk_text)>2500) long_chunks,
       SUM(chunk_title IS NULL OR TRIM(chunk_title)='') missing_titles,
       (
         SELECT COUNT(*)
         FROM semantic_batch_jobs
         WHERE target IN ('law','admrul')
           AND status IN ('validating','in_progress','finalizing')
       ) active_jobs
FROM law_api_document_chunks c
JOIN law_api_documents d ON d.document_id=c.document_id
WHERE c.use_yn='Y'
  AND d.use_yn='Y'
  AND d.target IN ('law','admrul');
`, ["pendingChunks", "longChunks", "missingTitles", "activeJobs"]);
  return rows[0] || {
    pendingChunks: "0",
    longChunks: "0",
    missingTitles: "0",
    activeJobs: "0",
  };
}

function toNumber(value) {
  const parsed = Number(String(value ?? "0").replace(/,/g, ""));
  return Number.isFinite(parsed) ? parsed : 0;
}

function documentStatus(target, documentId) {
  const rows = dbRows(`
SELECT d.target,
       d.document_id,
       COUNT(*) total_chunks,
       SUM(c.index_status='INDEXED') indexed_chunks,
       SUM(c.index_status<>'INDEXED') pending_chunks,
       SUM(COALESCE(e.status,'NO_EMBED')='BATCH_SUBMITTED') batch_submitted_embeddings,
       SUM(COALESCE(e.status,'NO_EMBED')='FAILED') failed_embeddings,
       SUM(e.chunk_id IS NULL) missing_embeddings
FROM law_api_documents d
JOIN law_api_document_chunks c ON c.document_id=d.document_id
LEFT JOIN law_api_chunk_embeddings e
  ON e.chunk_id=c.chunk_id
 AND e.vector_store='law_chunks'
WHERE d.document_id=${Number(documentId)}
  AND d.target='${q(target)}'
  AND c.use_yn='Y'
GROUP BY d.target, d.document_id;
`, [
    "target",
    "documentId",
    "totalChunks",
    "indexedChunks",
    "pendingChunks",
    "batchSubmittedEmbeddings",
    "failedEmbeddings",
    "missingEmbeddings",
  ]);
  return rows[0] || {
    target,
    documentId: String(documentId),
    totalChunks: "0",
    indexedChunks: "0",
    pendingChunks: "0",
    batchSubmittedEmbeddings: "0",
    failedEmbeddings: "0",
    missingEmbeddings: "0",
  };
}

function summarizeSelected(selected) {
  const rows = selected || [];
  if (!options.compact) {
    return rows.map((item) => ({
      documentId: item.documentId,
      target: item.target,
      title: item.title,
      currentChunks: item.currentChunks,
      projectedChunks: item.projectedChunks,
      currentTinyChunks: item.currentTinyChunks,
    }));
  }
  return {
    documents: rows.length,
    currentChunks: rows.reduce((sum, item) => sum + toNumber(item.currentChunks), 0),
    projectedChunks: rows.reduce((sum, item) => sum + toNumber(item.projectedChunks), 0),
    currentTinyChunks: rows.reduce((sum, item) => sum + toNumber(item.currentTinyChunks), 0),
    sample: rows.slice(0, 5).map((item) => ({
      documentId: item.documentId,
      title: item.title,
      currentChunks: item.currentChunks,
      projectedChunks: item.projectedChunks,
      currentTinyChunks: item.currentTinyChunks,
    })),
  };
}

async function postIndex(target, documentId) {
  const url = new URL("/api/law-data/semantic/index-documents", options.baseUrl);
  url.searchParams.set("target", target);
  url.searchParams.append("documentIds", String(documentId));
  url.searchParams.set("limit", "10000");
  const response = await fetch(url, { method: "POST" });
  const body = await response.text();
  if (!response.ok) {
    throw new Error(`${response.status} ${body}`);
  }
  return JSON.parse(body);
}

async function postApi(apiPath, timeoutMs = 180000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(`${options.baseUrl}${apiPath}`, {
      method: "POST",
      signal: controller.signal,
    });
    const body = await response.text();
    if (!response.ok) {
      throw new Error(`${response.status} ${body}`);
    }
    return body.trim() ? JSON.parse(body) : {};
  } finally {
    clearTimeout(timer);
  }
}

async function waitForCleanStatus() {
  if (!options.apply) {
    return { status: "SKIPPED_DRY_RUN", checks: [] };
  }
  const checks = [];
  const deadline = Date.now() + Math.max(0, options.finalizeWaitMinutes) * 60_000;
  while (true) {
    const status = globalCounters();
    checks.push(status);
    if (
      toNumber(status.pendingChunks) === 0
      && toNumber(status.longChunks) === 0
      && toNumber(status.activeJobs) === 0
    ) {
      return { status: "CLEAN", checks };
    }
    if (Date.now() >= deadline) {
      return { status: "DIRTY_TIMEOUT", checks };
    }
    try {
      await postApi("/api/law-data/semantic/batches/poll", 180000);
    } catch (error) {
      checks.push({ pollError: error.message.slice(0, 500) });
    }
    await sleep(options.finalizePollMs);
  }
}

async function retryIndexFailures(failures) {
  const retried = [];
  for (const failure of failures) {
    let lastError = "";
    let lastStatus = null;
    for (let attempt = 1; attempt <= options.retryAttempts; attempt += 1) {
      try {
        const result = await postIndex(failure.target, failure.documentId);
        lastStatus = documentStatus(failure.target, failure.documentId);
        const pending = toNumber(lastStatus.pendingChunks);
        if (pending > 0) {
          lastError = `retry left ${pending} pending chunks`;
          if (attempt < options.retryAttempts) {
            await sleep(options.retryDelayMs);
          }
          continue;
        }
        retried.push({
          target: failure.target,
          documentId: failure.documentId,
          attempt,
          status: "INDEXED",
          requested: result.requested,
          indexed: result.indexed,
          dbStatus: lastStatus,
        });
        lastError = "";
        break;
      } catch (error) {
        lastError = error.message;
        if (attempt < options.retryAttempts) {
          await sleep(options.retryDelayMs);
        }
      }
    }
    if (lastError) {
      retried.push({
        target: failure.target,
        documentId: failure.documentId,
        status: "FAILED",
        error: lastError,
        dbStatus: lastStatus || documentStatus(failure.target, failure.documentId),
      });
    }
  }
  return retried;
}

function runEval() {
  const result = runNode(evalScript, [], {
    RAG_EVAL_CASE_BATCH_SIZE: "5",
    RAG_EVAL_REQUEST_TIMEOUT_MS: "120000",
    RAG_EVAL_INTER_BATCH_SLEEP_MS: "500",
  });
  return {
    exitCode: result.status,
    stdout: result.stdout.trim(),
    stderr: result.stderr.trim(),
  };
}

async function main() {
  const waves = [];
  let evalResult = null;
  for (let wave = 1; wave <= options.waves; wave += 1) {
    for (const target of options.targets) {
      const args = [
        `--apply=${options.apply}`,
        `--candidate=${options.candidate}`,
        `--target=${target}`,
        `--max-docs=${options.maxDocs}`,
        `--candidate-pool=${options.candidatePool}`,
        `--max-projected-chunks=${options.maxProjectedChunks}`,
        `--min-tiny=${options.minTiny}`,
        `--min-long=${options.minLong}`,
        `--max-projected-length=${options.maxProjectedLength}`,
        `--index=${options.index}`,
      ];
      const executed = runNode(waveScript, args);
      const parsed = parseJson(executed.stdout);
      const indexFailures = parsed?.indexFailures || [];
      const retried = options.retryFailures && indexFailures.length
        ? await retryIndexFailures(indexFailures)
        : [];
      waves.push({
        wave,
        target,
        exitCode: executed.status,
        status: parsed?.status || "UNKNOWN",
        selected: summarizeSelected(parsed?.selected),
        rebuildResults: parsed?.rebuildResults || [],
        indexFailures,
        retried,
      });
      const unresolved = retried.filter((item) => item.status === "FAILED");
      if ((executed.status || 0) !== 0 && (!indexFailures.length || unresolved.length)) {
        const summary = {
          generatedAt: new Date().toISOString(),
          status: "FAILED",
          options,
          waves,
          aggregateStatus: aggregateStatus(),
        };
        console.log(JSON.stringify(summary, null, 2));
        process.exit(1);
      }
      if (options.apply) {
        const targetFinalization = await waitForCleanStatus();
        waves[waves.length - 1].finalization = targetFinalization;
        if (targetFinalization.status !== "CLEAN") {
          const summary = {
            generatedAt: new Date().toISOString(),
            status: "PENDING_ACTIVE",
            options,
            waves,
            aggregateStatus: aggregateStatus(),
          };
          console.log(JSON.stringify(summary, null, 2));
          process.exit(1);
        }
      }
    }
    if (options.evalEvery > 0 && wave % options.evalEvery === 0) {
      evalResult = runEval();
      if (evalResult.exitCode !== 0) {
        const summary = {
          generatedAt: new Date().toISOString(),
          status: "EVAL_FAILED",
          options,
          waves,
          evalResult,
          aggregateStatus: aggregateStatus(),
        };
        console.log(JSON.stringify(summary, null, 2));
        process.exit(1);
      }
    }
  }

  const finalization = await waitForCleanStatus();
  const summary = {
    generatedAt: new Date().toISOString(),
    status: finalization.status === "CLEAN" || finalization.status === "SKIPPED_DRY_RUN" ? "OK" : "PENDING_ACTIVE",
    options,
    waves,
    evalResult,
    finalization,
    aggregateStatus: aggregateStatus(),
  };
  console.log(JSON.stringify(summary, null, 2));
  if (summary.status !== "OK") {
    process.exit(1);
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
