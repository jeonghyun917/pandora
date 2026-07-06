const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const workspace = path.resolve(__dirname, "..");
const mysql = process.env.MARIADB_EXE || "C:\\Program Files\\MariaDB 12.2\\bin\\mariadb.exe";
const baseUrl = process.env.PANDORA_BATCH_RUNNER_URL || "http://127.0.0.1:18080";
const model = process.env.PANDORA_EMBEDDING_MODEL || "text-embedding-3-small";
const vectorStore = process.env.PANDORA_LAW_VECTOR_STORE || "law_chunks";
const outPath = path.resolve(workspace, "logs", "law-parent-child-batch-guard-latest.md");
const jsonPath = path.resolve(workspace, "logs", "law-parent-child-batch-guard-latest.json");

const args = Object.fromEntries(process.argv.slice(2).map((arg) => {
  const [key, ...rest] = arg.replace(/^--/, "").split("=");
  return [key, rest.length ? rest.join("=") : "true"];
}));

const apply = String(args.apply || "false").toLowerCase() === "true";
const fallbackDirect = String(args["fallback-direct"] || "false").toLowerCase() === "true";
const poll = String(args.poll || "true").toLowerCase() !== "false";
const pauseMinutes = numberArg("pause-minutes", 20);
const abandonMinutes = numberArg("abandon-minutes", 60);
const partialAbandonMinutes = numberArg("partial-abandon-minutes", 60);
const partialIdleMinutes = numberArg("partial-idle-minutes", 20);
const recoverJobId = Number(args["recover-job-id"] || 0);

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
    maxBuffer: 128 * 1024 * 1024,
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

function fmt(value) {
  return number(value).toLocaleString("ko-KR");
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

async function postJson(apiPath, timeoutMs = 180000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(`${baseUrl}${apiPath}`, { method: "POST", signal: controller.signal });
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`${response.status} ${response.statusText}: ${text.slice(0, 500)}`);
    }
    return text.trim() ? JSON.parse(text) : {};
  } finally {
    clearTimeout(timer);
  }
}

function activeJobs() {
  return table(`
SELECT batch_job_id, openai_batch_id, status, target, query_text,
       submitted_count, completed_count, failed_count, ingested_count,
       COALESCE(output_file_id, '') AS output_file_id,
       TIMESTAMPDIFF(MINUTE, submitted_at, NOW()) AS age_min,
       TIMESTAMPDIFF(MINUTE, updated_at, NOW()) AS idle_min
FROM semantic_batch_jobs
WHERE target IN ('law','admrul')
  AND (
    status IN ('validating','in_progress','finalizing')
    OR (status='completed' AND ingested_count=0)
  )
ORDER BY batch_job_id;
`, ["batchJobId", "openaiBatchId", "status", "target", "queryText", "submitted", "completed", "failed", "ingested", "outputFileId", "ageMin", "idleMin"]);
}

function documentsForJob(batchJobId) {
  return table(`
SELECT DISTINCT d.target, d.document_id, d.title
FROM semantic_batch_job_chunks bjc
JOIN law_api_document_chunks c ON c.chunk_id=bjc.chunk_id
JOIN law_api_documents d ON d.document_id=c.document_id
WHERE bjc.batch_job_id=${Number(batchJobId)}
ORDER BY d.target, d.document_id;
`, ["target", "documentId", "title"]);
}

function abandonJob(job, reason, options = {}) {
  const requireCompletedZero = options.requireCompletedZero !== false;
  const errorCode = options.errorCode || "ABANDONED_LOCAL";
  const completedGuard = requireCompletedZero ? "AND completed_count=0" : "";
  const message = reason || "completed=0 timeout";
  db(`
START TRANSACTION;
UPDATE semantic_batch_jobs
SET status='ABANDONED',
    last_error_message='Abandoned locally by law-parent-child-batch-guard: ${q(message)}.',
    updated_at=NOW()
WHERE batch_job_id=${Number(job.batchJobId)}
  AND status IN ('validating','in_progress','finalizing')
  ${completedGuard};
UPDATE semantic_batch_job_chunks
SET status='FAILED',
    error_code='${q(errorCode)}',
    error_message='Parent batch abandoned locally after ${q(message)}.',
    updated_at=NOW()
WHERE batch_job_id=${Number(job.batchJobId)}
  AND status IN ('SUBMITTED','OUTPUT_READY');
UPDATE law_api_chunk_embeddings e
JOIN semantic_batch_job_chunks bjc ON bjc.chunk_id=e.chunk_id
SET e.status='FAILED',
    e.last_error_message=CONCAT('Batch job ', ${Number(job.batchJobId)}, ' abandoned locally after ${q(message)}.'),
    e.updated_at=NOW()
WHERE bjc.batch_job_id=${Number(job.batchJobId)}
  AND e.embedding_model='${q(model)}'
  AND e.vector_store='${q(vectorStore)}'
  AND e.status='BATCH_SUBMITTED';
UPDATE law_api_document_chunks c
JOIN semantic_batch_job_chunks bjc ON bjc.chunk_id=c.chunk_id
SET c.index_status='FAILED',
    c.last_error_message=CONCAT('Batch job ', ${Number(job.batchJobId)}, ' abandoned locally after ${q(message)}.'),
    c.updated_at=NOW()
WHERE bjc.batch_job_id=${Number(job.batchJobId)}
  AND c.index_status='BATCH_SUBMITTED';
COMMIT;
`);
}

async function directFallback(documents) {
  const responses = [];
  const byTarget = new Map();
  for (const document of documents) {
    if (!byTarget.has(document.target)) byTarget.set(document.target, []);
    byTarget.get(document.target).push(document.documentId);
  }
  for (const [target, ids] of byTarget) {
    const url = new URL("/api/law-data/semantic/index-documents", baseUrl);
    url.searchParams.set("target", target);
    url.searchParams.set("limit", "50000");
    for (const id of ids) url.searchParams.append("documentIds", id);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 600000);
    try {
      const response = await fetch(url, { method: "POST", signal: controller.signal });
      const text = await response.text();
      if (!response.ok) {
        throw new Error(`${response.status} ${response.statusText}: ${text.slice(0, 500)}`);
      }
      responses.push(text.trim() ? JSON.parse(text) : {});
    } finally {
      clearTimeout(timer);
    }
  }
  return responses;
}

async function main() {
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  const actions = [];
  const handled = [];

  if (recoverJobId > 0) {
    const documents = documentsForJob(recoverJobId);
    if (apply && fallbackDirect) {
      const fallbackResponses = await directFallback(documents);
      actions.push(`recover-direct-fallback:${recoverJobId}`);
      handled.push({
        batchJobId: String(recoverJobId),
        status: "RECOVERY",
        target: [...new Set(documents.map((document) => document.target))].join(","),
        submitted: documents.length,
        completed: "",
        ingested: "",
        ageMin: "",
        decision: "RECOVERED_DIRECT_FALLBACK",
        documents,
        fallbackResponses,
      });
    } else {
      handled.push({
        batchJobId: String(recoverJobId),
        status: "RECOVERY",
        target: [...new Set(documents.map((document) => document.target))].join(","),
        submitted: documents.length,
        completed: "",
        ingested: "",
        ageMin: "",
        decision: fallbackDirect ? "WOULD_RECOVER_DIRECT_FALLBACK" : "RECOVERY_NEEDS_FALLBACK_DIRECT",
        documents,
      });
    }
  }

  if (poll) {
    try {
      await postJson("/api/law-data/semantic/batches/poll", 180000);
      actions.push("poll");
    } catch (error) {
      actions.push(`poll_failed=${error.message.slice(0, 160)}`);
    }
  }

  const jobs = activeJobs();
  for (const job of jobs) {
    const documents = documentsForJob(job.batchJobId);
    const ageMin = number(job.ageMin);
    const idleMin = number(job.idleMin);
    const completed = number(job.completed);
    const submitted = number(job.submitted);
    if (job.status === "completed" && number(job.ingested) === 0 && job.outputFileId) {
      if (apply) {
        const ingested = await postJson(`/api/law-data/semantic/batches/${encodeURIComponent(job.openaiBatchId)}/ingest`, 600000);
        actions.push(`ingest:${job.batchJobId}`);
        handled.push({ ...job, decision: "INGESTED", documents, result: ingested });
      } else {
        handled.push({ ...job, decision: "WOULD_INGEST", documents });
      }
      continue;
    }
    if (completed === 0 && ageMin >= abandonMinutes) {
      if (apply) {
        abandonJob(job, "completed=0 timeout", {
          requireCompletedZero: true,
          errorCode: "ABANDONED_LOCAL",
        });
        actions.push(`abandon:${job.batchJobId}`);
        let fallbackResponses = [];
        if (fallbackDirect) {
          fallbackResponses = await directFallback(documents);
          actions.push(`direct-fallback:${job.batchJobId}`);
        }
        handled.push({ ...job, decision: fallbackDirect ? "ABANDONED_DIRECT_FALLBACK" : "ABANDONED", documents, fallbackResponses });
      } else {
        handled.push({ ...job, decision: fallbackDirect ? "WOULD_ABANDON_DIRECT_FALLBACK" : "WOULD_ABANDON", documents });
      }
      continue;
    }
    if (completed > 0 && completed < submitted && !job.outputFileId && ageMin >= partialAbandonMinutes && idleMin >= partialIdleMinutes) {
      if (apply) {
        abandonJob(job, `partial stall without output file: completed ${completed}/${submitted}`, {
          requireCompletedZero: false,
          errorCode: "ABANDONED_PARTIAL_LOCAL",
        });
        actions.push(`abandon-partial:${job.batchJobId}`);
        let fallbackResponses = [];
        if (fallbackDirect) {
          fallbackResponses = await directFallback(documents);
          actions.push(`direct-fallback:${job.batchJobId}`);
        }
        handled.push({ ...job, decision: fallbackDirect ? "ABANDONED_PARTIAL_DIRECT_FALLBACK" : "ABANDONED_PARTIAL", documents, fallbackResponses });
      } else {
        handled.push({ ...job, decision: fallbackDirect ? "WOULD_ABANDON_PARTIAL_DIRECT_FALLBACK" : "WOULD_ABANDON_PARTIAL", documents });
      }
      continue;
    }
    if (completed === 0 && ageMin >= pauseMinutes) {
      handled.push({ ...job, decision: "PAUSED_COMPLETED_ZERO", documents });
      continue;
    }
    handled.push({ ...job, decision: "WAIT", documents });
  }

  const result = {
    generatedAt: new Date().toISOString(),
    mode: apply ? "apply" : "dry-run",
    fallbackDirect,
    pauseMinutes,
    abandonMinutes,
    actions,
    jobs,
    handled,
  };
  writeReports(result);
  console.log(JSON.stringify(result, null, 2));
}

function writeReports(result) {
  fs.writeFileSync(jsonPath, JSON.stringify(result, null, 2), "utf8");
  const lines = [
    "# Law Parent-Child Batch Guard",
    "",
    `- Generated at: ${result.generatedAt}`,
    `- Mode: ${result.mode}`,
    `- Fallback direct: ${result.fallbackDirect}`,
    "",
    "## Active Jobs",
    "",
    mdTable(result.handled || [], [
      { key: "batchJobId", label: "Job", align: "right" },
      { key: "target", label: "Target" },
      { key: "status", label: "Status" },
      { key: "submitted", label: "Submitted", align: "right", format: "number" },
      { key: "completed", label: "Completed", align: "right", format: "number" },
      { key: "ingested", label: "Ingested", align: "right", format: "number" },
      { key: "ageMin", label: "Age Min", align: "right", format: "number" },
      { key: "decision", label: "Decision" },
    ]),
    "",
    "## Actions",
    "",
    (result.actions || []).map((action) => `- ${action}`).join("\n") || "_none_",
  ];
  fs.writeFileSync(outPath, lines.join("\n"), "utf8");
}

main().catch((error) => {
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
