const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const workspace = path.resolve(__dirname, "..");
const mysql = process.env.MARIADB_EXE || "C:\\Program Files\\MariaDB 12.2\\bin\\mariadb.exe";
const model = process.env.PANDORA_EMBEDDING_MODEL || "text-embedding-3-small";
const lawVectorStore = process.env.PANDORA_LAW_VECTOR_STORE || "law_chunks";
const ragVectorStore = process.env.PANDORA_RAG_VECTOR_STORE || "rag_chunks_v4";
const qdrantUrl = process.env.QDRANT_URL || "http://127.0.0.1:6333";
const batchRunnerUrl = process.env.PANDORA_BATCH_RUNNER_URL || "http://127.0.0.1:18080";
const outPath = path.resolve(workspace, "logs", "search-quality-diagnostics-latest.md");
const jsonPath = path.resolve(workspace, "logs", "search-quality-diagnostics-latest.json");
const evalGatePath = process.env.RAG_EVAL_GATE_JSON
  ? path.resolve(workspace, process.env.RAG_EVAL_GATE_JSON)
  : resolveLatestEvalGatePath(path.resolve(workspace, "logs", "rag-eval-gate-latest.json"));

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

function one(sql, fallback = "0") {
  const rows = db(sql);
  return rows[0]?.[0] ?? fallback;
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
  if (value > 0 && value < 0.01) {
    return "<0.01%";
  }
  return `${value.toFixed(2)}%`;
}

function ratio(part, total) {
  const denominator = number(total);
  if (!denominator) return 0;
  return number(part) / denominator;
}

function mdTable(rows, columns) {
  if (!rows.length) {
    return "_none_";
  }
  const header = `| ${columns.map((column) => column.label).join(" | ")} |`;
  const sep = `| ${columns.map((column) => column.align === "right" ? "---:" : "---").join(" | ")} |`;
  const body = rows.map((row) => `| ${columns.map((column) => {
    const raw = row[column.key] ?? "";
    const value = column.format === "number" ? fmt(raw) : String(raw);
    return value.replace(/\r?\n/g, " ").replace(/\|/g, "\\|");
  }).join(" | ")} |`);
  return [header, sep, ...body].join("\n");
}
function compactText(value, maxLength = 180) {
  const text = String(value ?? "").replace(/\s+/g, " ").trim();
  return text.length > maxLength ? `${text.slice(0, maxLength - 3)}...` : text;
}
function qdrantOptimizerSummary(value) {
  if (value == null || value === "") {
    return "";
  }
  if (typeof value === "string") {
    return compactText(value);
  }
  if (typeof value === "object" && value.error) {
    return compactText(value.error);
  }
  return compactText(JSON.stringify(value));
}

async function qdrantCollection(name) {
  try {
    const response = await fetch(`${qdrantUrl}/collections/${encodeURIComponent(name)}`, { signal: AbortSignal.timeout(5000) });
    if (!response.ok) {
      return { name, ok: false, error: `HTTP ${response.status}` };
    }
    const json = await response.json();
    return {
      name,
      ok: true,
      status: json.result?.status ?? "",
      optimizer: qdrantOptimizerSummary(json.result?.optimizer_status),
      points: json.result?.points_count ?? 0,
      vectors: json.result?.vectors_count ?? "",
    };
  } catch (error) {
    return { name, ok: false, error: error.message };
  }
}

async function qdrantCountByTarget(name, target) {
  try {
    const response = await fetch(`${qdrantUrl}/collections/${encodeURIComponent(name)}/points/count`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      signal: AbortSignal.timeout(30000),
      body: JSON.stringify({
        exact: true,
        filter: {
          must: [
            { key: "target", match: { value: target } },
          ],
        },
      }),
    });
    if (!response.ok) {
      return { collection: name, target, ok: false, count: "", error: `HTTP ${response.status}` };
    }
    const json = await response.json();
    return { collection: name, target, ok: true, count: json.result?.count ?? 0, error: "" };
  } catch (error) {
    return { collection: name, target, ok: false, count: "", error: error.message };
  }
}

function fileInfo(filePath) {
  try {
    const stat = fs.statSync(filePath);
    return {
      path: filePath,
      exists: true,
      size: stat.size,
      mtime: stat.mtime.toISOString(),
    };
  } catch (error) {
    return { path: filePath, exists: false, size: "", mtime: "", error: error.message };
  }
}

function readJsonIfExists(filePath) {
  try {
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
  } catch {
    return null;
  }
}

function classifiedRagShortSamples(audit, maxPerCategory = 6) {
  if (!audit || !audit.samples || typeof audit.samples !== "object") {
    return [];
  }
  const categoryOrder = [
    "decorative_footer_or_title",
    "image_or_empty",
    "navigation_or_attachment_notice",
    "field_label_fragment",
    "toc_or_heading_fragment",
    "ambiguous_short",
    "meaningful_short_evidence",
  ];
  const categories = [
    ...categoryOrder,
    ...Object.keys(audit.samples).filter((category) => !categoryOrder.includes(category)).sort(),
  ];
  const rows = [];
  for (const category of categories) {
    const samples = Array.isArray(audit.samples[category]) ? audit.samples[category] : [];
    for (const sample of samples.slice(0, maxPerCategory)) {
      rows.push({
        category,
        action: sample.action || "",
        sourceOrg: sample.sourceOrg || "",
        documentId: sample.documentId ?? "",
        chunkId: sample.chunkId ?? "",
        sectionType: sample.sectionType || "",
        length: sample.length ?? "",
        sample: compactText(sample.sample, 160),
      });
    }
  }
  return rows;
}

function resolveLatestEvalGatePath(fallbackPath) {
  const logDir = path.resolve(workspace, "logs");
  let best = null;
  try {
    for (const fileName of fs.readdirSync(logDir)) {
      if (!/^rag-eval.*\.json$/i.test(fileName)) {
        continue;
      }
      const filePath = path.join(logDir, fileName);
      const gate = readJsonIfExists(filePath);
      if (!gate || !Array.isArray(gate.results) || !Number.isFinite(Number(gate.total)) || typeof gate.gatePassed !== "boolean") {
        continue;
      }
      const stat = fs.statSync(filePath);
      const candidate = {
        filePath,
        total: Number(gate.total),
        mtimeMs: stat.mtimeMs,
      };
      if (!best || candidate.total > best.total || (candidate.total === best.total && candidate.mtimeMs > best.mtimeMs)) {
        best = candidate;
      }
    }
  } catch {
    return fallbackPath;
  }
  return best?.filePath || fallbackPath;
}

async function batchRunnerStatus() {
  try {
    const response = await fetch(`${batchRunnerUrl}/api/law-data/semantic/batches/scheduler-status`, {
      signal: AbortSignal.timeout(8000),
    });
    if (!response.ok) {
      return { ok: false, status: `HTTP ${response.status}`, running: "", lastStatus: "", lastErrorMessage: "" };
    }
    const json = await response.json();
    return {
      ok: true,
      status: "UP",
      running: json.running ?? "",
      lastStatus: json.lastStatus ?? "",
      lastErrorMessage: json.lastErrorMessage ?? "",
      lastStartedAt: json.lastStartedAt ?? "",
      lastFinishedAt: json.lastFinishedAt ?? "",
    };
  } catch (error) {
    return { ok: false, status: "DOWN", running: "", lastStatus: "", lastErrorMessage: error.message };
  }
}

function riskRows(metrics) {
  const rows = [];
  const lawTotal = number(metrics.lawLength?.[0]?.chunks);
  const lawOver4000 = number(metrics.lawLength?.[0]?.over_4000);
  const lawOver6000 = number(metrics.lawLength?.[0]?.over_6000);
  const lawMissingTitle = number(metrics.lawMetadata?.[0]?.missing_title);
  const lawMissingChunkNo = number(metrics.lawMetadata?.[0]?.missing_chunk_no);
  const lawMissingSourceDate = number(metrics.lawMetadata?.[0]?.missing_source_date);
  const lawTinyUnder80 = number(metrics.lawLength?.[0]?.under_80);
  const residualTinyAudit = metrics.residualTinyAudit;
  const residualTinyTotal = number(residualTinyAudit?.total ?? lawTinyUnder80);
  const lowSignalTiny = (residualTinyAudit?.summary ?? [])
    .filter((row) => ["image_only", "navigation_notice", "layout_number", "revision_marker"].includes(row.category))
    .reduce((sum, row) => sum + number(row.chunks), 0);
  const ragTotal = (metrics.ragLength ?? []).reduce((sum, row) => sum + number(row.chunks), 0);
  const ragOver2500 = metrics.ragLength.reduce((sum, row) => sum + number(row.over_2500), 0);
  const ragUnder120 = metrics.ragLength.reduce((sum, row) => sum + number(row.under_120), 0);
  const ragMissingParent = metrics.ragMetadata.reduce((sum, row) => sum + number(row.missing_parent), 0);
  const lawBacklog = number(metrics.backlog.find((row) => row.source === "law_api")?.remaining_candidates);
  const ragBacklog = number(metrics.backlog.find((row) => row.source === "rag")?.remaining_candidates);
  const staleRag = number(metrics.staleRagMissing?.[0]?.stale_missing);
  const staleEmbeddings = number(metrics.staleEmbeddings?.[0]?.stale_embeddings);
  const qdrantStatusIssues = (metrics.qdrant || [])
    .filter((row) => !row.ok || String(row.status || "").toLowerCase() !== "green");
  const qdrantDeltaTotal = (metrics.qdrantTargetRows || [])
    .reduce((sum, row) => sum + Math.abs(number(row.delta)), 0);
  const lawQdrantAdmrulDelta = metrics.qdrantTargetRows
    ?.filter((row) => row.collection === "law_chunks" && row.target === "admrul")
    .reduce((sum, row) => sum + number(row.delta), 0) ?? 0;
  const officialChunksPoints = metrics.qdrantTargetRows
    ?.filter((row) => row.collection === "official_chunks" && row.target === "official_doc")
    .reduce((sum, row) => sum + number(row.qdrant_count), 0) ?? 0;

  if (lawOver4000 > 0) {
    rows.push({
      priority: "P1",
      area: "법령/행정규칙 청크",
      finding: `4,000자 초과 청크 ${fmt(lawOver4000)}개 (${pct(lawOver4000, lawTotal)})`,
      action: "신규 sync는 parent-child planner 적용 완료, 기존 데이터 controlled rechunk/reindex 후보",
    });
  }
  if (residualTinyTotal > 0) {
    const tinyRatio = ratio(residualTinyTotal, lawTotal);
    const priority = tinyRatio > 0.005 ? "P1" : "P2";
    rows.push({
      priority,
      area: "법령/행정규칙 청크",
      finding: `80자 미만 잔여 tiny 청크 ${fmt(lawTinyUnder80)}개 (${pct(lawTinyUnder80, lawTotal)})`,
      action: priority === "P1"
        ? "safe wave dry-run으로 projected tiny 0 후보만 계속 전환"
        : "잔여율이 낮으므로 강제 삭제/병합하지 말고 residual audit로 의미 있는 단문과 저신호 조각을 분리",
    });
  }
  if (lowSignalTiny > 0) {
    rows.push({
      priority: "P2",
      area: "low-signal tiny evidence",
      finding: `image/menu/attachment/revision residual tiny ${fmt(lowSignalTiny)} chunks`,
      action: "Keep EvidenceNoiseClassifier suppression and review residual-tiny-audit samples before using tiny chunks as answer grounds",
    });
  }
  if (false && lawTinyUnder80 > 0) {
    rows.push({
      priority: "P1",
      area: "법령/행정규칙 청크",
      finding: `80자 미만 legacy tiny 청크 ${fmt(lawTinyUnder80)}개 (${pct(lawTinyUnder80, lawTotal)})`,
      action: "parent-child rechunk wave를 계속 돌리되 projected tiny는 차단",
    });
  }
  if (lawOver6000 > 0) {
    rows.push({
      priority: "P1",
      area: "법령/행정규칙 청크",
      finding: `6,000자 초과 청크 ${fmt(lawOver6000)}개`,
      action: "분할 기준 또는 기존 생성 데이터 이상 여부 샘플 검수",
    });
  }
  if (lawMissingTitle > 0) {
    rows.push({
      priority: "P2",
      area: "법령 메타데이터",
      finding: `chunk_title 누락 ${fmt(lawMissingTitle)}개 (${pct(lawMissingTitle, lawTotal)})`,
      action: "조문명/별표/부칙 title 보강 가능성 확인",
    });
  }
  if (lawMissingChunkNo > 0) {
    rows.push({
      priority: "P2",
      area: "법령 메타데이터",
      finding: `chunk_no 누락 ${fmt(lawMissingChunkNo)}개 (${pct(lawMissingChunkNo, lawTotal)})`,
      action: "대부분 legacy 잔재. safe wave로 줄이고 projected tiny 후보는 별도 planner 개선",
    });
  }
  if (lawMissingSourceDate > 0) {
    rows.push({
      priority: "P2",
      area: "법령 최신성",
      finding: `source_date 누락 문서 ${fmt(lawMissingSourceDate)}건`,
      action: "현행/개정/시행일 필터 신뢰도 확인",
    });
  }
  if (ragOver2500 > 0) {
    rows.push({
      priority: "P2",
      area: "공식문서 RAG 청크",
      finding: `2,500자 초과 최신 청크 ${fmt(ragOver2500)}개 (${pct(ragOver2500, ragTotal)})`,
      action: "긴 문서 상위 20개 샘플 검수",
    });
  }
  if (ragUnder120 > 0) {
    rows.push({
      priority: "P2",
      area: "RAG short chunks",
      finding: `Latest RAG chunks shorter than 120 chars: ${fmt(ragUnder120)} (${pct(ragUnder120, ragTotal)})`,
      action: "Sample short chunks by source_org and downrank/rechunk low-signal notices if they appear in evidence",
    });
  }
  const ragShortAuditSuppress = (metrics.ragShortAudit?.summary ?? [])
    .filter((row) => ["suppress_or_downrank", "suppress_or_rechunk", "merge_or_downrank"].includes(row.action))
    .reduce((sum, row) => sum + number(row.chunks), 0);
  if (ragShortAuditSuppress > 0) {
    rows.push({
      priority: "P2",
      area: "RAG short audit",
      finding: `Short chunk audit found ${fmt(ragShortAuditSuppress)} suppress/downrank/merge candidates`,
      action: "Keep evidence suppression active; review logs/rag-short-chunk-audit-latest.md before using these chunks as final grounds",
    });
  }
  if (ragMissingParent > 0) {
    rows.push({
      priority: "P2",
      area: "공식문서 RAG 메타데이터",
      finding: `parent_section_title 누락 ${fmt(ragMissingParent)}개`,
      action: "문서 유형별 제목 추론 실패 케이스 확인",
    });
  }
  if (lawBacklog || ragBacklog) {
    rows.push({
      priority: "P1",
      area: "임베딩 대기",
      finding: `law ${fmt(lawBacklog)} / rag ${fmt(ragBacklog)} 후보`,
      action: "18080 batch-runner로 색인 필요",
    });
  }
  if ((metrics.evalGate?.total ?? 0) < 300) {
    rows.push({
      priority: "P1",
      area: "RAG eval coverage",
      finding: `Eval cases ${fmt(metrics.evalGate?.total ?? 0)} / target 300`,
      action: "Expand privacy/public-data/law/admrul/official-doc/NO_GROUNDS regression cases",
    });
  }
  if (metrics.evalGate && metrics.evalGate.gatePassed === false) {
    rows.push({
      priority: "P1",
      area: "검색 회귀 평가",
      finding: `평가셋 ${fmt(metrics.evalGate.passed)}/${fmt(metrics.evalGate.total)} PASS`,
      action: "실패 원인 리포트의 Likely Cause 기준으로 검색/근거 판정 보정",
    });
  }
  if (qdrantStatusIssues.length > 0) {
    rows.push({
      priority: "P1",
      area: "Qdrant 상태",
      finding: qdrantStatusIssues
        .map((row) => `${row.name}:${row.ok ? row.status : row.error}${row.optimizer ? ` (${row.optimizer})` : ""}`)
        .join(", "),
      action: "collection status가 green이 될 때까지 Qdrant 재기동 또는 optimizer 오류 원인 확인",
    });
  }
  if (qdrantDeltaTotal > 0) {
    rows.push({
      priority: "P1",
      area: "Qdrant 정합성",
      finding: `DB INDEXED와 Qdrant point delta 합계 ${fmt(qdrantDeltaTotal)}개`,
      action: "target별 stale audit/delete 또는 미색인 복구 후 search-quality-diagnostics 재실행",
    });
  }
  if (staleEmbeddings > 0) {
    rows.push({
      priority: "P2",
      area: "stale embedding",
      finding: `content_hash 불일치 또는 비최신 embedding ${fmt(staleEmbeddings)}개`,
      action: "stale cleanup 정책 검수",
    });
  }
  if (lawQdrantAdmrulDelta > 0) {
    rows.push({
      priority: "P1",
      area: "Qdrant 정합성",
      finding: `law_chunks/admrul Qdrant 초과 ${fmt(lawQdrantAdmrulDelta)} points`,
      action: "DB에 없는 행정규칙 point id 샘플링 후 stale cleanup",
    });
  }
  if (officialChunksPoints > 0) {
    rows.push({
      priority: "P2",
      area: "Qdrant 컬렉션 정리",
      finding: `official_chunks에 과거 official_doc ${fmt(officialChunksPoints)} points 잔존`,
      action: "검색 경로 미사용 확인 후 collection drop 또는 보관 정책 결정",
    });
  }
  if (staleRag > 0) {
    rows.push({
      priority: "P3",
      area: "구버전 RAG 청크",
      finding: `비최신 chunk_version missing row ${fmt(staleRag)}개`,
      action: "검색 대상은 아니므로 삭제보다 보존/정리 정책 결정",
    });
  }
  return rows;
}

async function main() {
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  const generatedAt = new Date().toISOString();
  const dbNow = one("SELECT NOW()");

  const metrics = {};
  metrics.backlog = table(`
SELECT 'law_api' AS source, COUNT(*) AS remaining_candidates
FROM law_api_document_chunks c
JOIN law_api_documents doc ON doc.document_id=c.document_id
LEFT JOIN law_api_chunk_embeddings e
  ON e.chunk_id=c.chunk_id
 AND e.embedding_model='${q(model)}'
 AND e.vector_store='${q(lawVectorStore)}'
WHERE c.use_yn='Y'
  AND doc.use_yn='Y'
  AND (
    e.chunk_id IS NULL
    OR e.status IN ('FAILED','ERROR')
    OR COALESCE(e.content_hash, '') != COALESCE(c.content_hash, '')
  )
UNION ALL
SELECT 'rag' AS source, COUNT(*) AS remaining_candidates
FROM rag_document_chunks c
JOIN rag_documents d ON d.document_id=c.document_id
LEFT JOIN rag_chunk_embeddings e
  ON e.chunk_id=c.chunk_id
 AND e.embedding_model='${q(model)}'
 AND e.vector_store='${q(ragVectorStore)}'
WHERE c.use_yn='Y'
  AND d.use_yn='Y'
  AND c.chunk_version=(
    SELECT MAX(c2.chunk_version)
    FROM rag_document_chunks c2
    WHERE c2.document_id=c.document_id
      AND c2.use_yn='Y'
  )
  AND (
    e.chunk_id IS NULL
    OR e.status IN ('FAILED','ERROR')
    OR COALESCE(e.content_hash, '') != COALESCE(c.content_hash, '')
  );
`, ["source", "remaining_candidates"]);

  metrics.lawStatus = table(`
SELECT doc.target, COALESCE(e.status,'MISSING_ROW') AS status, COUNT(*) AS chunks
FROM law_api_document_chunks c
JOIN law_api_documents doc ON doc.document_id=c.document_id
LEFT JOIN law_api_chunk_embeddings e
  ON e.chunk_id=c.chunk_id
 AND e.embedding_model='${q(model)}'
 AND e.vector_store='${q(lawVectorStore)}'
WHERE c.use_yn='Y'
  AND doc.use_yn='Y'
GROUP BY doc.target, COALESCE(e.status,'MISSING_ROW')
ORDER BY doc.target, status;
`, ["target", "status", "chunks"]);

  metrics.ragStatus = table(`
SELECT d.document_type, COALESCE(d.source_org,'(none)') AS source_org, COALESCE(e.status,'MISSING_ROW') AS status, COUNT(*) AS chunks
FROM rag_document_chunks c
JOIN rag_documents d ON d.document_id=c.document_id
LEFT JOIN rag_chunk_embeddings e
  ON e.chunk_id=c.chunk_id
 AND e.embedding_model='${q(model)}'
 AND e.vector_store='${q(ragVectorStore)}'
WHERE c.use_yn='Y'
  AND d.use_yn='Y'
  AND c.chunk_version=(
    SELECT MAX(c2.chunk_version)
    FROM rag_document_chunks c2
    WHERE c2.document_id=c.document_id
      AND c2.use_yn='Y'
  )
GROUP BY d.document_type, COALESCE(d.source_org,'(none)'), COALESCE(e.status,'MISSING_ROW')
ORDER BY d.document_type, source_org, status;
`, ["document_type", "source_org", "status", "chunks"]);

  metrics.lawLength = table(`
SELECT
  COUNT(*) AS chunks,
  ROUND(AVG(CHAR_LENGTH(c.chunk_text))) AS avg_len,
  MIN(CHAR_LENGTH(c.chunk_text)) AS min_len,
  MAX(CHAR_LENGTH(c.chunk_text)) AS max_len,
  SUM(CHAR_LENGTH(c.chunk_text) > 1200) AS over_1200,
  SUM(CHAR_LENGTH(c.chunk_text) > 2500) AS over_2500,
  SUM(CHAR_LENGTH(c.chunk_text) > 4000) AS over_4000,
  SUM(CHAR_LENGTH(c.chunk_text) > 6000) AS over_6000,
  SUM(CHAR_LENGTH(c.chunk_text) < 80) AS under_80,
  SUM(CHAR_LENGTH(c.chunk_text) < 200) AS under_200
FROM law_api_document_chunks c
JOIN law_api_documents d ON d.document_id=c.document_id
WHERE c.use_yn='Y'
  AND d.use_yn='Y';
`, ["chunks", "avg_len", "min_len", "max_len", "over_1200", "over_2500", "over_4000", "over_6000", "under_80", "under_200"]);

  metrics.lawTargetLength = table(`
SELECT
  d.target,
  COUNT(*) AS chunks,
  ROUND(AVG(CHAR_LENGTH(c.chunk_text))) AS avg_len,
  MAX(CHAR_LENGTH(c.chunk_text)) AS max_len,
  SUM(CHAR_LENGTH(c.chunk_text) > 2500) AS over_2500,
  SUM(CHAR_LENGTH(c.chunk_text) > 4000) AS over_4000,
  SUM(CHAR_LENGTH(c.chunk_text) > 6000) AS over_6000,
  SUM(CHAR_LENGTH(c.chunk_text) < 80) AS under_80,
  SUM(CHAR_LENGTH(c.chunk_text) < 200) AS under_200
FROM law_api_document_chunks c
JOIN law_api_documents d ON d.document_id=c.document_id
WHERE c.use_yn='Y'
  AND d.use_yn='Y'
GROUP BY d.target
ORDER BY d.target;
`, ["target", "chunks", "avg_len", "max_len", "over_2500", "over_4000", "over_6000", "under_80", "under_200"]);

  metrics.lawMetadata = table(`
SELECT
  COUNT(*) AS chunks,
  SUM(c.chunk_title IS NULL OR TRIM(c.chunk_title)='') AS missing_title,
  SUM(c.chunk_no IS NULL OR TRIM(c.chunk_no)='') AS missing_chunk_no,
  SUM(c.source_url IS NULL OR TRIM(c.source_url)='') AS missing_source_url,
  SUM(d.source_date IS NULL OR TRIM(d.source_date)='') AS missing_source_date,
  SUM(d.effective_date IS NULL OR TRIM(d.effective_date)='') AS missing_effective_date,
  SUM(d.effective_status='UNKNOWN') AS unknown_effective_status
FROM law_api_document_chunks c
JOIN law_api_documents d ON d.document_id=c.document_id
WHERE c.use_yn='Y'
  AND d.use_yn='Y';
`, ["chunks", "missing_title", "missing_chunk_no", "missing_source_url", "missing_source_date", "missing_effective_date", "unknown_effective_status"]);

  metrics.ragLength = table(`
SELECT
  d.document_type,
  COALESCE(d.source_org,'(none)') AS source_org,
  COUNT(*) AS chunks,
  ROUND(AVG(CHAR_LENGTH(c.chunk_text))) AS avg_len,
  MIN(CHAR_LENGTH(c.chunk_text)) AS min_len,
  MAX(CHAR_LENGTH(c.chunk_text)) AS max_len,
  SUM(CHAR_LENGTH(c.chunk_text) > 1700) AS over_1700,
  SUM(CHAR_LENGTH(c.chunk_text) > 2500) AS over_2500,
  SUM(CHAR_LENGTH(c.chunk_text) < 120) AS under_120
FROM rag_document_chunks c
JOIN rag_documents d ON d.document_id=c.document_id
WHERE d.use_yn='Y'
  AND c.use_yn='Y'
  AND c.chunk_version=(
    SELECT MAX(c2.chunk_version)
    FROM rag_document_chunks c2
    WHERE c2.document_id=c.document_id
      AND c2.use_yn='Y'
  )
GROUP BY d.document_type, COALESCE(d.source_org,'(none)')
ORDER BY chunks DESC;
`, ["document_type", "source_org", "chunks", "avg_len", "min_len", "max_len", "over_1700", "over_2500", "under_120"]);

  metrics.ragShortSamples = table(`
SELECT
  d.document_type,
  COALESCE(d.source_org,'(none)') AS source_org,
  d.title,
  c.chunk_id,
  LEFT(COALESCE(c.parent_section_title,''), 80) AS parent,
  LEFT(COALESCE(c.chunk_title,''), 80) AS chunk_title,
  c.section_type,
  CHAR_LENGTH(c.chunk_text) AS len,
  LEFT(REPLACE(REPLACE(REPLACE(c.chunk_text, CHAR(9), ' '), CHAR(10), ' '), CHAR(13), ' '), 180) AS sample
FROM rag_document_chunks c
JOIN rag_documents d ON d.document_id=c.document_id
WHERE d.use_yn='Y'
  AND c.use_yn='Y'
  AND c.chunk_version=(
    SELECT MAX(c2.chunk_version)
    FROM rag_document_chunks c2
    WHERE c2.document_id=c.document_id
      AND c2.use_yn='Y'
  )
  AND CHAR_LENGTH(c.chunk_text) < 120
ORDER BY d.document_type, COALESCE(d.source_org,'(none)'), c.chunk_id
LIMIT 40;
`, ["document_type", "source_org", "title", "chunk_id", "parent", "chunk_title", "section_type", "len", "sample"]);

  metrics.ragMetadata = table(`
SELECT
  d.document_type,
  COALESCE(d.source_org,'(none)') AS source_org,
  COUNT(*) AS chunks,
  SUM(c.parent_section_title IS NULL OR TRIM(c.parent_section_title)='') AS missing_parent,
  SUM(c.chunk_title IS NULL OR TRIM(c.chunk_title)='') AS missing_title,
  SUM(c.embedding_text IS NULL OR TRIM(c.embedding_text)='') AS missing_embedding_text,
  SUM(c.page_no IS NULL) AS missing_page_no
FROM rag_document_chunks c
JOIN rag_documents d ON d.document_id=c.document_id
WHERE d.use_yn='Y'
  AND c.use_yn='Y'
  AND c.chunk_version=(
    SELECT MAX(c2.chunk_version)
    FROM rag_document_chunks c2
    WHERE c2.document_id=c.document_id
      AND c2.use_yn='Y'
  )
GROUP BY d.document_type, COALESCE(d.source_org,'(none)')
ORDER BY chunks DESC;
`, ["document_type", "source_org", "chunks", "missing_parent", "missing_title", "missing_embedding_text", "missing_page_no"]);

  metrics.ragSectionTypes = table(`
SELECT c.section_type, COUNT(*) AS chunks
FROM rag_document_chunks c
JOIN rag_documents d ON d.document_id=c.document_id
WHERE d.use_yn='Y'
  AND c.use_yn='Y'
  AND c.chunk_version=(
    SELECT MAX(c2.chunk_version)
    FROM rag_document_chunks c2
    WHERE c2.document_id=c.document_id
      AND c2.use_yn='Y'
  )
GROUP BY c.section_type
ORDER BY chunks DESC;
`, ["section_type", "chunks"]);

  metrics.duplicateLawHashes = table(`
SELECT COUNT(*) AS duplicate_hash_groups, COALESCE(SUM(group_count),0) AS duplicate_chunks
FROM (
  SELECT c.content_hash, COUNT(*) AS group_count
  FROM law_api_document_chunks c
  JOIN law_api_documents d ON d.document_id=c.document_id
  WHERE c.use_yn='Y'
    AND d.use_yn='Y'
    AND c.content_hash IS NOT NULL
  GROUP BY c.content_hash
  HAVING COUNT(*) > 1
) x;
`, ["duplicate_hash_groups", "duplicate_chunks"]);

  metrics.duplicateRagHashes = table(`
SELECT COUNT(*) AS duplicate_hash_groups, COALESCE(SUM(group_count),0) AS duplicate_chunks
FROM (
  SELECT c.content_hash, COUNT(*) AS group_count
  FROM rag_document_chunks c
  JOIN rag_documents d ON d.document_id=c.document_id
  WHERE c.use_yn='Y'
    AND d.use_yn='Y'
    AND c.chunk_version=(
      SELECT MAX(c2.chunk_version)
      FROM rag_document_chunks c2
      WHERE c2.document_id=c.document_id
        AND c2.use_yn='Y'
    )
    AND c.content_hash IS NOT NULL
  GROUP BY c.content_hash
  HAVING COUNT(*) > 1
) x;
`, ["duplicate_hash_groups", "duplicate_chunks"]);

  metrics.staleEmbeddings = table(`
SELECT COUNT(*) AS stale_embeddings
FROM (
  SELECT e.chunk_id
  FROM law_api_chunk_embeddings e
  JOIN law_api_document_chunks c ON c.chunk_id=e.chunk_id
  JOIN law_api_documents d ON d.document_id=c.document_id
  WHERE e.embedding_model='${q(model)}'
    AND e.vector_store='${q(lawVectorStore)}'
    AND e.status='INDEXED'
    AND (c.use_yn <> 'Y' OR d.use_yn <> 'Y' OR COALESCE(e.content_hash,'') <> COALESCE(c.content_hash,''))
  UNION ALL
  SELECT e.chunk_id
  FROM rag_chunk_embeddings e
  JOIN rag_document_chunks c ON c.chunk_id=e.chunk_id
  JOIN rag_documents d ON d.document_id=c.document_id
  WHERE e.embedding_model='${q(model)}'
    AND e.vector_store='${q(ragVectorStore)}'
    AND e.status='INDEXED'
    AND (
      c.use_yn <> 'Y'
      OR d.use_yn <> 'Y'
      OR COALESCE(e.content_hash,'') <> COALESCE(c.content_hash,'')
      OR c.chunk_version <> (
        SELECT MAX(c2.chunk_version)
        FROM rag_document_chunks c2
        WHERE c2.document_id=c.document_id
          AND c2.use_yn='Y'
      )
    )
) stale;
`, ["stale_embeddings"]);

  metrics.staleRagMissing = table(`
SELECT COUNT(*) AS stale_missing
FROM rag_document_chunks c
JOIN rag_documents d ON d.document_id=c.document_id
LEFT JOIN rag_chunk_embeddings e
  ON e.chunk_id=c.chunk_id
 AND e.embedding_model='${q(model)}'
 AND e.vector_store='${q(ragVectorStore)}'
WHERE d.document_type='official_doc'
  AND d.use_yn='Y'
  AND c.use_yn='Y'
  AND c.chunk_version < (
    SELECT MAX(c2.chunk_version)
    FROM rag_document_chunks c2
    WHERE c2.document_id=c.document_id
      AND c2.use_yn='Y'
  )
  AND e.chunk_id IS NULL;
`, ["stale_missing"]);

  metrics.longLawSamples = table(`
SELECT d.target, d.title, c.chunk_id, c.chunk_type, c.chunk_no, LEFT(COALESCE(c.chunk_title,''), 120) AS chunk_title, CHAR_LENGTH(c.chunk_text) AS len
FROM law_api_document_chunks c
JOIN law_api_documents d ON d.document_id=c.document_id
WHERE c.use_yn='Y'
  AND d.use_yn='Y'
ORDER BY CHAR_LENGTH(c.chunk_text) DESC
LIMIT 20;
`, ["target", "title", "chunk_id", "chunk_type", "chunk_no", "chunk_title", "len"]);

  metrics.longRagSamples = table(`
SELECT d.document_type, COALESCE(d.source_org,'(none)') AS source_org, d.title, c.chunk_id, LEFT(COALESCE(c.parent_section_title,''), 80) AS parent, LEFT(COALESCE(c.chunk_title,''), 80) AS chunk_title, c.section_type, CHAR_LENGTH(c.chunk_text) AS len
FROM rag_document_chunks c
JOIN rag_documents d ON d.document_id=c.document_id
WHERE d.use_yn='Y'
  AND c.use_yn='Y'
  AND c.chunk_version=(
    SELECT MAX(c2.chunk_version)
    FROM rag_document_chunks c2
    WHERE c2.document_id=c.document_id
      AND c2.use_yn='Y'
  )
ORDER BY CHAR_LENGTH(c.chunk_text) DESC
LIMIT 20;
`, ["document_type", "source_org", "title", "chunk_id", "parent", "chunk_title", "section_type", "len"]);

  metrics.activeJobs = table(`
SELECT batch_job_id, status, target, query_text, vector_store, submitted_count, completed_count, failed_count, ingested_count,
       TIMESTAMPDIFF(MINUTE, created_at, NOW()) AS age_min
FROM semantic_batch_jobs
WHERE status IN ('validating','in_progress','finalizing','cancelling','completed')
ORDER BY batch_job_id DESC;
`, ["batch_job_id", "status", "target", "query_text", "vector_store", "submitted_count", "completed_count", "failed_count", "ingested_count", "age_min"]);

  metrics.recentBadJobs = table(`
SELECT batch_job_id, status, target, query_text, vector_store, submitted_count, completed_count, failed_count, ingested_count, updated_at
FROM semantic_batch_jobs
WHERE status IN ('ABANDONED','CANCELLED_LOCAL','cancelled','failed','expired')
ORDER BY batch_job_id DESC
LIMIT 5;
`, ["batch_job_id", "status", "target", "query_text", "vector_store", "submitted_count", "completed_count", "failed_count", "ingested_count", "updated_at"]);

  metrics.badJobSummary = table(`
SELECT status, target, COALESCE(query_text,'') AS query_text, vector_store,
       COUNT(*) AS jobs,
       COALESCE(SUM(submitted_count),0) AS submitted,
       COALESCE(SUM(completed_count),0) AS completed,
       COALESCE(SUM(ingested_count),0) AS ingested,
       MAX(updated_at) AS latest_updated
FROM semantic_batch_jobs
WHERE status IN ('ABANDONED','CANCELLED_LOCAL','cancelled','failed','expired')
GROUP BY status, target, COALESCE(query_text,''), vector_store
ORDER BY latest_updated DESC
LIMIT 12;
`, ["status", "target", "query_text", "vector_store", "jobs", "submitted", "completed", "ingested", "latest_updated"]);

  metrics.batchRunner = {
    endpoint: batchRunnerUrl,
    status: await batchRunnerStatus(),
    targetJar: fileInfo(path.resolve(workspace, "target", "pandora-0.0.1-SNAPSHOT.jar")),
    batchJar: fileInfo(path.resolve(workspace, "runtime", "batch", "pandora-batch-runner.jar")),
    metadata: readJsonIfExists(path.resolve(workspace, "runtime", "batch", "pandora-batch-runner.meta.json")),
  };
  metrics.evalGateFile = fileInfo(evalGatePath);
  metrics.evalGate = readJsonIfExists(evalGatePath);
  metrics.evalCoverage = readJsonIfExists(path.resolve(workspace, "logs", "rag-eval-coverage-latest.json"));
  metrics.residualTinyAuditFile = fileInfo(path.resolve(workspace, "logs", "residual-tiny-audit-latest.json"));
  metrics.residualTinyAudit = readJsonIfExists(path.resolve(workspace, "logs", "residual-tiny-audit-latest.json"));
  metrics.ragShortAuditFile = fileInfo(path.resolve(workspace, "logs", "rag-short-chunk-audit-latest.json"));
  metrics.ragShortAudit = readJsonIfExists(path.resolve(workspace, "logs", "rag-short-chunk-audit-latest.json"));
  metrics.ragShortClassifiedSamples = classifiedRagShortSamples(metrics.ragShortAudit);

  metrics.qdrant = await Promise.all([
    qdrantCollection(lawVectorStore),
    qdrantCollection(ragVectorStore),
    qdrantCollection("official_chunks"),
  ]);
  const qdrantTargets = ["law", "admrul", "official_doc", "internal_doc", "reference_doc"];
  const qdrantTargetCounts = [];
  for (const collection of [lawVectorStore, ragVectorStore, "official_chunks"]) {
    for (const target of qdrantTargets) {
      qdrantTargetCounts.push(await qdrantCountByTarget(collection, target));
    }
  }

  const lawIndexed = metrics.lawStatus.reduce((sum, row) => row.status === "INDEXED" ? sum + number(row.chunks) : sum, 0);
  const ragIndexed = metrics.ragStatus.reduce((sum, row) => row.status === "INDEXED" ? sum + number(row.chunks) : sum, 0);
  const dbByCollectionTarget = new Map();
  for (const row of metrics.lawStatus) {
    if (row.status === "INDEXED") {
      dbByCollectionTarget.set(`${lawVectorStore}:${row.target}`, number(row.chunks));
    }
  }
  for (const row of metrics.ragStatus) {
    if (row.status === "INDEXED") {
      dbByCollectionTarget.set(`${ragVectorStore}:${row.document_type}`, (dbByCollectionTarget.get(`${ragVectorStore}:${row.document_type}`) || 0) + number(row.chunks));
    }
  }
  metrics.qdrantTargetRows = qdrantTargetCounts
    .map((row) => {
      const dbCount = dbByCollectionTarget.get(`${row.collection}:${row.target}`) || 0;
      return {
        collection: row.collection,
        target: row.target,
        qdrant_count: row.ok ? row.count : row.error,
        db_indexed: dbCount,
        delta: row.ok ? number(row.count) - dbCount : "",
      };
    })
    .filter((row) => number(row.qdrant_count) !== 0 || number(row.db_indexed) !== 0 || row.qdrant_count);
  const risks = riskRows(metrics);
  const qdrantRows = metrics.qdrant.map((row) => ({
    collection: row.name,
    status: row.ok ? row.status : "DOWN",
    optimizer: row.ok ? row.optimizer : row.error,
    points: row.ok ? row.points : row.error,
    db_indexed: row.name === lawVectorStore ? lawIndexed : row.name === ragVectorStore ? ragIndexed : 0,
    delta: row.name === lawVectorStore ? number(row.points) - lawIndexed : row.name === ragVectorStore ? number(row.points) - ragIndexed : number(row.points),
  }));

  const lines = [];
  lines.push("# Search Quality Diagnostics");
  lines.push("");
  lines.push(`- Generated at: ${generatedAt}`);
  lines.push(`- DB time: ${dbNow}`);
  lines.push(`- Embedding model: ${model}`);
  lines.push(`- Collections: ${lawVectorStore}, ${ragVectorStore}`);
  lines.push("");
  lines.push("## Executive Risks");
  lines.push("");
  lines.push(mdTable(risks, [
    { key: "priority", label: "Priority" },
    { key: "area", label: "Area" },
    { key: "finding", label: "Finding" },
    { key: "action", label: "Recommended action" },
  ]));
  lines.push("");
  lines.push("## Eval Gate");
  lines.push("");
  lines.push(`- Gate file: ${path.relative(workspace, evalGatePath).replace(/\\/g, "/")}`);
  lines.push("");
  lines.push(mdTable(metrics.evalGate ? [{
    total: metrics.evalGate.total,
    passed: metrics.evalGate.passed,
    failed: metrics.evalGate.failed,
    passRate: `${Math.round((metrics.evalGate.passRate ?? 0) * 100)}%`,
    gatePassed: String(Boolean(metrics.evalGate.gatePassed)),
    failureIds: (metrics.evalGate.blockingFailureIds || []).join(", ") || "-",
  }] : [], [
    { key: "total", label: "Total", align: "right", format: "number" },
    { key: "passed", label: "Passed", align: "right", format: "number" },
    { key: "failed", label: "Failed", align: "right", format: "number" },
    { key: "passRate", label: "Pass rate" },
    { key: "gatePassed", label: "Gate passed" },
    { key: "failureIds", label: "Failure IDs" },
  ]));
  lines.push("");
  lines.push("## Embedding Backlog");
  lines.push("");
  lines.push(mdTable(metrics.backlog, [
    { key: "source", label: "Source" },
    { key: "remaining_candidates", label: "Remaining candidates", align: "right", format: "number" },
  ]));
  lines.push("");
  lines.push("## Qdrant Consistency");
  lines.push("");
  lines.push(mdTable(qdrantRows, [
    { key: "collection", label: "Collection" },
    { key: "status", label: "Status" },
    { key: "optimizer", label: "Optimizer" },
    { key: "points", label: "Qdrant points", align: "right" },
    { key: "db_indexed", label: "DB INDEXED", align: "right", format: "number" },
    { key: "delta", label: "Delta", align: "right" },
  ]));
  lines.push("");
  lines.push("Target distribution:");
  lines.push(mdTable(metrics.qdrantTargetRows, [
    { key: "collection", label: "Collection" },
    { key: "target", label: "Target" },
    { key: "qdrant_count", label: "Qdrant count", align: "right" },
    { key: "db_indexed", label: "DB INDEXED", align: "right", format: "number" },
    { key: "delta", label: "Delta", align: "right" },
  ]));
  lines.push("");
  lines.push("## Law / Administrative Rule Chunk Status");
  lines.push("");
  lines.push(mdTable(metrics.lawStatus, [
    { key: "target", label: "Target" },
    { key: "status", label: "Status" },
    { key: "chunks", label: "Chunks", align: "right", format: "number" },
  ]));
  lines.push("");
  lines.push("## Law Length Profile");
  lines.push("");
  lines.push(mdTable(metrics.lawLength, [
    { key: "chunks", label: "Chunks", align: "right", format: "number" },
    { key: "avg_len", label: "Avg", align: "right", format: "number" },
    { key: "min_len", label: "Min", align: "right", format: "number" },
    { key: "max_len", label: "Max", align: "right", format: "number" },
    { key: "over_1200", label: ">1200", align: "right", format: "number" },
    { key: "over_2500", label: ">2500", align: "right", format: "number" },
    { key: "over_4000", label: ">4000", align: "right", format: "number" },
    { key: "over_6000", label: ">6000", align: "right", format: "number" },
    { key: "under_80", label: "<80", align: "right", format: "number" },
    { key: "under_200", label: "<200", align: "right", format: "number" },
  ]));
  lines.push("");
  lines.push(mdTable(metrics.lawTargetLength, [
    { key: "target", label: "Target" },
    { key: "chunks", label: "Chunks", align: "right", format: "number" },
    { key: "avg_len", label: "Avg", align: "right", format: "number" },
    { key: "max_len", label: "Max", align: "right", format: "number" },
    { key: "over_2500", label: ">2500", align: "right", format: "number" },
    { key: "over_4000", label: ">4000", align: "right", format: "number" },
    { key: "over_6000", label: ">6000", align: "right", format: "number" },
    { key: "under_80", label: "<80", align: "right", format: "number" },
    { key: "under_200", label: "<200", align: "right", format: "number" },
  ]));
  lines.push("");
  lines.push("## Residual Tiny Audit");
  lines.push("");
  lines.push(`- Audit file: ${metrics.residualTinyAuditFile.exists ? path.relative(workspace, metrics.residualTinyAuditFile.path).replace(/\\/g, "/") : "missing"}`);
  lines.push(`- Total tiny chunks: ${fmt(metrics.residualTinyAudit?.total ?? metrics.lawLength?.[0]?.under_80 ?? 0)}`);
  lines.push("");
  lines.push(mdTable(metrics.residualTinyAudit?.summary ?? [], [
    { key: "target", label: "Target" },
    { key: "category", label: "Category" },
    { key: "chunks", label: "Chunks", align: "right", format: "number" },
    { key: "action", label: "Recommended action" },
  ]));
  lines.push("");
  lines.push("## Law Metadata Completeness");
  lines.push("");
  lines.push(mdTable(metrics.lawMetadata, [
    { key: "chunks", label: "Chunks", align: "right", format: "number" },
    { key: "missing_title", label: "Missing title", align: "right", format: "number" },
    { key: "missing_chunk_no", label: "Missing chunk no", align: "right", format: "number" },
    { key: "missing_source_url", label: "Missing source URL", align: "right", format: "number" },
    { key: "missing_source_date", label: "Missing source date", align: "right", format: "number" },
    { key: "missing_effective_date", label: "Missing effective date", align: "right", format: "number" },
    { key: "unknown_effective_status", label: "Unknown effective status", align: "right", format: "number" },
  ]));
  lines.push("");
  lines.push("## Official / Internal RAG Chunk Status");
  lines.push("");
  lines.push(mdTable(metrics.ragStatus, [
    { key: "document_type", label: "Type" },
    { key: "source_org", label: "Source org" },
    { key: "status", label: "Status" },
    { key: "chunks", label: "Chunks", align: "right", format: "number" },
  ]));
  lines.push("");
  lines.push("## RAG Length Profile");
  lines.push("");
  lines.push(mdTable(metrics.ragLength, [
    { key: "document_type", label: "Type" },
    { key: "source_org", label: "Source org" },
    { key: "chunks", label: "Chunks", align: "right", format: "number" },
    { key: "avg_len", label: "Avg", align: "right", format: "number" },
    { key: "min_len", label: "Min", align: "right", format: "number" },
    { key: "max_len", label: "Max", align: "right", format: "number" },
    { key: "over_1700", label: ">1700", align: "right", format: "number" },
    { key: "over_2500", label: ">2500", align: "right", format: "number" },
    { key: "under_120", label: "<120", align: "right", format: "number" },
  ]));
  lines.push("");
  lines.push("RAG short chunk samples (<120 chars):");
  lines.push(mdTable(metrics.ragShortSamples, [
    { key: "document_type", label: "Type" },
    { key: "source_org", label: "Source org" },
    { key: "title", label: "Title" },
    { key: "chunk_id", label: "Chunk ID", align: "right" },
    { key: "parent", label: "Parent" },
    { key: "chunk_title", label: "Chunk title" },
    { key: "section_type", label: "Section type" },
    { key: "len", label: "Length", align: "right", format: "number" },
    { key: "sample", label: "Sample" },
  ]));
  lines.push("");
  lines.push("## RAG Short Chunk Audit");
  lines.push("");
  lines.push(`- Audit file: ${metrics.ragShortAuditFile.exists ? path.relative(workspace, metrics.ragShortAuditFile.path).replace(/\\/g, "/") : "missing"}`);
  lines.push(`- Total short chunks: ${fmt(metrics.ragShortAudit?.total ?? 0)}`);
  lines.push("");
  lines.push(mdTable(metrics.ragShortAudit?.summary ?? [], [
    { key: "sourceOrg", label: "Source org" },
    { key: "category", label: "Category" },
    { key: "chunks", label: "Chunks", align: "right", format: "number" },
    { key: "action", label: "Recommended action" },
  ]));
  lines.push("");
  lines.push("Classified short chunk samples:");
  lines.push(mdTable(metrics.ragShortClassifiedSamples ?? [], [
    { key: "category", label: "Category" },
    { key: "action", label: "Action" },
    { key: "sourceOrg", label: "Source org" },
    { key: "documentId", label: "Doc", align: "right" },
    { key: "chunkId", label: "Chunk", align: "right" },
    { key: "sectionType", label: "Type" },
    { key: "length", label: "Len", align: "right", format: "number" },
    { key: "sample", label: "Sample" },
  ]));
  lines.push("");
  lines.push("## RAG Metadata Completeness");
  lines.push("");
  lines.push(mdTable(metrics.ragMetadata, [
    { key: "document_type", label: "Type" },
    { key: "source_org", label: "Source org" },
    { key: "chunks", label: "Chunks", align: "right", format: "number" },
    { key: "missing_parent", label: "Missing parent", align: "right", format: "number" },
    { key: "missing_title", label: "Missing title", align: "right", format: "number" },
    { key: "missing_embedding_text", label: "Missing embedding text", align: "right", format: "number" },
    { key: "missing_page_no", label: "Missing page", align: "right", format: "number" },
  ]));
  lines.push("");
  lines.push("## RAG Section Types");
  lines.push("");
  lines.push(mdTable(metrics.ragSectionTypes, [
    { key: "section_type", label: "Section type" },
    { key: "chunks", label: "Chunks", align: "right", format: "number" },
  ]));
  lines.push("");
  lines.push("## Duplicate / Stale Signals");
  lines.push("");
  lines.push("Law duplicate hashes:");
  lines.push(mdTable(metrics.duplicateLawHashes, [
    { key: "duplicate_hash_groups", label: "Duplicate hash groups", align: "right", format: "number" },
    { key: "duplicate_chunks", label: "Duplicate chunks", align: "right", format: "number" },
  ]));
  lines.push("");
  lines.push("RAG duplicate hashes:");
  lines.push(mdTable(metrics.duplicateRagHashes, [
    { key: "duplicate_hash_groups", label: "Duplicate hash groups", align: "right", format: "number" },
    { key: "duplicate_chunks", label: "Duplicate chunks", align: "right", format: "number" },
  ]));
  lines.push("");
  lines.push(mdTable(metrics.staleEmbeddings, [
    { key: "stale_embeddings", label: "Stale INDEXED embeddings", align: "right", format: "number" },
  ]));
  lines.push("");
  lines.push(mdTable(metrics.staleRagMissing, [
    { key: "stale_missing", label: "Non-latest RAG missing rows", align: "right", format: "number" },
  ]));
  lines.push("");
  lines.push("## Long Law Chunk Samples");
  lines.push("");
  lines.push(mdTable(metrics.longLawSamples, [
    { key: "target", label: "Target" },
    { key: "title", label: "Title" },
    { key: "chunk_id", label: "Chunk ID", align: "right" },
    { key: "chunk_type", label: "Type" },
    { key: "chunk_no", label: "No" },
    { key: "chunk_title", label: "Chunk title" },
    { key: "len", label: "Length", align: "right", format: "number" },
  ]));
  lines.push("");
  lines.push("## Long RAG Chunk Samples");
  lines.push("");
  lines.push(mdTable(metrics.longRagSamples, [
    { key: "document_type", label: "Type" },
    { key: "source_org", label: "Source org" },
    { key: "title", label: "Title" },
    { key: "chunk_id", label: "Chunk ID", align: "right" },
    { key: "parent", label: "Parent" },
    { key: "chunk_title", label: "Chunk title" },
    { key: "section_type", label: "Section type" },
    { key: "len", label: "Length", align: "right", format: "number" },
  ]));
  lines.push("");
  lines.push("## Batch Health");
  lines.push("");
  lines.push("Batch runner:");
  lines.push(mdTable([{
    endpoint: metrics.batchRunner.endpoint,
    status: metrics.batchRunner.status.status,
    running: String(metrics.batchRunner.status.running),
    lastStatus: metrics.batchRunner.status.lastStatus,
    lastErrorMessage: metrics.batchRunner.status.lastErrorMessage || "",
    batchJarMtime: metrics.batchRunner.batchJar.mtime || "",
    batchJarSize: metrics.batchRunner.batchJar.size || "",
  }], [
    { key: "endpoint", label: "Endpoint" },
    { key: "status", label: "Status" },
    { key: "running", label: "Running" },
    { key: "lastStatus", label: "Last status" },
    { key: "lastErrorMessage", label: "Last error" },
    { key: "batchJarMtime", label: "Batch jar mtime" },
    { key: "batchJarSize", label: "Batch jar bytes", align: "right", format: "number" },
  ]));
  lines.push("");
  lines.push("Active/open jobs:");
  lines.push(mdTable(metrics.activeJobs, [
    { key: "batch_job_id", label: "Job", align: "right" },
    { key: "status", label: "Status" },
    { key: "target", label: "Target" },
    { key: "query_text", label: "Query" },
    { key: "vector_store", label: "Store" },
    { key: "submitted_count", label: "Submitted", align: "right", format: "number" },
    { key: "completed_count", label: "Completed", align: "right", format: "number" },
    { key: "failed_count", label: "Failed", align: "right", format: "number" },
    { key: "ingested_count", label: "Ingested", align: "right", format: "number" },
    { key: "age_min", label: "Age min", align: "right", format: "number" },
  ]));
  lines.push("");
  lines.push("Isolated/failed job summary:");
  lines.push(mdTable(metrics.badJobSummary, [
    { key: "status", label: "Status" },
    { key: "target", label: "Target" },
    { key: "query_text", label: "Query" },
    { key: "vector_store", label: "Store" },
    { key: "jobs", label: "Jobs", align: "right", format: "number" },
    { key: "submitted", label: "Submitted", align: "right", format: "number" },
    { key: "completed", label: "Completed", align: "right", format: "number" },
    { key: "ingested", label: "Ingested", align: "right", format: "number" },
    { key: "latest_updated", label: "Latest updated" },
  ]));
  lines.push("");
  lines.push("Recent isolated/failed jobs (latest 5):");
  lines.push(mdTable(metrics.recentBadJobs, [
    { key: "batch_job_id", label: "Job", align: "right" },
    { key: "status", label: "Status" },
    { key: "target", label: "Target" },
    { key: "query_text", label: "Query" },
    { key: "vector_store", label: "Store" },
    { key: "submitted_count", label: "Submitted", align: "right", format: "number" },
    { key: "completed_count", label: "Completed", align: "right", format: "number" },
    { key: "failed_count", label: "Failed", align: "right", format: "number" },
    { key: "ingested_count", label: "Ingested", align: "right", format: "number" },
    { key: "updated_at", label: "Updated" },
  ]));

  const report = lines.join("\n") + "\n";
  fs.writeFileSync(outPath, report, "utf8");
  fs.writeFileSync(jsonPath, JSON.stringify({ generatedAt, dbNow, metrics, risks, qdrantRows }, null, 2), "utf8");
  console.log(outPath);
  console.log(jsonPath);
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});

