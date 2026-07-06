const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");

const workspace = path.resolve(__dirname, "..");
const logDir = path.join(workspace, "logs");
const logPath = path.join(logDir, "admrul-batch-monitor.log");
const reportPath = path.join(logDir, "admrul-batch-report-latest.md");
const skipFillQueuePath = path.join(logDir, "admrul-skip-fill-queue.flag");
const lockPath = path.join(process.env.TEMP || workspace, "pandora-admrul-batch-monitor.lock");
const monitorLockMinutes = 20;
const waveLockMaxMinutes = 360;
const mysql = "C:\\Program Files\\MariaDB 12.2\\bin\\mariadb.exe";
const baseUrl = process.env.PANDORA_BASE_URL || "http://localhost:18080";
const qdrantUrl = "http://localhost:6333/collections/law_chunks";
const model = "text-embedding-3-small";
const vectorStore = "law_chunks";
const syncTargets = csvEnv("PANDORA_SYNC_TARGETS", "law,admrul");
const batchTargets = csvEnv("PANDORA_BATCH_TARGETS", syncTargets.join(","));
const syncQuery = process.env.PANDORA_SYNC_QUERY || "*";
const syncSort = process.env.PANDORA_SYNC_SORT || "efdes";
const syncPages = positiveIntEnv("PANDORA_SYNC_PAGES", 1, 10);
const syncDisplay = positiveIntEnv("PANDORA_SYNC_DISPLAY", 100, 100);
const batchFillLimit = positiveIntEnv("PANDORA_BATCH_FILL_LIMIT", 100, 5000);
const batchMaxActiveJobs = positiveIntEnv("PANDORA_BATCH_MAX_ACTIVE_JOBS", 1, 2);
const syncDate = process.env.PANDORA_SYNC_DATE || "";
const syncEfYd = process.env.PANDORA_SYNC_EFYD || "";
const syncAncYd = process.env.PANDORA_SYNC_ANCYD || recentDateRange(120);

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

function csvEnv(name, fallback) {
  return (process.env[name] || fallback)
    .split(",")
    .map((value) => value.trim())
    .filter((value) => value === "law" || value === "admrul");
}

function positiveIntEnv(name, fallback, max) {
  const value = Number(process.env[name] || fallback);
  if (!Number.isFinite(value) || value <= 0) {
    return fallback;
  }
  return Math.min(Math.floor(value), max);
}

function formatYmd(date) {
  const pad = (value) => String(value).padStart(2, "0");
  return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}`;
}

function recentDateRange(days) {
  const end = new Date();
  const start = new Date(end);
  start.setDate(start.getDate() - days);
  return `${formatYmd(start)}~${formatYmd(end)}`;
}

function sqlTargets(targets) {
  const safeTargets = targets.filter((target) => target === "law" || target === "admrul");
  return safeTargets.length ? safeTargets.map((target) => `'${target}'`).join(",") : "'admrul'";
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

async function syncTarget(target, page) {
  const params = new URLSearchParams({
    target,
    query: syncQuery,
    page: String(page),
    display: String(syncDisplay),
    fetchDetails: "true",
    sort: syncSort,
  });
  if (target === "law") {
    if (syncDate) {
      params.set("date", syncDate);
    }
    if (syncEfYd) {
      params.set("efYd", syncEfYd);
    }
    if (syncAncYd) {
      params.set("ancYd", syncAncYd);
    }
  }
  return api("POST", `/api/law-data/sync?${params.toString()}`, 900000);
}

function splitRow(row) {
  return row.split("\t");
}

function submittedNewBatch(fill) {
  if (!fill) {
    return false;
  }
  try {
    const rows = JSON.parse(fill);
    return Array.isArray(rows) && rows.some((row) => row.batchJobId && row.batchJobId > 0 && row.openaiBatchId);
  } catch (error) {
    return true;
  }
}

function buildReport(context) {
  const {
    springStatus,
    qdrantStatus,
    qdrantPoints,
    jobs,
    summary,
    fill,
    recoveryActions,
    risks,
  } = context;
  const status = Object.fromEntries(summary.map((line) => {
    const [key, value] = line.split("=");
    return [key, value];
  }));
  const active = status.active || "0";
  const indexed = status.INDEXED || "0";
  const submitted = status.BATCH_SUBMITTED || "0";
  const failed = status.FAILED || "0";
  const noEmbed = status.NO_EMBED || "0";
  const reportRisks = [...risks];
  if (Number(failed) > 0) {
    reportRisks.push(`FAILED ${Number(failed).toLocaleString()}건은 반복 실패로 자동 재제출 제외됨`);
  }
  const docsAndChunks = db(`
SELECT CONCAT('documents=', COUNT(DISTINCT doc.document_id))
FROM law_api_documents doc
WHERE doc.target IN (${sqlTargets(batchTargets)}) AND doc.use_yn = 'Y'
UNION ALL
SELECT CONCAT('chunks=', COUNT(*))
FROM law_api_document_chunks c
JOIN law_api_documents doc ON doc.document_id = c.document_id
WHERE doc.target IN (${sqlTargets(batchTargets)}) AND c.use_yn = 'Y' AND doc.use_yn = 'Y'
`);
  const total = Object.fromEntries(docsAndChunks.map((line) => {
    const [key, value] = line.split("=");
    return [key, value];
  }));

  const filled = submittedNewBatch(fill);
  const jobLines = jobs.map((row) => {
    const [id, openaiId, state, requested, submittedCount, completed, failedCount, ingested, updatedAt] = splitRow(row);
    const batch = `${Number(completed).toLocaleString()}/${Number(submittedCount).toLocaleString()}`;
    const ingest = Number(failedCount) > 0 && Number(ingested) === 0
      ? `FAILED ${Number(failedCount).toLocaleString()}`
      : (ingested === "0" ? `대기 ${Number(ingested).toLocaleString()}` : `INDEXED ${Number(ingested).toLocaleString()}`);
    return `| ${id} | ${state} | ${batch} | ${ingest} | failed ${failedCount}, ${updatedAt}, ${openaiId} |`;
  }).join("\n");

  return `# admrul batch monitor latest

- 현재 시각: ${nowLine()}
- Spring 8080: ${springStatus}
- Qdrant law_chunks: ${qdrantStatus}, points=${Number(qdrantPoints || 0).toLocaleString()}
- 전체 admrul 문서/청크: ${Number(total.documents || 0).toLocaleString()} / ${Number(total.chunks || 0).toLocaleString()}
- INDEXED / BATCH_SUBMITTED / FAILED / NO_EMBED: ${Number(indexed).toLocaleString()} / ${Number(submitted).toLocaleString()} / ${Number(failed).toLocaleString()} / ${Number(noEmbed).toLocaleString()}
- active OpenAI job 수: ${active}
- 새 batch 제출 여부: ${filled ? "제출됨" : "없음"}
- 수행한 복구 조치: ${recoveryActions.length ? recoveryActions.join("; ") : "없음"}
- 남은 위험: ${reportRisks.length ? reportRisks.join("; ") : "없음"}

| Job | 상태 | Batch 완료 | Ingest/Index 상태 | 비고 |
| --- | --- | --- | --- | --- |
${jobLines || "| - | - | - | - | - |"}
`;
}

function buildReportReadable(context) {
  const {
    springStatus,
    qdrantStatus,
    qdrantPoints,
    jobs,
    summary,
    fill,
    recoveryActions,
    risks,
  } = context;
  const status = Object.fromEntries(summary.map((line) => {
    const [key, value] = line.split("=");
    return [key, value];
  }));
  const active = status.active || "0";
  const indexed = status.INDEXED || "0";
  const submitted = status.BATCH_SUBMITTED || "0";
  const failed = status.FAILED || "0";
  const noEmbed = status.NO_EMBED || "0";
  const reportRisks = [...risks];
  if (Number(failed) > 0) {
    reportRisks.push(`FAILED ${Number(failed).toLocaleString()}건은 반복 실패로 자동 재제출 제외됨`);
  }
  const docsAndChunks = db(`
SELECT CONCAT('documents=', COUNT(DISTINCT doc.document_id))
FROM law_api_documents doc
WHERE doc.target IN (${sqlTargets(batchTargets)}) AND doc.use_yn = 'Y'
UNION ALL
SELECT CONCAT('chunks=', COUNT(*))
FROM law_api_document_chunks c
JOIN law_api_documents doc ON doc.document_id = c.document_id
WHERE doc.target IN (${sqlTargets(batchTargets)}) AND c.use_yn = 'Y' AND doc.use_yn = 'Y'
`);
  const total = Object.fromEntries(docsAndChunks.map((line) => {
    const [key, value] = line.split("=");
    return [key, value];
  }));
  const indexedPercent = Number(total.chunks || 0) === 0
    ? "0.00"
    : ((Number(indexed) / Number(total.chunks)) * 100).toFixed(2);
  const filled = submittedNewBatch(fill);
  const jobLines = jobs.map((row) => {
    const [id, openaiId, state, requested, submittedCount, completed, failedCount, ingested, updatedAt] = splitRow(row);
    const batch = `${Number(completed).toLocaleString()}/${Number(submittedCount).toLocaleString()}`;
    const ingest = Number(failedCount) > 0 && Number(ingested) === 0
      ? `FAILED ${Number(failedCount).toLocaleString()}`
      : (ingested === "0"
        ? `SUBMITTED/WAIT ${Number(ingested).toLocaleString()}`
        : `INDEXED ${Number(ingested).toLocaleString()}`);
    const note = Number(failedCount) > 0 ? `failed ${failedCount}` : "완료/진행";
    return `| ${id} | ${state} | ${batch} | ${ingest} | ${note}, ${updatedAt}, ${openaiId} |`;
  }).join("\n");

  return `# admrul 배치 모니터링 보고

최신 상태 파일 기준 시각은 ${nowLine()} 입니다.

| Job | 상태 | Batch 완료 | Ingest/Index | 비고 |
| --- | --- | --- | --- | --- |
${jobLines || "| - | - | - | - | - |"}

전체 상태:

- 전체 문서/청크: ${Number(total.documents || 0).toLocaleString()} / ${Number(total.chunks || 0).toLocaleString()}
- INDEXED: ${Number(indexed).toLocaleString()} (${indexedPercent}%)
- BATCH_SUBMITTED: ${Number(submitted).toLocaleString()}
- FAILED: ${Number(failed).toLocaleString()}
- NO_EMBED: ${Number(noEmbed).toLocaleString()}
- active OpenAI jobs: ${active} / 2
- Qdrant: ${qdrantStatus}, points ${Number(qdrantPoints || 0).toLocaleString()}
- Spring: ${springStatus}
- 새 batch 제출: ${filled ? "제출됨" : "없음"}
- 복구 조치: ${recoveryActions.length ? recoveryActions.join("; ") : "없음"}
- 남은 위험: ${reportRisks.length ? reportRisks.join("; ") : "없음"}
`;
}

async function main() {
  if (fs.existsSync(lockPath)) {
    const ageMinutes = (Date.now() - fs.statSync(lockPath).mtimeMs) / 60000;
    const lockContent = fs.readFileSync(lockPath, "utf8").trim();
    if (lockContent.startsWith("law-parent-child-wave") && ageMinutes < waveLockMaxMinutes) {
      log(`skip: law parent-child wave active age_min=${ageMinutes.toFixed(1)}`);
      return;
    }
    if (ageMinutes < monitorLockMinutes) {
      log("skip: previous monitor run still active");
      return;
    }
    log(`stale monitor lock replaced age_min=${ageMinutes.toFixed(1)} content=${lockContent.slice(0, 80)}`);
  }
  fs.writeFileSync(lockPath, `monitor pid=${process.pid} started=${new Date().toISOString()}`, "utf8");
  const recoveryActions = [];
  const risks = [];
  let fill = "";
  try {
    process.chdir(workspace);
    log("start admrul monitor node");

    const springResponse = await fetch(`${baseUrl}/`);
    const springStatus = springResponse.status;
    log(`spring status=${springStatus}`);

    const qdrant = await getJson(qdrantUrl);
    const qdrantStatus = qdrant.result?.status || "unknown";
    const qdrantPoints = qdrant.result?.points_count || 0;
    log(`qdrant status=${qdrantStatus} points=${qdrantPoints}`);

    for (const target of syncTargets) {
      for (let page = 1; page <= syncPages; page++) {
        try {
          const sync = await syncTarget(target, page);
          log(`sync target=${target} page=${page} ${sync}`);
          recoveryActions.push(`sync ${target} p${page} ${sync}`);
        } catch (error) {
          log(`sync-error target=${target} page=${page} ${error.message}`);
          risks.push(`sync 실패 ${target} p${page}: ${error.message.slice(0, 160)}`);
        }
      }
    }

    for (const target of batchTargets) {
      const recover = await api("POST", `/api/law-data/semantic/batches/recover-stale?target=${encodeURIComponent(target)}`, 120000);
      log(`recover-stale target=${target} ${recover}`);
      recoveryActions.push(`recover-stale ${target} ${recover}`);
    }

    const poll = await api("POST", "/api/law-data/semantic/batches/poll", 180000);
    log(`poll ${poll}`);

    const completedJobs = db(`
SELECT openai_batch_id
FROM semantic_batch_jobs
WHERE target IN (${sqlTargets(batchTargets)})
  AND status = 'completed'
  AND ingested_count = 0
ORDER BY batch_job_id
`);

    for (const batchId of completedJobs) {
      if (!batchId.trim()) {
        continue;
      }
      log(`ingest ${batchId}`);
      try {
        const ingest = await api("POST", `/api/law-data/semantic/batches/${batchId}/ingest`, 900000);
        log(`ingest-result ${ingest}`);
        recoveryActions.push(`ingest ${batchId} 성공`);
      } catch (error) {
        log(`ingest-error batch=${batchId} ${error.message}`);
        risks.push(`ingest 실패 ${batchId}: ${error.message.slice(0, 160)}`);
      }
    }

    const fillResults = [];
    if (fs.existsSync(skipFillQueuePath)) {
      log("fill-queue skipped by admrul-skip-fill-queue.flag");
      recoveryActions.push("fill-queue skipped by admrul-skip-fill-queue.flag");
    } else {
      for (const target of batchTargets) {
        try {
          const params = new URLSearchParams({
            target,
            limit: String(batchFillLimit),
            maxActiveJobs: String(batchMaxActiveJobs),
          });
          const targetFill = await api("POST", `/api/law-data/semantic/batches/fill-queue?${params.toString()}`, 600000);
          fillResults.push(targetFill);
          log(`fill-queue target=${target} limit=${batchFillLimit} maxActiveJobs=${batchMaxActiveJobs} ${targetFill}`);
        } catch (error) {
          log(`fill-queue-error target=${target} ${error.message}`);
          risks.push(`fill-queue 실패 ${target}: ${error.message.slice(0, 160)}`);
        }
      }
    }
    fill = fillResults.join("\n");

    const statusAges = db(`
SELECT CONCAT(
  'job=', batch_job_id,
  ',status=', status,
  ',completed=', completed_count, '/', submitted_count,
  ',failed=', failed_count,
  ',updated_age_min=', TIMESTAMPDIFF(MINUTE, updated_at, NOW()),
  ',submitted_age_min=', TIMESTAMPDIFF(MINUTE, submitted_at, NOW()),
  ',decision=',
  CASE
    WHEN status IN ('validating','in_progress') AND completed_count = 0 AND TIMESTAMPDIFF(MINUTE, submitted_at, NOW()) >= 30
      THEN 'completed_zero_over_30m_needs_cancel_retry_review'
    WHEN status = 'in_progress'
      AND completed_count > 0
      AND submitted_count > 0
      AND completed_count >= submitted_count * 0.95
      AND TIMESTAMPDIFF(MINUTE, updated_at, NOW()) >= 240
      THEN 'tail_stall_over_4h_cancel_or_partial_recovery_review'
    WHEN status = 'in_progress'
      AND completed_count > 0
      AND submitted_count > 0
      AND completed_count >= submitted_count * 0.95
      AND TIMESTAMPDIFF(MINUTE, updated_at, NOW()) >= 120
      THEN 'tail_stall_over_2h_attention'
    WHEN status = 'in_progress'
      AND completed_count > 0
      AND submitted_count > 0
      AND completed_count >= submitted_count * 0.95
      AND TIMESTAMPDIFF(MINUTE, updated_at, NOW()) >= 30
      THEN 'tail_stall_over_30m_watch'
    WHEN status = 'finalizing' AND TIMESTAMPDIFF(MINUTE, updated_at, NOW()) >= 240
      THEN 'finalizing_over_4h_strong_abnormal_manual_cancel_review'
    WHEN status = 'finalizing' AND TIMESTAMPDIFF(MINUTE, updated_at, NOW()) >= 60
      THEN 'finalizing_over_1h_long_running'
    WHEN status = 'finalizing' AND TIMESTAMPDIFF(MINUTE, updated_at, NOW()) >= 30
      THEN 'finalizing_over_30m_watch'
    ELSE 'normal_watch'
  END
)
FROM semantic_batch_jobs
WHERE target IN (${sqlTargets(batchTargets)})
  AND status IN ('validating','in_progress','finalizing','completed')
ORDER BY batch_job_id DESC
`);
    log(`status-ages ${statusAges.join("; ")}`);
    for (const row of statusAges) {
      if (row.includes("finalizing_over_") || row.includes("completed_zero_over_30m") || row.includes("tail_stall_over_")) {
        risks.push(row);
      }
    }

    const summary = db(`
SELECT CONCAT('active=', COUNT(*))
FROM semantic_batch_jobs
WHERE target IN (${sqlTargets(batchTargets)}) AND status IN ('validating','in_progress','finalizing')
UNION ALL
SELECT CONCAT(COALESCE(e.status,'NO_EMBED'), '=', COUNT(*))
FROM law_api_document_chunks c
JOIN law_api_documents doc ON doc.document_id = c.document_id
LEFT JOIN law_api_chunk_embeddings e
  ON e.chunk_id = c.chunk_id
  AND e.embedding_model = '${model}'
  AND e.vector_store = '${vectorStore}'
WHERE doc.target IN (${sqlTargets(batchTargets)})
  AND c.use_yn = 'Y'
  AND doc.use_yn = 'Y'
GROUP BY COALESCE(e.status,'NO_EMBED')
`);
    log(`summary ${summary.join("; ")}`);

    const jobs = db(`
SELECT batch_job_id, openai_batch_id, status, requested_count, submitted_count, completed_count, failed_count, ingested_count, updated_at
FROM semantic_batch_jobs
WHERE target IN (${sqlTargets(batchTargets)})
ORDER BY
  CASE WHEN status = 'INGESTED' THEN 1 ELSE 0 END,
  batch_job_id DESC
LIMIT 10
`);
    fs.writeFileSync(reportPath, buildReportReadable({
      springStatus,
      qdrantStatus,
      qdrantPoints,
      jobs,
      summary,
      fill,
      recoveryActions,
      risks,
    }), "utf8");
    log("codex chat report prepared only");
    log("finish admrul monitor node");
  } catch (error) {
    log(`error ${error.message}`);
    throw error;
  } finally {
    fs.rmSync(lockPath, { force: true });
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
