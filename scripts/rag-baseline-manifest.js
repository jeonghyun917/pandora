const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const {
  loadEvalCases,
  splitCaseIds,
} = require('./lib/rag-eval-cases');
const { buildBaselineManifest } = require('./lib/rag-baseline-manifest');
const { assertFullBaselineUniverse, datasetHash, selectionHash } = require('./lib/rag-eval-provenance');

const baseUrl = process.env.RAG_EVAL_BASE_URL || 'http://127.0.0.1:8080';
const runtimeInfoEndpoint = `${baseUrl.replace(/\/$/, '')}/api/law-data/ai/debug/runtime-info`;
const casePaths = [
  path.resolve('src/main/resources/rag-evaluation-cases.tsv'),
  path.resolve('src/main/resources/rag-evaluation-cases.generated.tsv'),
];
const answerOraclePath = path.resolve('src/main/resources/rag-answer-evaluation-oracles.tsv');
const caseIds = splitCaseIds(process.env.RAG_EVAL_CASE_IDS || '');
const caseLimit = Number(process.env.RAG_EVAL_CASE_LIMIT || 0);

async function main() {
  if (!process.argv.slice(2).includes('--write')) {
    throw new Error('usage: node scripts/rag-baseline-manifest.js --write');
  }
  const allCases = loadEvalCases(casePaths, { answerOraclePath });
  const gateProfile = String(process.env.RAG_EVAL_GATE_PROFILE ?? 'release').trim().toLowerCase() || 'release';
  assertFullBaselineUniverse(allCases, allCases, { caseIds, caseLimit, gateProfile });
  const runtimeInfo = await loadRuntimeInfo();
  const cases = allCases;
  const manifest = buildBaselineManifest({
    gitCommit: gitOutput(['rev-parse', 'HEAD']),
    gitDirty: Boolean(gitOutput(['status', '--porcelain'])),
    runtimeInfo,
    datasetHash: datasetHash([...casePaths, answerOraclePath].filter((filePath) => fs.existsSync(filePath))),
    selectionHash: selectionHash(cases),
    selectionCaseIds: cases.map((item) => item.id),
  });
  const outputPath = path.resolve(process.env.RAG_EVAL_BASELINE_MANIFEST_OUTPUT
    || `logs/rag-baseline-manifest-${runId()}.json`);
  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
  process.stdout.write(`${outputPath}\n`);
}

async function loadRuntimeInfo() {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 5000);
  try {
    const response = await fetch(runtimeInfoEndpoint, { signal: controller.signal });
    if (!response.ok) {
      throw new Error(`runtime info HTTP ${response.status}`);
    }
    return await response.json();
  } finally {
    clearTimeout(timer);
  }
}

function gitOutput(args) {
  try {
    return execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
  } catch {
    return '';
  }
}

function runId() {
  return new Date().toISOString().replace(/[-:]/g, '').replace('T', '-').replace(/\.(\d{3})Z$/, '-$1Z');
}

main().catch((error) => {
  console.error(`[rag-baseline-manifest] ${error?.message ?? error}`);
  process.exit(1);
});
