const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const workspace = path.resolve(__dirname, "..");
const mysql = process.env.MARIADB_EXE || "C:\\Program Files\\MariaDB 12.2\\bin\\mariadb.exe";
const maxLength = Number(process.env.TINY_AUDIT_MAX_LENGTH || 80);
const outPath = path.resolve(workspace, "logs", "residual-tiny-audit-latest.md");
const jsonPath = path.resolve(workspace, "logs", "residual-tiny-audit-latest.json");

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
    maxBuffer: 64 * 1024 * 1024,
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

function mdTable(rows, columns) {
  if (!rows.length) return "_none_";
  const header = `| ${columns.map((column) => column.label).join(" | ")} |`;
  const sep = `| ${columns.map((column) => column.align === "right" ? "---:" : "---").join(" | ")} |`;
  const body = rows.map((row) => `| ${columns.map((column) => {
    const raw = row[column.key] ?? "";
    const value = column.format === "number" ? fmt(raw) : String(raw);
    return value.replace(/\r?\n/g, " ").replace(/\|/g, "\\|");
  }).join(" | ")} |`);
  return [header, sep, ...body].join("\n");
}

function compact(value, max = 180) {
  const text = String(value ?? "").replace(/\s+/g, " ").trim();
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}

function visibleText(value) {
  return String(value ?? "")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;|&lt;|&gt;|&amp;/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function containsAny(text, terms) {
  return terms.some((term) => text.includes(term));
}

function hasSubstantiveSignal(text) {
  return /제\s*\d+\s*(조|항|호|목)/.test(text)
    || containsAny(text, [
      "하여야", "해야", "기준", "대상", "절차", "요건", "의무", "금지", "제외", "가능", "신청", "처리",
      "보유", "파기", "동의", "공개", "고지", "제출", "심사", "계약", "설치", "운영", "관리",
    ]);
}

function isStrongNavigationNotice(text) {
  return containsAny(text, [
    "자세한내용", "확인하십시오", "이용하십시오", "상단메뉴", "상단첨부파일", "첨부파일을다운로드",
    "버튼을이용", "메뉴를이용", "첨부파일을이용",
  ])
    && containsAny(text, ["첨부", "첨부파일", "상단", "메뉴", "클릭", "버튼", "화면", "이미지", "다운로드", "누르", "이용"])
    && !/제\s*\d+\s*(조|항|호|목)/.test(text);
}

function classify(row) {
  const raw = row.chunk_text ?? "";
  const visible = visibleText(raw);
  const compactVisible = visible.replace(/\s+/g, "");
  const lowerRaw = raw.toLowerCase();
  if (lowerRaw.includes("<img") && compactVisible.length <= 20) {
    return "image_only";
  }
  if (/^<[^>]{1,45}>?$/.test(compactVisible) || /^\d{4}\.\d{1,2}\.\d{1,2}(,\d{4}\.\d{1,2}\.\d{1,2})*>?$/.test(compactVisible)) {
    return "revision_marker";
  }
  const navigationTerms = ["첨부", "첨부파일", "상단", "메뉴", "클릭", "버튼", "화면", "이미지", "다운로드", "누르", "이용"];
  if (visible.length <= 160 && isStrongNavigationNotice(compactVisible)) {
    return "navigation_notice";
  }
  if (visible.length <= 160 && containsAny(visible, navigationTerms) && !hasSubstantiveSignal(visible)) {
    return "navigation_notice";
  }
  if (compactVisible.length <= 30 && /^[\d.,:;()\[\]\-_/\\]+$/.test(compactVisible)) {
    return "layout_number";
  }
  if (hasSubstantiveSignal(visible)) {
    return "meaningful_short_evidence";
  }
  return "ambiguous_short";
}

function actionFor(category) {
  switch (category) {
    case "image_only":
    case "navigation_notice":
      return "suppress_or_downrank";
    case "revision_marker":
      return "suppress_except_revision_questions";
    case "layout_number":
      return "suppress";
    case "meaningful_short_evidence":
      return "keep";
    default:
      return "manual_review";
  }
}

async function main() {
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  const generatedAt = new Date().toISOString();
  const rows = table(`
SELECT
  doc.target,
  doc.document_id,
  REPLACE(REPLACE(REPLACE(doc.title, CHAR(9), ' '), CHAR(10), ' '), CHAR(13), ' ') AS title,
  c.chunk_id,
  REPLACE(REPLACE(REPLACE(COALESCE(c.chunk_no,''), CHAR(9), ' '), CHAR(10), ' '), CHAR(13), ' ') AS chunk_no,
  REPLACE(REPLACE(REPLACE(COALESCE(c.chunk_title,''), CHAR(9), ' '), CHAR(10), ' '), CHAR(13), ' ') AS chunk_title,
  COALESCE(c.chunk_type,'') AS chunk_type,
  CHAR_LENGTH(c.chunk_text) AS len,
  REPLACE(REPLACE(REPLACE(c.chunk_text, CHAR(9), ' '), CHAR(10), ' '), CHAR(13), ' ') AS chunk_text
FROM law_api_document_chunks c
JOIN law_api_documents doc ON doc.document_id = c.document_id
WHERE doc.use_yn = 'Y'
  AND c.use_yn = 'Y'
  AND CHAR_LENGTH(c.chunk_text) < ${number(maxLength)}
ORDER BY doc.target, c.chunk_id;
`, ["target", "document_id", "title", "chunk_id", "chunk_no", "chunk_title", "chunk_type", "len", "chunk_text"]);

  const classified = rows.map((row) => ({
    ...row,
    category: classify(row),
    action: actionFor(classify(row)),
    sample: compact(visibleText(row.chunk_text), 180),
  }));
  const summaryMap = new Map();
  for (const row of classified) {
    const key = `${row.target}:${row.category}`;
    const existing = summaryMap.get(key) ?? { target: row.target, category: row.category, chunks: 0, action: row.action };
    existing.chunks += 1;
    summaryMap.set(key, existing);
  }
  const summary = [...summaryMap.values()].sort((a, b) =>
    a.target.localeCompare(b.target) || b.chunks - a.chunks || a.category.localeCompare(b.category)
  );
  const samplesByCategory = Object.fromEntries([...new Set(classified.map((row) => row.category))]
    .sort()
    .map((category) => [category, classified.filter((row) => row.category === category).slice(0, 12)]));

  const lines = [
    "# Residual Tiny Chunk Audit",
    "",
    `- Generated at: ${generatedAt}`,
    `- Max length: <${maxLength}`,
    `- Total tiny chunks: ${fmt(classified.length)}`,
    "",
    "## Summary",
    "",
    mdTable(summary, [
      { key: "target", label: "Target" },
      { key: "category", label: "Category" },
      { key: "chunks", label: "Chunks", align: "right", format: "number" },
      { key: "action", label: "Recommended action" },
    ]),
    "",
    "## Samples",
    "",
  ];
  for (const [category, samples] of Object.entries(samplesByCategory)) {
    lines.push(`### ${category}`);
    lines.push("");
    lines.push(mdTable(samples, [
      { key: "target", label: "Target" },
      { key: "chunk_id", label: "Chunk ID", align: "right" },
      { key: "title", label: "Document" },
      { key: "chunk_title", label: "Chunk title" },
      { key: "len", label: "Len", align: "right", format: "number" },
      { key: "sample", label: "Sample" },
      { key: "action", label: "Action" },
    ]));
    lines.push("");
  }

  fs.writeFileSync(outPath, `${lines.join("\n")}\n`, "utf8");
  fs.writeFileSync(jsonPath, JSON.stringify({ generatedAt, maxLength, total: classified.length, summary, samplesByCategory, rows: classified }, null, 2), "utf8");
  console.log(outPath);
  console.log(jsonPath);
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
