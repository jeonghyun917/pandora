const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const {
  loadEvalCases,
  selectEvalCases,
  splitCaseIds,
} = require('./lib/rag-eval-cases');
const {
  STAGE_NAMES,
  measureRetrievalCase,
  summarizeRetrievalCases,
} = require('./lib/rag-retrieval-metrics');
const {
  END_TO_END_STAGES,
  extendEvidenceCoverage,
  summarizeEndToEndEvidenceCoverage,
} = require('./lib/rag-evidence-coverage');
const {
  assertEvaluationRuntimeReady,
  buildProvenance,
  datasetHash,
  determineRunScope,
  isRuntimeStable,
  selectionHash,
} = require('./lib/rag-eval-provenance');

const CASE_PATHS = [
  path.resolve('src/main/resources/rag-evaluation-cases.tsv'),
  path.resolve('src/main/resources/rag-evaluation-cases.generated.tsv'),
];
const ANSWER_ORACLE_PATH = path.resolve('src/main/resources/rag-answer-evaluation-oracles.tsv');
const DATASET_PATHS = [...CASE_PATHS, ANSWER_ORACLE_PATH];

async function main() {
  const options = parseOptions(process.argv.slice(2), process.env);
  const allCases = loadEvalCases(CASE_PATHS, { answerOraclePath: ANSWER_ORACLE_PATH });
  const cases = selectEvalCases(allCases, options);
  if (cases.length === 0) {
    throw new Error('no evaluation cases selected');
  }
  const scope = determineRunScope(cases, allCases, options.caseIds, options.caseLimit);
  const outputPaths = resolveOutputPaths(scope, options);
  const datasetHashValue = datasetHash(DATASET_PATHS.filter((casePath) => fs.existsSync(casePath)));
  const selectionHashValue = selectionHash(cases);
  const runtimeInfo = await loadRuntimeInfo(options.baseUrl, options.timeoutMs);
  assertEvaluationRuntimeReady(runtimeInfo, scope);

  const measurements = [];
  const errors = [];
  await mapWithConcurrency(cases, options.concurrency, async (evalCase, index) => {
    try {
      const response = await loadDebugResponse(evalCase, options);
      measurements[index] = {
        ...measureRetrievalCase(evalCase, response, options.k),
        question: evalCase.question,
        targets: evalCase.targets,
      };
    } catch (error) {
      errors.push({ id: evalCase.id, message: error?.message ?? String(error) });
    }
    const completed = measurements.filter(Boolean).length + errors.length;
    if (completed % 10 === 0 || completed === cases.length) {
      console.log(`[rag-retrieval-eval] ${completed}/${cases.length}`);
    }
  });

  const finalRuntimeInfo = await loadRuntimeInfo(options.baseUrl, options.timeoutMs);
  if (!isRuntimeStable(runtimeInfo, finalRuntimeInfo)) {
    throw new Error('runtime identity or index revision changed during retrieval evaluation');
  }

  const successfulMeasurements = measurements.filter(Boolean);
  const provenance = buildProvenance({
    scope,
    baseUrl: options.baseUrl,
    gitCommit: gitOutput(['rev-parse', 'HEAD']),
    gitDirty: Boolean(gitOutput(['status', '--porcelain'])),
    datasetHashValue,
    selectionHashValue,
    selectedCount: cases.length,
    totalCaseCount: allCases.length,
    runtimeInfo,
  });
  const answerResults = options.answerEvalPath
    ? prepareAnswerEvaluation(
      readJson(options.answerEvalPath),
      provenance,
      cases.map((evalCase) => evalCase.id),
    )
    : null;
  const casesById = new Map(cases.map((evalCase) => [evalCase.id, evalCase]));
  for (const measurement of successfulMeasurements) {
    measurement.endToEndEvidenceCoverage = extendEvidenceCoverage(
      casesById.get(measurement.id),
      measurement.evidenceCoverage,
      answerResults?.get(measurement.id),
    );
  }
  const summary = summarizeRetrievalCases(successfulMeasurements, options.k);
  summary.endToEndEvidenceCoverage = summarizeEndToEndEvidenceCoverage(successfulMeasurements);
  const body = {
    ...summary,
    selectedCases: cases.length,
    completedCases: successfulMeasurements.length,
    requestErrors: errors,
    complete: errors.length === 0 && successfulMeasurements.length === cases.length,
    provenance,
    runtimeVerifiedAtEnd: true,
    results: successfulMeasurements,
  };
  writeJson(outputPaths.outputPath, body);
  writeReport(outputPaths.reportPath, body);
  console.log(`[rag-retrieval-eval] wrote ${outputPaths.outputPath}`);
  if (!body.complete) {
    process.exitCode = 1;
  }
}

function parseOptions(argv = [], env = process.env) {
  const values = {
    caseIds: splitCaseIds(env.RAG_RETRIEVAL_CASE_IDS || ''),
    caseLimit: positiveInteger(env.RAG_RETRIEVAL_CASE_LIMIT, 0, true),
    k: positiveInteger(env.RAG_RETRIEVAL_K, 10),
    outputPath: env.RAG_RETRIEVAL_OUTPUT || null,
    reportPath: env.RAG_RETRIEVAL_REPORT || null,
    baseUrl: env.RAG_RETRIEVAL_BASE_URL || env.RAG_EVAL_BASE_URL || 'http://127.0.0.1:8080',
    concurrency: positiveInteger(env.RAG_RETRIEVAL_CONCURRENCY, 2),
    timeoutMs: positiveInteger(env.RAG_RETRIEVAL_REQUEST_TIMEOUT_MS, 180000),
    answerEvalPath: env.RAG_RETRIEVAL_ANSWER_EVAL || null,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    const [flag, inlineValue] = argument.split(/=(.*)/s, 2);
    const readValue = () => {
      if (inlineValue != null) {
        return inlineValue;
      }
      index += 1;
      if (index >= argv.length) {
        throw new Error(`${flag} requires a value`);
      }
      return argv[index];
    };
    switch (flag) {
      case '--case-ids':
        values.caseIds = splitCaseIds(readValue());
        break;
      case '--case-limit':
      case '--limit':
        values.caseLimit = positiveInteger(readValue(), 0, true);
        break;
      case '--k':
        values.k = positiveInteger(readValue(), 10);
        break;
      case '--output':
        values.outputPath = readValue();
        break;
      case '--report':
        values.reportPath = readValue();
        break;
      case '--base-url':
        values.baseUrl = readValue();
        break;
      case '--concurrency':
        values.concurrency = positiveInteger(readValue(), 2);
        break;
      case '--answer-eval': {
        const answerEvalPath = readValue();
        if (!String(answerEvalPath).trim()) {
          throw new Error('--answer-eval requires a value');
        }
        values.answerEvalPath = answerEvalPath;
        break;
      }
      default:
        throw new Error(`unknown option: ${flag}`);
    }
  }
  values.baseUrl = String(values.baseUrl).replace(/\/$/, '');
  if (values.outputPath && !values.reportPath) {
    values.reportPath = replaceExtension(values.outputPath, '.md');
  }
  return values;
}

function resolveOutputPaths(scope, options) {
  const outputPath = options.outputPath || `logs/rag-retrieval-eval-${scope}-latest.json`;
  const reportPath = options.reportPath || replaceExtension(outputPath, '.md');
  return { outputPath, reportPath };
}

async function loadRuntimeInfo(baseUrl, timeoutMs) {
  const endpoint = `${baseUrl}/api/law-data/ai/debug/runtime-info`;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), Math.min(timeoutMs, 5000));
  try {
    const response = await fetch(endpoint, { signal: controller.signal });
    if (!response.ok) {
      throw new Error(`runtime info HTTP ${response.status}`);
    }
    return { ...(await response.json()), source: 'server' };
  } finally {
    clearTimeout(timer);
  }
}

async function loadDebugResponse(evalCase, options) {
  const endpoint = `${options.baseUrl}/api/law-data/ai/debug/search`;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), options.timeoutMs);
  try {
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(buildDebugRequest(evalCase, options)),
      signal: controller.signal,
    });
    const text = await response.text();
    let body;
    try {
      body = JSON.parse(text);
    } catch {
      body = null;
    }
    if (!response.ok || !body) {
      throw new Error(`debug search HTTP ${response.status}: ${text.slice(0, 200)}`);
    }
    return assertDebugResponse(body, { requireMatchedChildText: true });
  } catch (error) {
    if (error?.name === 'AbortError') {
      throw new Error(`debug search timed out after ${options.timeoutMs}ms`);
    }
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

function buildDebugRequest(evalCase, options) {
  return {
    targets: evalCase.targets,
    question: evalCase.question,
    limit: options.k,
    includeFuture: true,
    includeMatchedChildText: true,
  };
}

function assertDebugResponse(body, { requireMatchedChildText = false } = {}) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    throw new Error('debug search response must be an object');
  }
  if (typeof body.resultMsg !== 'string') {
    throw new Error('debug search response resultMsg must be a string');
  }
  for (const stage of STAGE_NAMES) {
    if (!Array.isArray(body[stage])) {
      throw new Error(`debug search response ${stage} must be an array`);
    }
    for (const [index, item] of body[stage].entries()) {
      if (!item || typeof item !== 'object' || Array.isArray(item)) {
        throw new Error(`debug search response ${stage}[${index}] must be an object`);
      }
      const missing = ['parentSectionTitle', 'sectionType']
        .filter((field) => !Object.hasOwn(item, field));
      if (missing.length > 0) {
        throw new Error(`debug search response ${stage}[${index}] missing ${missing.join(', ')}`);
      }
      if (requireMatchedChildText && typeof item.matchedChildText !== 'string') {
        throw new Error(`debug search response ${stage}[${index}] matchedChildText must be a string`);
      }
    }
  }
  return body;
}

async function mapWithConcurrency(values, concurrency, callback) {
  let nextIndex = 0;
  const worker = async () => {
    while (nextIndex < values.length) {
      const index = nextIndex;
      nextIndex += 1;
      await callback(values[index], index);
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, values.length) }, worker));
}

function writeJson(filePath, body) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(body, null, 2)}\n`, 'utf8');
}

function writeReport(filePath, body) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  const stageRows = STAGE_NAMES.map((stage) => {
    const metrics = body.stages[stage];
    return [
      stage,
      fraction(metrics.documentHits, metrics.documentGoldCases),
      percent(metrics.documentHitRate),
      percent(metrics.documentTermCoverageAtK),
      fraction(metrics.sectionParentHits, metrics.sectionParentGoldCases),
      percent(metrics.sectionParentHitRate),
      percent(metrics.sectionParentTermCoverageAtK),
      percent(metrics.directHitRate),
    ];
  });
  const caseRows = body.results.map((result) => [
    result.id,
    result.recallEligible ? 'yes' : 'no',
    result.firstDropStage ?? (result.recallEligible ? 'survived' : result.exclusionReason),
    result.falseGround ? 'yes' : 'no',
  ]);
  const lines = [
    '# RAG Retrieval Recall',
    '',
    `- Scope: ${body.provenance.runScope}`,
    `- Generated at: ${body.provenance.generatedAt}`,
    `- K: ${body.k}`,
    `- Cases: ${body.completedCases}/${body.selectedCases}`,
    `- Recall eligible: ${body.recallEligibleCases}`,
    `- No-ground cases: ${body.noGroundCases}`,
    `- False grounds: ${body.falseGround.falseGrounds}/${body.falseGround.cases}`,
    `- Runtime artifact SHA-256: ${body.provenance.runtimeArtifactSha256 ?? '-'}`,
    `- Runtime instance ID: ${body.provenance.runtimeInstanceId ?? '-'}`,
    `- Index revision: ${body.provenance.indexRevision ?? '-'}`,
    `- Runtime stable through end: ${body.runtimeVerifiedAtEnd}`,
    '',
    '| Stage | Document hit | Hit@K | Document term coverage@K | Section/parent hit | Hit@K | Section/parent term coverage@K | Direct hit@K |',
    '|---|---:|---:|---:|---:|---:|---:|---:|',
    ...stageRows.map((row) => `| ${row.join(' | ')} |`),
    '',
    `- First drop: ${Object.entries(body.firstDropCounts).map(([stage, count]) => `${stage}=${count}`).join(', ') || '-'}`,
    `- Request errors: ${body.requestErrors.length}`,
    '',
    '| ID | Recall eligible | First drop | False ground |',
    '|---|---:|---|---:|',
    ...caseRows.map((row) => `| ${row.map(escapeCell).join(' | ')} |`),
    '',
    formatEndToEndEvidenceCoverageMarkdown(body),
    '',
  ];
  fs.writeFileSync(filePath, `${lines.join('\n')}\n`, 'utf8');
}

function prepareAnswerEvaluation(answerEvaluation, retrievalProvenance, requestedIds) {
  if (!answerEvaluation || typeof answerEvaluation !== 'object' || Array.isArray(answerEvaluation)) {
    throw new Error('answer-eval must be a JSON object');
  }
  const mismatches = provenanceMismatches(answerEvaluation.provenance, retrievalProvenance);
  if (mismatches.length > 0) {
    throw new Error(`answer-eval provenance mismatch: ${mismatches.join(', ')}`);
  }
  if (!Array.isArray(answerEvaluation.results)) {
    throw new Error('answer-eval results must be an array');
  }
  const byId = new Map();
  const duplicateIds = new Set();
  for (const result of answerEvaluation.results) {
    const id = result?.id;
    if (typeof id !== 'string' || id.length === 0) {
      throw new Error('answer-eval result ID must be a non-empty string');
    }
    assertMeasurableAnswerResult(result, id);
    if (byId.has(id)) {
      duplicateIds.add(id);
    }
    byId.set(id, result);
  }
  if (duplicateIds.size > 0) {
    throw new Error(`duplicate answer-eval IDs: ${Array.from(duplicateIds).join(', ')}`);
  }
  const missingIds = requestedIds.filter((id) => !byId.has(id));
  if (missingIds.length > 0) {
    throw new Error(`missing requested answer-eval IDs: ${missingIds.join(', ')}`);
  }
  return byId;
}

function assertMeasurableAnswerResult(result, id) {
  if (!Array.isArray(result.claimEvidenceLinks)) {
    throw new Error(`answer-eval result ${id} claimEvidenceLinks must be an array`);
  }
  if (typeof result.verifiedAnswer !== 'string') {
    throw new Error(`answer-eval result ${id} verifiedAnswer must be a string`);
  }
  for (const link of result.claimEvidenceLinks) {
    if (link?.relation === 'SUPPORTED' && typeof link.evidenceSentence !== 'string') {
      throw new Error(`answer-eval result ${id} SUPPORTED evidenceSentence must be a string`);
    }
  }
}

function provenanceMismatches(answerProvenance, retrievalProvenance) {
  const fields = [
    'baseUrl',
    'runtimeArtifactSha256',
    'runtimeInstanceId',
    'indexRevision',
    'datasetHash',
    'selectionHash',
  ];
  return fields.filter((field) => {
    const answerValue = normalizedProvenanceValue(field, answerProvenance?.[field]);
    const retrievalValue = normalizedProvenanceValue(field, retrievalProvenance?.[field]);
    return answerValue == null || retrievalValue == null || answerValue !== retrievalValue;
  });
}

function normalizedProvenanceValue(field, value) {
  if (typeof value !== 'string' || value.length === 0) {
    return null;
  }
  if (field === 'baseUrl') {
    try {
      return new URL(value).toString().replace(/\/+$/, '');
    } catch {
      return null;
    }
  }
  if (field === 'runtimeArtifactSha256') {
    return value.toLowerCase();
  }
  return value;
}

function readJson(filePath) {
  let text;
  try {
    text = fs.readFileSync(filePath, 'utf8');
  } catch (error) {
    throw new Error(`cannot read answer-eval ${filePath}: ${error?.message ?? error}`);
  }
  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`answer-eval is not valid JSON: ${filePath}`);
  }
}

function formatEndToEndEvidenceCoverageMarkdown(body) {
  const lines = ['## End-to-end Evidence Coverage', ''];
  for (const [type, title] of [
    ['proposition', 'Proposition group coverage'],
    ['condition', 'Condition group coverage'],
  ]) {
    const summary = body?.endToEndEvidenceCoverage?.[type] ?? {
      totalGroups: 0,
      stages: {},
      firstLossCounts: {},
    };
    lines.push(`### ${title}`, '');
    lines.push('| Stage | Covered | Coverage | Status |');
    lines.push('|---|---:|---:|---|');
    for (const stage of END_TO_END_STAGES) {
      const metrics = summary.stages?.[stage];
      if (!metrics) {
        continue;
      }
      const covered = metrics.status === 'measured'
        ? fraction(metrics.coveredGroups, summary.totalGroups)
        : '-';
      lines.push(`| ${stage} | ${covered} | ${percent(metrics.rate)} | ${metrics.status} |`);
    }
    lines.push('');
    lines.push(`- First loss: ${formatCounts(summary.firstLossCounts)}`);
    lines.push('');
  }
  lines.push('### Case-level missing groups', '');
  lines.push('| ID | Stage | Missing proposition groups | Missing condition groups |');
  lines.push('|---|---|---|---|');
  let missingRows = 0;
  for (const result of body?.results ?? []) {
    for (const stage of END_TO_END_STAGES) {
      const missing = result?.endToEndEvidenceCoverage?.missingGroups?.[stage];
      if (!missing) {
        continue;
      }
      lines.push(`| ${[
        result.id,
        stage,
        (missing.proposition ?? []).join(', ') || '-',
        (missing.condition ?? []).join(', ') || '-',
      ].map(escapeCell).join(' | ')} |`);
      missingRows += 1;
    }
  }
  if (missingRows === 0) {
    lines.push('| - | - | - | - |');
  }
  return lines.join('\n');
}

function formatCounts(counts) {
  return Object.entries(counts ?? {}).map(([label, count]) => `${label}=${count}`).join(', ') || '-';
}

function positiveInteger(value, fallback, allowZero = false) {
  if (value == null || String(value).trim() === '') {
    return fallback;
  }
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < (allowZero ? 0 : 1)) {
    throw new Error(`expected ${allowZero ? 'non-negative' : 'positive'} integer, got ${value}`);
  }
  return parsed;
}

function replaceExtension(filePath, extension) {
  const currentExtension = path.extname(filePath);
  return currentExtension ? filePath.slice(0, -currentExtension.length) + extension : filePath + extension;
}

function gitOutput(args) {
  try {
    return execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
  } catch {
    return '';
  }
}

function percent(value) {
  return value == null ? '-' : `${(value * 100).toFixed(1)}%`;
}

function fraction(numerator, denominator) {
  return denominator > 0 ? `${numerator}/${denominator}` : '-';
}

function escapeCell(value) {
  return String(value ?? '-').replace(/\|/g, '\\|').replace(/\n/g, '<br>');
}

if (require.main === module) {
  main().catch((error) => {
    console.error(`[rag-retrieval-eval] ERROR ${error?.message ?? error}`);
    process.exitCode = 1;
  });
}

module.exports = {
  assertDebugResponse,
  buildDebugRequest,
  formatEndToEndEvidenceCoverageMarkdown,
  main,
  parseOptions,
  prepareAnswerEvaluation,
};
