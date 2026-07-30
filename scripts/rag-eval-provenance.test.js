const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const { execFileSync, spawn } = require('node:child_process');
const test = require('node:test');
const {
  loadEvalCases,
  selectEvalCases,
} = require('./lib/rag-eval-cases');
const {
  assertEvaluationRuntimeReady,
  buildProvenance,
  buildCheckpointIdentity,
  datasetHash,
  determineRunScope,
  evaluationBreakdown,
  isCheckpointCompatible,
  isRuntimeStable,
  resolveReportPaths,
  selectionHash,
} = require('./lib/rag-eval-provenance');
const {
  assertSameManifest,
  buildBaselineManifest,
} = require('./lib/rag-baseline-manifest');

const repositoryRoot = path.resolve(__dirname, '..');
const baseEvaluationCasePaths = [
  path.join(repositoryRoot, 'src', 'main', 'resources', 'rag-evaluation-cases.tsv'),
  path.join(repositoryRoot, 'src', 'main', 'resources', 'rag-evaluation-cases.generated.tsv'),
];
const answerOraclePath = path.join(
  repositoryRoot,
  'src',
  'main',
  'resources',
  'rag-answer-evaluation-oracles.tsv',
);
const evaluationDatasetPaths = [...baseEvaluationCasePaths, answerOraclePath];

test('baseline manifest is canonical and rejects index revision drift', () => {
  const manifest = buildBaselineManifest({
    gitCommit: 'abc123',
    gitDirty: false,
    runtimeInfo: {
      runtimeArtifactSha256: 'jar-a',
      runtimeArtifactSize: 52000000,
      runtimeInstanceId: 'instance-a',
      runtimeConfigSha256: 'config-a',
      indexRevision: 'index-a',
      lexicalRevision: 'legacy-law-like-v1+rag-terms-v2-ready',
      qdrantReady: true,
      qdrantSearchFailureCount: 0,
    },
    datasetHash: 'dataset-a',
    selectionHash: 'selection-a',
  });

  assert.match(manifest.manifestId, /^[0-9a-f]{64}$/);
  assert.equal(assertSameManifest(manifest, { ...manifest }), true);
  assert.throws(
    () => assertSameManifest(manifest, { ...manifest, indexRevision: 'index-b' }),
    /indexRevision/,
  );
});

async function runGateAgainstResults(requestedIds, results, options = {}) {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pandora-rag-eval-'));
  const checkpointPath = path.join(tempDir, 'checkpoint.json');
  const outputPath = path.join(tempDir, 'output.json');
  const responseStatus = options.responseStatus ?? 200;
  const runtimeInfo = {
    indexVersion: 'law_chunks+rag_chunks_v4',
    embeddingModel: 'text-embedding-3-small',
    answerModel: 'gpt-5-mini',
    lawCollection: 'law_chunks',
    ragCollection: 'rag_chunks_v4',
    runtimeArtifactKind: 'jar',
    runtimeArtifactSha256: 'test-jar',
    runtimeArtifactSize: 123,
    runtimeInstanceId: 'test-instance',
    runtimeConfigSha256: 'test-config',
    indexRevision: 'test-index',
    lexicalRevision: 'legacy-law-like-v1+rag-terms-v2-ready',
    qdrantReady: true,
    qdrantSearchFailureCount: 0,
  };
  let evaluationRequestCount = 0;
  const server = http.createServer((request, response) => {
    response.setHeader('Content-Type', 'application/json; charset=utf-8');
    if (request.method === 'GET' && request.url === '/api/law-data/ai/debug/runtime-info') {
      response.end(JSON.stringify(runtimeInfo));
      return;
    }
    if (request.method === 'POST' && request.url === '/api/law-data/ai/debug/evaluate/gate') {
      evaluationRequestCount += 1;
      let requestBody = '';
      request.setEncoding('utf8');
      request.on('data', (chunk) => { requestBody += chunk; });
      request.on('end', () => {
        const payload = JSON.parse(requestBody || '{}');
        const responseResults = typeof results === 'function'
          ? results(payload, evaluationRequestCount)
          : results;
        const passed = responseResults.filter((result) => result.passed).length;
        const gatePassed = Object.hasOwn(options, 'responseGatePassed')
          ? options.responseGatePassed
          : responseResults.length > 0 && passed === responseResults.length;
        const responseBody = {
          results: responseResults,
          total: responseResults.length,
          passed,
          failed: responseResults.length - passed,
          passRate: responseResults.length === 0 ? 0 : passed / responseResults.length,
          gatePassed,
          minimumPassed: responseResults.length,
          blockingFailureIds: responseResults.filter((result) => !result.passed).map((result) => result.id),
        };
        response.statusCode = responseStatus;
        response.end(JSON.stringify(responseBody));
      });
      return;
    }
    response.statusCode = 404;
    response.end(JSON.stringify({ error: 'not found' }));
  });

  try {
    await new Promise((resolve, reject) => {
      server.once('error', reject);
      server.listen(0, '127.0.0.1', resolve);
    });
    const address = server.address();
    const baseUrl = `http://127.0.0.1:${address.port}`;
    if (Object.hasOwn(options, 'checkpointResults')) {
      const allCases = loadEvalCases(baseEvaluationCasePaths, { answerOraclePath });
      const selectedCases = selectEvalCases(allCases, { caseIds: requestedIds, caseLimit: 0 });
      const checkpointIdentity = buildCheckpointIdentity({
        scope: determineRunScope(selectedCases, allCases, requestedIds, 0),
        baseUrl,
        datasetHashValue: datasetHash(evaluationDatasetPaths),
        selectionHashValue: selectionHash(selectedCases),
        selectedCount: selectedCases.length,
        runtimeInfo: { ...runtimeInfo, source: 'server' },
      });
      fs.writeFileSync(checkpointPath, JSON.stringify({
        checkpointIdentity,
        results: options.checkpointResults,
      }), 'utf8');
    }
    const baselineManifestPath = options.baselineRuntimeInfo
      ? writeBaselineManifest(tempDir, requestedIds, runtimeInfo, options.baselineRuntimeInfo)
      : '';
    const child = spawn(process.execPath, [path.join(repositoryRoot, 'scripts', 'rag-eval-gate.js')], {
      cwd: repositoryRoot,
      env: {
        ...process.env,
        RAG_EVAL_ARCHIVE: 'false',
        RAG_EVAL_BASE_URL: baseUrl,
        RAG_EVAL_BASELINE_MANIFEST: baselineManifestPath,
        RAG_EVAL_CASE_BATCH_SIZE: String(options.caseBatchSize ?? 10),
        RAG_EVAL_CASE_IDS: requestedIds.join(','),
        RAG_EVAL_CHECKPOINT: checkpointPath,
        RAG_EVAL_ERROR_RETRIES: String(options.errorRetries ?? 0),
        RAG_EVAL_OUTPUT: outputPath,
        RAG_EVAL_GATE_PROFILE: options.gateProfile ?? 'release',
        RAG_EVAL_REPORT: path.join(tempDir, 'report.md'),
        RAG_EVAL_RESUME: options.resume ? 'true' : 'false',
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.stderr.on('data', (chunk) => { stderr += chunk; });
    const code = await new Promise((resolve, reject) => {
      child.once('error', reject);
      child.once('close', resolve);
    });
    const outputBody = fs.existsSync(outputPath)
      ? JSON.parse(fs.readFileSync(outputPath, 'utf8'))
      : null;
    const checkpointBody = fs.existsSync(checkpointPath)
      ? JSON.parse(fs.readFileSync(checkpointPath, 'utf8'))
      : null;
    return {
      code,
      stdout,
      stderr,
      evaluationRequestCount,
      outputBody,
      checkpointBody,
    };
  } finally {
    await new Promise((resolve) => server.close(resolve));
    fs.rmSync(tempDir, { force: true, recursive: true });
  }
}

function writeBaselineManifest(tempDir, requestedIds, runtimeInfo, runtimeOverrides) {
  const allCases = loadEvalCases(baseEvaluationCasePaths, { answerOraclePath });
  const selectedCases = selectEvalCases(allCases, { caseIds: requestedIds, caseLimit: 0 });
  const manifest = buildBaselineManifest({
    gitCommit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repositoryRoot, encoding: 'utf8' }).trim(),
    gitDirty: Boolean(execFileSync('git', ['status', '--porcelain'], { cwd: repositoryRoot, encoding: 'utf8' }).trim()),
    runtimeInfo: { ...runtimeInfo, ...runtimeOverrides },
    datasetHash: datasetHash(evaluationDatasetPaths),
    selectionHash: selectionHash(selectedCases),
  });
  const manifestPath = path.join(tempDir, 'baseline-manifest.json');
  fs.writeFileSync(manifestPath, JSON.stringify(manifest), 'utf8');
  return manifestPath;
}

test('dataset provenance hash changes when only the answer-oracle sidecar changes', (t) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'rag-eval-dataset-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const basePath = path.join(directory, 'rag-evaluation-cases.tsv');
  const generatedPath = path.join(directory, 'rag-evaluation-cases.generated.tsv');
  const oraclePath = path.join(directory, 'rag-answer-evaluation-oracles.tsv');
  fs.writeFileSync(basePath, 'base', 'utf8');
  fs.writeFileSync(generatedPath, 'generated', 'utf8');
  fs.writeFileSync(oraclePath, 'oracle-a', 'utf8');
  const before = datasetHash([basePath, generatedPath, oraclePath]);

  fs.writeFileSync(oraclePath, 'oracle-b', 'utf8');

  assert.notEqual(datasetHash([basePath, generatedPath, oraclePath]), before);
});

test('full and targeted runs use separate latest and checkpoint files', () => {
  const cases = [{ id: 'a' }, { id: 'b' }];
  assert.equal(determineRunScope(cases, cases, [], 0), 'full');
  assert.equal(determineRunScope(cases.slice(0, 1), cases, [], 1), 'targeted');
  assert.deepEqual(resolveReportPaths('full', {}), {
    outputPath: 'logs/rag-eval-gate-full-latest.json',
    reportPath: 'logs/rag-eval-gate-full-latest.md',
    checkpointPath: 'logs/rag-eval-gate-full-checkpoint.json',
  });
  assert.equal(resolveReportPaths('targeted', {}).outputPath, 'logs/rag-eval-gate-targeted-latest.json');
});

test('targeted runs cannot overwrite reserved full-run report paths', () => {
  assert.throws(
    () => resolveReportPaths('targeted', {
      RAG_EVAL_OUTPUT: 'logs/rag-eval-gate-full-latest.json',
    }),
    /targeted run cannot use reserved full-run path/i,
  );
  assert.throws(
    () => resolveReportPaths('targeted', {
      RAG_EVAL_CHECKPOINT: 'logs/rag-eval-gate-full-checkpoint.json',
    }),
    /targeted run cannot use reserved full-run path/i,
  );
  assert.throws(
    () => resolveReportPaths('targeted', {
      RAG_EVAL_OUTPUT: 'logs/rag-eval-gate-latest.json',
    }),
    /targeted run cannot use reserved full-run path/i,
  );
  assert.throws(
    () => resolveReportPaths('targeted', {
      RAG_EVAL_CHECKPOINT: 'logs/rag-eval-gate-full-latest.checkpoint.json',
    }),
    /targeted run cannot use reserved full-run path/i,
  );
});

test('evaluation breakdown separates curated, generated, and answer verification', () => {
  const result = evaluationBreakdown([
    { id: 'manual', passed: true, answerVerificationRequired: true, answerVerified: true },
    { id: 'gen-one', passed: true, answerVerificationRequired: false, answerVerified: true },
    { id: 'gen-two', passed: false, answerVerificationRequired: false, answerVerified: true },
  ]);
  assert.deepEqual(result.curated, { total: 1, passed: 1, failed: 0, passRate: 1 });
  assert.deepEqual(result.generated, { total: 2, passed: 1, failed: 1, passRate: 0.5 });
  assert.equal(result.answerVerification.required, 1);
  assert.equal(result.answerVerification.passed, 1);
});

test('provenance records runtime identity without secrets', () => {
  const provenance = buildProvenance({
    scope: 'full',
    baseUrl: 'http://127.0.0.1:8080',
    gitCommit: 'abc123',
    gitDirty: false,
    datasetHashValue: 'dataset',
    selectionHashValue: 'selection',
    selectedCount: 10,
    totalCaseCount: 10,
    runtimeInfo: {
      source: 'server',
      indexVersion: 'law_chunks+rag_chunks_v4',
      embeddingModel: 'text-embedding-3-small',
      answerModel: 'gpt-5-mini',
      lawCollection: 'law_chunks',
      ragCollection: 'rag_chunks_v4',
      runtimeArtifactKind: 'jar',
      runtimeArtifactSha256: 'jar-sha-256',
      runtimeArtifactSize: 51851066,
      runtimeInstanceId: 'instance-a',
      runtimeConfigSha256: 'config-a',
      indexRevision: 'snapshot-a',
      lexicalRevision: 'legacy-law-like-v1+rag-terms-v2-ready',
      qdrantReady: true,
      qdrantSearchFailureCount: 0,
    },
  });
  assert.equal(provenance.executionPort, 8080);
  assert.equal(provenance.indexVersion, 'law_chunks+rag_chunks_v4');
  assert.equal(provenance.runtimeArtifactSha256, 'jar-sha-256');
  assert.equal(provenance.runtimeArtifactSize, 51851066);
  assert.equal(provenance.runtimeInstanceId, 'instance-a');
  assert.equal(provenance.runtimeConfigSha256, 'config-a');
  assert.equal(provenance.indexRevision, 'snapshot-a');
  assert.equal(provenance.lexicalRevision, 'legacy-law-like-v1+rag-terms-v2-ready');
  assert.equal(provenance.qdrantReady, true);
  assert.equal(provenance.qdrantSearchFailureCount, 0);
  assert.equal(Object.hasOwn(provenance, 'apiKey'), false);
  assert.equal(Object.hasOwn(provenance, 'runtimeArtifactPath'), false);
});

test('missing runtime artifact size remains unknown instead of becoming zero', () => {
  const provenance = buildProvenance({
    scope: 'targeted',
    baseUrl: 'http://127.0.0.1:8080',
    selectedCount: 1,
    totalCaseCount: 1,
    runtimeInfo: { source: 'server', runtimeArtifactSize: null },
  });

  assert.equal(provenance.runtimeArtifactSize, null);
});

test('checkpoint reuse requires the same inputs and server artifact', () => {
  const identity = buildCheckpointIdentity({
    scope: 'targeted',
    baseUrl: 'http://127.0.0.1:8080',
    datasetHashValue: 'dataset-a',
    selectionHashValue: 'selection-a',
    selectedCount: 12,
    gateProfile: 'release',
    runtimeInfo: {
      source: 'server',
      runtimeArtifactSha256: 'jar-a',
      runtimeInstanceId: 'instance-a',
      runtimeConfigSha256: 'config-a',
      indexRevision: 'snapshot-a',
      lexicalRevision: 'legacy-law-like-v1+rag-terms-v2-ready',
      qdrantReady: true,
      qdrantSearchFailureCount: 0,
      indexVersion: 'law_chunks+rag_chunks_v4',
      embeddingModel: 'text-embedding-3-small',
      answerModel: 'gpt-5-mini',
      lawCollection: 'law_chunks',
      ragCollection: 'rag_chunks_v4',
    },
  });

  assert.equal(identity.gateProfile, 'release');
  assert.equal(isCheckpointCompatible({ checkpointIdentity: identity }, identity), true);
  assert.equal(isCheckpointCompatible({ checkpointIdentity: identity }, {
    ...identity,
    gateProfile: 'curated',
  }), false);
  assert.equal(isCheckpointCompatible({
    checkpointIdentity: { ...identity, selectionHash: 'selection-b' },
  }, identity), false);
  assert.equal(isCheckpointCompatible({
    checkpointIdentity: { ...identity, runtimeArtifactSha256: 'jar-b' },
  }, identity), false);
  assert.equal(isCheckpointCompatible({
    checkpointIdentity: { ...identity, runtimeArtifactSha256: null },
  }, { ...identity, runtimeArtifactSha256: null }), false);
  assert.equal(isCheckpointCompatible({ checkpointIdentity: identity }, {
    ...identity,
    runtimeInfoSource: 'environment',
  }), false);
  assert.equal(isCheckpointCompatible({ checkpointIdentity: identity }, {
    ...identity,
    indexRevision: null,
  }), false);
  assert.equal(isCheckpointCompatible({ checkpointIdentity: identity }, {
    ...identity,
    qdrantSearchFailureCount: 1,
  }), false);
  assert.equal(isCheckpointCompatible({ results: [{ id: 'old' }] }, identity), false);
});

test('runtime stability detects same-jar restarts and configuration changes', () => {
  const runtime = {
    source: 'server',
    runtimeArtifactSha256: 'jar-a',
    runtimeInstanceId: 'instance-a',
    runtimeConfigSha256: 'config-a',
    indexRevision: null,
    lexicalRevision: 'legacy-law-like-v1+rag-terms-v2-ready',
    indexVersion: 'law_chunks+rag_chunks_v4',
    embeddingModel: 'text-embedding-3-small',
    answerModel: 'gpt-5-mini',
    lawCollection: 'law_chunks',
    ragCollection: 'rag_chunks_v4',
    qdrantReady: true,
    qdrantSearchFailureCount: 7,
  };

  assert.equal(isRuntimeStable(runtime, { ...runtime }), true);
  assert.equal(isRuntimeStable(runtime, { ...runtime, runtimeInstanceId: 'instance-b' }), false);
  assert.equal(isRuntimeStable(runtime, { ...runtime, runtimeConfigSha256: 'config-b' }), false);
  assert.equal(isRuntimeStable(runtime, { ...runtime, qdrantReady: false }), false);
  assert.equal(isRuntimeStable(runtime, { ...runtime, qdrantSearchFailureCount: 8 }), false);
  assert.equal(isRuntimeStable(runtime, { ...runtime, qdrantSearchFailureCount: undefined }), false);
  assert.equal(isRuntimeStable(runtime, { ...runtime, source: 'environment' }), false);
  assert.equal(isRuntimeStable({ ...runtime, runtimeInstanceId: null }, runtime), false);
});

test('evaluation preflight fails closed when Qdrant is not ready', () => {
  assert.doesNotThrow(() => assertEvaluationRuntimeReady({
    source: 'server',
    qdrantReady: true,
    qdrantSearchFailureCount: 0,
  }, 'targeted'));
  assert.doesNotThrow(() => assertEvaluationRuntimeReady({
    source: 'server',
    qdrantReady: true,
    qdrantSearchFailureCount: 0,
    indexRevision: 'dynamic-watermark-a',
  }, 'full'));
  assert.throws(
    () => assertEvaluationRuntimeReady({ source: 'server', qdrantReady: false }, 'targeted'),
    /qdrant.*not ready/i,
  );
  assert.throws(
    () => assertEvaluationRuntimeReady({ source: 'environment', qdrantReady: true }, 'targeted'),
    /server runtime.*unavailable/i,
  );
  assert.throws(
    () => assertEvaluationRuntimeReady({
      source: 'server',
      qdrantReady: true,
      qdrantSearchFailureCount: 0,
      indexRevision: null,
    }, 'full'),
    /full gate.*index revision/i,
  );
  assert.throws(
    () => assertEvaluationRuntimeReady({ source: 'server', qdrantReady: true }, 'targeted'),
    /search failure counter.*unavailable/i,
  );
  assert.throws(
    () => assertEvaluationRuntimeReady({
      source: 'server',
      qdrantReady: true,
      qdrantSearchFailureCount: -1,
    }, 'targeted'),
    /search failure counter.*unavailable/i,
  );
  assert.throws(
    () => assertEvaluationRuntimeReady({
      source: 'server',
      qdrantReady: true,
      qdrantSearchFailureCount: Number.MAX_SAFE_INTEGER + 1,
    }, 'targeted'),
    /search failure counter.*unavailable/i,
  );
  assert.throws(
    () => assertEvaluationRuntimeReady({
      source: 'server',
      qdrantReady: true,
      qdrantSearchFailureCount: 0,
      indexRevision: '   ',
    }, 'full'),
    /full gate.*index revision/i,
  );
});

test('gate accepts a response with exactly one result per requested case ID', async () => {
  const result = await runGateAgainstResults(
    ['project-review-target', 'project-review-simple-software'],
    [
      { id: 'project-review-target', passed: true, resultMsg: 'OK' },
      { id: 'project-review-simple-software', passed: true, resultMsg: 'OK' },
    ],
  );

  assert.equal(result.code, 0, result.stderr);
  assert.match(result.stdout, /PASS 2\/2/);
});

test('gate rejects a supplied baseline manifest before evaluation when the index revision differs', async () => {
  const result = await runGateAgainstResults(
    ['project-review-target'],
    [{ id: 'project-review-target', passed: true, resultMsg: 'OK' }],
    { baselineRuntimeInfo: { indexRevision: 'different-index' } },
  );

  assert.notEqual(result.code, 0, result.stdout);
  assert.equal(result.evaluationRequestCount, 0);
  assert.equal(result.outputBody, null);
  assert.match(result.stderr, /baseline manifest mismatch: indexRevision/i);
});

test('gate fails closed when a generated result uses a truthy non-boolean passed value', async () => {
  const result = await runGateAgainstResults(
    ['gen-official-89414'],
    [{ id: 'gen-official-89414', passed: 'true', resultMsg: 'OK' }],
  );

  assert.notEqual(result.code, 0, result.stdout);
  assert.equal(result.outputBody.passed, 0);
  assert.equal(result.outputBody.failed, 1);
  assert.deepEqual(result.outputBody.blockingFailureIds, ['gen-official-89414']);
});

test('gate restores selected case expectations before reporting blocking gates', async () => {
  const result = await runGateAgainstResults(
    ['project-review-target', 'no-oecd-footer-as-policy-ground'],
    [
      { id: 'project-review-target', passed: true, resultMsg: 'OK' },
      { id: 'no-oecd-footer-as-policy-ground', passed: true, resultMsg: 'NO_GROUNDS' },
    ],
  );

  assert.equal(result.code, 0, result.stderr);
  assert.equal(result.outputBody.results[0].answerVerificationRequired, true);
  assert.deepEqual(result.outputBody.results[1].expectedResultMsgs, ['NO_GROUNDS']);
  assert.deepEqual(result.outputBody.blockingGates.noGrounds, {
    total: 1,
    passed: 1,
    failed: 0,
    passRate: 1,
    gatePassed: true,
    blockingFailureIds: [],
  });
});

test('no-grounds gate profile is recorded in the provenance', async () => {
  const expectedCases = loadEvalCases(baseEvaluationCasePaths, { answerOraclePath }).filter((item) =>
    item.expectedResultMsgs.includes('NO_GROUNDS') || item.id.startsWith('no-'));
  const result = await runGateAgainstResults(
    [],
    (payload) => payload.cases.map((item) => ({
      id: item.id,
      passed: true,
      resultMsg: 'NO_GROUNDS',
    })),
    { gateProfile: 'no-grounds', caseBatchSize: 0 },
  );

  assert.equal(result.code, 0, result.stderr);
  assert.equal(result.outputBody.provenance.gateProfile, 'no-grounds');
  assert.equal(result.outputBody.total, expectedCases.length);
  assert.equal(result.outputBody.results.every((item) =>
    item.expectedResultMsgs.includes('NO_GROUNDS') || item.id.startsWith('no-')), true);
});

test('gate fails closed when a successful response omits a requested case ID', async () => {
  const result = await runGateAgainstResults(
    ['project-review-target', 'project-review-simple-software'],
    [{ id: 'project-review-target', passed: true, resultMsg: 'OK' }],
  );

  assert.notEqual(result.code, 0, result.stdout);
  assert.match(result.stderr, /missing response IDs.*project-review-simple-software/i);
});

test('gate fails closed when a successful response duplicates a case ID', async () => {
  const result = await runGateAgainstResults(
    ['project-review-target', 'project-review-simple-software'],
    [
      { id: 'project-review-target', passed: true, resultMsg: 'OK' },
      { id: 'project-review-target', passed: true, resultMsg: 'OK' },
    ],
  );

  assert.notEqual(result.code, 0, result.stdout);
  assert.match(result.stderr, /duplicate response IDs.*project-review-target/i);
});

test('gate fails closed when a successful response contains an unexpected case ID', async () => {
  const result = await runGateAgainstResults(
    ['project-review-target', 'project-review-simple-software'],
    [
      { id: 'project-review-target', passed: true, resultMsg: 'OK' },
      { id: 'unexpected-case', passed: true, resultMsg: 'OK' },
    ],
  );

  assert.notEqual(result.code, 0, result.stdout);
  assert.match(result.stderr, /unexpected response IDs.*unexpected-case/i);
});

test('gate treats a 409 evaluation response as a completed failing gate', async () => {
  const result = await runGateAgainstResults(
    ['project-review-target', 'project-review-simple-software'],
    [
      { id: 'project-review-target', passed: true, resultMsg: 'OK' },
      { id: 'project-review-simple-software', passed: false, resultMsg: 'NO_GROUNDS' },
    ],
    { responseStatus: 409 },
  );

  assert.notEqual(result.code, 0, result.stdout);
  assert.match(result.stderr, /\[rag-eval-gate] FAIL 1\/2/);
  assert.doesNotMatch(result.stderr, /HTTP 409/);
});

test('gate rejects a 409 response whose exact results all passed', async () => {
  const result = await runGateAgainstResults(
    ['project-review-target', 'project-review-simple-software'],
    [
      { id: 'project-review-target', passed: true, resultMsg: 'OK' },
      { id: 'project-review-simple-software', passed: true, resultMsg: 'OK' },
    ],
    { responseStatus: 409 },
  );

  assert.notEqual(result.code, 0, result.stdout);
  assert.match(result.stderr, /HTTP 409/);
});

test('gate rejects a contradictory 409 response marked failed when all exact results passed', async () => {
  const result = await runGateAgainstResults(
    ['project-review-target', 'project-review-simple-software'],
    [
      { id: 'project-review-target', passed: true, resultMsg: 'OK' },
      { id: 'project-review-simple-software', passed: true, resultMsg: 'OK' },
    ],
    { responseStatus: 409, responseGatePassed: false },
  );

  assert.notEqual(result.code, 0, result.stdout);
  assert.match(result.stderr, /HTTP 409/);
});

test('gate rejects an evaluation-shaped 500 response even when every result passed', async () => {
  const result = await runGateAgainstResults(
    ['project-review-target', 'project-review-simple-software'],
    [
      { id: 'project-review-target', passed: true, resultMsg: 'OK' },
      { id: 'project-review-simple-software', passed: true, resultMsg: 'OK' },
    ],
    { responseStatus: 500 },
  );

  assert.notEqual(result.code, 0, result.stdout);
  assert.match(result.stderr, /HTTP 500/);
});

test('gate preserves exact response validation across multiple batches', async () => {
  const result = await runGateAgainstResults(
    ['project-review-target', 'project-review-simple-software'],
    (payload) => payload.cases.map((item) => ({ id: item.id, passed: true, resultMsg: 'OK' })),
    { caseBatchSize: 1 },
  );

  assert.equal(result.code, 0, result.stderr);
  assert.equal(result.evaluationRequestCount, 2);
  assert.match(result.stdout, /PASS 2\/2/);
});

test('gate persists successful evaluation-error retries in the final batch checkpoint', async () => {
  const result = await runGateAgainstResults(
    ['project-review-target', 'project-review-simple-software'],
    (payload, requestCount) => (requestCount === 1
      ? [{ id: payload.cases[0].id, passed: false, resultMsg: 'EVALUATION_ERROR' }]
      : payload.cases.map((item) => ({ id: item.id, passed: true, resultMsg: 'OK' }))),
    { caseBatchSize: 1, errorRetries: 1 },
  );

  assert.equal(result.code, 0, result.stderr);
  assert.equal(result.evaluationRequestCount, 3);
  assert.ok(result.outputBody);
  assert.ok(result.checkpointBody);
  assert.deepEqual(result.checkpointBody.results, result.outputBody.results);
  assert.deepEqual(
    {
      total: result.checkpointBody.total,
      passed: result.checkpointBody.passed,
      failed: result.checkpointBody.failed,
      passRate: result.checkpointBody.passRate,
      gatePassed: result.checkpointBody.gatePassed,
      minimumPassed: result.checkpointBody.minimumPassed,
      blockingFailureIds: result.checkpointBody.blockingFailureIds,
    },
    {
      total: result.outputBody.total,
      passed: result.outputBody.passed,
      failed: result.outputBody.failed,
      passRate: result.outputBody.passRate,
      gatePassed: result.outputBody.gatePassed,
      minimumPassed: result.outputBody.minimumPassed,
      blockingFailureIds: result.outputBody.blockingFailureIds,
    },
  );
  assert.equal(result.checkpointBody.checkpoint, true);
  assert.ok(result.checkpointBody.checkpointIdentity);
  assert.ok(result.checkpointBody.checkpointUpdatedAt);
  assert.equal(Object.hasOwn(result.outputBody, 'checkpoint'), false);
  assert.equal(Object.hasOwn(result.outputBody, 'checkpointIdentity'), false);
  assert.equal(Object.hasOwn(result.outputBody, 'checkpointUpdatedAt'), false);
});

test('gate resumes from a valid partial compatible checkpoint', async () => {
  const result = await runGateAgainstResults(
    ['project-review-target', 'project-review-simple-software'],
    (payload) => payload.cases.map((item) => ({ id: item.id, passed: true, resultMsg: 'OK' })),
    {
      caseBatchSize: 1,
      resume: true,
      checkpointResults: [
        { id: 'project-review-target', passed: true, resultMsg: 'OK' },
      ],
    },
  );

  assert.equal(result.code, 0, result.stderr);
  assert.equal(result.evaluationRequestCount, 1);
  assert.match(result.stdout, /skipping batch 1\/2 from checkpoint/);
});

test('gate rejects a compatible checkpoint with an invalid result ID before evaluation', async () => {
  const result = await runGateAgainstResults(
    ['project-review-target', 'project-review-simple-software'],
    (payload) => payload.cases.map((item) => ({ id: item.id, passed: true, resultMsg: 'OK' })),
    {
      caseBatchSize: 1,
      resume: true,
      checkpointResults: [{ id: null, passed: true, resultMsg: 'OK' }],
    },
  );

  assert.notEqual(result.code, 0, result.stdout);
  assert.equal(result.evaluationRequestCount, 0);
  assert.match(result.stderr, /checkpoint.*invalid response ID/i);
});

test('gate rejects a compatible checkpoint with an unexpected result ID before evaluation', async () => {
  const result = await runGateAgainstResults(
    ['project-review-target', 'project-review-simple-software'],
    (payload) => payload.cases.map((item) => ({ id: item.id, passed: true, resultMsg: 'OK' })),
    {
      caseBatchSize: 1,
      resume: true,
      checkpointResults: [{ id: 'unexpected-case', passed: true, resultMsg: 'OK' }],
    },
  );

  assert.notEqual(result.code, 0, result.stdout);
  assert.equal(result.evaluationRequestCount, 0);
  assert.match(result.stderr, /checkpoint.*unexpected response ID.*unexpected-case/i);
});

test('gate rejects a compatible checkpoint with duplicate result IDs before evaluation', async () => {
  const result = await runGateAgainstResults(
    ['project-review-target', 'project-review-simple-software'],
    (payload) => payload.cases.map((item) => ({ id: item.id, passed: true, resultMsg: 'OK' })),
    {
      caseBatchSize: 1,
      resume: true,
      checkpointResults: [
        { id: 'project-review-target', passed: true, resultMsg: 'OK' },
        { id: 'project-review-target', passed: true, resultMsg: 'OK' },
      ],
    },
  );

  assert.notEqual(result.code, 0, result.stdout);
  assert.equal(result.evaluationRequestCount, 0);
  assert.match(result.stderr, /checkpoint.*duplicate response ID.*project-review-target/i);
});

test('retry responses keep exact ID validation for missing, duplicate, and unexpected IDs', async (t) => {
  const malformedRetries = [
    {
      name: 'missing',
      results: [],
      error: /missing response IDs.*project-review-target/i,
    },
    {
      name: 'duplicate',
      results: [
        { id: 'project-review-target', passed: true, resultMsg: 'OK' },
        { id: 'project-review-target', passed: true, resultMsg: 'OK' },
      ],
      error: /duplicate response IDs.*project-review-target/i,
    },
    {
      name: 'unexpected',
      results: [{ id: 'unexpected-case', passed: true, resultMsg: 'OK' }],
      error: /unexpected response IDs.*unexpected-case/i,
    },
  ];

  for (const malformed of malformedRetries) {
    await t.test(malformed.name, async () => {
      const result = await runGateAgainstResults(
        ['project-review-target', 'project-review-simple-software'],
        (_payload, requestCount) => (requestCount === 1
          ? [
            { id: 'project-review-target', passed: false, resultMsg: 'EVALUATION_ERROR' },
            { id: 'project-review-simple-software', passed: true, resultMsg: 'OK' },
          ]
          : malformed.results),
        { errorRetries: 1 },
      );

      assert.notEqual(result.code, 0, result.stdout);
      assert.equal(result.evaluationRequestCount, 2);
      assert.match(result.stderr, malformed.error);
    });
  }
});
