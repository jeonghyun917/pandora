const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const workspace = path.resolve(__dirname, "..");
const mysql = process.env.MARIADB_EXE || "C:\\Program Files\\MariaDB 12.2\\bin\\mariadb.exe";
const qdrantUrl = process.env.QDRANT_URL || "http://127.0.0.1:6333";
const collection = process.env.PANDORA_LAW_VECTOR_STORE || "law_chunks";
const model = process.env.PANDORA_EMBEDDING_MODEL || "text-embedding-3-small";

const args = Object.fromEntries(process.argv.slice(2).map((arg) => {
  const [key, ...rest] = arg.replace(/^--/, "").split("=");
  return [key, rest.length ? rest.join("=") : "true"];
}));

const apply = String(args.apply || "false").toLowerCase() === "true";
const target = String(args.target || "all").trim();
const limit = Number(args.limit || 1000);
const outPath = path.resolve(workspace, "logs", "law-metadata-repair-latest.json");

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

function rows() {
  const targetWhere = target === "all" ? "" : `AND d.target='${q(target)}'`;
  return db(`
SELECT d.target,
       d.document_id,
       d.title,
       c.chunk_id,
       COALESCE(c.chunk_title, '') AS chunk_title,
       COALESCE(c.chunk_text, '') AS chunk_text,
       COALESCE(e.vector_point_id, c.chunk_id) AS vector_point_id
FROM law_api_documents d
JOIN law_api_document_chunks c ON c.document_id=d.document_id
LEFT JOIN law_api_chunk_embeddings e
  ON e.chunk_id=c.chunk_id
 AND e.embedding_model='${q(model)}'
 AND e.vector_store='${q(collection)}'
 AND e.status='INDEXED'
WHERE d.use_yn='Y'
  AND c.use_yn='Y'
  AND c.index_status='INDEXED'
  AND (c.chunk_no IS NULL OR TRIM(c.chunk_no)='')
  ${targetWhere}
ORDER BY d.target, d.document_id, c.chunk_id
LIMIT ${Number(limit)};
`).map((row) => ({
    target: row[0] || "",
    documentId: Number(row[1]),
    documentTitle: row[2] || "",
    chunkId: Number(row[3]),
    chunkTitle: row[4] || "",
    chunkText: row[5] || "",
    vectorPointId: row[6] || String(row[3] || ""),
  }));
}

function cleanText(text) {
  const cleaned = String(text || "")
    .replace(/<[^>]+>/g, " ")
    .replace(/[│┃║]/g, " | ")
    .replace(/[─━═┬┴┼├┤┌┐└┘]+/g, " ")
    .replace(/\s*\|\s*/g, " | ")
    .replace(/\s+/g, " ")
    .replace(/(?:\|\s*){2,}/g, "| ")
    .replace(/^(?:\|\s*)+/, "")
    .replace(/(?:\s*\|)+$/, "")
    .trim();
  return /[A-Za-z0-9가-힣]/.test(cleaned.replace(/\|/g, "")) ? cleaned : "";
}

function truncate(value, max) {
  const text = String(value || "");
  return text.length <= max ? text : `${text.slice(0, Math.max(0, max - 3))}...`;
}

function labelFromTechnicalTitle(chunkTitle, fallback) {
  const title = String(chunkTitle || "").trim();
  const match = title.match(/^(제개정이유내용|개정문내용|별표내용)\[(\d+)](?:\[(\d+)])?#?\d*$/);
  if (match) {
    const base = {
      "제개정이유내용": "제개정이유",
      "개정문내용": "개정문",
      "별표내용": "별표",
    }[match[1]] || fallback;
    const first = Number(match[2]) + 1;
    const second = match[3] === undefined ? null : Number(match[3]) + 1;
    return second === null ? `${base} ${first}` : `${base} ${first}-${second}`;
  }
  if (title) return title.replace(/#\d+$/, "");
  return fallback;
}

function repair(row) {
  const fallback = row.target === "admrul" ? "행정규칙 본문" : "법령 본문";
  const chunkNo = truncate(labelFromTechnicalTitle(row.chunkTitle, fallback), 100);
  const sample = cleanText(row.chunkText);
  const chunkTitle = truncate(sample ? `${chunkNo} - ${sample}` : chunkNo, 500);
  return { ...row, chunkNo, repairedTitle: chunkTitle };
}

function updateDb(items) {
  if (!items.length) return;
  const statements = items.map((item) => `
UPDATE law_api_document_chunks
SET chunk_no='${q(item.chunkNo)}',
    chunk_title='${q(item.repairedTitle)}',
    updated_at=NOW()
WHERE chunk_id=${Number(item.chunkId)}
  AND (chunk_no IS NULL OR TRIM(chunk_no)='');
`).join("\n");
  db(`START TRANSACTION;\n${statements}\nCOMMIT;`);
}

async function updateQdrant(items) {
  for (const item of items) {
    const point = Number(item.vectorPointId);
    if (!Number.isFinite(point)) continue;
    const response = await fetch(`${qdrantUrl}/collections/${encodeURIComponent(collection)}/points/payload?wait=true`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        payload: { chunkNo: item.chunkNo },
        points: [point],
      }),
      signal: AbortSignal.timeout(60000),
    });
    if (!response.ok) {
      const text = await response.text();
      throw new Error(`Qdrant payload update failed for chunk ${item.chunkId}: HTTP ${response.status} ${text.slice(0, 300)}`);
    }
  }
}

async function main() {
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  const candidates = rows();
  const repaired = candidates.map(repair);
  if (apply) {
    updateDb(repaired);
    await updateQdrant(repaired);
  }
  const result = {
    generatedAt: new Date().toISOString(),
    mode: apply ? "apply" : "dry-run",
    target,
    collection,
    model,
    candidates: candidates.length,
    repaired: repaired.map((item) => ({
      target: item.target,
      documentId: item.documentId,
      chunkId: item.chunkId,
      chunkNo: item.chunkNo,
      chunkTitle: item.repairedTitle,
    })),
  };
  fs.writeFileSync(outPath, JSON.stringify(result, null, 2), "utf8");
  console.log(JSON.stringify(result, null, 2));
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
