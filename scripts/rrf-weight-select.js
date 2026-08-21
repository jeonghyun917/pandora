const fs = require('node:fs');
const path = require('node:path');

const { loadEvalCases } = require('./lib/rag-eval-cases');
const {
  loadTrainingManifest,
  selectWeights,
} = require('./lib/rrf-weight-selection');

const CASE_PATHS = [
  path.resolve('src/main/resources/rag-evaluation-cases.tsv'),
  path.resolve('src/main/resources/rag-evaluation-cases.generated.tsv'),
];
const ANSWER_ORACLE_PATH = path.resolve('src/main/resources/rag-answer-evaluation-oracles.tsv');

function main(argv = process.argv.slice(2)) {
  const options = parseCliOptions(argv);
  const allCases = loadEvalCases(CASE_PATHS, { answerOraclePath: ANSWER_ORACLE_PATH });
  const manifestInfo = loadTrainingManifest(options.manifestPath, allCases);
  const run1 = readJson(options.run1Path, 'run 1');
  const run2 = readJson(options.run2Path, 'run 2');
  const selection = selectWeights({ manifestInfo, run1, run2, topK: 30, rrfK: 60 });
  writeJsonAtomic(options.outputPath, selection);
  console.log(`[rrf-weight-select] ${selection.status} ${options.outputPath}`);
  return selection;
}

function parseCliOptions(argv) {
  const values = {};
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
      case '--manifest':
        values.manifestPath = readValue();
        break;
      case '--run-1':
        values.run1Path = readValue();
        break;
      case '--run-2':
        values.run2Path = readValue();
        break;
      case '--output':
        values.outputPath = readValue();
        break;
      default:
        throw new Error(`unknown option: ${flag}`);
    }
  }
  for (const [property, flag] of [
    ['manifestPath', '--manifest'],
    ['run1Path', '--run-1'],
    ['run2Path', '--run-2'],
    ['outputPath', '--output'],
  ]) {
    if (!values[property]) {
      throw new Error(`${flag} is required`);
    }
  }
  return values;
}

function readJson(filePath, label) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'));
  } catch (error) {
    throw new Error(`cannot read ${label} JSON ${filePath}: ${error.message}`);
  }
}

function writeJsonAtomic(filePath, value) {
  const content = `${JSON.stringify(value, null, 2)}\n`;
  if (fs.existsSync(filePath)) {
    const existing = fs.readFileSync(filePath, 'utf8');
    if (existing === content) {
      return false;
    }
    throw new Error(`refusing to overwrite different selection evidence: ${filePath}`);
  }
  const directory = path.dirname(filePath);
  fs.mkdirSync(directory, { recursive: true });
  const temporaryPath = path.join(directory, `.${path.basename(filePath)}.${process.pid}.tmp`);
  try {
    fs.writeFileSync(temporaryPath, content, { encoding: 'utf8', flag: 'wx' });
    fs.renameSync(temporaryPath, filePath);
  } finally {
    if (fs.existsSync(temporaryPath)) {
      fs.rmSync(temporaryPath, { force: true });
    }
  }
  return true;
}

if (require.main === module) {
  try {
    main();
  } catch (error) {
    console.error(`[rrf-weight-select] ERROR ${error?.message ?? error}`);
    process.exitCode = 1;
  }
}

module.exports = {
  main,
  parseCliOptions,
  writeJsonAtomic,
};
