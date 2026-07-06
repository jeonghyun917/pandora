const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const workspace = path.resolve(__dirname, "..");
const mysql = process.env.MARIADB_EXE || "C:\\Program Files\\MariaDB 12.2\\bin\\mariadb.exe";
const baseUrl = (process.env.PANDORA_BATCH_RUNNER_URL || "http://127.0.0.1:18080").replace(/\/$/, "");
const apply = boolArg("apply", false);
const maxDocs = Number(arg("max-docs", process.env.RAG_SHORT_RECHUNK_MAX_DOCS || 3));
const minShort = Number(arg("min-short", process.env.RAG_SHORT_RECHUNK_MIN_SHORT || 20));
const minNoisy = Number(arg("min-noisy", process.env.RAG_SHORT_RECHUNK_MIN_NOISY || 20));
const excludeRecentHours = Number(arg("exclude-recent-hours", process.env.RAG_SHORT_RECHUNK_EXCLUDE_RECENT_HOURS || 24));
const indexNow = boolArg("index-now", true);
const outMd = path.resolve(workspace, "logs", "rag-short-rechunk-wave-latest.md");
const outJson = path.resolve(workspace, "logs", "rag-short-rechunk-wave-latest.json");

async function main() {
  const before = await snapshot();
  const candidates = candidateDocuments().slice(0, maxDocs);
  const actions = [];
  if (apply && candidates.length) {
    await assertRunnerHealthy();
    for (const candidate of candidates) {
      const response = await reimport(candidate);
      actions.push({ documentId: candidate.documentId, title: candidate.title, response });
    }
  }
  const after = await snapshot();
  const result = {
    generatedAt: new Date().toISOString(),
    apply,
    indexNow,
    maxDocs,
    minShort,
    minNoisy,
    excludeRecentHours,
    before,
    after,
    candidates,
    actions,
  };
  fs.mkdirSync(path.dirname(outMd), { recursive: true });
  fs.writeFileSync(outJson, JSON.stringify(result, null, 2), "utf8");
  fs.writeFileSync(outMd, markdown(result), "utf8");
  console.log(outMd);
  console.log(outJson);
}

async function assertRunnerHealthy() {
  const response = await fetch(`${baseUrl}/api/law-data/semantic/batches/scheduler-status`, {
    signal: AbortSignal.timeout(15000),
  });
  if (!response.ok) {
    throw new Error(`Batch runner health failed: HTTP ${response.status}`);
  }
}

async function reimport(candidate) {
  const params = new URLSearchParams({
    documentType: "official_doc",
    path: candidate.filePath,
    indexNow: String(indexNow),
    force: "true",
  });
  const response = await fetch(`${baseUrl}/api/rag-documents/import-folder?${params.toString()}`, {
    method: "POST",
    signal: AbortSignal.timeout(900000),
  });
  const text = await response.text();
  let body = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = { raw: text.slice(0, 500) };
  }
  if (!response.ok) {
    throw new Error(`Reimport failed for document ${candidate.documentId}: HTTP ${response.status} ${JSON.stringify(body)}`);
  }
  if (!body || !["SUCCESS", "PARTIAL_SUCCESS"].includes(body.status)) {
    throw new Error(`Reimport failed for document ${candidate.documentId}: ${JSON.stringify(body)}`);
  }
  return body;
}

async function snapshot() {
  const rows = dbRows(`
SELECT 'rag_short' AS name, COUNT(*) AS value
FROM rag_document_chunks c
JOIN rag_documents d ON d.document_id=c.document_id
WHERE d.use_yn='Y'
  AND c.use_yn='Y'
  AND d.document_type='official_doc'
  AND c.chunk_version=(
    SELECT MAX(c2.chunk_version)
    FROM rag_document_chunks c2
    WHERE c2.document_id=c.document_id
      AND c2.use_yn='Y'
  )
  AND CHAR_LENGTH(c.chunk_text) < 120
UNION ALL
SELECT 'embedding_backlog' AS name, COUNT(*) AS value
FROM rag_document_chunks c
JOIN rag_documents d ON d.document_id=c.document_id
LEFT JOIN rag_chunk_embeddings e
  ON e.chunk_id=c.chunk_id
 AND e.embedding_model='text-embedding-3-small'
 AND e.vector_store='rag_chunks_v4'
 AND e.status='INDEXED'
 AND COALESCE(e.content_hash,'')=COALESCE(c.content_hash,'')
WHERE d.use_yn='Y'
  AND c.use_yn='Y'
  AND d.document_type='official_doc'
  AND c.chunk_version=(
    SELECT MAX(c2.chunk_version)
    FROM rag_document_chunks c2
    WHERE c2.document_id=c.document_id
      AND c2.use_yn='Y'
  )
  AND e.chunk_id IS NULL
UNION ALL
SELECT 'active_batch' AS name, COUNT(*) AS value
FROM semantic_batch_jobs
WHERE target='official_doc'
  AND vector_store='rag_chunks_v4'
  AND status IN ('in_progress','validating','finalizing','completed','BATCH_SUBMITTED')
  AND COALESCE(ingested_count,0)=0
`, ["name", "value"]);
  return Object.fromEntries(rows.map((row) => [row.name, Number(row.value || 0)]));
}

function candidateDocuments() {
  return dbRows(`
SELECT
  CAST(d.document_id AS CHAR) AS document_id,
  COALESCE(d.source_org,'') AS source_org,
  d.title,
  d.file_path,
  COUNT(*) AS chunks,
  SUM(CHAR_LENGTH(c.chunk_text) < 120) AS short_chunks,
  ROUND(SUM(CHAR_LENGTH(c.chunk_text) < 120) / COUNT(*) * 100, 2) AS short_pct,
  SUM(
    c.chunk_text LIKE '%©%'
    OR LOWER(c.chunk_text) LIKE '%copyright%'
    OR LOWER(c.chunk_text) LIKE '%oecd 2026%'
    OR c.chunk_text LIKE '%첨부%'
    OR c.chunk_text LIKE '%상단%'
    OR c.chunk_text LIKE '%메뉴%'
    OR LOWER(c.chunk_text) LIKE '%<img%'
  ) AS noisy_like
FROM rag_documents d
JOIN rag_document_chunks c ON c.document_id=d.document_id
WHERE d.use_yn='Y'
  AND c.use_yn='Y'
  AND d.document_type='official_doc'
  AND d.file_path IS NOT NULL
  AND d.file_path <> ''
  AND NOT EXISTS (
    SELECT 1
    FROM rag_import_jobs j
    WHERE j.import_path=d.file_path
      AND j.document_type='official_doc'
      AND j.status IN ('SUCCESS','PARTIAL_SUCCESS')
      AND j.finished_at >= DATE_SUB(NOW(), INTERVAL ${Math.max(0, Number(excludeRecentHours))} HOUR)
  )
  AND c.chunk_version=(
    SELECT MAX(c2.chunk_version)
    FROM rag_document_chunks c2
    WHERE c2.document_id=c.document_id
      AND c2.use_yn='Y'
  )
GROUP BY d.document_id, d.source_org, d.title, d.file_path
HAVING short_chunks >= ${Number(minShort)} AND noisy_like >= ${Number(minNoisy)}
ORDER BY noisy_like DESC, short_chunks DESC, short_pct DESC, d.document_id
LIMIT ${Math.max(maxDocs * 3, maxDocs)};
`, ["documentId", "sourceOrg", "title", "filePath", "chunks", "shortChunks", "shortPct", "noisyLike"])
    .filter((row) => fs.existsSync(row.filePath));
}

function dbRows(sql, columns) {
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
    maxBuffer: 128 * 1024 * 1024,
    windowsHide: true,
  });
  return output.trim().split(/\r?\n/).filter(Boolean).map((line) => {
    const values = line.split("\t");
    return Object.fromEntries(columns.map((column, index) => [column, values[index] ?? ""]));
  });
}

function markdown(result) {
  const rows = [
    "# RAG Short Rechunk Wave",
    "",
    `- Generated at: ${result.generatedAt}`,
    `- Apply: ${result.apply}`,
    `- Index now: ${result.indexNow}`,
    `- Exclude recent successful imports: ${result.excludeRecentHours}h`,
    "",
    "## Snapshot",
    "",
    "| Metric | Before | After | Delta |",
    "| --- | ---: | ---: | ---: |",
    ...["rag_short", "embedding_backlog", "active_batch"].map((key) => {
      const before = Number(result.before?.[key] || 0);
      const after = Number(result.after?.[key] || 0);
      return `| ${key} | ${before.toLocaleString("ko-KR")} | ${after.toLocaleString("ko-KR")} | ${(after - before).toLocaleString("ko-KR")} |`;
    }),
    "",
    "## Candidates",
    "",
    table(result.candidates.map((row) => ({
      documentId: row.documentId,
      sourceOrg: row.sourceOrg,
      title: row.title,
      chunks: row.chunks,
      shortChunks: row.shortChunks,
      shortPct: row.shortPct,
      noisyLike: row.noisyLike,
    })), [
      ["documentId", "Doc"],
      ["sourceOrg", "Source"],
      ["title", "Title"],
      ["chunks", "Chunks"],
      ["shortChunks", "Short"],
      ["shortPct", "Short %"],
      ["noisyLike", "Noisy-like"],
    ]),
    "",
    "## Actions",
    "",
    result.actions.length
      ? table(result.actions.map((row) => ({
        documentId: row.documentId,
        title: row.title,
        status: row.response?.status || "",
        imported: row.response?.imported || row.response?.importedCount || "",
        indexed: row.response?.indexed || row.response?.indexedCount || "",
      })), [
        ["documentId", "Doc"],
        ["title", "Title"],
        ["status", "Status"],
        ["imported", "Imported"],
        ["indexed", "Indexed"],
      ])
      : "_none_",
    "",
  ];
  return rows.join("\n");
}

function table(rows, columns) {
  if (!rows.length) {
    return "_none_";
  }
  return [
    `| ${columns.map(([, label]) => label).join(" | ")} |`,
    `| ${columns.map(() => "---").join(" | ")} |`,
    ...rows.map((row) => `| ${columns.map(([key]) => String(row[key] ?? "").replace(/\|/g, "\\|")).join(" | ")} |`),
  ].join("\n");
}

function arg(name, fallback) {
  const prefix = `--${name}=`;
  const found = process.argv.find((value) => value.startsWith(prefix));
  return found ? found.slice(prefix.length) : fallback;
}

function boolArg(name, fallback) {
  const value = arg(name, "");
  if (!value) {
    return fallback;
  }
  return ["1", "true", "yes", "y"].includes(value.toLowerCase());
}

main().catch((error) => {
  console.error(error.stack || error.message || String(error));
  process.exit(1);
});
