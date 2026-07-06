const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const workspace = path.resolve(__dirname, "..");
const mysql = process.env.MARIADB_EXE || "C:\\Program Files\\MariaDB 12.2\\bin\\mariadb.exe";
const outputPath = path.resolve(workspace, "src", "main", "resources", "rag-evaluation-cases.generated.tsv");
const targetCount = Number(process.env.RAG_EVAL_GENERATED_TARGET || 859);
const queryLimit = Math.max(1200, targetCount * 3);
const model = process.env.EMBEDDING_MODEL || "text-embedding-3-small";
const lawStore = process.env.LAW_VECTOR_STORE || "law_chunks";
const ragStore = process.env.RAG_VECTOR_STORE || "rag_chunks_v4";

const HEADER = [
  "id",
  "question",
  "targets",
  "expectedTerms",
  "requiredMatches",
  "expectedTitleTerms",
  "expectedSectionTypes",
  "forbiddenTerms",
  "expectedDocumentTerms",
  "expectedPageNumbers",
  "expectedParentTerms",
  "answerDirection",
  "expectedResultMsgs",
];

function main() {
  const existingIds = new Set(readExistingIds());
  const officialRows = queryOfficialRows();
  const lawRows = queryLawRows("law");
  const admrulRows = queryLawRows("admrul");
  const officialCount = Math.ceil(targetCount * 0.36);
  const lawCount = Math.ceil(targetCount * 0.32);
  const admrulCount = Math.max(0, targetCount - officialCount - lawCount);

  const cases = [
    ...selectOfficialCases(officialRows, officialCount, existingIds),
    ...selectLawCases(lawRows, lawCount, existingIds),
    ...selectLawCases(admrulRows, admrulCount, existingIds),
  ].slice(0, targetCount);

  if (cases.length < targetCount) {
    throw new Error(`Only generated ${cases.length}/${targetCount} cases. Check indexed corpus coverage.`);
  }

  const body = [
    `# ${HEADER.join("\t")}`,
    ...cases.map((row) => HEADER.map((key) => safeCell(row[key])).join("\t")),
    "",
  ].join("\n");
  fs.writeFileSync(outputPath, body, "utf8");
  console.log(outputPath);
  console.log(`generated=${cases.length}`);
}

function readExistingIds() {
  const files = [
    path.resolve(workspace, "src", "main", "resources", "rag-evaluation-cases.tsv"),
  ];
  const ids = [];
  for (const file of files) {
    if (!fs.existsSync(file)) {
      continue;
    }
    const lines = fs.readFileSync(file, "utf8").split(/\r?\n/);
    for (const line of lines) {
      if (!line.trim() || line.startsWith("#")) {
        continue;
      }
      const id = line.split("\t")[0]?.trim();
      if (id) {
        ids.push(id);
      }
    }
  }
  return ids;
}

function queryOfficialRows() {
  return query(`
SELECT
  'official_doc' AS target,
  COALESCE(d.source_org,'') AS source_org,
  d.title,
  c.chunk_id,
  COALESCE(c.chunk_title,'') AS chunk_title,
  COALESCE(c.parent_section_title,'') AS parent_title,
  COALESCE(c.section_type,'') AS section_type,
  COALESCE(c.page_no,'') AS page_no,
  LEFT(REPLACE(REPLACE(REPLACE(c.chunk_text, CHAR(9), ' '), CHAR(10), ' '), CHAR(13), ' '), 700) AS chunk_text
FROM rag_document_chunks c
JOIN rag_documents d ON d.document_id=c.document_id
JOIN rag_chunk_embeddings e
  ON e.chunk_id=c.chunk_id
 AND e.embedding_model='${sql(model)}'
 AND e.vector_store='${sql(ragStore)}'
 AND e.status='INDEXED'
WHERE d.use_yn='Y'
  AND c.use_yn='Y'
  AND d.document_type='official_doc'
  AND d.source_org IS NOT NULL
  AND TRIM(d.source_org) <> ''
  AND CHAR_LENGTH(c.chunk_text) BETWEEN 180 AND 1700
  AND c.chunk_text NOT LIKE '%©%'
  AND LOWER(c.chunk_text) NOT LIKE '%copyright%'
  AND LOWER(c.chunk_text) NOT LIKE '%all rights reserved%'
  AND LOWER(c.chunk_text) NOT LIKE '%you must cite%'
  AND c.chunk_version=(
    SELECT MAX(c2.chunk_version)
    FROM rag_document_chunks c2
    WHERE c2.document_id=c.document_id
      AND c2.use_yn='Y'
  )
ORDER BY COALESCE(d.source_org,''), COALESCE(c.section_type,''), c.chunk_id
LIMIT ${queryLimit};
`, ["target", "source_org", "title", "chunk_id", "chunk_title", "parent_title", "section_type", "page_no", "chunk_text"]);
}

function queryLawRows(target) {
  return query(`
SELECT
  d.target,
  COALESCE(d.agency_name,'') AS source_org,
  d.title,
  c.chunk_id,
  COALESCE(c.chunk_title,'') AS chunk_title,
  COALESCE(c.chunk_no,'') AS parent_title,
  COALESCE(c.chunk_type,'') AS section_type,
  '' AS page_no,
  LEFT(REPLACE(REPLACE(REPLACE(c.chunk_text, CHAR(9), ' '), CHAR(10), ' '), CHAR(13), ' '), 700) AS chunk_text
FROM law_api_document_chunks c
JOIN law_api_documents d ON d.document_id=c.document_id
JOIN law_api_chunk_embeddings e
  ON e.chunk_id=c.chunk_id
 AND e.embedding_model='${sql(model)}'
 AND e.vector_store='${sql(lawStore)}'
 AND e.status='INDEXED'
WHERE d.use_yn='Y'
  AND c.use_yn='Y'
  AND d.target='${sql(target)}'
  AND CHAR_LENGTH(c.chunk_text) BETWEEN 180 AND 2500
ORDER BY d.target, d.agency_name, c.chunk_id
LIMIT ${queryLimit};
`, ["target", "source_org", "title", "chunk_id", "chunk_title", "parent_title", "section_type", "page_no", "chunk_text"]);
}

function selectOfficialCases(rows, count, existingIds) {
	return roundRobin(rows, (row) => `${row.source_org || "(none)"}:${row.title || row.chunk_id}`, Math.min(rows.length, count * 3))
		.map((row) => toEvalCase(row, existingIds))
		.filter(Boolean)
		.slice(0, count);
}

function selectLawCases(rows, count, existingIds) {
	return roundRobin(rows, (row) => `${row.source_org || row.target}:${row.title || row.chunk_id}`, Math.min(rows.length, count * 3))
		.map((row) => toEvalCase(row, existingIds))
		.filter(Boolean)
		.slice(0, count);
}

function toEvalCase(row, existingIds) {
	const target = row.target;
	if (target === "official_doc" && !containsHangul(`${row.title} ${row.chunk_title} ${row.parent_title} ${row.chunk_text}`)) {
		return null;
	}
	if (target === "official_doc" && cleanText(row.title).length > 120) {
		return null;
	}
	const prefix = target === "official_doc" ? "gen-official" : `gen-${target}`;
	const id = uniqueId(`${prefix}-${row.chunk_id}`, existingIds);
	const titleTerms = pickTerms(row.title, 2);
	if (!titleTerms.length || (target !== "official_doc" && titleTerms.length < 2)) {
		return null;
	}
	const sectionTerms = pickTerms(`${row.chunk_title} ${row.parent_title}`, 2);
	const bodyTerms = pickBodyTerms(row.chunk_text, 4, [...titleTerms, ...sectionTerms]);
	const expectedTerms = unique([
		...bodyTerms.slice(0, 3),
		...sectionTerms.slice(0, 1),
		...titleTerms.slice(0, 1),
	]).slice(0, 5);
	if (expectedTerms.length < 2) {
		return null;
	}
	const source = cleanText(row.source_org || (target === "official_doc" ? "공식문서" : "국가법령정보센터"));
	const title = cleanText(row.title);
	const sourcePrefix = target === "official_doc" && containsHangul(source) ? `${source} ` : "";
	const sectionLabel = sectionTerms.length ? sectionTerms.join(" ") : expectedSectionType(row.section_type);
	const evidenceLabel = bodyTerms.slice(0, 2).join(" ");
	const sectionPrompt = sectionLabel && sectionLabel !== title
		? ` ${sectionLabel} 관련`
		: "";
	const evidencePrompt = evidenceLabel
		? ` ${evidenceLabel} 관련`
		: sectionPrompt;
	const question = target === "official_doc"
		? `${sourcePrefix}${title} 문서에서${evidencePrompt} 본문 근거를 찾아줘`
		: `${title}에서${evidencePrompt} 조항 근거를 알려줘`;
	return {
		id,
		question,
		targets: target,
		expectedTerms: expectedTerms.join("|"),
		requiredMatches: Math.min(2, expectedTerms.length),
		expectedTitleTerms: target === "official_doc" ? titleTerms.join("|") : "",
		expectedSectionTypes: "",
		forbiddenTerms: "",
		expectedDocumentTerms: titleTerms.join("|"),
    expectedPageNumbers: "",
    expectedParentTerms: "",
		answerDirection: "자동 생성 coverage 평가: 질문에 포함된 본문 핵심어와 문서 제목이 함께 맞는 근거를 사용해야 한다",
		expectedResultMsgs: "",
	};
}

function roundRobin(rows, keyFn, count) {
  const groups = new Map();
  for (const row of rows) {
    const key = keyFn(row);
    if (!groups.has(key)) {
      groups.set(key, []);
    }
    groups.get(key).push(row);
  }
  const buckets = Array.from(groups.values()).filter((bucket) => bucket.length);
  const selected = [];
  let cursor = 0;
  while (selected.length < count && buckets.length) {
    const bucket = buckets[cursor % buckets.length];
    const row = bucket.shift();
    if (row) {
      selected.push(row);
    }
    if (!bucket.length) {
      buckets.splice(cursor % buckets.length, 1);
      cursor = 0;
    } else {
      cursor += 1;
    }
  }
  return selected;
}

function query(sqlText, columns) {
  const result = spawnSync(mysql, [
    "--default-character-set=utf8mb4",
    "-uroot",
    "pandora",
    "--batch",
    "--raw",
    "--skip-column-names",
    "-e",
    sqlText,
  ], {
    cwd: workspace,
    encoding: "utf8",
    maxBuffer: 128 * 1024 * 1024,
  });
  if (result.status !== 0) {
    throw new Error(result.stderr || result.stdout || `mariadb exited ${result.status}`);
  }
  return result.stdout
    .split(/\r?\n/)
    .filter((line) => line.trim())
    .map((line) => {
      const values = line.split("\t");
      return Object.fromEntries(columns.map((column, index) => [column, values[index] ?? ""]));
    });
}

function pickTerms(value, limit) {
  const text = cleanText(value)
    .replace(/[._()[\]{}<>「」『』【】]+/g, " ")
    .replace(/[-–—]+/g, " ");
  const matches = text.match(/[\p{L}\p{N}][\p{L}\p{N}·ㆍ\-_()]{1,}/gu) || [];
  return unique(matches
    .map((term) => term.replace(/^[\d._\-()]+|[\d._\-()]+$/g, ""))
    .map(normalizeKoreanTerm)
    .filter(isUsefulTerm))
    .slice(0, limit);
}

function pickBodyTerms(value, limit, exclusions = []) {
  const excluded = new Set(exclusions.map((term) => term.toLowerCase()));
  return pickTerms(value, limit * 4)
    .filter((term) => !excluded.has(term.toLowerCase()))
    .filter((term) => term.length >= 3 || /[A-Z]{2,}/.test(term))
    .slice(0, limit);
}

function expectedSectionType(value) {
  const sectionType = cleanText(value);
  if (!sectionType || sectionType === "body" || sectionType === "toc") {
    return "";
  }
  return sectionType;
}

function containsHangul(value) {
	return /[가-힣]/.test(cleanText(value));
}

function isUsefulTerm(term) {
	if (!term || term.length < 2 || /^\d+$/.test(term)) {
		return false;
	}
	const lower = term.toLowerCase();
	if (STOP_TERMS.has(term) || STOP_TERMS.has(lower)) {
		return false;
	}
	if (/^[a-z]{1,3}$/i.test(term) && !/^[A-Z]{2,}$/.test(term)) {
		return false;
	}
	return true;
}

function normalizeKoreanTerm(term) {
	const value = String(term ?? "")
		.replace(/(으로서|으로써|에게서|에서|에게|부터|까지|으로|로서|로써|에는|에게는|에서는|와|과|은|는|이|가|을|를|에|도|만)$/u, "")
		.trim();
	if (value.length > 3 && !value.endsWith("정의")) {
		return value.replace(/의$/u, "").trim();
	}
	return value;
}

const STOP_TERMS = new Set([
  "있다",
  "있는",
  "한다",
  "대한",
  "관련",
  "문서",
  "근거",
  "경우",
  "통해",
  "위한",
	"및",
	"등",
	"제",
	"장",
	"절",
	"항",
	"표",
	"그림",
	"내용",
	"주요",
	"페이지",
	"사업",
	"관리",
	"추진",
	"활용",
	"the",
	"and",
	"on",
	"of",
	"to",
	"in",
	"or",
	"as",
	"by",
	"is",
	"be",
	"it",
	"its",
	"into",
	"their",
	"them",
	"they",
	"these",
	"those",
	"such",
	"may",
	"more",
	"most",
	"over",
	"under",
	"for",
	"with",
	"from",
	"this",
	"that",
	"was",
	"were",
	"are",
	"you",
	"your",
	"must",
	"work",
	"original",
	"following",
	"approved",
	"declassified",
	"identify",
	"changes",
	"add",
	"cite",
	"overall",
	"reported",
	"include",
	"includes",
	"included",
	"approximately",
	"however",
	"where",
	"when",
	"which",
	"well",
]);

function unique(values) {
  const seen = new Set();
  const result = [];
  for (const value of values) {
    const key = value.toLowerCase();
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    result.push(value);
  }
  return result;
}

function uniqueId(baseId, existingIds) {
  let id = baseId;
  let suffix = 2;
  while (existingIds.has(id)) {
    id = `${baseId}-${suffix}`;
    suffix += 1;
  }
  existingIds.add(id);
  return id;
}

function cleanText(value) {
  return String(value ?? "")
    .replace(/[\t\r\n]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function safeCell(value) {
  const text = cleanText(value);
  return text || "-";
}

function sql(value) {
  return String(value ?? "").replace(/'/g, "''");
}

main();
