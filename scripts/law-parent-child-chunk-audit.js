const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const workspace = path.resolve(__dirname, "..");
const mysql = process.env.MARIADB_EXE || "C:\\Program Files\\MariaDB 12.2\\bin\\mariadb.exe";
const outPath = path.resolve(workspace, "logs", "law-parent-child-chunk-audit-latest.md");
const jsonPath = path.resolve(workspace, "logs", "law-parent-child-chunk-audit-latest.json");

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

function fmt(value) {
  return number(value).toLocaleString("ko-KR");
}

function pct(part, total) {
  const denominator = number(total);
  if (!denominator) return "0.00%";
  const value = (number(part) / denominator) * 100;
  if (value > 0 && value < 0.01) return "<0.01%";
  return `${value.toFixed(2)}%`;
}

function mdTable(rows, columns) {
  if (!rows.length) return "_없음_";
  const header = `| ${columns.map((column) => column.label).join(" | ")} |`;
  const sep = `| ${columns.map((column) => column.align === "right" ? "---:" : "---").join(" | ")} |`;
  const body = rows.map((row) => `| ${columns.map((column) => {
    const raw = row[column.key] ?? "";
    const value = column.format === "number" ? fmt(raw) : String(raw);
    return value.replace(/\r?\n/g, " ").replace(/\|/g, "\\|");
  }).join(" | ")} |`);
  return [header, sep, ...body].join("\n");
}

const parentExpression = `
CASE
  WHEN c.source_path IS NOT NULL AND c.source_path != ''
    THEN c.source_path
  WHEN c.chunk_title REGEXP '^(항내용|호내용|목내용|조문내용|목내용)#'
    THEN CONCAT(c.chunk_title, ':sort:', FLOOR(c.sort_order / 20))
  WHEN c.chunk_title LIKE '$.%'
    THEN REGEXP_REPLACE(c.chunk_title, '\\\\[[0-9]+\\\\]#?[0-9]*$', '')
  WHEN COALESCE(NULLIF(TRIM(REGEXP_REPLACE(c.chunk_title, '\\\\s*문단\\\\s*[0-9]+.*$', '')), ''), '') != ''
    THEN TRIM(REGEXP_REPLACE(c.chunk_title, '\\\\s*문단\\\\s*[0-9]+.*$', ''))
  WHEN COALESCE(NULLIF(TRIM(REGEXP_REPLACE(c.chunk_no, '\\\\s*문단[0-9]+.*$', '')), ''), '') != ''
    THEN TRIM(REGEXP_REPLACE(c.chunk_no, '\\\\s*문단[0-9]+.*$', ''))
  ELSE CONCAT('sort:', FLOOR(c.sort_order / 20))
END`;

const baseFilter = `
FROM law_api_document_chunks c
JOIN law_api_documents doc ON doc.document_id=c.document_id
WHERE doc.use_yn='Y'
  AND c.use_yn='Y'`;

const profileByType = table(`
SELECT
  doc.target,
  c.chunk_type,
  COUNT(*) AS chunks,
  ROUND(AVG(CHAR_LENGTH(c.chunk_text)),1) AS avg_len,
  MIN(CHAR_LENGTH(c.chunk_text)) AS min_len,
  MAX(CHAR_LENGTH(c.chunk_text)) AS max_len,
  SUM(CHAR_LENGTH(c.chunk_text) < 20) AS lt20,
  SUM(CHAR_LENGTH(c.chunk_text) < 80) AS lt80,
  SUM(CHAR_LENGTH(c.chunk_text) < 200) AS lt200,
  SUM(CHAR_LENGTH(c.chunk_text) > 1200) AS gt1200,
  SUM(CHAR_LENGTH(c.chunk_text) > 2500) AS gt2500
${baseFilter}
GROUP BY doc.target, c.chunk_type
ORDER BY doc.target, chunks DESC;
`, ["target", "chunkType", "chunks", "avgLen", "minLen", "maxLen", "lt20", "lt80", "lt200", "gt1200", "gt2500"]);

const parentProjection = table(`
WITH grouped AS (
  SELECT
    doc.target,
    c.document_id,
    ${parentExpression} AS parent_key,
    COUNT(*) AS source_chunks,
    SUM(CHAR_LENGTH(c.chunk_text)) AS text_len,
    SUM(CHAR_LENGTH(c.chunk_text) < 20) AS lt20,
    SUM(CHAR_LENGTH(c.chunk_text) < 80) AS lt80,
    SUM(c.chunk_text REGEXP '^<[^>]*$|^[0-9]{4}\\\\.[0-9]{1,2}\\\\.[0-9]{1,2}>$|^삭제( <[^>]+>)?$') AS revision_noise
  ${baseFilter}
  GROUP BY doc.target, c.document_id, parent_key
)
SELECT
  target,
  COUNT(*) AS parent_groups,
  SUM(source_chunks) AS source_chunks,
  SUM(CASE WHEN text_len <= 2500 THEN 1 ELSE CEIL(text_len / 1800) END) AS projected_child_chunks,
  ROUND(AVG(text_len),1) AS avg_parent_len,
  MAX(text_len) AS max_parent_len,
  SUM(lt20) AS lt20,
  SUM(lt80) AS lt80,
  SUM(revision_noise) AS revision_noise
FROM grouped
GROUP BY target
ORDER BY target;
`, ["target", "parentGroups", "sourceChunks", "projectedChildChunks", "avgParentLen", "maxParentLen", "lt20", "lt80", "revisionNoise"]);

const parentRisk = table(`
WITH grouped AS (
  SELECT
    doc.target,
    doc.title,
    c.document_id,
    ${parentExpression} AS parent_key,
    COUNT(*) AS source_chunks,
    SUM(CHAR_LENGTH(c.chunk_text)) AS text_len,
    SUM(CHAR_LENGTH(c.chunk_text) < 80) AS tiny_chunks
  ${baseFilter}
  GROUP BY doc.target, doc.title, c.document_id, parent_key
)
SELECT
  target,
  title,
  parent_key,
  source_chunks,
  text_len,
  tiny_chunks,
  CASE WHEN text_len <= 2500 THEN 1 ELSE CEIL(text_len / 1800) END AS projected_children
FROM grouped
ORDER BY source_chunks DESC, text_len DESC
LIMIT 25;
`, ["target", "title", "parentKey", "sourceChunks", "textLen", "tinyChunks", "projectedChildren"]);

const duplicateNoise = table(`
SELECT
  doc.target,
  c.chunk_text,
  COUNT(*) AS count
${baseFilter}
GROUP BY doc.target, c.content_hash, c.chunk_text
HAVING count >= 100
ORDER BY count DESC
LIMIT 30;
`, ["target", "chunkText", "count"]);

const tinySamples = table(`
SELECT
  doc.target,
  doc.title,
  c.chunk_id,
  c.chunk_type,
  c.chunk_no,
  c.chunk_title,
  CHAR_LENGTH(c.chunk_text) AS length,
  LEFT(REPLACE(REPLACE(c.chunk_text, CHAR(10), ' '), CHAR(13), ' '), 180) AS sample
${baseFilter}
  AND CHAR_LENGTH(c.chunk_text) < 20
ORDER BY c.chunk_id
LIMIT 40;
`, ["target", "title", "chunkId", "chunkType", "chunkNo", "chunkTitle", "length", "sample"]);

const metadataGaps = table(`
SELECT
  doc.target,
  COUNT(*) AS chunks,
  SUM(c.chunk_title IS NULL OR TRIM(c.chunk_title) = '') AS missing_title,
  SUM(c.chunk_no IS NULL OR TRIM(c.chunk_no) = '') AS missing_chunk_no,
  SUM(c.content_hash IS NULL OR TRIM(c.content_hash) = '') AS missing_hash,
  SUM(c.indexed_at IS NULL) AS missing_indexed_at,
  SUM(c.index_status IS NULL OR c.index_status != 'INDEXED') AS not_indexed
${baseFilter}
GROUP BY doc.target
ORDER BY doc.target;
`, ["target", "chunks", "missingTitle", "missingChunkNo", "missingHash", "missingIndexedAt", "notIndexed"]);

const embeddingStatus = table(`
SELECT
  doc.target,
  COALESCE(e.vector_store, '(none)') AS vector_store,
  COALESCE(e.status, 'NO_EMBED') AS status,
  COUNT(*) AS chunks
FROM law_api_document_chunks c
JOIN law_api_documents doc ON doc.document_id=c.document_id
LEFT JOIN law_api_chunk_embeddings e
  ON e.chunk_id = c.chunk_id
WHERE doc.use_yn='Y'
  AND c.use_yn='Y'
GROUP BY doc.target, COALESCE(e.vector_store, '(none)'), COALESCE(e.status, 'NO_EMBED')
ORDER BY doc.target, vector_store, status;
`, ["target", "vectorStore", "status", "chunks"]);

const projectionRows = parentProjection.map((row) => ({
  ...row,
  reduction: number(row.sourceChunks) && number(row.projectedChildChunks)
    ? `${(100 - (number(row.projectedChildChunks) / number(row.sourceChunks)) * 100).toFixed(1)}%`
    : "0.0%",
  tinyRate: pct(row.lt80, row.sourceChunks),
  noiseRate: pct(row.revisionNoise, row.sourceChunks),
}));

const qualitySummary = projectionRows.map((row) => ({
  target: row.target,
  currentChunks: number(row.sourceChunks),
  projectedChildChunks: number(row.projectedChildChunks),
  projectedReduction: row.reduction,
  tinyChunksUnder80: number(row.lt80),
  tinyRateUnder80: row.tinyRate,
  revisionNoiseChunks: number(row.revisionNoise),
  revisionNoiseRate: row.noiseRate,
  maxParentLen: number(row.maxParentLen),
}));

const result = {
  generatedAt: new Date().toISOString(),
  profileByType,
  parentProjection,
  qualitySummary,
  metadataGaps,
  embeddingStatus,
  parentRisk,
  duplicateNoise,
  tinySamples,
};

const markdown = [
  "# Law Parent-Child Chunk Audit",
  "",
  `- Generated at: ${result.generatedAt}`,
  "- Purpose: 법령/행정규칙 기존 line-level 청크를 parent section + semantic child chunk 구조로 재설계하기 위한 사전 계측",
  "",
  "## Current Chunk Profile",
  "",
  mdTable(profileByType, [
    { key: "target", label: "Target" },
    { key: "chunkType", label: "Type" },
    { key: "chunks", label: "Chunks", align: "right", format: "number" },
    { key: "avgLen", label: "Avg", align: "right" },
    { key: "minLen", label: "Min", align: "right", format: "number" },
    { key: "maxLen", label: "Max", align: "right", format: "number" },
    { key: "lt20", label: "<20", align: "right", format: "number" },
    { key: "lt80", label: "<80", align: "right", format: "number" },
    { key: "gt1200", label: ">1200", align: "right", format: "number" },
    { key: "gt2500", label: ">2500", align: "right", format: "number" },
  ]),
  "",
  "## Parent Group Projection",
  "",
  "Heuristic: 같은 문서의 조문/제목 base를 parent로 묶고, parent 본문은 2,500자 이하 1개 child, 초과 시 약 1,800자 단위 child로 분할.",
  "",
  mdTable(projectionRows, [
    { key: "target", label: "Target" },
    { key: "parentGroups", label: "Parent groups", align: "right", format: "number" },
    { key: "sourceChunks", label: "Current chunks", align: "right", format: "number" },
    { key: "projectedChildChunks", label: "Projected children", align: "right", format: "number" },
    { key: "reduction", label: "Reduction" },
    { key: "avgParentLen", label: "Avg parent len", align: "right" },
    { key: "maxParentLen", label: "Max parent len", align: "right", format: "number" },
    { key: "tinyRate", label: "<80 rate" },
    { key: "noiseRate", label: "Revision/noise rate" },
  ]),
  "",
  "## Metadata / Index Status",
  "",
  mdTable(metadataGaps, [
    { key: "target", label: "Target" },
    { key: "chunks", label: "Chunks", align: "right", format: "number" },
    { key: "missingTitle", label: "Missing title", align: "right", format: "number" },
    { key: "missingChunkNo", label: "Missing no", align: "right", format: "number" },
    { key: "missingHash", label: "Missing hash", align: "right", format: "number" },
    { key: "missingIndexedAt", label: "No indexed_at", align: "right", format: "number" },
    { key: "notIndexed", label: "Not INDEXED", align: "right", format: "number" },
  ]),
  "",
  "## Embedding Status",
  "",
  mdTable(embeddingStatus, [
    { key: "target", label: "Target" },
    { key: "vectorStore", label: "Vector store" },
    { key: "status", label: "Status" },
    { key: "chunks", label: "Chunks", align: "right", format: "number" },
  ]),
  "",
  "## Largest Parent Candidates",
  "",
  mdTable(parentRisk, [
    { key: "target", label: "Target" },
    { key: "title", label: "Title" },
    { key: "parentKey", label: "Parent key" },
    { key: "sourceChunks", label: "Source chunks", align: "right", format: "number" },
    { key: "textLen", label: "Text len", align: "right", format: "number" },
    { key: "tinyChunks", label: "Tiny", align: "right", format: "number" },
    { key: "projectedChildren", label: "Projected", align: "right", format: "number" },
  ]),
  "",
  "## Duplicate Noise Samples",
  "",
  mdTable(duplicateNoise, [
    { key: "target", label: "Target" },
    { key: "chunkText", label: "Text" },
    { key: "count", label: "Count", align: "right", format: "number" },
  ]),
  "",
  "## Tiny Chunk Samples",
  "",
  mdTable(tinySamples, [
    { key: "target", label: "Target" },
    { key: "title", label: "Title" },
    { key: "chunkId", label: "Chunk", align: "right", format: "number" },
    { key: "chunkType", label: "Type" },
    { key: "chunkNo", label: "No" },
    { key: "chunkTitle", label: "Chunk title" },
    { key: "length", label: "Len", align: "right", format: "number" },
    { key: "sample", label: "Sample" },
  ]),
  "",
  "## Design Implications",
  "",
  "- line-level `<개정`, 시행일, `삭제` 조각은 독립 embedding point로 두면 검색 후보 오염 가능성이 큽니다.",
  "- parent는 원문 조문/섹션 보존 단위, child는 검색 단위로 분리해야 합니다.",
  "- child embedding input에는 법령명, 현행/시행일, parent 조문명, 항/호/목/별표 위치, 의미 본문을 함께 넣어야 합니다.",
  "- 긴 parent는 child로 나누되, 짧은 line은 같은 parent 안에서 800~2,500자 범위로 병합하는 쪽이 우선입니다.",
].join("\n");

fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, markdown, "utf8");
fs.writeFileSync(jsonPath, JSON.stringify(result, null, 2), "utf8");
console.log(outPath);
console.log(jsonPath);
