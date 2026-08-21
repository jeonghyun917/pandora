const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const TITLE_ALIASES = new Map([
  ['국가계약법 시행령', '국가를 당사자로 하는 계약에 관한 법률 시행령'],
]);

function normalizeWhitespace(value) {
  return String(value ?? '').normalize('NFC').replace(/\s+/g, ' ').trim();
}

function canonicalTitle(value) {
  const normalized = normalizeWhitespace(value);
  const undecorated = normalized.replace(/^\([^()\r\n]{1,80}\)\s*/, '');
  return TITLE_ALIASES.get(undecorated) ?? undecorated;
}

function classifyTitleMatch(query, candidate) {
  const normalizedQuery = normalizeWhitespace(query);
  const normalizedCandidate = normalizeWhitespace(candidate);
  if (normalizedQuery === normalizedCandidate) {
    return 'exact';
  }
  const canonicalQuery = canonicalTitle(normalizedQuery);
  const canonicalCandidate = canonicalTitle(normalizedCandidate);
  if (canonicalQuery === canonicalCandidate) {
    return 'canonical';
  }
  if (canonicalCandidate.includes(canonicalQuery) || canonicalQuery.includes(canonicalCandidate)) {
    return 'text';
  }
  return 'none';
}

function sqlLiteral(value) {
  return `'${String(value ?? '').replace(/\\/g, '\\\\').replace(/'/g, "''")}'`;
}

function parseRows(output, columns) {
  return String(output ?? '')
    .trim()
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => {
      const values = line.split('\t');
      return Object.fromEntries(columns.map((column, index) => [column, values[index] ?? '']));
    });
}

function queryDocuments(queries, options) {
  const mysql = options.mysql
    ?? process.env.MARIADB_EXE
    ?? 'C:\\Program Files\\MariaDB 12.2\\bin\\mariadb.exe';
  const dbPassword = options.dbPassword ?? process.env.PANDORA_DB_PASSWORD;
  if (!dbPassword) {
    throw new Error('PANDORA_DB_PASSWORD is required for the document presence audit.');
  }
  const terms = Array.from(new Set(queries.flatMap((query) => [
    normalizeWhitespace(query),
    canonicalTitle(query),
  ]).filter(Boolean)));
  const titlePredicate = terms
    .map((term) => `doc.title LIKE CONCAT('%', ${sqlLiteral(term)}, '%')`)
    .join(' OR ');
  const sql = `
    SELECT
      doc.document_id,
      doc.target,
      doc.external_id,
      doc.title,
      COALESCE(doc.source_date, ''),
      COALESCE(doc.effective_date, ''),
      COALESCE(doc.effective_status, 'UNKNOWN'),
      doc.use_yn,
      COUNT(chunk.chunk_id),
      COALESCE(GROUP_CONCAT(DISTINCT chunk.chunk_no ORDER BY chunk.sort_order SEPARATOR '||'), '')
    FROM law_api_documents doc
    LEFT JOIN law_api_document_chunks chunk
      ON chunk.document_id = doc.document_id
      AND chunk.use_yn = 'Y'
    WHERE doc.target IN ('law', 'admrul')
      AND (${titlePredicate || '1 = 0'})
    GROUP BY
      doc.document_id, doc.target, doc.external_id, doc.title, doc.source_date,
      doc.effective_date, doc.effective_status, doc.use_yn
    ORDER BY
      CASE doc.effective_status
        WHEN 'CURRENT' THEN 0
        WHEN 'UNKNOWN' THEN 1
        WHEN 'FUTURE' THEN 2
        ELSE 3
      END,
      doc.source_date DESC,
      doc.document_id DESC
  `;
  const output = execFileSync(mysql, [
    '--ssl=0',
    '-h', options.dbHost ?? process.env.PANDORA_DB_HOST ?? 'localhost',
    '-P', String(options.dbPort ?? process.env.PANDORA_DB_PORT ?? '3306'),
    `-u${options.dbUser ?? process.env.PANDORA_DB_USER ?? 'pandora'}`,
    '--batch',
    '--raw',
    '--skip-column-names',
    '--default-character-set=utf8mb4',
    options.dbName ?? process.env.PANDORA_DB_NAME ?? 'pandora',
    '-e',
    sql,
  ], {
    encoding: 'utf8',
    windowsHide: true,
    maxBuffer: 32 * 1024 * 1024,
    env: {
      ...process.env,
      MYSQL_PWD: dbPassword,
    },
  });
  return parseRows(output, [
    'documentId',
    'target',
    'externalId',
    'title',
    'sourceDate',
    'effectiveDate',
    'effectiveStatus',
    'useYn',
    'dbChunkCount',
    'dbChunkNos',
  ]).map((row) => ({
    ...row,
    documentId: Number(row.documentId),
    dbChunkCount: Number(row.dbChunkCount),
    dbChunkNos: row.dbChunkNos ? row.dbChunkNos.split('||') : [],
    matches: queries
      .map((query) => ({ query, mode: classifyTitleMatch(query, row.title) }))
      .filter((match) => match.mode !== 'none'),
  }));
}

function qdrantDocumentFilter(title, documentId) {
  return {
    must: [
      { key: 'title', match: { value: title } },
      { key: 'documentId', match: { value: Number(documentId) } },
    ],
  };
}

async function scrollDocument(title, documentId, qdrantUrl) {
  const points = [];
  let offset = null;
  do {
    const response = await fetch(
      `${qdrantUrl}/collections/law_chunks/points/scroll`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify({
          filter: qdrantDocumentFilter(title, documentId),
          limit: 256,
          offset,
          with_payload: true,
          with_vector: false,
        }),
        signal: AbortSignal.timeout(15_000),
      },
    );
    if (!response.ok) {
      throw new Error(`Qdrant title scroll failed for "${title}": HTTP ${response.status}`);
    }
    const body = await response.json();
    const page = Array.isArray(body?.result?.points) ? body.result.points : [];
    points.push(...page);
    offset = body?.result?.next_page_offset ?? null;
  } while (offset != null);
  return points;
}

async function auditDocuments(queries, options = {}) {
  const normalizedQueries = Array.from(new Set((queries ?? []).map(normalizeWhitespace).filter(Boolean)));
  if (normalizedQueries.length === 0) {
    throw new Error('At least one non-blank title query is required.');
  }
  const dbDocuments = queryDocuments(normalizedQueries, options);
  const qdrantUrl = options.qdrantUrl ?? process.env.QDRANT_URL ?? 'http://127.0.0.1:6333';
  const qdrantDocuments = [];
  for (const document of dbDocuments) {
    const points = await scrollDocument(document.title, document.documentId, qdrantUrl);
    qdrantDocuments.push({
      documentId: document.documentId,
      target: document.target,
      title: document.title,
      effectiveStatus: document.effectiveStatus,
      sourceDate: document.sourceDate,
      matches: document.matches,
      qdrantPointCount: points.length,
      qdrantDocumentIds: Array.from(new Set(points.map((point) => Number(point?.payload?.documentId)).filter(Number.isFinite))),
      qdrantChunkNos: Array.from(new Set(points.map((point) => normalizeWhitespace(point?.payload?.chunkNo)).filter(Boolean))),
    });
  }
  return {
    generatedAt: new Date().toISOString(),
    queries: normalizedQueries,
    dbDocuments,
    qdrantDocuments,
  };
}

function parseArgs(argv) {
  const options = { queries: [], output: null };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--query') {
      options.queries.push(argv[++index] ?? '');
      continue;
    }
    if (argument.startsWith('--query=')) {
      options.queries.push(argument.slice('--query='.length));
      continue;
    }
    if (argument === '--output') {
      options.output = argv[++index] ?? '';
      continue;
    }
    if (argument.startsWith('--output=')) {
      options.output = argument.slice('--output='.length);
      continue;
    }
    throw new Error(`Unknown argument: ${argument}`);
  }
  if (options.queries.length === 0) {
    options.queries = ['국가계약법 시행령', '용역계약일반조건'];
  }
  return options;
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const report = await auditDocuments(options.queries);
  const json = `${JSON.stringify(report, null, 2)}\n`;
  if (options.output) {
    const outputPath = path.resolve(options.output);
    fs.mkdirSync(path.dirname(outputPath), { recursive: true });
    fs.writeFileSync(outputPath, json, 'utf8');
    console.log(`[rag-document-presence-audit] wrote ${outputPath}`);
    return;
  }
  process.stdout.write(json);
}

module.exports = {
  TITLE_ALIASES,
  auditDocuments,
  canonicalTitle,
  classifyTitleMatch,
  parseArgs,
  qdrantDocumentFilter,
};

if (require.main === module) {
  main().catch((error) => {
    console.error(`[rag-document-presence-audit] ${error?.message ?? error}`);
    process.exitCode = 1;
  });
}
