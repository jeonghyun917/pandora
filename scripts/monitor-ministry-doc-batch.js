const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");

const workspace = path.resolve(__dirname, "..");
const logDir = path.join(workspace, "logs");
const logPath = path.join(logDir, "ministry-doc-batch-monitor.log");
const reportPath = path.join(logDir, "ministry-doc-batch-report-latest.md");
const skipFillQueuePath = path.join(logDir, "ministry-doc-skip-fill-queue.flag");
const lockPath = path.join(process.env.TEMP || workspace, "pandora-ministry-doc-batch-monitor.lock");
const mysql = "C:\\Program Files\\MariaDB 12.2\\bin\\mariadb.exe";
const baseUrl = process.env.PANDORA_BASE_URL || "http://localhost:18080";
const qdrantUrl = "http://localhost:6333/collections/rag_chunks_v4";
const model = "text-embedding-3-small";
const vectorStore = "rag_chunks_v4";

fs.mkdirSync(logDir, { recursive: true });

function nowLine() {
  const date = new Date();
  const pad = (value) => String(value).padStart(2, "0");
  const offsetMinutes = -date.getTimezoneOffset();
  const sign = offsetMinutes >= 0 ? "+" : "-";
  const absOffset = Math.abs(offsetMinutes);
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())} ${sign}${pad(Math.floor(absOffset / 60))}:${pad(absOffset % 60)}`;
}

function log(message) {
  fs.appendFileSync(logPath, `${nowLine()} ${message}\n`, "utf8");
}

function db(sql) {
  const output = execFileSync(mysql, [
    "--ssl=0",
    "-upandora",
    "-ppandora",
    "--batch",
    "--skip-column-names",
    "pandora",
    "-e",
    sql,
  ], { cwd: workspace, encoding: "utf8", windowsHide: true });
  return output.trim().split(/\r?\n/).filter(Boolean);
}

async function api(method, apiPath, timeoutMs = 120000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(`${baseUrl}${apiPath}`, { method, signal: controller.signal });
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`HTTP ${response.status} ${text.slice(0, 300)}`);
    }
    return text;
  } finally {
    clearTimeout(timer);
  }
}

async function getJson(url, timeoutMs = 15000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { signal: controller.signal });
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`HTTP ${response.status} ${text.slice(0, 300)}`);
    }
    return JSON.parse(text);
  } finally {
    clearTimeout(timer);
  }
}

function splitRow(row) {
  return row.split("\t");
}

function buildReport(context) {
  const status = Object.fromEntries(context.summary.map((line) => {
    const [key, value] = line.split("=");
    return [key, value];
  }));
  const docsAndChunks = db(`
SELECT CONCAT('documents=', COUNT(DISTINCT doc.document_id))
FROM rag_documents doc
WHERE doc.document_type='official_doc'
  AND doc.use_yn='Y'
UNION ALL
SELECT CONCAT('chunks=', COUNT(*))
FROM rag_document_chunks c
JOIN rag_documents doc ON doc.document_id=c.document_id
WHERE doc.document_type='official_doc'
  AND doc.use_yn='Y'
  AND c.use_yn='Y'
  AND c.chunk_version = (
    SELECT MAX(c2.chunk_version)
    FROM rag_document_chunks c2
    WHERE c2.document_id = c.document_id
      AND c2.use_yn='Y'
  )
`);
  const docMap = Object.fromEntries(docsAndChunks.map((line) => line.split("=")));
  const risks = [...context.risks];
  if (Number(status.FAILED || 0) > 0) {
    risks.push(`FAILED ${Number(status.FAILED).toLocaleString()} chunks need retry or inspection.`);
  }
  if (Number(status.NO_EMBED || 0) > 0 && Number(status.active || 0) === 0) {
    risks.push("NO_EMBED candidates remain while no active OpenAI job is running.");
  }
  if (!risks.length) {
    risks.push("No blocking risk found.");
  }

  const toJobLine = (row) => {
    const [id, state, completed, submitted, ingested, indexed, failed, openaiState, minutes] = splitRow(row);
    const batch = `${Number(completed || 0).toLocaleString()}/${Number(submitted || 0).toLocaleString()}`;
    const ingest = state === "INGESTED" ? `INDEXED ${Number(indexed || 0).toLocaleString()}` : `INGESTED ${Number(ingested || 0).toLocaleString()}`;
    const note = [openaiState, `${minutes || 0}m`, Number(failed || 0) ? `failed ${failed}` : ""].filter(Boolean).join(", ");
    return `| ${id} | ${state} | ${batch} | ${ingest} | ${note} |`;
  };
  const emptyJobLine = "| - | none | - | - | - |";
  const activeJobLines = context.activeJobs.map(toJobLine);
  const recentIngestedJobLines = context.recentIngestedJobs.map(toJobLine);
  const historicalJobLines = context.historicalJobs.map(toJobLine);

  return [
    "ministry document batch monitoring report.",
    "",
    `Latest status time: ${nowLine()}`,
    "",
    "Active/Open jobs:",
    "| Job | Status | Batch complete | Ingest/Index | Note |",
    "| --- | --- | ---: | --- | --- |",
    ...(activeJobLines.length ? activeJobLines : [emptyJobLine]),
    "",
    "Recent ingested jobs:",
    "| Job | Status | Batch complete | Ingest/Index | Note |",
    "| --- | --- | ---: | --- | --- |",
    ...(recentIngestedJobLines.length ? recentIngestedJobLines : [emptyJobLine]),
    "",
    "Historical / locally closed jobs:",
    "| Job | Status | Batch complete | Ingest/Index | Note |",
    "| --- | --- | ---: | --- | --- |",
    ...(historicalJobLines.length ? historicalJobLines : [emptyJobLine]),
    "",
    "Overall:",
    `- Documents / chunks: ${Number(docMap.documents || 0).toLocaleString()} / ${Number(docMap.chunks || 0).toLocaleString()}`,
    `- INDEXED: ${Number(status.INDEXED || 0).toLocaleString()}`,
    `- BATCH_SUBMITTED: ${Number(status.BATCH_SUBMITTED || 0).toLocaleString()}`,
    `- FAILED: ${Number(status.FAILED || 0).toLocaleString()}`,
    `- NO_EMBED: ${Number(status.NO_EMBED || 0).toLocaleString()}`,
    `- Active OpenAI jobs: ${status.active || 0} / 2`,
    `- Historical / locally closed jobs: ${context.historicalJobCount}`,
    `- Qdrant: ${context.qdrantStatus}, points ${Number(context.qdrantPoints || 0).toLocaleString()}`,
    `- Spring: ${context.springStatus}`,
    `- Collection run: ${context.collectionRun}`,
    `- New batch submitted: ${context.newBatch ? "yes" : "no"}`,
    `- Recovery action: ${context.recoveryActions.length ? context.recoveryActions.join("; ") : "none"}`,
    `- Remaining risk: ${risks.join(" / ")}`,
    "",
  ].join("\n");
}

async function main() {
  if (fs.existsSync(lockPath)) {
    const ageMs = Date.now() - fs.statSync(lockPath).mtimeMs;
    if (ageMs < 9 * 60 * 1000) {
      log("skip locked");
      return;
    }
  }
  fs.writeFileSync(lockPath, String(process.pid), "utf8");
  const recoveryActions = [];
  const risks = [];
  let collectionRun = "not run";
  let newBatch = false;
  try {
    log("start");
    try {
      const collectionFillQueue = fs.existsSync(skipFillQueuePath) ? "false" : "true";
      const collection = await api("POST", `/api/rag-collection/ministry/run?agency=ALL&fillQueue=${collectionFillQueue}&maxArticles=20&maxAttachmentsPerArticle=3`, 900000);
      const parsed = JSON.parse(collection);
      collectionRun = `run ${parsed.runId} ${parsed.status}, imported ${parsed.importedCount}, failed ${parsed.failedCount}`;
      recoveryActions.push("ministry collection checked");
      if (collectionFillQueue === "false") {
        recoveryActions.push("collection fill-queue skipped by ministry-doc-skip-fill-queue.flag");
      }
    } catch (error) {
      risks.push(`collection failed: ${error.message.slice(0, 180)}`);
      collectionRun = `failed: ${error.message.slice(0, 180)}`;
    }

    try {
      await api("POST", "/api/law-data/semantic/batches/recover-stale?target=official_doc", 120000);
      recoveryActions.push("recover-stale official_doc");
    } catch (error) {
      risks.push(`recover-stale failed: ${error.message.slice(0, 180)}`);
    }

    try {
      await api("POST", "/api/law-data/semantic/batches/poll", 180000);
      recoveryActions.push("poll active batches");
    } catch (error) {
      risks.push(`poll failed: ${error.message.slice(0, 180)}`);
    }

    const completedJobs = db(`
SELECT openai_batch_id
FROM semantic_batch_jobs j
WHERE j.target='official_doc'
  AND j.status='COMPLETED'
  AND COALESCE(j.ingested_count, 0)=0
  AND EXISTS (
    SELECT 1
    FROM semantic_batch_job_chunks bjc
    JOIN rag_document_chunks c ON c.chunk_id=bjc.chunk_id
    WHERE bjc.batch_job_id=j.batch_job_id
      AND c.use_yn='Y'
  )
ORDER BY j.batch_job_id
LIMIT 3
`);
    for (const batchJobId of completedJobs) {
      try {
        await api("POST", `/api/law-data/semantic/batches/${batchJobId}/ingest`, 900000);
        recoveryActions.push(`ingest job ${batchJobId}`);
      } catch (error) {
        risks.push(`ingest ${batchJobId} failed: ${error.message.slice(0, 180)}`);
      }
    }

    if (fs.existsSync(skipFillQueuePath)) {
      recoveryActions.push("fill-queue skipped by ministry-doc-skip-fill-queue.flag");
    } else {
    try {
      const beforeMaxBatchJobId = Number(db(`
SELECT COALESCE(MAX(batch_job_id), 0)
FROM semantic_batch_jobs
WHERE target='official_doc'
`)[0] || 0);
      await api("POST", "/api/law-data/semantic/batches/fill-queue?target=official_doc&maxActiveJobs=2&limit=50000", 600000);
      const afterMaxBatchJobId = Number(db(`
SELECT COALESCE(MAX(batch_job_id), 0)
FROM semantic_batch_jobs
WHERE target='official_doc'
`)[0] || 0);
      newBatch = afterMaxBatchJobId > beforeMaxBatchJobId;
      recoveryActions.push("fill-queue official_doc");
    } catch (error) {
      risks.push(`fill-queue failed: ${error.message.slice(0, 180)}`);
    }
    }

    const springStatus = await api("GET", "/api/law-data/semantic/batches/scheduler-status", 15000)
      .then(() => "200")
      .catch((error) => `ERROR ${error.message.slice(0, 80)}`);
    const qdrant = await getJson(qdrantUrl).catch((error) => ({ error: error.message }));
    const qdrantStatus = qdrant?.result?.status || (qdrant?.error ? `ERROR ${qdrant.error.slice(0, 80)}` : "unknown");
    const qdrantPoints = qdrant?.result?.points_count || 0;
    const activeJobs = db(`
SELECT batch_job_id, status, COALESCE(completed_count,0), submitted_count,
       COALESCE(ingested_count,0), COALESCE(ingested_count,0), COALESCE(failed_count,0),
       COALESCE(status,''), TIMESTAMPDIFF(MINUTE, updated_at, NOW())
FROM semantic_batch_jobs
WHERE target='official_doc'
  AND status IN ('validating','in_progress','finalizing','completed')
ORDER BY
  batch_job_id DESC
LIMIT 10
`);
    const recentIngestedJobs = db(`
SELECT batch_job_id, status, COALESCE(completed_count,0), submitted_count,
       COALESCE(ingested_count,0), COALESCE(ingested_count,0), COALESCE(failed_count,0),
       COALESCE(status,''), TIMESTAMPDIFF(MINUTE, updated_at, NOW())
FROM semantic_batch_jobs
WHERE target='official_doc'
  AND status IN ('INGESTED','INDEXED')
ORDER BY batch_job_id DESC
LIMIT 10
`);
    const historicalJobs = db(`
SELECT batch_job_id, status, COALESCE(completed_count,0), submitted_count,
       COALESCE(ingested_count,0), COALESCE(ingested_count,0), COALESCE(failed_count,0),
       COALESCE(status,''), TIMESTAMPDIFF(MINUTE, updated_at, NOW())
FROM semantic_batch_jobs
WHERE target='official_doc'
  AND status IN ('CANCELLED_LOCAL','ABANDONED','cancelled','CANCELLED','FAILED','failed','EXPIRED','expired','cancelling')
ORDER BY updated_at DESC, batch_job_id DESC
LIMIT 10
`);
    const historicalJobCount = Number(db(`
SELECT COUNT(*)
FROM semantic_batch_jobs
WHERE target='official_doc'
  AND status IN ('CANCELLED_LOCAL','ABANDONED','cancelled','CANCELLED','FAILED','failed','EXPIRED','expired','cancelling')
`)[0] || 0);
    const summary = db(`
SELECT CONCAT(status_key, '=', status_count)
FROM (
  SELECT COALESCE(e.status, 'NO_EMBED') AS status_key, COUNT(*) AS status_count
  FROM rag_document_chunks c
  JOIN rag_documents doc ON doc.document_id=c.document_id
  LEFT JOIN rag_chunk_embeddings e
    ON e.chunk_id=c.chunk_id
   AND e.embedding_model='${model}'
   AND e.vector_store='${vectorStore}'
  WHERE doc.document_type='official_doc'
    AND doc.use_yn='Y'
    AND c.use_yn='Y'
    AND c.chunk_version = (
      SELECT MAX(c2.chunk_version)
      FROM rag_document_chunks c2
      WHERE c2.document_id = c.document_id
        AND c2.use_yn='Y'
    )
  GROUP BY COALESCE(e.status, 'NO_EMBED')
  UNION ALL
  SELECT 'active', COUNT(*)
  FROM semantic_batch_jobs
  WHERE target='official_doc'
    AND status IN ('validating','in_progress','finalizing')
) x
`);
    const report = buildReport({
      springStatus,
      qdrantStatus,
      qdrantPoints,
      activeJobs,
      recentIngestedJobs,
      historicalJobs,
      historicalJobCount,
      summary,
      collectionRun,
      newBatch,
      recoveryActions,
      risks,
    });
    fs.writeFileSync(reportPath, report, "utf8");
    log("done");
  } finally {
    fs.rmSync(lockPath, { force: true });
  }
}

main().catch((error) => {
  log(`fatal ${error.stack || error.message}`);
  fs.writeFileSync(reportPath, `ministry document batch monitoring report.\n\nFatal error: ${error.message}\n`, "utf8");
  try {
    fs.rmSync(lockPath, { force: true });
  } catch {}
  process.exitCode = 1;
});
