const { execFileSync } = require("child_process");

const workspace = require("path").resolve(__dirname, "..");
const mysql = "C:\\Program Files\\MariaDB 12.2\\bin\\mariadb.exe";
const baseUrl = process.env.PANDORA_BASE_URL || "http://localhost:18080";
const model = process.env.PANDORA_EMBEDDING_MODEL || "text-embedding-3-small";
const vectorStore = process.env.PANDORA_VECTOR_STORE || "rag_chunks_v4";

const args = Object.fromEntries(process.argv.slice(2).map((arg) => {
  const [key, ...rest] = arg.replace(/^--/, "").split("=");
  return [key, rest.join("=") || "true"];
}));

const query = args.query || process.env.RAG_BATCH_QUERY || "";
const target = args.target || "official_doc";
const dryRun = args["dry-run"] === "true";
const completedZeroPauseMinutes = Number(args.completedZeroPauseMinutes || 20);
const abandonMinutes = Number(args.abandonMinutes || 60);
const tranches = String(args.tranches || "2,50,100,250,500")
  .split(",")
  .map((value) => Number(value.trim()))
  .filter((value) => Number.isFinite(value) && value > 0);

function db(sql) {
  const output = execFileSync(mysql, [
    "--ssl=0",
    "-h", "localhost",
    "-P", "3306",
    "-upandora",
    "-ppandora",
    "--batch",
    "--skip-column-names",
    "--default-character-set=utf8mb4",
    "pandora",
    "-e",
    sql,
  ], { cwd: workspace, encoding: "utf8", windowsHide: true });
  return output.trim().split(/\r?\n/).filter(Boolean);
}

function q(value) {
  return String(value ?? "").replace(/\\/g, "\\\\").replace(/'/g, "''");
}

function rows(sql) {
  return db(sql).map((line) => line.split("\t"));
}

async function api(method, apiPath, timeoutMs = 180000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(`${baseUrl}${apiPath}`, { method, signal: controller.signal });
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`HTTP ${response.status} ${text.slice(0, 300)}`);
    }
    return text.trim() ? JSON.parse(text) : {};
  } finally {
    clearTimeout(timer);
  }
}

function candidateWhere() {
  const querySql = q(query);
  return `
doc.document_type='${q(target)}'
AND doc.use_yn='Y'
AND c.use_yn='Y'
AND c.chunk_version = (
  SELECT MAX(c2.chunk_version)
  FROM rag_document_chunks c2
  WHERE c2.document_id = c.document_id
    AND c2.use_yn='Y'
)
AND (
  '${querySql}' = ''
  OR doc.title LIKE CONCAT('%', '${querySql}', '%')
  OR doc.source_org LIKE CONCAT('%', '${querySql}', '%')
  OR doc.document_category LIKE CONCAT('%', '${querySql}', '%')
  OR doc.document_topic LIKE CONCAT('%', '${querySql}', '%')
  OR c.chunk_no LIKE CONCAT('%', '${querySql}', '%')
  OR c.chunk_title LIKE CONCAT('%', '${querySql}', '%')
  OR c.parent_section_title LIKE CONCAT('%', '${querySql}', '%')
  OR c.embedding_text LIKE CONCAT('%', '${querySql}', '%')
  OR c.chunk_text LIKE CONCAT('%', '${querySql}', '%')
)`;
}

function searchableChunkPredicate() {
  return "COALESCE(c.quality_status, 'PASS') IN ('PASS', 'REVIEW')";
}

function nonSearchableChunkPredicate() {
  return "COALESCE(c.quality_status, 'PASS') NOT IN ('PASS', 'REVIEW')";
}

function backlogCount() {
  const [line] = db(`
SELECT COUNT(*)
FROM rag_document_chunks c
JOIN rag_documents doc ON doc.document_id=c.document_id
LEFT JOIN rag_chunk_embeddings e
  ON e.chunk_id=c.chunk_id
 AND e.embedding_model='${q(model)}'
 AND e.vector_store='${q(vectorStore)}'
WHERE ${candidateWhere()}
AND ${searchableChunkPredicate()}
AND (
  e.chunk_id IS NULL
  OR e.status IN ('FAILED','ERROR')
  OR COALESCE(e.content_hash, '') != COALESCE(c.content_hash, '')
)`);
  return Number(line || 0);
}

function nonSearchableMissingCount() {
  const [line] = db(`
SELECT COUNT(*)
FROM rag_document_chunks c
JOIN rag_documents doc ON doc.document_id=c.document_id
LEFT JOIN rag_chunk_embeddings e
  ON e.chunk_id=c.chunk_id
 AND e.embedding_model='${q(model)}'
 AND e.vector_store='${q(vectorStore)}'
WHERE ${candidateWhere()}
AND ${nonSearchableChunkPredicate()}
AND e.chunk_id IS NULL`);
  return Number(line || 0);
}

function reconcileNonSearchableMissingRows() {
  const pending = nonSearchableMissingCount();
  if (!pending || dryRun) {
    return pending;
  }
  db(`
INSERT INTO rag_chunk_embeddings (
  chunk_id, embedding_model, vector_store, vector_point_id, content_hash,
  status, embedded_at, last_error_message
)
SELECT
  c.chunk_id,
  '${q(model)}',
  '${q(vectorStore)}',
  9000000000000000 + c.chunk_id,
  COALESCE(c.content_hash, SHA2(COALESCE(c.embedding_text, c.chunk_text, ''), 256)),
  'SUPERSEDED',
  NOW(),
  CONCAT(
    'Excluded by v4 chunk quality gate: ',
    COALESCE(c.quality_status, 'UNKNOWN'),
    '/',
    COALESCE(c.quality_reason, '')
  )
FROM rag_document_chunks c
JOIN rag_documents doc ON doc.document_id=c.document_id
LEFT JOIN rag_chunk_embeddings e
  ON e.chunk_id=c.chunk_id
 AND e.embedding_model='${q(model)}'
 AND e.vector_store='${q(vectorStore)}'
WHERE ${candidateWhere()}
AND ${nonSearchableChunkPredicate()}
AND e.chunk_id IS NULL
ON DUPLICATE KEY UPDATE
  vector_point_id = VALUES(vector_point_id),
  content_hash = VALUES(content_hash),
  status = VALUES(status),
  embedded_at = VALUES(embedded_at),
  last_error_message = VALUES(last_error_message)`);
  return pending;
}

function activeJobs() {
  return rows(`
SELECT batch_job_id, openai_batch_id, status, query_text, submitted_count,
       completed_count, failed_count, ingested_count,
       TIMESTAMPDIFF(MINUTE, created_at, NOW()) AS age_min,
       TIMESTAMPDIFF(MINUTE, updated_at, NOW()) AS idle_min
FROM semantic_batch_jobs
WHERE target='${q(target)}'
  AND status IN ('validating','in_progress','finalizing')
ORDER BY batch_job_id`);
}

function successfulQueryJobs() {
  return rows(`
SELECT submitted_count
FROM semantic_batch_jobs
WHERE target='${q(target)}'
  AND query_text='${q(query)}'
  AND status='INGESTED'
  AND submitted_count > 0
  AND completed_count=submitted_count
  AND failed_count=0
ORDER BY batch_job_id DESC
LIMIT 5`).map(([submitted]) => Number(submitted || 0));
}

function latestClosedQueryJob() {
  const [row] = rows(`
SELECT status, submitted_count, completed_count, failed_count
FROM semantic_batch_jobs
WHERE target='${q(target)}'
  AND query_text='${q(query)}'
  AND status NOT IN ('validating','in_progress','finalizing')
ORDER BY batch_job_id DESC
LIMIT 1`);
  if (!row) {
    return null;
  }
  const [status, submitted, completed, failed] = row;
  return {
    status,
    submitted: Number(submitted || 0),
    completed: Number(completed || 0),
    failed: Number(failed || 0),
  };
}

function nextLimit(backlog) {
  const latest = latestClosedQueryJob();
  if (latest && latest.status !== "INGESTED") {
    return Math.min(tranches[0] || 2, backlog);
  }
  const successes = successfulQueryJobs();
  let limit = tranches[0] || 2;
  if (successes.length) {
    const largestSuccess = Math.max(...successes);
    const index = tranches.findIndex((value) => value > largestSuccess);
    limit = index >= 0 ? tranches[index] : tranches[tranches.length - 1];
  }
  return Math.min(limit, backlog);
}

function abandonJob(batchJobId) {
  if (dryRun) {
    return;
  }
  db(`
START TRANSACTION;
UPDATE semantic_batch_jobs
SET status='ABANDONED',
    last_error_message='Abandoned locally by official-doc-batch-guard: completed=0 timeout.',
    updated_at=NOW()
WHERE batch_job_id=${Number(batchJobId)}
  AND status IN ('validating','in_progress','finalizing')
  AND completed_count=0;
UPDATE semantic_batch_job_chunks
SET status='FAILED',
    error_code='ABANDONED_LOCAL',
    error_message='Parent batch abandoned locally after completed=0 timeout.',
    updated_at=NOW()
WHERE batch_job_id=${Number(batchJobId)}
  AND status='SUBMITTED';
UPDATE rag_chunk_embeddings e
JOIN semantic_batch_job_chunks bjc ON bjc.chunk_id=e.chunk_id
SET e.status='FAILED',
    e.last_error_message=CONCAT('Batch job ', ${Number(batchJobId)}, ' abandoned locally after completed=0 timeout; eligible for smaller retry.'),
    e.updated_at=NOW()
WHERE bjc.batch_job_id=${Number(batchJobId)}
  AND e.vector_store='${q(vectorStore)}'
  AND e.status='BATCH_SUBMITTED';
COMMIT;`);
}

async function main() {
  const actions = [];
  try {
    await api("POST", "/api/law-data/semantic/batches/poll", 180000);
    actions.push("poll");
  } catch (error) {
    actions.push(`poll_failed=${error.message.slice(0, 120)}`);
  }

  const active = activeJobs();
  for (const row of active) {
    const [batchJobId, openaiBatchId, status, jobQuery, submitted, completed, failed, ingested, age] = row;
    const ageMin = Number(age || 0);
    if (Number(completed || 0) === 0 && ageMin >= abandonMinutes) {
      abandonJob(batchJobId);
      actions.push(`abandoned job=${batchJobId} openai=${openaiBatchId} age=${ageMin}m submitted=${submitted}`);
    } else if (Number(completed || 0) === 0 && ageMin >= completedZeroPauseMinutes) {
      actions.push(`paused job=${batchJobId} status=${status} age=${ageMin}m completed=0`);
      console.log(JSON.stringify({ status: "PAUSED", query, actions }, null, 2));
      return;
    } else {
      actions.push(`active job=${batchJobId} status=${status} age=${ageMin}m completed=${completed}/${submitted}`);
      console.log(JSON.stringify({ status: "ACTIVE", query, actions }, null, 2));
      return;
    }
  }

  const nonSearchable = reconcileNonSearchableMissingRows();
  if (nonSearchable) {
    actions.push(dryRun
      ? `non_searchable_missing=${nonSearchable}`
      : `reconciled_non_searchable=${nonSearchable}`);
  }

  const backlog = backlogCount();
  if (!backlog) {
    console.log(JSON.stringify({ status: "DONE", query, backlog, actions }, null, 2));
    return;
  }

  const limit = nextLimit(backlog);
  actions.push(`backlog=${backlog}`);
  actions.push(`next_limit=${limit}`);
  if (dryRun) {
    console.log(JSON.stringify({ status: "DRY_RUN", query, backlog, limit, actions }, null, 2));
    return;
  }

  const params = new URLSearchParams({
    target,
    query,
    limit: String(limit),
  });
  const submitted = await api("POST", `/api/law-data/semantic/batches/submit-next?${params.toString()}`, 600000);
  actions.push(`submitted job=${submitted.batchJobId} count=${submitted.submittedCount}`);
  console.log(JSON.stringify({ status: "SUBMITTED", query, backlog, limit, submitted, actions }, null, 2));
}

main().catch((error) => {
  console.error(JSON.stringify({ status: "ERROR", query, error: error.stack || error.message }, null, 2));
  process.exitCode = 1;
});
