const fs = require('fs');
const path = require('path');

const baseUrl = process.env.RAG_EVAL_BASE_URL || 'http://127.0.0.1:8080';
const endpoint = `${baseUrl.replace(/\/$/, '')}/api/law-data/ai/debug/evaluate/gate`;
const outputPath = process.env.RAG_EVAL_OUTPUT || 'logs/rag-eval-gate-latest.json';
const reportPath = process.env.RAG_EVAL_REPORT || 'logs/rag-eval-gate-latest.md';
const casePaths = [
  path.resolve('src/main/resources/rag-evaluation-cases.tsv'),
  path.resolve('src/main/resources/rag-evaluation-cases.generated.tsv'),
];
const maxEvaluationErrorRetries = Number(process.env.RAG_EVAL_ERROR_RETRIES || 3);
const caseBatchSize = Number(process.env.RAG_EVAL_CASE_BATCH_SIZE || 10);
const requestTimeoutMs = Number(process.env.RAG_EVAL_REQUEST_TIMEOUT_MS || 180000);
const interBatchSleepMs = Number(process.env.RAG_EVAL_INTER_BATCH_SLEEP_MS || 300);
const caseLimit = Number(process.env.RAG_EVAL_CASE_LIMIT || 0);
const caseIds = splitCaseIds(process.env.RAG_EVAL_CASE_IDS || '');
const checkpointPath = process.env.RAG_EVAL_CHECKPOINT || 'logs/rag-eval-gate-checkpoint.json';
const resumeFromCheckpoint = ['1', 'true', 'yes', 'y'].includes(
  String(process.env.RAG_EVAL_RESUME || '').trim().toLowerCase(),
);

async function main() {
  const cases = selectCases(loadCases());
  let body = await runEvaluationForCases(cases);
  body = await retryEvaluationErrors(body);
  writeJson(outputPath, body);
  writeReport(reportPath, body, baseUrl);
  if (!body?.gatePassed) {
    const passed = body?.passed ?? 0;
    const total = body?.total ?? 0;
    const blocking = body?.blockingFailureIds ?? [];
    console.error(`[rag-eval-gate] FAIL ${passed}/${total}`);
    for (const id of blocking) {
      console.error(`- ${id}`);
    }
    process.exit(1);
  }
  const percent = Math.round((body.passRate ?? 0) * 100);
  console.log(`[rag-eval-gate] PASS ${body.passed}/${body.total} (${percent}%)`);
}

async function runEvaluationForCases(cases) {
  if (!cases.length || caseBatchSize <= 0 || cases.length <= caseBatchSize) {
    return runEvaluation(cases.length ? { cases } : {}, "all");
  }
  const batches = chunk(cases, caseBatchSize);
  const selectedIds = new Set(cases.map((item) => item.id));
  const checkpoint = resumeFromCheckpoint ? readJson(checkpointPath) : null;
  const results = Array.isArray(checkpoint?.results)
    ? checkpoint.results.filter((result) => selectedIds.has(result.id))
    : [];
  const completedIds = new Set(results.map((result) => result.id));
  const attempts = [];
  for (let index = 0; index < batches.length; index += 1) {
    const batch = batches[index].filter((item) => !completedIds.has(item.id));
    const batchLabel = `batch ${index + 1}/${batches.length}`;
    if (batch.length === 0) {
      console.log(`[rag-eval-gate] skipping ${batchLabel} from checkpoint`);
      continue;
    }
    console.log(`[rag-eval-gate] running ${batchLabel} (${batch.length} cases)`);
    const body = await runEvaluation({ cases: batch }, batchLabel);
    attempts.push({
      batch: index + 1,
      total: body.total ?? batch.length,
      passed: body.passed ?? 0,
      failed: body.failed ?? Math.max(0, (body.total ?? batch.length) - (body.passed ?? 0)),
    });
    for (const result of body.results ?? []) {
      results.push(result);
      completedIds.add(result.id);
    }
    writeJson(checkpointPath, recomputeGate({
      results,
      batchAttempts: attempts,
      batchSize: caseBatchSize,
      selectedCaseLimit: caseLimit > 0 ? caseLimit : null,
      selectedCaseIds: caseIds,
      checkpoint: true,
      checkpointUpdatedAt: new Date().toISOString(),
    }));
    if (index < batches.length - 1 && interBatchSleepMs > 0) {
      await sleep(interBatchSleepMs);
    }
  }
  return recomputeGate({
    results,
    batchAttempts: attempts,
    batchSize: caseBatchSize,
    selectedCaseLimit: caseLimit > 0 ? caseLimit : null,
    selectedCaseIds: caseIds,
  });
}

async function runEvaluation(payload, label = "all") {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), requestTimeoutMs);
  try {
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(payload),
      signal: controller.signal,
    });
    const text = await response.text();
    const body = parseJson(text);
    if (!response.ok) {
      if (isEvaluationResponse(body)) {
        return body;
      }
      const details = body
        ? JSON.stringify({
          status: body.status ?? response.status,
          error: body.error,
          message: body.message,
          path: body.path,
        })
        : text.slice(0, 300);
      throw new Error(`evaluation ${label} HTTP ${response.status} ${response.statusText}: ${details}`);
    }
    if (!body) {
      throw new Error(`evaluation ${label} HTTP ${response.status} ${response.statusText}: ${text.slice(0, 300)}`);
    }
    return body;
  } catch (error) {
    if (error?.name === "AbortError") {
      throw new Error(`evaluation ${label} timed out after ${requestTimeoutMs}ms`);
    }
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

async function retryEvaluationErrors(body) {
  if (!body?.results?.length || maxEvaluationErrorRetries <= 0) {
    return recomputeGate(body);
  }
  const casesById = new Map(loadCases().map((row) => [row.id, row]));
  let merged = body;
  const attempts = [];
  for (let attempt = 1; attempt <= maxEvaluationErrorRetries; attempt += 1) {
    const retryIds = (merged.results ?? [])
      .filter((result) => !result.passed && result.resultMsg === 'EVALUATION_ERROR')
      .map((result) => result.id);
    if (retryIds.length === 0) {
      break;
    }
    const retryCases = retryIds.map((id) => casesById.get(id)).filter(Boolean);
    if (retryCases.length === 0) {
      break;
    }
    await sleep(500 * attempt);
    const retryBody = await runEvaluationForCases(retryCases);
    attempts.push({
      attempt,
      ids: retryIds,
      passed: retryBody.passed ?? 0,
      total: retryBody.total ?? retryCases.length,
    });
    merged = mergeResults(merged, retryBody.results ?? []);
  }
  return recomputeGate({ ...merged, retryAttempts: attempts });
}

function mergeResults(body, retryResults) {
  const retryById = new Map(retryResults.map((result) => [result.id, result]));
  const results = (body.results ?? []).map((result) => retryById.get(result.id) ?? result);
  return { ...body, results };
}

function recomputeGate(body) {
  const results = body?.results ?? [];
  const total = results.length;
  const passed = results.filter((result) => result.passed).length;
  const failed = total - passed;
  return {
    ...body,
    total,
    passed,
    failed,
    passRate: total === 0 ? 0 : passed / total,
    gatePassed: total > 0 && failed === 0,
    minimumPassed: total,
    blockingFailureIds: results.filter((result) => !result.passed).map((result) => result.id),
  };
}

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function readJson(filePath) {
  if (!fs.existsSync(filePath)) {
    return null;
  }
  return parseJson(fs.readFileSync(filePath, 'utf8'));
}

function isEvaluationResponse(body) {
  return Boolean(
    body
    && Array.isArray(body.results)
    && typeof body.total === 'number'
    && typeof body.passed === 'number'
    && typeof body.failed === 'number'
    && typeof body.gatePassed === 'boolean'
  );
}

function loadCases() {
  const byId = new Map();
  for (const casePath of casePaths) {
    if (!fs.existsSync(casePath)) {
      continue;
    }
    const rows = fs.readFileSync(casePath, 'utf8')
      .split(/\r?\n/)
      .filter((line) => line.trim() && !line.startsWith('#'))
      .map((line) => line.split('\t'))
      .filter((columns) => columns.length >= 8 && columns[0].trim().toLowerCase() !== 'id')
      .map((columns) => ({
        id: columns[0].trim(),
        question: columns[1].trim(),
        targets: splitList(columns[2]),
        expectedTerms: splitList(columns[3]),
        requiredMatches: parseRequiredMatches(columns[4]),
        expectedTitleTerms: splitList(columns[5]),
        expectedSectionTypes: splitList(columns[6]),
        forbiddenTerms: splitList(columns[7]),
        expectedDocumentTerms: splitList(columns[8]),
        expectedPageNumbers: splitList(columns[9]),
        expectedParentTerms: splitList(columns[10]),
        answerDirection: (columns[11] ?? '').trim(),
        expectedResultMsgs: expectedResultMsgs(columns[0], columns[12]),
        answerVerificationRequired: parseOptionalBoolean(columns[13]),
        expectedAnswerTerms: splitList(columns[14]),
        forbiddenAnswerTerms: splitList(columns[15]),
      }));
    for (const row of rows) {
      byId.set(row.id, row);
    }
  }
  return Array.from(byId.values());
}

function selectCases(cases) {
  let selected = cases;
  if (caseIds.length > 0) {
    const allowed = new Set(caseIds);
    selected = selected.filter((item) => allowed.has(item.id));
  }
  if (caseLimit > 0) {
    selected = selected.slice(0, caseLimit);
  }
  return selected;
}

function splitList(value) {
  if (!value || value.trim() === '-') {
    return [];
  }
  return value.split('|').map((term) => term.trim()).filter(Boolean);
}

function splitCaseIds(value) {
  if (!value || value.trim() === '-') {
    return [];
  }
  return value.split(/[|,]/).map((term) => term.trim()).filter(Boolean);
}

function parseRequiredMatches(value) {
  if (!value || !value.trim()) {
    return null;
  }
  const parsed = Number.parseInt(value.trim(), 10);
  return Number.isFinite(parsed) ? Math.max(0, parsed) : null;
}

function parseOptionalBoolean(value) {
  if (!value || !value.trim() || value.trim() === '-') {
    return null;
  }
  const normalized = value.trim().toLowerCase();
  if (['true', '1', 'yes', 'y'].includes(normalized)) {
    return true;
  }
  if (['false', '0', 'no', 'n'].includes(normalized)) {
    return false;
  }
  return null;
}

function expectedResultMsgs(id, value) {
  const explicit = splitList(value);
  if (explicit.length > 0) {
    return explicit;
  }
  return String(id || '').trim().startsWith('no-') ? ['NO_GROUNDS'] : [];
}

function chunk(values, size) {
  const result = [];
  for (let index = 0; index < values.length; index += size) {
    result.push(values.slice(index, index + size));
  }
  return result;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function writeJson(filePath, body) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(body, null, 2)}\n`, 'utf8');
}

function writeReport(filePath, body, baseUrl) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  const failures = (body.results ?? []).filter((result) => !result.passed);
  const diagnostics = failures.map((result) => ({
    ...diagnoseFailureReadable(result),
    id: result.id,
  }));
  const diagnosticCounts = diagnostics.reduce((counts, item) => {
    counts[item.cause] = (counts[item.cause] ?? 0) + 1;
    return counts;
  }, {});
  const rows = failures.map((result) => {
    const diagnosis = diagnoseFailureReadable(result);
    return [
      result.id,
      result.resultMsg,
      diagnosis.label,
      nextActionForCause(diagnosis.cause),
      list(result.missingTerms),
      list(result.missingTitleTerms),
      list(result.missingSectionTypes),
      list(result.missingDocumentTerms),
      list(result.missingParentTerms),
      list(result.forbiddenMatchedTerms),
      list(result.missingAnswerTerms),
      list(result.unsupportedAnswerClaims),
      selectedSummary(result),
    ];
  });
  const lines = [
    '# RAG Eval Gate',
    '',
    `- Base URL: ${baseUrl}`,
    `- Total: ${body.total ?? 0}`,
    `- Passed: ${body.passed ?? 0}`,
    `- Failed: ${body.failed ?? 0}`,
    `- Pass rate: ${Math.round((body.passRate ?? 0) * 100)}%`,
    `- Gate passed: ${Boolean(body.gatePassed)}`,
    `- Batch size: ${body.batchSize ?? 'single request'}`,
    `- Failure causes: ${Object.keys(diagnosticCounts).length ? Object.entries(diagnosticCounts).map(([cause, count]) => `${cause}=${count}`).join(', ') : '-'}`,
    '',
    '| ID | Result | Likely Cause | Next Action | Missing Terms | Missing Title | Missing Section | Missing Doc | Missing Parent | Forbidden | Missing Answer | Unsupported Claims | Top Selected |',
    '|---|---:|---|---|---|---|---|---|---|---|---|---|---|',
    ...rows.map((row) => `| ${row.map(escapeCell).join(' | ')} |`),
    '',
  ];
  fs.writeFileSync(filePath, `${lines.join('\n')}\n`, 'utf8');
}

function diagnoseFailureReadable(result) {
  const selected = result?.selected ?? [];
  const resultMsg = result?.resultMsg ?? '';
  if (!result) {
    return { cause: 'invalid_result', label: 'Invalid result' };
  }
  if (!selected.length && resultMsg === 'NO_GROUNDS') {
    return { cause: 'no_grounds', label: 'No direct grounds' };
  }
  if (!selected.length) {
    return { cause: 'retrieval_empty', label: 'No selected candidates' };
  }
  if (hasValues(result.forbiddenMatchedTerms)) {
    return { cause: 'forbidden_evidence', label: 'Forbidden evidence selected' };
  }
  if (result.answerVerificationRequired && !result.answerVerified) {
    return { cause: 'answer_verification', label: 'Answer not grounded' };
  }
  if (hasValues(result.missingDocumentTerms) || hasValues(result.missingTitleTerms)) {
    return { cause: 'wrong_document', label: 'Wrong document or title' };
  }
  if (hasValues(result.missingSectionTypes) || hasValues(result.missingParentTerms)) {
    return { cause: 'wrong_section', label: 'Wrong section or parent context' };
  }
  if (hasValues(result.missingPageNumbers)) {
    return { cause: 'wrong_page', label: 'Wrong page' };
  }
  if (hasValues(result.missingTerms)) {
    return { cause: 'partial_evidence', label: 'Partial direct evidence' };
  }
  if (resultMsg && resultMsg !== 'OK') {
    return { cause: 'result_status', label: `Status=${resultMsg}` };
  }
  return { cause: 'unknown_gate_condition', label: 'Unknown gate condition' };
}

/*
function diagnoseFailure(result) {
  const selected = result?.selected ?? [];
  const resultMsg = result?.resultMsg ?? '';
  if (!result) {
    return { cause: 'invalid_result', label: '결과 없음' };
  }
  if (!selected.length && resultMsg === 'NO_GROUNDS') {
    return { cause: 'no_grounds', label: '근거 없음' };
  }
  if (!selected.length) {
    return { cause: 'retrieval_empty', label: '검색 후보 없음' };
  }
  if (hasValues(result.forbiddenMatchedTerms)) {
    return { cause: 'forbidden_evidence', label: '금지 근거 혼입' };
  }
  if (hasValues(result.missingDocumentTerms) || hasValues(result.missingTitleTerms)) {
    return { cause: 'wrong_document', label: '문서/제목 불일치' };
  }
  if (hasValues(result.missingSectionTypes) || hasValues(result.missingParentTerms)) {
    return { cause: 'wrong_section', label: '섹션/상위문맥 불일치' };
  }
  if (hasValues(result.missingPageNumbers)) {
    return { cause: 'wrong_page', label: '페이지 불일치' };
  }
  if (hasValues(result.missingTerms)) {
    return { cause: 'partial_evidence', label: '직접근거 일부 부족' };
  }
  if (resultMsg && resultMsg !== 'OK') {
    return { cause: 'result_status', label: `상태=${resultMsg}` };
  }
  return { cause: 'unknown_gate_condition', label: '게이트 조건 확인 필요' };
}

*/
function nextActionForCause(cause) {
  switch (cause) {
    case 'no_grounds':
      return 'Check retrieval recall first; if candidates exist, inspect EvidenceJudge rejection.';
    case 'retrieval_empty':
      return 'Check query planner synonyms, lexical fallback, and collection filters.';
    case 'forbidden_evidence':
      return 'Strengthen rerank/evidence exclusion rules for the forbidden domain.';
    case 'wrong_document':
      return 'Tune title/source_org boost, expected document aliases, or document lookup routing.';
    case 'wrong_section':
      return 'Tune section metadata, parent-child context expansion, or section-type scoring.';
    case 'wrong_page':
      return 'Check parser page mapping and citation extraction.';
    case 'partial_evidence':
      return 'Check chunk granularity and direct-evidence term coverage.';
    case 'answer_verification':
      return 'Inspect generated answer, ClaimVerifier unsupported claims, and answer-level expected terms.';
    case 'result_status':
      return 'Inspect answer service resultMsg path and fallback policy.';
    default:
      return 'Inspect raw selected chunks and case expectations.';
  }
}

function hasValues(values) {
  return Array.isArray(values) && values.length > 0;
}

function selectedSummary(result) {
  const item = (result?.selected ?? [])[0];
  if (!item) {
    return '-';
  }
  const parts = [
    item.target,
    item.title,
    item.chunkTitle,
    item.pageNo == null ? '' : `p.${item.pageNo}`,
  ].filter(Boolean);
  return parts.join(' / ');
}

function list(values) {
  return Array.isArray(values) && values.length > 0 ? values.join(', ') : '-';
}

function escapeCell(value) {
  return String(value ?? '-').replace(/\|/g, '\\|').replace(/\n/g, '<br>');
}

main().catch((error) => {
  console.error(`[rag-eval-gate] ERROR ${error?.message ?? error}`);
  process.exit(1);
});
