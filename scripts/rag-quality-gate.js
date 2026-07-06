const fs = require('fs');
const path = require('path');

const workspace = process.cwd();
const outputPath = process.env.RAG_QUALITY_GATE_OUTPUT || path.resolve(workspace, 'logs', 'rag-quality-gate-latest.json');
const reportPath = process.env.RAG_QUALITY_GATE_REPORT || path.resolve(workspace, 'logs', 'rag-quality-gate-latest.md');

const thresholds = {
  maxResidualTinyTotal: numberEnv('RAG_QUALITY_MAX_RESIDUAL_TINY_TOTAL', 500),
  maxResidualTinyManualReview: numberEnv('RAG_QUALITY_MAX_RESIDUAL_TINY_MANUAL_REVIEW', 150),
  maxResidualTinySuppress: numberEnv('RAG_QUALITY_MAX_RESIDUAL_TINY_SUPPRESS', 150),
  maxRagShortTotal: numberEnv('RAG_QUALITY_MAX_RAG_SHORT_TOTAL', 15000),
  maxRagShortNoisy: numberEnv('RAG_QUALITY_MAX_RAG_SHORT_NOISY', 5000),
  maxRagShortManualReview: numberEnv('RAG_QUALITY_MAX_RAG_SHORT_MANUAL_REVIEW', 1000),
};

function main() {
  const residualTiny = readJson(path.resolve(workspace, 'logs', 'residual-tiny-audit-latest.json'));
  const ragShort = readJson(path.resolve(workspace, 'logs', 'rag-short-chunk-audit-latest.json'));
  const lawParentChild = readJson(path.resolve(workspace, 'logs', 'law-parent-child-chunk-audit-latest.json'));

  const checks = [
    fileCheck('residual-tiny-audit', residualTiny),
    fileCheck('rag-short-chunk-audit', ragShort),
    fileCheck('law-parent-child-chunk-audit', lawParentChild),
  ];
  checks.push(...residualTinyChecks(residualTiny));
  checks.push(...ragShortChecks(ragShort));

  const failed = checks.filter((check) => !check.passed);
  const result = {
    generatedAt: new Date().toISOString(),
    gatePassed: failed.length === 0,
    thresholds,
    checks,
    failedChecks: failed.map((check) => check.key),
  };
  writeJson(outputPath, result);
  writeReport(reportPath, result);

  if (!result.gatePassed) {
    console.error(`[rag-quality-gate] FAIL ${failed.length}/${checks.length}`);
    for (const check of failed) {
      console.error(`- ${check.key}: ${check.value} > ${check.threshold ?? 'required'}`);
    }
    process.exit(1);
  }
  console.log(`[rag-quality-gate] PASS ${checks.length}/${checks.length}`);
}

function residualTinyChecks(audit) {
  if (!audit) {
    return [];
  }
  const summary = Array.isArray(audit.summary) ? audit.summary : [];
  const manualReview = sum(summary.filter((row) => row.action === 'manual_review'), 'chunks');
  const suppress = sum(summary.filter((row) => row.action === 'suppress_or_downrank'), 'chunks');
  return [
    thresholdCheck('residual_tiny_total', Number(audit.total ?? 0), thresholds.maxResidualTinyTotal),
    thresholdCheck('residual_tiny_manual_review', manualReview, thresholds.maxResidualTinyManualReview),
    thresholdCheck('residual_tiny_suppress_or_downrank', suppress, thresholds.maxResidualTinySuppress),
  ];
}

function ragShortChecks(audit) {
  if (!audit) {
    return [];
  }
  const summary = Array.isArray(audit.summary) ? audit.summary : [];
  const noisy = sum(summary.filter((row) => ['merge_or_downrank', 'suppress_or_downrank'].includes(row.action)), 'chunks');
  const manualReview = sum(summary.filter((row) => row.action === 'manual_review'), 'chunks');
  return [
    thresholdCheck('rag_short_total', Number(audit.total ?? 0), thresholds.maxRagShortTotal),
    thresholdCheck('rag_short_noisy', noisy, thresholds.maxRagShortNoisy),
    thresholdCheck('rag_short_manual_review', manualReview, thresholds.maxRagShortManualReview),
  ];
}

function fileCheck(key, body) {
  return {
    key,
    passed: Boolean(body),
    value: body ? 1 : 0,
    threshold: 1,
    detail: body ? `generatedAt=${body.generatedAt ?? '-'}` : 'audit output is missing',
  };
}

function thresholdCheck(key, value, threshold) {
  return {
    key,
    passed: value <= threshold,
    value,
    threshold,
    detail: `${value.toLocaleString('ko-KR')} / ${threshold.toLocaleString('ko-KR')}`,
  };
}

function sum(rows, field) {
  return rows.reduce((total, row) => total + Number(row?.[field] ?? 0), 0);
}

function readJson(filePath) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'));
  } catch {
    return null;
  }
}

function writeJson(filePath, body) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(body, null, 2)}\n`, 'utf8');
}

function writeReport(filePath, body) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  const rows = body.checks.map((check) => [
    check.passed ? 'PASS' : 'FAIL',
    check.key,
    String(check.value),
    check.threshold == null ? '-' : String(check.threshold),
    check.detail ?? '',
  ]);
  const lines = [
    '# RAG Quality Gate',
    '',
    `- Generated at: ${body.generatedAt}`,
    `- Gate passed: ${body.gatePassed}`,
    '',
    '| Status | Check | Value | Threshold | Detail |',
    '|---|---|---:|---:|---|',
    ...rows.map((row) => `| ${row.map(escapeCell).join(' | ')} |`),
    '',
  ];
  fs.writeFileSync(filePath, `${lines.join('\n')}\n`, 'utf8');
}

function escapeCell(value) {
  return String(value ?? '').replace(/\|/g, '\\|').replace(/\n/g, '<br>');
}

function numberEnv(name, fallback) {
  const value = Number(process.env[name]);
  return Number.isFinite(value) ? value : fallback;
}

main();
