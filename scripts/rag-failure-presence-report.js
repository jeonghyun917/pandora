const fs = require('node:fs');
const path = require('node:path');
const {
  buildFailurePresenceReport,
} = require('./lib/rag-failure-presence-report');

function main(argv = process.argv.slice(2)) {
  const options = parseOptions(argv);
  const evaluationReport = readJson(options.evaluationPath);
  const retrievalReport = readJson(options.retrievalPath);
  const report = buildFailurePresenceReport(evaluationReport, retrievalReport);

  writeText(options.outputPath, `${JSON.stringify(report, null, 2)}\n`);
  writeText(options.reportPath, renderMarkdown(report));
  console.log(`[rag-failure-presence-report] wrote ${options.outputPath}`);
}

function parseOptions(argv = []) {
  const values = {
    evaluationPath: null,
    retrievalPath: null,
    outputPath: null,
    reportPath: null,
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
      case '--evaluation':
        values.evaluationPath = readValue();
        break;
      case '--retrieval':
        values.retrievalPath = readValue();
        break;
      case '--output':
        values.outputPath = readValue();
        break;
      case '--report':
        values.reportPath = readValue();
        break;
      default:
        throw new Error(`unknown option: ${flag}`);
    }
  }
  for (const [name, value] of Object.entries({
    evaluation: values.evaluationPath,
    retrieval: values.retrievalPath,
    output: values.outputPath,
  })) {
    if (!value) {
      throw new Error(`--${name} is required`);
    }
  }
  if (!values.reportPath) {
    values.reportPath = replaceExtension(values.outputPath, '.md');
  }
  return values;
}

function renderMarkdown(report) {
  const failureRows = Object.entries(report?.failureCategoryCounts ?? {});
  const presenceRows = Object.entries(report?.presenceClassificationCounts ?? {});
	const candidateLossRows = Object.entries(report?.candidateFirstLossStageCounts ?? {});
	const candidateReasonRows = Object.entries(report?.candidateReasonCodeCounts ?? {});
  const caseRows = report?.results ?? [];
  const lines = [
    '# Failed Proposition Presence Audit',
    '',
    `- Failed cases: ${report?.totalFailures ?? 0}`,
    `- Candidate K: ${report?.k ?? '-'}`,
    '',
    '## Failure categories',
    '',
    '| Category | Count |',
    '|---|---:|',
    ...failureRows.map(([category, count]) => `| ${escapeCell(category)} | ${count} |`),
    '',
    '## Proposition presence',
    '',
    '| Classification | Count |',
    '|---|---:|',
    ...presenceRows.map(([classification, count]) => `| ${escapeCell(classification)} | ${count} |`),
	'',
	'## Candidate first loss',
	'',
	'| Stage | Count |',
	'|---|---:|',
	...candidateLossRows.map(([stage, count]) => `| ${escapeCell(stage)} | ${count} |`),
	'',
	'## Candidate loss reasons',
	'',
	'| Reason | Count |',
	'|---|---:|',
	...candidateReasonRows.map(([reason, count]) => `| ${escapeCell(reason)} | ${count} |`),
    '',
    '## Cases',
    '',
    '| ID | Failure | Presence | First proposition loss | Retrieval gold first drop |',
    '|---|---|---|---|---|',
    ...caseRows.map((row) => `| ${[
      row.id,
      row.failureCategory,
      row.presenceClassification,
      row.firstLossStage ?? '-',
      row.retrievalFirstDropStage ?? '-',
    ].map(escapeCell).join(' | ')} |`),
    '',
  ];
  return `${lines.join('\n')}\n`;
}

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function writeText(filePath, content) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, content, 'utf8');
}

function replaceExtension(filePath, extension) {
  const currentExtension = path.extname(filePath);
  return currentExtension
    ? filePath.slice(0, -currentExtension.length) + extension
    : filePath + extension;
}

function escapeCell(value) {
  return String(value ?? '-').replace(/\|/g, '\\|').replace(/\n/g, '<br>');
}

if (require.main === module) {
  try {
    main();
  } catch (error) {
    console.error(`[rag-failure-presence-report] ERROR ${error?.message ?? error}`);
    process.exitCode = 1;
  }
}

module.exports = {
  main,
  parseOptions,
  renderMarkdown,
};
