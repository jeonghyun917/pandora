const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const workspace = path.resolve(__dirname, "..");
const mysql = process.env.MARIADB_EXE || "C:\\Program Files\\MariaDB 12.2\\bin\\mariadb.exe";
const qdrantUrl = process.env.QDRANT_URL || "http://127.0.0.1:6333";
const model = process.env.PANDORA_EMBEDDING_MODEL || "text-embedding-3-small";

const args = Object.fromEntries(process.argv.slice(2).map((arg) => {
  const [key, ...rest] = arg.replace(/^--/, "").split("=");
  return [key, rest.join("=") || "true"];
}));

const collection = args.collection || "law_chunks";
const target = args.target || "admrul";
const limit = Number(args.limit || 10000);
const deleteStale = args.delete === "true";
const outPath = path.resolve(workspace, "logs", `qdrant-stale-${collection}-${target}-latest.json`);

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

function indexedPointIds() {
  if (collection === "law_chunks" && ["law", "admrul"].includes(target)) {
    return db(`
SELECT e.vector_point_id
FROM law_api_chunk_embeddings e
JOIN law_api_document_chunks c ON c.chunk_id=e.chunk_id
JOIN law_api_documents d ON d.document_id=c.document_id
WHERE e.embedding_model='${q(model)}'
  AND e.vector_store='${q(collection)}'
  AND e.status='INDEXED'
  AND c.use_yn='Y'
  AND d.use_yn='Y'
  AND d.target='${q(target)}'
  AND COALESCE(e.content_hash,'')=COALESCE(c.content_hash,'');
`).map((row) => String(row[0]));
  }
  if (["rag_chunks_v4", "official_chunks"].includes(collection) && ["official_doc", "internal_doc", "reference_doc"].includes(target)) {
    return db(`
SELECT e.vector_point_id
FROM rag_chunk_embeddings e
JOIN rag_document_chunks c ON c.chunk_id=e.chunk_id
JOIN rag_documents d ON d.document_id=c.document_id
WHERE e.embedding_model='${q(model)}'
  AND e.vector_store='${q(collection)}'
  AND e.status='INDEXED'
  AND c.use_yn='Y'
  AND d.use_yn='Y'
  AND d.document_type='${q(target)}'
  AND c.chunk_version=(
    SELECT MAX(c2.chunk_version)
    FROM rag_document_chunks c2
    WHERE c2.document_id=c.document_id
      AND c2.use_yn='Y'
  )
  AND COALESCE(e.content_hash,'')=COALESCE(c.content_hash,'');
`).map((row) => String(row[0]));
  }
  throw new Error(`Unsupported audit target: collection=${collection}, target=${target}`);
}

async function qdrantScroll(offset) {
  const body = {
    limit,
    with_payload: false,
    with_vector: false,
    filter: {
      must: [
        { key: "target", match: { value: target } },
      ],
    },
  };
  if (offset !== undefined && offset !== null) {
    body.offset = offset;
  }
  const response = await fetch(`${qdrantUrl}/collections/${encodeURIComponent(collection)}/points/scroll`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(60000),
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`Qdrant scroll failed HTTP ${response.status}: ${text.slice(0, 300)}`);
  }
  return response.json();
}

async function qdrantCollectionInfo() {
  try {
    const response = await fetch(`${qdrantUrl}/collections/${encodeURIComponent(collection)}`, {
      signal: AbortSignal.timeout(10000),
    });
    const text = await response.text();
    if (!response.ok) {
      return {
        ok: false,
        status: `HTTP_${response.status}`,
        error: text.slice(0, 500),
      };
    }
    const json = text ? JSON.parse(text) : {};
    return {
      ok: true,
      status: String(json.result?.status || ""),
      optimizerStatus: json.result?.optimizer_status || "",
      pointsCount: Number(json.result?.points_count || 0),
      updateQueueLength: Number(json.result?.update_queue?.length || 0),
    };
  } catch (error) {
    return {
      ok: false,
      status: "UNREACHABLE",
      error: error.message,
    };
  }
}

function isGreenCollection(info) {
  return info && info.ok && String(info.status || "").toLowerCase() === "green";
}

async function deletePoints(points) {
  for (let index = 0; index < points.length; index += 512) {
    const batch = points.slice(index, index + 512);
    const response = await fetch(`${qdrantUrl}/collections/${encodeURIComponent(collection)}/points/delete`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ points: batch }),
      signal: AbortSignal.timeout(60000),
    });
    if (!response.ok) {
      const text = await response.text();
      throw new Error(`Qdrant delete failed HTTP ${response.status}: ${text.slice(0, 300)}`);
    }
  }
}

async function main() {
  const startedAt = new Date().toISOString();
  const qdrantHealth = await qdrantCollectionInfo();
  if (deleteStale && !isGreenCollection(qdrantHealth)) {
    throw new Error(
      `Refusing to delete stale points because Qdrant collection ${collection} is not green: ${JSON.stringify(qdrantHealth)}`,
    );
  }
  const expectedIds = new Set(indexedPointIds());
  let offset = undefined;
  let scanned = 0;
  const stale = [];

  while (true) {
    const json = await qdrantScroll(offset);
    const points = json.result?.points || [];
    for (const point of points) {
      scanned += 1;
      const id = String(point.id);
      if (!expectedIds.has(id)) {
        stale.push(point.id);
      }
    }
    offset = json.result?.next_page_offset;
    if (!offset || !points.length) {
      break;
    }
  }

  if (deleteStale && stale.length) {
    await deletePoints(stale);
  }

  const result = {
    startedAt,
    finishedAt: new Date().toISOString(),
    collection,
    target,
    qdrantHealth,
    dbIndexed: expectedIds.size,
    qdrantScanned: scanned,
    staleCount: stale.length,
    staleSample: stale.slice(0, 50),
    deleted: deleteStale ? stale.length : 0,
  };
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, JSON.stringify(result, null, 2), "utf8");
  console.log(JSON.stringify(result, null, 2));
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
