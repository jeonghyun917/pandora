const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const workspace = path.resolve(__dirname, "..");
const mysql = process.env.MARIADB_EXE || "C:\\Program Files\\MariaDB 12.2\\bin\\mariadb.exe";
const vectorStore = process.env.PANDORA_RAG_VECTOR_STORE || "rag_chunks_v4";
const maxLength = Number(process.env.RAG_SHORT_AUDIT_MAX_LENGTH || 120);
const outMd = path.resolve(workspace, "logs", "rag-short-chunk-audit-latest.md");
const outJson = path.resolve(workspace, "logs", "rag-short-chunk-audit-latest.json");
const decisionsPath = path.resolve(workspace, "logs", "rag-short-chunk-decisions-latest.json");
const apply = process.argv.includes("--apply");
const qdrantBaseUrl = (process.env.PANDORA_QDRANT_URL || "http://127.0.0.1:6333").replace(/\/$/, "");

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
    maxBuffer: 128 * 1024 * 1024,
    windowsHide: true,
  });
  return output.trim().split(/\r?\n/).filter(Boolean).map((line) => line.split("\t"));
}

function execSql(sql) {
  execFileSync(mysql, [
    "--ssl=0",
    "-h", "localhost",
    "-P", "3306",
    "-upandora",
    "-ppandora",
    "--batch",
    "--raw",
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
}

function q(value) {
  return String(value ?? "").replace(/\\/g, "\\\\").replace(/'/g, "''");
}

function unhex(value) {
  return Buffer.from(value || "", "hex").toString("utf8");
}

function number(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function clean(value) {
  return String(value ?? "")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;|&lt;|&gt;|&amp;/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function normalize(value) {
  return clean(value).toLowerCase();
}

function containsAny(text, terms) {
  const compact = String(text ?? "").replace(/\s+/g, "");
  return terms.some((term) => {
    const normalizedTerm = term.toLowerCase();
    return text.includes(normalizedTerm) || compact.includes(normalizedTerm.replace(/\s+/g, ""));
  });
}

const navigationTerms = [
  "상단", "메뉴", "버튼", "첨부", "첨부파일", "다운로드", "클릭", "확인하십시오", "이용하십시오",
  "download", "attachment", "click", "menu", "button",
];
const navigationActionTerms = [
  "클릭", "선택", "누르", "이동", "다운로드", "열기", "확인",
  "click", "select", "press", "move", "download", "open", "confirm",
];
const substantiveTerms = [
  "기준", "절차", "신청", "제출", "대상", "요건", "의무", "예외", "공개", "처리", "관리", "보안",
  "계약", "검토", "협의", "제공", "등록", "점검", "산정", "구매", "평가", "심의", "조치", "방법",
  "요구사항", "충족", "검증", "제외",
];
const publicationFooterTerms = [
  "©", "copyright", "all rights reserved", "oecd 2026", "national tax service",
];
const romanTocMarker = /[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]\s*\.?/gu;
const numberedTocMarker = /(?<!\d)\d{1,2}\s*\./gu;
const romanTocLineWithPage = /^\s*[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+\s*\.?\s+\S.{0,90}\s+\d{1,4}(\s+\S.{0,40})?$/iu;
const pageWrappedHeading = /^\s*[-–—|]?\s*\d{1,4}\s*(\([^)]{1,20}\))?\s*[-–—]?\s+\S.{1,110}$/iu;
const pageRomanHeadingOrCaption = /^\s*\d{1,4}\s*[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+\s*\.?\s+\S.{1,110}$/iu;
const bracketedIndexLabel = /^\s*\[[^\]]{1,40}\]\s*[\d①-⑳⑴-⑽ⅰ-ⅹ]+\s*$/iu;
const reportHeadingWithPage = /^\s*.{2,50}\s+\d{1,3}\s+.{2,70}(보고서|백서|가이드|안내서|매뉴얼|계획서)\d?\s*$/iu;
const midPageHeading = /^\s*.{2,80}\s+[-–—]\s*\d{1,4}\s*[-–—]\s+[■□▪•]?\s*\S.{1,100}$/iu;
const reportTitleFragment = /^\s*.{1,90}(보고서|백서|가이드|안내서|매뉴얼|계획서|조사표)(\s*\d{1,3})?\s*$/iu;
const figureOrTableCaption = /(\[\s*(그림|표)\s*\d|그림\s*\d|표\s*\d|단위\s*[:：])/iu;
const brandedCoverFragment = /^\s*.{0,70}\b(city|sokcho|korea|platform|service)\b.{0,40}$/iu;
const decorativeMark = /[▪󰠏■□•]/gu;
const repeatedCultureMark = /(三樂|三寶|三)/gu;

function classify(row) {
  const text = normalize(row.sample);
  const titleText = normalize(`${row.parentSectionTitle} ${row.chunkTitle}`);
  const documentTitle = normalize(row.title);
  if (!text && /<img/i.test(row.rawSample || "")) {
    return { category: "image_or_empty", action: "suppress_or_rechunk" };
  }
  if (!text) {
    return { category: "image_or_empty", action: "suppress_or_rechunk" };
  }
  if (isDecorativeFooterOrTitle(text, documentTitle, titleText)) {
    return { category: "decorative_footer_or_title", action: "suppress_or_downrank" };
  }
  if (isRequirementFieldLabel(text)) {
    return { category: "field_label_fragment", action: "downrank_context_only" };
  }
  if (isTableUnitOrPageMarker(text)) {
    return { category: "table_unit_or_page_marker", action: "suppress_or_downrank" };
  }
  if (isRunningHeaderOrCoverTitle(text, documentTitle, titleText)) {
    return { category: "running_header_or_cover_title", action: "downrank_context_only" };
  }
  if (isTocLikeShortFragment(row.sample)) {
    return { category: "toc_or_heading_fragment", action: "merge_or_downrank" };
  }
  if (isNavigationInstruction(text)) {
    return hasNavigationSubject(text)
      ? { category: "navigation_with_subject", action: "manual_review" }
      : { category: "navigation_or_attachment_notice", action: "downrank_context_only" };
  }
  if (row.sectionType === "toc" || looksLikeHeadingOnly(text, titleText)) {
    return { category: "toc_or_heading_fragment", action: "merge_or_downrank" };
  }
  if (isPagedHeadingOrFormFragment(row.sample, text)) {
    return { category: "toc_or_heading_fragment", action: "merge_or_downrank" };
  }
  if (isSymbolicRepetitionFragment(row.sample, text)) {
    return { category: "decorative_footer_or_title", action: "suppress_or_downrank" };
  }
  if (containsAny(text, substantiveTerms) || containsAny(titleText, substantiveTerms)) {
    return { category: "meaningful_short_evidence", action: "keep" };
  }
  return { category: "ambiguous_short", action: "manual_review" };
}

function qualityStatus(action) {
  if (action === "keep") return "PASS";
  if (action === "manual_review") return "REVIEW";
  if (action === "suppress_or_rechunk") return "REJECT";
  return "CONTEXT_ONLY";
}

function isNavigationInstruction(text) {
  const hasNavigationTerm = containsAny(text, navigationTerms) || text.includes(">") || text.includes("→");
  return hasNavigationTerm && containsAny(text, navigationActionTerms);
}

function hasNavigationSubject(text) {
  let remainder = String(text ?? "").toLowerCase();
  for (const term of [...navigationTerms, ...navigationActionTerms]) {
    remainder = remainder.split(term.toLowerCase()).join(" ");
  }
  remainder = remainder
    .replace(/(?:화면|페이지|해당|메뉴경로)/gu, " ")
    .replace(/[^\p{Letter}\p{Number}]/gu, "");
  return remainder.length >= 4;
}

function isDecorativeFooterOrTitle(text, documentTitle, titleText) {
  if (!text) return true;
  if (text.length <= 1) return true;
  if (/^[0-9]+$/.test(text)) return true;
  if (/^[\p{P}\s·ㆍ\-_/\\|0-9]+$/u.test(text)) return true;
  const hasFooterCue = publicationFooterTerms.some((term) => text.includes(term));
  const hasUrlCue = /www\.[a-z0-9._-]+\.[a-z]{2,}/i.test(text);
  if ((hasFooterCue || hasUrlCue) && !containsAny(text, substantiveTerms)) {
    return true;
  }
  const titleOnly = text.length <= 120
    && (documentTitle.includes(text)
      || titleText.includes(text)
      || hasSharedRun(compactForMatch(text), compactForMatch(`${documentTitle} ${titleText}`), 16))
    && !containsAny(text, substantiveTerms);
  return titleOnly;
}

function isRequirementFieldLabel(text) {
  return text.length <= 120
    && /^(요구사항(분류|고유번호|명칭)|관련요구사항|준수항목|requirement(id|name|type)).{0,80}$/i.test(text);
}

function isTableUnitOrPageMarker(text) {
  if (!text || text.length > 140 || containsAny(text, substantiveTerms)) {
    return false;
  }
  return /^\s*[-–—]?\s*\d{1,4}\s*[-–—]?\s*[-–—]?\s*.{0,80}\(\s*단위\s*[:：][^)]+\).*$/iu.test(text)
    || /^\s*[-–—]\s*\d{1,4}\s*[-–—]\s*[-–—]\s*.{1,50}\s*[-–—]\s*$/iu.test(text);
}

function isRunningHeaderOrCoverTitle(text, documentTitle, titleText) {
  if (!text || text.length > 160 || containsAny(text, substantiveTerms)) {
    return false;
  }
  const compact = compactForMatch(text);
  const stripped = compact.replace(/^p\d{1,4}/i, "").replace(/^\d{1,4}/, "");
  const titleCompact = compactForMatch(`${documentTitle} ${titleText}`);
  if (!stripped || !titleCompact) {
    return false;
  }
  const hasPageCue = /^\s*[-–—]?\s*\d{1,4}\s*[-–—]?\s*.{6,140}$/u.test(text)
    || /^\d{2,4}[\p{Letter}\p{Number}가-힣]/u.test(compact);
  const titleEcho = titleCompact.includes(stripped)
    || stripped.includes(titleCompact)
    || hasSharedRun(stripped, titleCompact, 12);
  return titleEcho && (hasPageCue || looksLikeCoverTitleFragment(text, compact));
}

function looksLikeCoverTitleFragment(text, compact) {
  if (!text || text.length > 120) {
    return false;
  }
  return /\b\d{4}(\.\s*\d{1,2})?\b/u.test(text)
    || /[A-Z]{2,}/.test(text)
    || /(보고서|백서|가이드|매뉴얼|안내서|사례집|보도자료|결과보고서)/.test(compact);
}

function compactForMatch(value) {
  return clean(value)
    .replace(/[^\p{Letter}\p{Number}가-힣]/gu, "")
    .toLowerCase();
}

function hasSharedRun(left, right, minLength) {
  if (!left || !right || left.length < minLength || right.length < minLength) {
    return false;
  }
  const shorter = left.length <= right.length ? left : right;
  const longer = left.length <= right.length ? right : left;
  for (let start = 0; start <= shorter.length - minLength; start += 1) {
    if (longer.includes(shorter.slice(start, start + minLength))) {
      return true;
    }
  }
  return false;
}

function looksLikeHeadingOnly(text, titleText) {
  if (text.length > 90) {
    return false;
  }
  if (/^(part|chapter|section|appendix|[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+\.?|부록|붙임|제\s*\d+\s*[장절관])(?=\s|$)/i.test(text)) {
    return true;
  }
  if (/^\d+(\.\d+)*\.?\s+\S+/.test(text) && text.length < 70) {
    return true;
  }
  if (/(white\s*paper|appendix|directory\s*book|annual\s*report).{0,120}$/i.test(text)) {
    return true;
  }
  return titleText && titleText.includes(text) && text.length < 60;
}

function isTocLikeShortFragment(value) {
  const text = clean(value);
  if (!text || text.length > 140) {
    return false;
  }
  const romanMarkers = countMatches(text, romanTocMarker);
  const numberedMarkers = countMatches(text, numberedTocMarker);
  if (romanMarkers >= 2 || numberedMarkers >= 3) {
    return true;
  }
  if (romanTocLineWithPage.test(text)) {
    return true;
  }
  const lower = text.toLowerCase();
  return lower.includes("white paper") && (lower.includes("appendix") || romanMarkers >= 1);
}

function isPagedHeadingOrFormFragment(rawText, normalizedText) {
  const text = clean(rawText);
  if (!text || text.length > 140 || containsAny(normalizedText, substantiveTerms)) {
    return false;
  }
  return pageWrappedHeading.test(text)
    || pageRomanHeadingOrCaption.test(text)
    || bracketedIndexLabel.test(text)
    || reportHeadingWithPage.test(text)
    || midPageHeading.test(text)
    || reportTitleFragment.test(text)
    || figureOrTableCaption.test(text)
    || brandedCoverFragment.test(text);
}

function isSymbolicRepetitionFragment(rawText, normalizedText) {
  const text = clean(rawText);
  if (!text || text.length > 120 || containsAny(normalizedText, substantiveTerms)) {
    return false;
  }
  return countMatches(text, decorativeMark) >= 4
    || countMatches(text, repeatedCultureMark) >= 6;
}

function countMatches(value, pattern) {
  pattern.lastIndex = 0;
  let count = 0;
  while (pattern.exec(value)) {
    count += 1;
  }
  pattern.lastIndex = 0;
  return count;
}

function rows() {
  return db(`
SELECT d.document_type,
       HEX(COALESCE(NULLIF(TRIM(d.source_org), ''), '(none)')),
       d.document_id,
       HEX(d.title),
       c.chunk_id,
       HEX(COALESCE(c.parent_section_title, '')),
       HEX(COALESCE(c.chunk_title, '')),
       c.section_type,
       CHAR_LENGTH(c.chunk_text),
       HEX(LEFT(c.chunk_text, 500))
FROM rag_document_chunks c
JOIN rag_documents d ON d.document_id = c.document_id AND d.use_yn = 'Y'
JOIN rag_chunk_embeddings e
  ON e.chunk_id = c.chunk_id
 AND e.vector_store = '${q(vectorStore)}'
 AND e.status = 'INDEXED'
WHERE c.use_yn = 'Y'
  AND d.document_type IN ('official_doc', 'internal_doc', 'reference_doc')
  AND CHAR_LENGTH(c.chunk_text) < ${maxLength}
ORDER BY d.source_org IS NULL DESC, d.source_org, d.document_id, c.sort_order, c.chunk_id
`);
}

function mdTable(tableRows, columns) {
  if (!tableRows.length) return "_none_";
  const header = `| ${columns.map((column) => column.label).join(" | ")} |`;
  const sep = `| ${columns.map((column) => column.align === "right" ? "---:" : "---").join(" | ")} |`;
  const body = tableRows.map((row) => `| ${columns.map((column) => {
    const value = String(row[column.key] ?? "");
    return value.replace(/\r?\n/g, " ").replace(/\|/g, "\\|");
  }).join(" | ")} |`);
  return [header, sep, ...body].join("\n");
}

async function main() {
  const records = rows().map((row) => {
    const record = {
      documentType: row[0],
      sourceOrg: unhex(row[1]),
      documentId: number(row[2]),
      title: unhex(row[3]),
      chunkId: number(row[4]),
      parentSectionTitle: unhex(row[5]),
      chunkTitle: unhex(row[6]),
      sectionType: row[7],
      length: number(row[8]),
      rawSample: unhex(row[9]),
    };
    record.sample = clean(record.rawSample);
    const classified = { ...record, ...classify(record) };
    classified.qualityStatus = qualityStatus(classified.action);
    return classified;
  });

  const summaryMap = new Map();
  for (const record of records) {
    const key = `${record.sourceOrg}\t${record.category}\t${record.action}`;
    const current = summaryMap.get(key) ?? {
      sourceOrg: record.sourceOrg,
      category: record.category,
      action: record.action,
      chunks: 0,
    };
    current.chunks += 1;
    summaryMap.set(key, current);
  }
  const summary = Array.from(summaryMap.values())
    .sort((a, b) => b.chunks - a.chunks || a.sourceOrg.localeCompare(b.sourceOrg) || a.category.localeCompare(b.category));

  const sampleByCategory = new Map();
  for (const record of records) {
    if (!sampleByCategory.has(record.category)) {
      sampleByCategory.set(record.category, []);
    }
    const samples = sampleByCategory.get(record.category);
    if (samples.length < 15) {
      samples.push(record);
    }
  }

  const output = {
    generatedAt: new Date().toISOString(),
    vectorStore,
    maxLength,
    total: records.length,
    summary,
    samples: Object.fromEntries(sampleByCategory),
    applyRequested: apply,
    dbApplyCompleted: false,
    vectorCleanupCompleted: false,
    applyCompleted: false,
  };
  fs.mkdirSync(path.dirname(outMd), { recursive: true });

  let applyError = null;
  if (apply) {
    try {
      const rollbackState = captureRollbackState(records);
      const qdrantSnapshot = await createQdrantSnapshot();
      const rollbackPath = path.resolve(
        workspace,
        "logs",
        `rag-short-chunk-rollback-${output.generatedAt.replace(/[:.]/g, "-")}.json`,
      );
      fs.writeFileSync(rollbackPath, JSON.stringify({
        generatedAt: output.generatedAt,
        vectorStore,
        qdrantSnapshot,
        chunks: rollbackState.chunks,
        embeddings: rollbackState.embeddings,
      }, null, 2), "utf8");
      output.rollbackManifest = rollbackPath;
      output.qdrantSnapshot = qdrantSnapshot;
      applyQualityDecisions(records);
      output.dbApplyCompleted = true;
      await removeNonSearchableVectors();
      output.vectorCleanupCompleted = true;
      output.applyCompleted = true;
    } catch (error) {
      applyError = error;
      output.applyError = String(error?.message || error);
    }
  }

  fs.writeFileSync(outJson, JSON.stringify(output, null, 2), "utf8");
  fs.writeFileSync(decisionsPath, JSON.stringify({
    generatedAt: output.generatedAt,
    vectorStore,
    maxLength,
    applyRequested: apply,
    dbApplyCompleted: output.dbApplyCompleted,
    vectorCleanupCompleted: output.vectorCleanupCompleted,
    applyCompleted: output.applyCompleted,
    applyError: output.applyError || null,
    rollbackManifest: output.rollbackManifest || null,
    qdrantSnapshot: output.qdrantSnapshot || null,
    decisions: records.map((record) => ({
      chunkId: record.chunkId,
      documentId: record.documentId,
      category: record.category,
      action: record.action,
      qualityStatus: record.qualityStatus,
    })),
  }, null, 2), "utf8");

  const lines = [];
  lines.push("# RAG Short Chunk Audit");
  lines.push("");
  lines.push(`- Generated at: ${output.generatedAt}`);
  lines.push(`- Vector store: ${vectorStore}`);
  lines.push(`- Max length: <${maxLength}`);
  lines.push(`- Total short chunks: ${records.length.toLocaleString("ko-KR")}`);
  lines.push(`- Apply requested: ${apply}`);
  lines.push(`- DB apply completed: ${output.dbApplyCompleted}`);
  lines.push(`- Vector cleanup completed: ${output.vectorCleanupCompleted}`);
  lines.push(`- Apply completed: ${output.applyCompleted}`);
  lines.push("");
  lines.push("## Summary");
  lines.push("");
  lines.push(mdTable(summary, [
    { key: "sourceOrg", label: "Source org" },
    { key: "category", label: "Category" },
    { key: "chunks", label: "Chunks", align: "right" },
    { key: "action", label: "Recommended action" },
  ]));
  lines.push("");
  lines.push("## Samples");
  for (const [category, samples] of sampleByCategory.entries()) {
    lines.push("");
    lines.push(`### ${category}`);
    lines.push("");
    lines.push(mdTable(samples.map((sample) => ({
      sourceOrg: sample.sourceOrg,
      documentId: sample.documentId,
      chunkId: sample.chunkId,
      title: sample.title,
      sectionType: sample.sectionType,
      length: sample.length,
      sample: sample.sample.slice(0, 180),
      action: sample.action,
    })), [
      { key: "sourceOrg", label: "Source org" },
      { key: "documentId", label: "Doc", align: "right" },
      { key: "chunkId", label: "Chunk", align: "right" },
      { key: "title", label: "Title" },
      { key: "sectionType", label: "Type" },
      { key: "length", label: "Len", align: "right" },
      { key: "sample", label: "Sample" },
      { key: "action", label: "Action" },
    ]));
  }
  lines.push("");
  fs.writeFileSync(outMd, lines.join("\n"), "utf8");
  console.log(outMd);
  console.log(outJson);
  console.log(decisionsPath);
  if (applyError) {
    throw applyError;
  }
}

function applyQualityDecisions(records) {
  const decisionGroups = new Map();
  for (const row of records) {
    const key = `${row.qualityStatus}\u0000${row.category}`;
    if (!decisionGroups.has(key)) {
      decisionGroups.set(key, []);
    }
    decisionGroups.get(key).push(row.chunkId);
  }
  for (const [key, chunkIds] of decisionGroups.entries()) {
    const [qualityStatus, category] = key.split("\u0000");
    for (let start = 0; start < chunkIds.length; start += 300) {
      const ids = chunkIds.slice(start, start + 300).join(",");
      execSql(`
        UPDATE rag_document_chunks
        SET quality_status = '${q(qualityStatus)}',
            quality_reason = '${q(category)}'
        WHERE chunk_id IN (${ids});
      `);
    }
  }

  const nonSearchable = records.filter((row) => ["CONTEXT_ONLY", "REJECT"].includes(row.qualityStatus));
  for (let start = 0; start < nonSearchable.length; start += 300) {
    const ids = nonSearchable.slice(start, start + 300).map((row) => row.chunkId).join(",");
    execSql(`
      UPDATE rag_chunk_embeddings
      SET status = 'SUPERSEDED',
          last_error_message = 'Excluded by v4 chunk quality gate',
          updated_at = NOW()
      WHERE chunk_id IN (${ids})
        AND vector_store = '${q(vectorStore)}';
    `);
  }
}

function captureRollbackState(records) {
  const chunks = [];
  const embeddings = [];
  for (let start = 0; start < records.length; start += 400) {
    const ids = records.slice(start, start + 400).map((row) => row.chunkId).join(",");
    for (const row of db(`
      SELECT chunk_id,
             COALESCE(quality_status, 'PASS'),
             HEX(COALESCE(quality_reason, ''))
      FROM rag_document_chunks
      WHERE chunk_id IN (${ids});
    `)) {
      chunks.push({
        chunkId: number(row[0]),
        qualityStatus: row[1],
        qualityReason: unhex(row[2]),
      });
    }
    for (const row of db(`
      SELECT chunk_id,
             status,
             HEX(COALESCE(last_error_message, ''))
      FROM rag_chunk_embeddings
      WHERE chunk_id IN (${ids})
        AND vector_store = '${q(vectorStore)}';
    `)) {
      embeddings.push({
        chunkId: number(row[0]),
        status: row[1],
        lastErrorMessage: unhex(row[2]),
      });
    }
  }
  return { chunks, embeddings };
}

async function createQdrantSnapshot() {
  const response = await fetch(`${qdrantBaseUrl}/collections/${encodeURIComponent(vectorStore)}/snapshots?wait=true`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    signal: AbortSignal.timeout(120000),
  });
  if (!response.ok) {
    throw new Error(`Qdrant pre-cleanup snapshot failed: HTTP ${response.status} ${await response.text()}`);
  }
  const payload = await response.json();
  const snapshotName = payload?.result?.name;
  if (!snapshotName) {
    throw new Error("Qdrant pre-cleanup snapshot response did not include a snapshot name.");
  }
  return snapshotName;
}

async function removeNonSearchableVectors() {
  const pointIds = db(`
    SELECT DISTINCT e.vector_point_id
    FROM rag_document_chunks c
    JOIN rag_documents d
      ON d.document_id = c.document_id
     AND d.use_yn = 'Y'
    JOIN rag_chunk_embeddings e
      ON e.chunk_id = c.chunk_id
     AND e.vector_store = '${q(vectorStore)}'
    WHERE c.use_yn = 'Y'
      AND c.quality_status IN ('CONTEXT_ONLY', 'REJECT')
      AND e.vector_point_id IS NOT NULL;
  `)
    .map((row) => number(row[0]))
    .filter((pointId) => Number.isSafeInteger(pointId) && pointId > 0);
  for (let start = 0; start < pointIds.length; start += 64) {
    await deleteQdrantPointBatch(pointIds.slice(start, start + 64));
  }
}

async function deleteQdrantPointBatch(pointIds) {
  let lastError = null;
  for (let attempt = 1; attempt <= 4; attempt += 1) {
    try {
      const response = await fetch(`${qdrantBaseUrl}/collections/${encodeURIComponent(vectorStore)}/points/delete?wait=true`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ points: pointIds }),
        signal: AbortSignal.timeout(30000),
      });
      if (response.ok) {
        return;
      }
      lastError = new Error(`HTTP ${response.status} ${await response.text()}`);
    } catch (error) {
      lastError = error;
    }
    if (attempt < 4) {
      await new Promise((resolve) => setTimeout(resolve, attempt * 500));
    }
  }
  throw new Error(`Qdrant quality cleanup failed after retries: ${lastError?.message || lastError}`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
