const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const workspace = path.resolve(__dirname, "..");
const mysql = process.env.MARIADB_EXE || "C:\\Program Files\\MariaDB 12.2\\bin\\mariadb.exe";
const vectorStore = process.env.PANDORA_RAG_VECTOR_STORE || "rag_chunks_v4";
const qdrantUrl = process.env.QDRANT_URL || "http://127.0.0.1:6333";
const collection = process.env.PANDORA_RAG_COLLECTION || vectorStore;
const apply = process.argv.includes("--apply");
const updateQdrant = apply && process.argv.includes("--qdrant");
const overridesPath = path.resolve(workspace, "config", "rag-source-org-overrides.json");
const outMd = path.resolve(workspace, "logs", "rag-source-org-repair-latest.md");
const outJson = path.resolve(workspace, "logs", "rag-source-org-repair-latest.json");

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

function execute(sql) {
  execFileSync(mysql, [
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
}

function unhex(value) {
  return Buffer.from(value || "", "hex").toString("utf8");
}

function compact(value) {
  return String(value ?? "")
    .normalize("NFKC")
    .replace(/[+_()[\]{}.,·ㆍ\-–—]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function lower(value) {
  return compact(value).toLowerCase();
}

function loadSourceOverrides() {
  if (!fs.existsSync(overridesPath)) {
    return new Map();
  }
  const rows = JSON.parse(fs.readFileSync(overridesPath, "utf8"));
  return new Map(rows.map((row) => [Number(row.documentId), row]));
}

const sourceOverrides = loadSourceOverrides();

const rules = [
  {
    sourceOrg: "공공데이터포털",
    confidence: "high",
    reason: "public-data keywords or data.go.kr source",
    explicit: ["공공데이터포털", "data.go.kr"],
    topic: ["공공데이터베이스", "공공데이터", "데이터베이스 표준화", "데이터 표준화"],
  },
  {
    sourceOrg: "개인정보보호위원회",
    confidence: "high",
    reason: "privacy/PIPC guidance keywords",
    explicit: ["개인정보보호위원회", "개인정보위", "pipc"],
    topic: ["개인정보", "영상정보처리기기", "cctv", "가명정보"],
  },
  {
    sourceOrg: "Ministry of Culture, Sports and Tourism",
    confidence: "high",
    reason: "MCST/culture ministry keywords",
    explicit: ["문화체육관광부", "문체부", "mcst", "ministry of culture"],
    topic: [],
  },
  {
    sourceOrg: "Ministry of the Interior and Safety",
    confidence: "high",
    reason: "MOIS/e-government/IRM/pre-consultation/security-review keywords",
    explicit: ["행정안전부", "행안부", "mois", "ministry of the interior"],
    topic: ["전자정부", "정보화사업", "사전협의", "보안성 검토", "정보자원관리", "irm", "범정부", "클라우드", "정보자원", "기록관리기준표"],
  },
  {
    sourceOrg: "Ministry of Science and ICT",
    confidence: "high",
    reason: "MSIT/software public project keywords",
    explicit: ["과학기술정보통신부", "과기정통부", "msit", "ministry of science"],
    topic: ["공공sw", "공공소프트웨어", "소프트웨어사업", "sw사업", "상용소프트웨어", "소프트웨어 사업", "소프트웨어사업관련", "sw기술자"],
  },
];

function inferSourceOrg(document) {
  const override = sourceOverrides.get(Number(document.documentId));
  if (override?.sourceOrg) {
    const guardText = lower([document.title, document.fileName].join(" "));
    const titleContains = override.titleContains || "";
    if (titleContains && !guardText.includes(lower(titleContains))) {
      return {
        sourceOrg: override.sourceOrg,
        confidence: "review",
        reason: `manual override guard failed: expected title/file to include ${titleContains}`,
        hitTerms: [override.evidence || titleContains].filter(Boolean),
      };
    }
    return {
      sourceOrg: override.sourceOrg,
      confidence: "high",
      reason: `manual source confirmation: ${override.evidence || "confirmed override"}`,
      hitTerms: [override.titleContains || override.evidence || "manual override"].filter(Boolean),
    };
  }
  const haystack = lower([
    document.title,
    document.fileName,
    document.filePath,
    document.sourceUrl,
    document.documentCategory,
    document.documentTopic,
  ].join(" "));
  const explicitMatches = [];
  const topicMatches = [];
  for (const rule of rules) {
    const explicitTerms = rule.explicit.filter((term) => haystack.includes(lower(term)));
    if (explicitTerms.length > 0) {
      explicitMatches.push({ ...rule, hitTerms: explicitTerms, matchType: "explicit" });
    }
    const topicTerms = rule.topic.filter((term) => haystack.includes(lower(term)));
    if (topicTerms.length > 0) {
      topicMatches.push({ ...rule, hitTerms: topicTerms, matchType: "topic" });
    }
  }
  if (explicitMatches.length > 0) {
    explicitMatches.sort((a, b) => b.hitTerms.length - a.hitTerms.length || sourcePriority(a.sourceOrg) - sourcePriority(b.sourceOrg));
    const winner = explicitMatches[0];
    const competing = explicitMatches.filter((match) => match.sourceOrg !== winner.sourceOrg);
    if (competing.length > 0) {
      return {
        sourceOrg: winner.sourceOrg,
        confidence: "review",
        reason: `ambiguous explicit agency: ${winner.sourceOrg} vs ${competing.map((match) => match.sourceOrg).join(", ")}`,
        hitTerms: winner.hitTerms,
      };
    }
    return {
      sourceOrg: winner.sourceOrg,
      confidence: "high",
      reason: `${winner.reason}; explicit agency match`,
      hitTerms: winner.hitTerms,
    };
  }
  if (topicMatches.length === 0) {
    return { sourceOrg: "", confidence: "none", reason: "no rule matched", hitTerms: [] };
  }
  topicMatches.sort((a, b) => b.hitTerms.length - a.hitTerms.length || sourcePriority(a.sourceOrg) - sourcePriority(b.sourceOrg));
  const winner = topicMatches[0];
  const competing = topicMatches.filter((match) => match.sourceOrg !== winner.sourceOrg);
  if (competing.length > 0 && competing[0].hitTerms.length >= winner.hitTerms.length - 1) {
    return {
      sourceOrg: winner.sourceOrg,
      confidence: "review",
      reason: `ambiguous topic match: ${winner.sourceOrg} vs ${competing.map((match) => match.sourceOrg).join(", ")}`,
      hitTerms: winner.hitTerms,
    };
  }
  return {
    sourceOrg: winner.sourceOrg,
    confidence: winner.confidence,
    reason: `${winner.reason}; topic-only match`,
    hitTerms: winner.hitTerms,
  };
}

function sourcePriority(sourceOrg) {
  const order = [
    "공공데이터포털",
    "개인정보보호위원회",
    "Ministry of Culture, Sports and Tourism",
    "Ministry of the Interior and Safety",
    "Ministry of Science and ICT",
  ];
  const index = order.indexOf(sourceOrg);
  return index < 0 ? 999 : index;
}

function loadDocuments() {
  return db(`
SELECT d.document_id,
       HEX(d.title),
       HEX(COALESCE(d.file_name, '')),
       HEX(COALESCE(d.file_path, '')),
       HEX(COALESCE(d.source_url, '')),
       HEX(COALESCE(d.document_category, '')),
       HEX(COALESCE(d.document_topic, '')),
       COUNT(c.chunk_id) AS chunks,
       SUM(CHAR_LENGTH(c.chunk_text) < 120) AS short_chunks,
       SUM(CASE WHEN e.status = 'INDEXED' THEN 1 ELSE 0 END) AS indexed_chunks
FROM rag_documents d
LEFT JOIN rag_document_chunks c ON c.document_id = d.document_id AND c.use_yn = 'Y'
LEFT JOIN rag_chunk_embeddings e
  ON e.chunk_id = c.chunk_id
 AND e.vector_store = '${q(vectorStore)}'
WHERE d.use_yn = 'Y'
  AND d.document_type = 'official_doc'
  AND (d.source_org IS NULL OR TRIM(d.source_org) = '')
GROUP BY d.document_id, d.title, d.file_name, d.file_path, d.source_url, d.document_category, d.document_topic
ORDER BY d.document_id;
`).map((row) => ({
    documentId: Number(row[0]),
    title: unhex(row[1]),
    fileName: unhex(row[2]),
    filePath: unhex(row[3]),
    sourceUrl: unhex(row[4]),
    documentCategory: unhex(row[5]),
    documentTopic: unhex(row[6]),
    chunks: Number(row[7] || 0),
    shortChunks: Number(row[8] || 0),
    indexedChunks: Number(row[9] || 0),
  }));
}

function loadPointIds(documentIds) {
  if (documentIds.length === 0) {
    return [];
  }
  const ids = documentIds.map((id) => Number(id)).filter((id) => Number.isFinite(id) && id > 0).join(",");
  if (!ids) {
    return [];
  }
  return db(`
SELECT c.document_id, e.vector_point_id
FROM rag_document_chunks c
JOIN rag_chunk_embeddings e
  ON e.chunk_id = c.chunk_id
 AND e.vector_store = '${q(vectorStore)}'
 AND e.status = 'INDEXED'
WHERE c.use_yn = 'Y'
  AND c.document_id IN (${ids})
ORDER BY c.document_id, c.chunk_id;
`).map((row) => ({
    documentId: Number(row[0]),
    pointId: Number(row[1]),
  })).filter((row) => Number.isSafeInteger(row.pointId));
}

async function updateQdrantPayload(plan) {
  let updatedPoints = 0;
  const bySource = new Map();
  for (const item of plan) {
    if (!bySource.has(item.sourceOrg)) {
      bySource.set(item.sourceOrg, []);
    }
    bySource.get(item.sourceOrg).push(item.documentId);
  }
  for (const [sourceOrg, documentIds] of bySource.entries()) {
    const pointRows = loadPointIds(documentIds);
    for (let start = 0; start < pointRows.length; start += 512) {
      const batch = pointRows.slice(start, start + 512);
      const response = await fetch(`${qdrantUrl}/collections/${encodeURIComponent(collection)}/points/payload?wait=true`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          payload: {
            sourceOrg,
            agencyName: sourceOrg,
          },
          points: batch.map((row) => row.pointId),
        }),
        signal: AbortSignal.timeout(60000),
      });
      if (!response.ok) {
        const text = await response.text();
        throw new Error(`Qdrant payload update failed: HTTP ${response.status} ${text.slice(0, 300)}`);
      }
      updatedPoints += batch.length;
    }
  }
  return updatedPoints;
}

function applyDatabase(plan) {
  let updatedDocuments = 0;
  const bySource = new Map();
  for (const item of plan) {
    if (!bySource.has(item.sourceOrg)) {
      bySource.set(item.sourceOrg, []);
    }
    bySource.get(item.sourceOrg).push(item.documentId);
  }
  for (const [sourceOrg, documentIds] of bySource.entries()) {
    const ids = documentIds.map((id) => Number(id)).filter((id) => Number.isFinite(id) && id > 0).join(",");
    if (!ids) {
      continue;
    }
    execute(`
UPDATE rag_documents
SET source_org = '${q(sourceOrg)}',
    updated_at = NOW()
WHERE document_id IN (${ids})
  AND use_yn = 'Y'
  AND document_type = 'official_doc'
  AND (source_org IS NULL OR TRIM(source_org) = '');
`);
    updatedDocuments += documentIds.length;
  }
  return updatedDocuments;
}

function mdTable(rows, columns) {
  if (!rows.length) return "_none_";
  const header = `| ${columns.map((column) => column.label).join(" | ")} |`;
  const sep = `| ${columns.map((column) => column.align === "right" ? "---:" : "---").join(" | ")} |`;
  const body = rows.map((row) => `| ${columns.map((column) => {
    const value = String(row[column.key] ?? "");
    return value.replace(/\r?\n/g, " ").replace(/\|/g, "\\|");
  }).join(" | ")} |`);
  return [header, sep, ...body].join("\n");
}

async function main() {
  const documents = loadDocuments();
  const proposals = documents.map((document) => ({
    ...document,
    ...inferSourceOrg(document),
  }));
  const safePlan = proposals.filter((item) => item.confidence === "high" && item.sourceOrg);
  let updatedDocuments = 0;
  let updatedQdrantPoints = 0;
  if (apply) {
    updatedDocuments = applyDatabase(safePlan);
    if (updateQdrant) {
      updatedQdrantPoints = await updateQdrantPayload(safePlan);
    }
  }

  const summaryMap = new Map();
  for (const proposal of proposals) {
    const key = `${proposal.confidence}\t${proposal.sourceOrg || "(unresolved)"}`;
    const row = summaryMap.get(key) ?? {
      confidence: proposal.confidence,
      sourceOrg: proposal.sourceOrg || "(unresolved)",
      documents: 0,
      chunks: 0,
      indexedChunks: 0,
      shortChunks: 0,
    };
    row.documents += 1;
    row.chunks += proposal.chunks;
    row.indexedChunks += proposal.indexedChunks;
    row.shortChunks += proposal.shortChunks;
    summaryMap.set(key, row);
  }
  const summary = Array.from(summaryMap.values())
    .sort((a, b) => confidenceOrder(a.confidence) - confidenceOrder(b.confidence)
      || b.chunks - a.chunks
      || a.sourceOrg.localeCompare(b.sourceOrg));

  const output = {
    generatedAt: new Date().toISOString(),
    mode: apply ? "apply" : "dry-run",
    qdrantPayloadUpdated: updateQdrant,
    vectorStore,
    collection,
    totalDocuments: documents.length,
    safePlanDocuments: safePlan.length,
    safePlanChunks: safePlan.reduce((sum, item) => sum + item.chunks, 0),
    safePlanIndexedChunks: safePlan.reduce((sum, item) => sum + item.indexedChunks, 0),
    updatedDocuments,
    updatedQdrantPoints,
    summary,
    proposals,
  };
  fs.mkdirSync(path.dirname(outMd), { recursive: true });
  fs.writeFileSync(outJson, JSON.stringify(output, null, 2), "utf8");

  const lines = [];
  lines.push("# RAG Source Org Repair Plan");
  lines.push("");
  lines.push(`- Generated at: ${output.generatedAt}`);
  lines.push(`- Mode: ${output.mode}`);
  lines.push(`- Qdrant payload updated: ${output.qdrantPayloadUpdated}`);
  lines.push(`- Missing source_org documents: ${output.totalDocuments.toLocaleString("ko-KR")}`);
  lines.push(`- High-confidence repair documents: ${output.safePlanDocuments.toLocaleString("ko-KR")}`);
  lines.push(`- High-confidence repair chunks: ${output.safePlanChunks.toLocaleString("ko-KR")}`);
  lines.push(`- Updated documents: ${output.updatedDocuments.toLocaleString("ko-KR")}`);
  lines.push(`- Updated Qdrant points: ${output.updatedQdrantPoints.toLocaleString("ko-KR")}`);
  lines.push("");
  lines.push("## Summary");
  lines.push("");
  lines.push(mdTable(summary, [
    { key: "confidence", label: "Confidence" },
    { key: "sourceOrg", label: "Source org" },
    { key: "documents", label: "Documents", align: "right" },
    { key: "chunks", label: "Chunks", align: "right" },
    { key: "indexedChunks", label: "Indexed chunks", align: "right" },
    { key: "shortChunks", label: "Short chunks", align: "right" },
  ]));
  lines.push("");
  lines.push("## High-confidence Plan");
  lines.push("");
  lines.push(mdTable(safePlan.map(toReportRow), [
    { key: "documentId", label: "Doc", align: "right" },
    { key: "sourceOrg", label: "Source org" },
    { key: "title", label: "Title" },
    { key: "fileName", label: "File" },
    { key: "chunks", label: "Chunks", align: "right" },
    { key: "shortChunks", label: "Short", align: "right" },
    { key: "reason", label: "Reason" },
    { key: "hitTerms", label: "Terms" },
  ]));
  const review = proposals.filter((item) => item.confidence !== "high");
  lines.push("");
  lines.push("## Review / Unresolved");
  lines.push("");
  lines.push(mdTable(review.map(toReportRow), [
    { key: "documentId", label: "Doc", align: "right" },
    { key: "confidence", label: "Confidence" },
    { key: "title", label: "Title" },
    { key: "fileName", label: "File" },
    { key: "chunks", label: "Chunks", align: "right" },
    { key: "reason", label: "Reason" },
    { key: "hitTerms", label: "Terms" },
  ]));
  lines.push("");
  fs.writeFileSync(outMd, lines.join("\n"), "utf8");
  console.log(outMd);
  console.log(outJson);
  console.log(JSON.stringify({
    mode: output.mode,
    totalDocuments: output.totalDocuments,
    safePlanDocuments: output.safePlanDocuments,
    updatedDocuments,
    updatedQdrantPoints,
  }));
}

function confidenceOrder(confidence) {
  return { high: 0, review: 1, none: 2 }[confidence] ?? 9;
}

function toReportRow(item) {
  return {
    ...item,
    title: item.title.slice(0, 120),
    fileName: item.fileName.slice(0, 80),
    hitTerms: (item.hitTerms || []).join(", "),
  };
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
