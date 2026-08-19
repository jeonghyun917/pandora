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
		candidateLoss: extractCandidateLossAnalysis(response),
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
  const summary = summarizeRetrievalCases(successfulMeasurements, options.k);
  const body = {
    ...summary,
    selectedCases: cases.length,
    completedCases: successfulMeasurements.length,
    requestErrors: errors,
    complete: errors.length === 0 && successfulMeasurements.length === cases.length,
    provenance: buildProvenance({
      scope,
      baseUrl: options.baseUrl,
      gitCommit: gitOutput(['rev-parse', 'HEAD']),
      gitDirty: Boolean(gitOutput(['status', '--porcelain'])),
      datasetHashValue,
      selectionHashValue,
      selectedCount: cases.length,
      totalCaseCount: allCases.length,
      runtimeInfo,
    }),
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
      body: JSON.stringify(buildDebugRequest(evalCase, options.k)),
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
    const auditGroupCount = buildAuditTermGroups(evalCase).length;
    return assertDebugResponse(body, auditGroupCount);
  } catch (error) {
    if (error?.name === 'AbortError') {
      throw new Error(`debug search timed out after ${options.timeoutMs}ms`);
    }
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

function buildDebugRequest(evalCase, k) {
  return {
    targets: evalCase?.targets ?? [],
    question: evalCase?.question ?? '',
    limit: k,
    includeFuture: true,
    auditTermGroups: buildAuditTermGroups(evalCase),
  };
}

function buildAuditTermGroups(evalCase) {
  return [
    ...(Array.isArray(evalCase?.requiredPropositionGroups) ? evalCase.requiredPropositionGroups : []),
    ...(Array.isArray(evalCase?.requiredConditionGroups) ? evalCase.requiredConditionGroups : []),
  ];
}

function assertDebugResponse(body, auditGroupCount = 0) {
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
      const requiredFields = ['parentSectionTitle', 'sectionType'];
      if (auditGroupCount > 0) {
        requiredFields.push('matchedAuditGroupIndexes', 'matchedAuditAliases');
      }
      const missing = requiredFields
        .filter((field) => !Object.hasOwn(item, field));
      if (missing.length > 0) {
        throw new Error(`debug search response ${stage}[${index}] missing ${missing.join(', ')}`);
      }
      if (auditGroupCount > 0 && (
        !Array.isArray(item.matchedAuditGroupIndexes)
          || !Array.isArray(item.matchedAuditAliases)
      )) {
        throw new Error(`debug search response ${stage}[${index}] audit matches must be arrays`);
      }
    }
  }
	if (body.candidateTraces != null) {
		if (!Array.isArray(body.candidateTraces) || body.candidateTraces.length > 100) {
			throw new Error('debug search response candidateTraces must be an array with at most 100 items');
		}
		for (const [index, trace] of body.candidateTraces.entries()) {
			if (!trace || typeof trace !== 'object' || Array.isArray(trace)) {
				throw new Error(`debug search response candidateTraces[${index}] must be an object`);
			}
			for (const forbidden of ['chunkText', 'body', 'snippet']) {
				if (Object.hasOwn(trace, forbidden)) {
					throw new Error(`debug search response candidateTraces[${index}] must not contain ${forbidden}`);
				}
			}
		}
	}
  return body;
}

function extractCandidateLossAnalysis(response) {
	const traces = Array.isArray(response?.candidateTraces) ? response.candidateTraces : [];
	const auditMatches = new Map();
	for (const stage of STAGE_NAMES) {
		for (const item of Array.isArray(response?.[stage]) ? response[stage] : []) {
			const indexes = Array.isArray(item?.matchedAuditGroupIndexes)
				? item.matchedAuditGroupIndexes.filter(Number.isInteger)
				: [];
			if (indexes.length === 0) {
				continue;
			}
			const key = candidateKey(item);
			if (!key) {
				continue;
			}
			const existing = auditMatches.get(key) ?? new Set();
			indexes.forEach((index) => existing.add(index));
			auditMatches.set(key, existing);
		}
	}
	const oracleCandidateTraces = traces
		.filter((trace) => auditMatches.has(String(trace?.candidateKey ?? '')))
		.map((trace) => ({
			candidateKey: String(trace.candidateKey),
			oraclePresenceStage: Array.isArray(trace.enteredStages) && trace.enteredStages.length > 0
				? trace.enteredStages[trace.enteredStages.length - 1]
				: null,
			matchedAuditGroupIndexes: Array.from(auditMatches.get(String(trace.candidateKey))).sort((a, b) => a - b),
			firstLossStage: trace.firstLossStage ?? null,
			reasonCodes: Array.isArray(trace.reasonCodes) ? [...trace.reasonCodes] : [],
		}));
	return {
		candidateTraces: traces,
		oracleCandidateTraces,
		firstLossStageCounts: countValues(traces.map((trace) => trace?.firstLossStage)),
		reasonCodeCounts: countValues(traces.flatMap((trace) =>
			Array.isArray(trace?.reasonCodes) ? trace.reasonCodes : [])),
	};
}

function candidateKey(item) {
	if (typeof item?.candidateKey === 'string' && item.candidateKey) {
		return item.candidateKey;
	}
	if (item?.target == null || item?.chunkId == null) {
		return null;
	}
	return `${item.target}:${item.chunkId}`;
}

function countValues(values) {
	const counts = {};
	for (const value of values) {
		if (value == null || String(value).trim() === '') {
			continue;
		}
		const key = String(value);
		counts[key] = (counts[key] ?? 0) + 1;
	}
	return counts;
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
  ];
  fs.writeFileSync(filePath, `${lines.join('\n')}\n`, 'utf8');
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
	extractCandidateLossAnalysis,
  main,
  parseOptions,
};
