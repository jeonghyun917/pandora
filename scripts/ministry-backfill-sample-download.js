const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const DB_EXE = process.env.MARIADB_EXE || 'C:\\Program Files\\MariaDB 12.2\\bin\\mariadb.exe';
const DB_ARGS = ['--ssl=0', '-upandora', '-ppandora', '--batch', '--raw', '--skip-column-names', 'pandora'];
const BASE_URL = process.env.PANDORA_BASE_URL || 'http://localhost:18080';
const USER_AGENT = 'PandoraMinistryBackfillSample/1.0';
const LIMIT = Number(process.env.SAMPLE_LIMIT || process.argv.find((arg) => arg.startsWith('--limit='))?.split('=')[1] || 10);
const AGENCY = String(process.env.SAMPLE_AGENCY || process.argv.find((arg) => arg.startsWith('--agency='))?.split('=')[1] || 'ALL').toUpperCase();
const MODE = String(process.env.BACKFILL_MODE || process.argv.find((arg) => arg.startsWith('--mode='))?.split('=')[1] || 'sample').toLowerCase();
const STATUSES = csvArg('BACKFILL_STATUSES', '--statuses=', 'DISCOVERED');
const EXTENSIONS = csvArg('BACKFILL_EXTENSIONS', '--extensions=', '.pdf,.hwpx,.docx');
const DOCUMENT_CATEGORY = MODE === 'limited' ? 'ministry_doc_backfill_limited' : 'ministry_doc_backfill_sample';
const ROOT = path.resolve('data', 'rag-upload', MODE === 'limited' ? 'ministry_backfill_limited' : 'ministry_backfill_sample');
const REPORT_PATH = path.resolve('logs', MODE === 'limited' ? 'ministry-backfill-limited-latest.md' : 'ministry-backfill-sample-latest.md');
const LOG_PATH = path.resolve('logs', MODE === 'limited' ? 'ministry-backfill-limited.log' : 'ministry-backfill-sample.log');
const SOFFICE_CANDIDATES = [
  process.env.SOFFICE_EXE,
  'C:\\Program Files\\LibreOffice\\program\\soffice.exe',
  'C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe',
  'soffice',
].filter(Boolean);

function csvArg(envName, prefix, fallback) {
  const raw = process.env[envName] || process.argv.find((arg) => arg.startsWith(prefix))?.slice(prefix.length) || fallback;
  return raw.split(',').map((item) => item.trim()).filter(Boolean);
}

function normalizeExtension(extension) {
  const value = String(extension || '').trim().toLowerCase();
  if (!value) return '';
  return value.startsWith('.') ? value : `.${value}`;
}

function log(line) {
  fs.mkdirSync(path.dirname(LOG_PATH), { recursive: true });
  fs.appendFileSync(LOG_PATH, `${new Date().toISOString()} ${line}\n`, 'utf8');
}

function db(sql) {
  const result = spawnSync(DB_EXE, DB_ARGS, {
    input: sql,
    encoding: 'utf8',
    windowsHide: true,
    maxBuffer: 20 * 1024 * 1024,
  });
  if (result.status !== 0) {
    throw new Error((result.stderr || result.stdout || '').trim());
  }
  return (result.stdout || '').trim();
}

function q(value) {
  if (value === null || value === undefined) return 'NULL';
  return `'${String(value).replace(/\\/g, '\\\\').replace(/'/g, "''")}'`;
}

function safeFileName(value) {
  const sanitized = String(value || '').replace(/[\\/:*?"<>|]/g, '_').replace(/\s+/g, ' ').trim();
  return (sanitized || 'attachment').slice(0, 180);
}

function decodeHtml(value) {
  return String(value || '')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&#039;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&nbsp;/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function sha256(bytes) {
  return crypto.createHash('sha256').update(bytes).digest('hex');
}

function findSoffice() {
  for (const candidate of SOFFICE_CANDIDATES) {
    if (candidate === 'soffice') return candidate;
    if (fs.existsSync(candidate)) return candidate;
  }
  return null;
}

function stripExtension(filePath) {
  return filePath.replace(/\.[^.\\/]+$/, '');
}

function convertHwpToPdf(filePath) {
  const soffice = findSoffice();
  if (!soffice) {
    throw new Error('LibreOffice soffice executable was not found for HWP conversion.');
  }
  const outputDir = path.dirname(filePath);
  const result = spawnSync(soffice, [
    '--headless',
    '--convert-to',
    'pdf',
    '--outdir',
    outputDir,
    filePath,
  ], {
    encoding: 'utf8',
    windowsHide: true,
    timeout: 180000,
  });
  if (result.status !== 0) {
    throw new Error((result.stderr || result.stdout || 'LibreOffice HWP conversion failed.').trim());
  }
  const pdfPath = `${stripExtension(filePath)}.pdf`;
  if (!fs.existsSync(pdfPath) || fs.statSync(pdfPath).size === 0) {
    throw new Error(`Converted PDF was not created: ${pdfPath}`);
  }
  return pdfPath;
}

function parseRows(output) {
  if (!output) return [];
  return output.split(/\r?\n/).filter(Boolean).map((line) => {
    const [attachmentId, url, fileName, extension, articleId, title, articleUrl, agencyCode, agencyName, sourceKey] = line.split('\t');
    return { attachmentId, url, fileName, extension, articleId, title, articleUrl, agencyCode, agencyName, sourceKey };
  });
}

function selectCandidates() {
  const agencyFilter = AGENCY === 'ALL' ? '' : `AND s.agency_code=${q(AGENCY)}`;
  const statusList = STATUSES.map((status) => q(status.toUpperCase())).join(', ');
  const extensionList = EXTENSIONS.map(normalizeExtension).filter(Boolean).map(q).join(', ');
  return parseRows(db(`
SELECT
  att.attachment_id,
  att.url,
  att.file_name,
  att.extension,
  a.article_id,
  a.title,
  a.link,
  s.agency_code,
  s.agency_name,
  s.source_key
FROM rag_source_attachments att
JOIN rag_source_articles a ON a.article_id=att.article_id
JOIN rag_collection_sources s ON s.source_id=a.source_id
WHERE s.source_type='BACKFILL'
  AND a.status='DISCOVERED'
  AND UPPER(att.status) IN (${statusList})
  AND LOWER(att.extension) IN (${extensionList})
  ${agencyFilter}
ORDER BY
  CASE LOWER(att.extension) WHEN '.pdf' THEN 0 WHEN '.hwpx' THEN 1 WHEN '.docx' THEN 2 WHEN '.hwp' THEN 3 ELSE 9 END,
  CASE UPPER(att.status) WHEN 'FAILED' THEN 0 ELSE 1 END,
  s.agency_code,
  a.article_id DESC,
  att.attachment_id
LIMIT ${Math.max(1, LIMIT)};
`));
}

async function download(candidate) {
  try {
    const response = await fetch(candidate.url, {
      headers: {
        'User-Agent': USER_AGENT,
        'Referer': candidate.articleUrl,
        'Accept': '*/*',
      },
    });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status} ${candidate.url}`);
    }
    return Buffer.from(await response.arrayBuffer());
  } catch (error) {
    try {
      return downloadWithCurl(candidate);
    } catch (curlError) {
      return downloadWithPowerShell(candidate, curlError);
    }
  }
}

function downloadWithCurl(candidate) {
  const tempDir = path.resolve('tmp', 'ministry-backfill-download');
  fs.mkdirSync(tempDir, { recursive: true });
  const tempFile = path.join(tempDir, `${candidate.attachmentId}-${Date.now()}${candidate.extension || '.bin'}`);
  const result = spawnSync('curl.exe', [
    '-L',
    '--fail',
    '--silent',
    '--show-error',
    '-A',
    USER_AGENT,
    '-H',
    `Referer: ${candidate.articleUrl}`,
    '-o',
    tempFile,
    candidate.url,
  ], {
    encoding: 'utf8',
    windowsHide: true,
    timeout: 120000,
  });
  if (result.status !== 0) {
    throw new Error((result.stderr || result.stdout || `curl failed for ${candidate.url}`).trim());
  }
  const bytes = fs.readFileSync(tempFile);
  fs.rmSync(tempFile, { force: true });
  return bytes;
}

function downloadWithPowerShell(candidate, originalError) {
  const tempDir = path.resolve('tmp', 'ministry-backfill-download');
  fs.mkdirSync(tempDir, { recursive: true });
  const tempFile = path.join(tempDir, `${candidate.attachmentId}-${Date.now()}${candidate.extension || '.bin'}`);
  const escapedUrl = String(candidate.url).replace(/'/g, "''");
  const escapedReferer = String(candidate.articleUrl || '').replace(/'/g, "''");
  const escapedTempFile = tempFile.replace(/'/g, "''");
  const script = [
    '$headers = @{ "User-Agent" = "Mozilla/5.0"; "Accept" = "*/*" }',
    escapedReferer ? `$headers["Referer"] = '${escapedReferer}'` : '',
    `Invoke-WebRequest -UseBasicParsing -Uri '${escapedUrl}' -Headers $headers -TimeoutSec 120 -OutFile '${escapedTempFile}'`,
  ].filter(Boolean).join('; ');
  const encodedCommand = Buffer.from(script, 'utf16le').toString('base64');
  const result = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', encodedCommand], {
    encoding: 'utf8',
    windowsHide: true,
    timeout: 180000,
  });
  if (result.status !== 0) {
    fs.rmSync(tempFile, { force: true });
    const message = (result.stderr || result.stdout || '').trim();
    throw new Error(`${originalError.message}; PowerShell fallback failed: ${message}`);
  }
  const bytes = fs.readFileSync(tempFile);
  fs.rmSync(tempFile, { force: true });
  return bytes;
}

function writeMeta(filePath, candidate, fileHash) {
  const base = filePath.replace(/\.[^.]+$/, '');
  const metaPath = `${base}.meta.json`;
  const meta = {
    documentType: 'official_doc',
    title: candidate.title,
    sourceOrg: candidate.agencyName,
    documentCategory: DOCUMENT_CATEGORY,
    documentTopic: `backfill ${candidate.sourceKey}`,
    publishedDate: null,
    version: fileHash.slice(0, 16),
    trustLevel: 1,
    sourceUrl: candidate.articleUrl,
  };
  meta.title = decodeHtml(meta.title);
  fs.writeFileSync(metaPath, `${JSON.stringify(meta, null, 2)}\n`, 'utf8');
}

async function importFolder(folder) {
  const url = `${BASE_URL}/api/rag-documents/import-folder?documentType=official_doc&path=${encodeURIComponent(folder)}&indexNow=false&force=false`;
  const response = await fetch(url, { method: 'POST' });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`import HTTP ${response.status}: ${text}`);
  }
  return JSON.parse(text);
}

function updateAttachment(candidate, filePath, fileHash, documentId, status, errorMessage) {
  db(`
UPDATE rag_source_attachments
SET local_path=${q(filePath)},
    file_hash=${q(fileHash)},
    document_id=${documentId ? Number(documentId) : 'document_id'},
    status=${q(status)},
    last_error_message=${errorMessage ? q(String(errorMessage).slice(0, 1000)) : 'NULL'},
    updated_at=NOW()
WHERE attachment_id=${Number(candidate.attachmentId)};
`);
}

function findDocumentId(fileHash) {
  const out = db(`SELECT document_id FROM rag_documents WHERE file_hash=${q(fileHash)} LIMIT 1;`);
  return out ? Number(out.split(/\s+/).pop()) : null;
}

function documentSummary(fileHash) {
  const out = db(`
SELECT doc.document_id, doc.title, doc.import_status, COUNT(c.chunk_id) chunks
FROM rag_documents doc
LEFT JOIN rag_document_chunks c ON c.document_id=doc.document_id AND c.use_yn='Y'
WHERE doc.file_hash=${q(fileHash)}
GROUP BY doc.document_id, doc.title, doc.import_status;
`);
  return out || '';
}

async function main() {
  log(`start limit=${LIMIT} agency=${AGENCY}`);
  const candidates = selectCandidates();
  const rows = [];
  let downloaded = 0;
  let imported = 0;
  let failed = 0;

  for (const candidate of candidates) {
    try {
      const bytes = await download(candidate);
      const fileHash = sha256(bytes);
      const folder = path.join(ROOT, candidate.agencyCode.toLowerCase(), String(candidate.articleId));
      fs.mkdirSync(folder, { recursive: true });
      const filePath = path.join(folder, safeFileName(candidate.fileName));
      fs.writeFileSync(filePath, bytes);
      let importPath = filePath;
      let importHash = fileHash;
      if (String(candidate.extension || '').toLowerCase() === '.hwp') {
        importPath = convertHwpToPdf(filePath);
        importHash = sha256(fs.readFileSync(importPath));
      }
      writeMeta(importPath, candidate, importHash);
      downloaded++;

      const response = await importFolder(path.resolve(folder));
      const documentId = findDocumentId(importHash);
      updateAttachment(candidate, path.resolve(importPath), importHash, documentId, documentId ? 'IMPORTED' : 'FAILED', response.lastErrorMessage);
      if (documentId) imported++;
      rows.push({ candidate, status: response.status, documentId, summary: documentSummary(importHash), error: response.lastErrorMessage || '' });
    } catch (error) {
      failed++;
      log(`failed attachment=${candidate.attachmentId} ${error.stack || error.message}`);
      updateAttachment(candidate, null, null, null, 'FAILED', error.message);
      rows.push({ candidate, status: 'FAILED', documentId: null, summary: '', error: error.message });
    }
  }

  const latest = new Date().toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', hour12: false });
  const lines = [
    'ministry backfill sample download report.',
    '',
    `Latest status time: ${latest}`,
    `Mode: ${MODE}`,
    `Agency: ${AGENCY}`,
    `Statuses: ${STATUSES.join(', ')}`,
    `Extensions: ${EXTENSIONS.join(', ')}`,
    `Document category: ${DOCUMENT_CATEGORY}`,
    `Limit: ${LIMIT}`,
    `Downloaded: ${downloaded}`,
    `Imported/chunked: ${imported}`,
    `Failed: ${failed}`,
    '',
    '| Agency | Attachment | Import | Document | Summary/Error |',
    '| --- | --- | --- | ---: | --- |',
    ...rows.map((row) => `| ${row.candidate.agencyCode} | ${row.candidate.fileName.replaceAll('|', '/')} | ${row.status} | ${row.documentId || ''} | ${(row.summary || row.error || '').replaceAll('\n', ' / ').replaceAll('|', '/').slice(0, 500)} |`),
  ];
  fs.mkdirSync(path.dirname(REPORT_PATH), { recursive: true });
  fs.writeFileSync(REPORT_PATH, `${lines.join('\n')}\n`, 'utf8');
  log('done');
}

main().catch((error) => {
  log(`fatal ${error.stack || error.message}`);
  process.exitCode = 1;
});
