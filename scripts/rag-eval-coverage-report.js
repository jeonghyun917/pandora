const fs = require("node:fs");
const path = require("node:path");

const workspace = path.resolve(__dirname, "..");
const casePaths = [
  path.resolve(workspace, "src", "main", "resources", "rag-evaluation-cases.tsv"),
  path.resolve(workspace, "src", "main", "resources", "rag-evaluation-cases.generated.tsv"),
];
const gatePath = process.env.RAG_EVAL_GATE_JSON
  ? path.resolve(workspace, process.env.RAG_EVAL_GATE_JSON)
  : resolveLatestEvalGatePath(path.resolve(workspace, "logs", "rag-eval-gate-latest.json"));
const outPath = path.resolve(workspace, "logs", "rag-eval-coverage-latest.md");
const jsonPath = path.resolve(workspace, "logs", "rag-eval-coverage-latest.json");

function parseTsvFiles(filePaths) {
  const byId = new Map();
  for (const filePath of filePaths) {
    if (!fs.existsSync(filePath)) {
      continue;
    }
    for (const row of parseTsv(filePath)) {
      byId.set(row.id, row);
    }
  }
  return Array.from(byId.values());
}

function parseTsv(filePath) {
  const lines = fs.readFileSync(filePath, "utf8")
    .split(/\r?\n/)
    .filter((line) => line.trim() && !line.startsWith("//"));
  const headerLine = lines.shift();
  if (!headerLine) {
    return [];
  }
  const header = headerLine
    .replace(/^#\s*/, "")
    .split("\t");
  return lines.map((line) => {
    const values = line.split("\t");
    return Object.fromEntries(header.map((name, index) => [name, values[index] ?? ""]));
  });
}

function readGate() {
  try {
    return JSON.parse(fs.readFileSync(gatePath, "utf8"));
  } catch {
    return null;
  }
}

function readJsonIfExists(filePath) {
  try {
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
  } catch {
    return null;
  }
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

function split(value, delimiter = "|") {
  return String(value ?? "")
    .split(delimiter)
    .map((item) => item.trim())
    .filter(Boolean)
    .filter((item) => item !== "-");
}

function domainOf(id) {
  const rules = [
    ["project-review", "공공SW 과업심의"],
    ["pre-consultation", "정보화사업 사전협의"],
    ["security-review", "보안성 검토"],
    ["rfp", "제안요청서"],
    ["public-data", "공공데이터"],
    ["procurement", "조달/디지털서비스"],
    ["commercial-sw", "상용SW 직접구매"],
    ["performance", "IRM/성과측정"],
    ["irm", "IRM/정보자원관리"],
    ["whistleblower", "공익신고자"],
    ["traffic", "도로교통"],
    ["ai", "인공지능 법령"],
    ["ai-law", "인공지능 법령"],
    ["video-cctv", "영상정보/CCTV"],
    ["cctv", "영상정보/CCTV"],
    ["personal-info", "개인정보보호"],
    ["privacy", "개인정보보호"],
    ["pseudonym", "개인정보보호"],
    ["law-effective", "현행 법령"],
    ["admin-rule", "행정규칙"],
    ["admrul", "행정규칙"],
    ["official-find", "공식문서 찾기"],
    ["official-doc", "공식문서 찾기"],
    ["mois", "행안부 공식문서"],
    ["mcst", "문체부 공식문서"],
    ["msit", "과기정통부 공식문서"],
    ["no-qdrant", "운영정보 차단"],
    ["no-openai", "운영정보 차단"],
    ["no-nonexistent", "오답 방지"],
    ["no-system", "운영정보 차단"],
    ["no-unrelated", "오답 방지"],
    ["no-made-up", "오답 방지"],
    ["no-current", "오답 방지"],
  ];
  return rules.find(([prefix]) => id.startsWith(prefix))?.[1] ?? "기타";
}

function countBy(rows, getter) {
  const map = new Map();
  for (const row of rows) {
    const values = getter(row);
    for (const value of Array.isArray(values) ? values : [values]) {
      const key = value || "(none)";
      map.set(key, (map.get(key) ?? 0) + 1);
    }
  }
  return Array.from(map, ([key, count]) => ({ key, count }))
    .sort((a, b) => b.count - a.count || a.key.localeCompare(b.key));
}

function mdTable(rows, columns) {
  if (!rows.length) {
    return "_없음_";
  }
  const header = `| ${columns.map((column) => column.label).join(" | ")} |`;
  const sep = `| ${columns.map((column) => column.align === "right" ? "---:" : "---").join(" | ")} |`;
  const body = rows.map((row) => `| ${columns.map((column) => {
    const value = String(row[column.key] ?? "");
    return value.replace(/\r?\n/g, " ").replace(/\|/g, "\\|");
  }).join(" | ")} |`);
  return [header, sep, ...body].join("\n");
}

function targetRisk(rows) {
  const byTarget = countBy(rows, (row) => split(row.targets));
  const targets = Object.fromEntries(byTarget.map((row) => [row.key, row.count]));
  const risks = [];
  for (const target of ["law", "admrul", "official_doc", "internal_doc"]) {
    if ((targets[target] ?? 0) < 8) {
      risks.push(`${target} 단독 또는 주요 포함 케이스가 부족합니다.`);
    }
  }
  return risks;
}

function domainRisk(rows) {
  const byDomain = countBy(rows, (row) => domainOf(row.id));
  return byDomain
    .filter((row) => row.count < 2)
    .map((row) => `${row.key} 도메인은 ${row.count}건뿐이라 회귀 탐지가 약합니다.`);
}

function sourceOrgRisk(rows) {
  const required = [
    { label: "개인정보보호위원회", aliases: ["개인정보보호위원회", "개보위", "PIPC"] },
    { label: "공공데이터포털", aliases: ["공공데이터포털", "data.go.kr"] },
    { label: "Ministry of the Interior and Safety", aliases: ["Ministry of the Interior and Safety", "행정안전부", "행안부", "MOIS"] },
    { label: "Ministry of Culture, Sports and Tourism", aliases: ["Ministry of Culture, Sports and Tourism", "문화체육관광부", "문체부", "MCST"] },
    { label: "Ministry of Science and ICT", aliases: ["Ministry of Science and ICT", "과학기술정보통신부", "과기정통부", "MSIT"] },
  ];
  const text = rows.map((row) => `${row.expectedDocumentTerms} ${row.expectedTitleTerms} ${row.question}`).join("\n");
  return required
    .filter((source) => !source.aliases.some((alias) => text.includes(alias)))
    .map((source) => `${source.label} 출처를 직접 겨냥한 평가 케이스가 부족합니다.`);
}

function main() {
  const rows = parseTsvFiles(casePaths);
  const gate = readGate();
  const resultById = new Map((gate?.results ?? []).map((result) => [result.id, result]));
  const withResult = rows.map((row) => ({
    ...row,
    domain: domainOf(row.id),
    passed: resultById.get(row.id)?.passed ?? null,
  }));
  const domainRows = countBy(withResult, (row) => row.domain).map((row) => ({
    domain: row.key,
    cases: row.count,
  }));
  const targetRows = countBy(withResult, (row) => split(row.targets)).map((row) => ({
    target: row.key,
    cases: row.count,
  }));
  const sectionRows = countBy(withResult, (row) => split(row.expectedSectionTypes)).map((row) => ({
    sectionType: row.key,
    cases: row.count,
  }));
  const passRows = [
    {
      metric: "평가 케이스",
      value: String(rows.length),
      target: "1차 50+, 안정화 100+",
    },
    {
      metric: "최근 게이트",
      value: gate ? `${gate.passed}/${gate.total} (${gate.gatePassed ? "PASS" : "FAIL"})` : "없음",
      target: "항상 PASS",
    },
  ];
  const risks = [
    ...targetRisk(withResult),
    ...domainRisk(withResult),
    ...sourceOrgRisk(withResult),
  ];
  const result = {
    generatedAt: new Date().toISOString(),
    caseCount: rows.length,
    gateFile: path.relative(workspace, gatePath).replace(/\\/g, "/"),
    gate: gate ? {
      total: gate.total,
      passed: gate.passed,
      failed: gate.failed,
      gatePassed: gate.gatePassed,
    } : null,
    domains: domainRows,
    targets: targetRows,
    sectionTypes: sectionRows,
    risks,
  };
  const markdown = [
    "# RAG Evaluation Coverage",
    "",
    `- Generated at: ${result.generatedAt}`,
    `- Gate file: ${result.gateFile}`,
    "",
    "## Summary",
    "",
    mdTable(passRows, [
      { key: "metric", label: "Metric" },
      { key: "value", label: "Current", align: "right" },
      { key: "target", label: "Target" },
    ]),
    "",
    "## Domain Coverage",
    "",
    mdTable(domainRows, [
      { key: "domain", label: "Domain" },
      { key: "cases", label: "Cases", align: "right" },
    ]),
    "",
    "## Target Coverage",
    "",
    mdTable(targetRows, [
      { key: "target", label: "Target" },
      { key: "cases", label: "Cases", align: "right" },
    ]),
    "",
    "## Section Coverage",
    "",
    mdTable(sectionRows, [
      { key: "sectionType", label: "Expected section" },
      { key: "cases", label: "Cases", align: "right" },
    ]),
    "",
    "## Coverage Risks",
    "",
    risks.length ? risks.map((risk) => `- ${risk}`).join("\n") : "_없음_",
    "",
    "## Next Expansion Targets",
    "",
    "- 법령 단독 질문: 시행일, 현행/폐지, 조문 정의, 조문별 의무/예외를 각각 추가합니다.",
    "- 행정규칙 단독 질문: 고시/지침/예규별 적용대상, 절차, 제출기한, 예외를 추가합니다.",
    "- 공식문서 출처별 질문: 행안부, 문체부, 과기정통부, 개인정보위, 공공데이터포털을 최소 5건씩 맞춥니다.",
    "- 실패 방지 질문: 문서가 없거나 직접근거가 약한 질문에서 근거 없음으로 멈추는 케이스를 추가합니다.",
    "",
  ].join("\n");
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, markdown, "utf8");
  fs.writeFileSync(jsonPath, JSON.stringify(result, null, 2), "utf8");
  console.log(outPath);
  console.log(jsonPath);
}

main();
