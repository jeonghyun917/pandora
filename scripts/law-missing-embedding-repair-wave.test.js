const assert = require("node:assert/strict");
const test = require("node:test");
const {
  planRepairWave,
  assertSuccessfulApply,
  assertPostWaveInvariants,
  runPostWaveAudits,
} = require("./law-missing-embedding-repair-wave");

test("planner binds a bounded document wave to the full audit runtime fence", () => {
  const plan = planRepairWave(audit([
    issue(101, 11),
    issue(102, 11),
    issue(201, 22),
  ]), { maxDocuments: 1, maxCandidates: 10 });

  assert.deepEqual(plan, {
    target: "law",
    expectedRuntimeInstanceId: "instance-a",
    expectedIndexRevision: "revision-a",
    expectedDocumentIds: [11],
    candidates: [
      { chunkId: 101, expectedChunkContentHash: "a".repeat(64) },
      { chunkId: 102, expectedChunkContentHash: "a".repeat(64) },
    ],
    apply: false,
  });
});

test("planner rejects malformed, non-missing, or unfenced audit issues", () => {
  assert.throws(() => planRepairWave(audit([{ ...issue(101, 11), cause: "QDRANT_POINT_MISSING" }])), /MISSING_EMBEDDING_ROW/);
  assert.throws(() => planRepairWave(audit([{ ...issue(101, 0) }])), /documentId/);
  assert.throws(() => planRepairWave({ ...audit([issue(101, 11)]), runtimeInstanceId: "" }), /runtime/);
});

test("planner never claims a document owner after the candidate cap is reached", () => {
  const plan = planRepairWave(audit([
    issue(101, 11),
    issue(102, 11),
    issue(201, 22),
  ]), { maxDocuments: 50, maxCandidates: 2 });

  assert.deepEqual(plan.expectedDocumentIds, [11]);
  assert.deepEqual(plan.candidates.map((candidate) => candidate.chunkId), [101, 102]);
});

test("runner rejects an incomplete or per-id failed apply result", () => {
  assert.throws(() => assertSuccessfulApply({ applied: true, complete: false, outcomes: [] }), /incomplete/);
  assert.throws(() => assertSuccessfulApply({ applied: true, complete: true, outcomes: [{ state: "VERIFICATION_FAILED" }] }), /failed/);
  assert.doesNotThrow(() => assertSuccessfulApply({ applied: true, complete: true, outcomes: [{ state: "INDEXED" }] }));
});

test("post-wave validation requires reconciled full audits, coverage, and DB-Qdrant counts", () => {
  assert.doesNotThrow(() => assertPostWaveInvariants({
    beforeAudit: audit([issue(101, 11), issue(102, 11)]),
    result: successfulResult(101, 102),
    integrityAudit: fullIntegrityAudit(0),
    parentChildAudit: parentChildAudit(10, 0),
    shortChunkAudit: { total: 3, summary: [], applyRequested: false, applyCompleted: false },
    runtimeInfo: runtimeInfo(10),
  }));
  assert.throws(() => assertPostWaveInvariants({
    beforeAudit: audit([issue(101, 11), issue(102, 11)]),
    result: successfulResult(101, 102),
    integrityAudit: fullIntegrityAudit(3),
    parentChildAudit: parentChildAudit(10, 3),
    shortChunkAudit: { total: 3, summary: [], applyRequested: false, applyCompleted: false },
    runtimeInfo: runtimeInfo(7),
  }), /backlog/);
  assert.throws(() => assertPostWaveInvariants({
    beforeAudit: audit([issue(101, 11)]),
    result: successfulResult(101),
    integrityAudit: fullIntegrityAudit(0),
    parentChildAudit: parentChildAudit(10, 0),
    shortChunkAudit: { total: 3, summary: [], applyRequested: false, applyCompleted: false },
    runtimeInfo: { ...runtimeInfo(10), lawQdrantExactPointCount: null },
  }), /DB-Qdrant/);
  assert.throws(() => assertPostWaveInvariants({
    beforeAudit: audit([issue(101, 11)]),
    result: successfulResult(101),
    integrityAudit: { ...fullIntegrityAudit(0), causeCounts: null },
    parentChildAudit: parentChildAudit(10, 0),
    shortChunkAudit: { total: 3, summary: [], applyRequested: false, applyCompleted: false },
    runtimeInfo: runtimeInfo(10),
  }), /causeCounts/);
  const auditWithoutCauseCounts = fullIntegrityAudit(0);
  delete auditWithoutCauseCounts.causeCounts;
  assert.throws(() => assertPostWaveInvariants({
    beforeAudit: audit([issue(101, 11)]),
    result: successfulResult(101),
    integrityAudit: auditWithoutCauseCounts,
    parentChildAudit: parentChildAudit(10, 0),
    shortChunkAudit: { total: 3, summary: [], applyRequested: false, applyCompleted: false },
    runtimeInfo: runtimeInfo(10),
  }), /causeCounts/);
  assert.throws(() => assertPostWaveInvariants({
    beforeAudit: audit([issue(101, 11)]),
    result: successfulResult(101),
    integrityAudit: { ...fullIntegrityAudit(0), causeCounts: { MISSING_EMBEDDING_ROW: "0" } },
    parentChildAudit: parentChildAudit(10, 0),
    shortChunkAudit: { total: 3, summary: [], applyRequested: false, applyCompleted: false },
    runtimeInfo: runtimeInfo(10),
  }), /causeCounts/);
});

test("post-wave runner invokes every required audit before returning success", async () => {
  const calls = [];
  const result = successfulResult(101);
  const output = await runPostWaveAudits({
    beforeAudit: audit([issue(101, 11)]),
    result,
    runIntegrityAudit: async () => {
      calls.push("integrity");
      return fullIntegrityAudit(0);
    },
    runParentChildAudit: async () => {
      calls.push("parent-child");
      return parentChildAudit(10, 0);
    },
    runShortChunkAudit: async () => {
      calls.push("short");
      return { total: 3, summary: [], applyRequested: false, applyCompleted: false };
    },
    loadRuntimeInfo: async () => {
      calls.push("runtime");
      return runtimeInfo(10);
    },
  });

  assert.deepEqual(calls, ["integrity", "parent-child", "short", "runtime"]);
  assert.equal(output.integrityAudit.causeCounts.MISSING_EMBEDDING_ROW, undefined);
});

function audit(issues) {
  return {
    target: "law",
    runtimeInstanceId: "instance-a",
    indexRevision: "revision-a",
    causeCounts: issues.reduce((counts, issue) => {
      counts[issue.cause] = (counts[issue.cause] || 0) + 1;
      return counts;
    }, {}),
    issues,
  };
}

function issue(chunkId, documentId) {
  return { cause: "MISSING_EMBEDDING_ROW", chunkId, documentId, chunkContentHash: "a".repeat(64), embeddingContentHash: null };
}

function successfulResult(...chunkIds) {
  return {
    applied: true,
    complete: true,
    runtime: { runtimeInstanceId: "instance-a", indexRevision: "revision-after-write" },
    outcomes: chunkIds.map((chunkId) => ({ state: "INDEXED", chunkId })),
  };
}

function fullIntegrityAudit(missingEmbeddingRows) {
  return {
    target: "law",
    pages: 1,
    scannedRows: 10,
    runtimeInstanceId: "instance-a",
    indexRevision: "revision-after-write",
    causeCounts: missingEmbeddingRows ? { MISSING_EMBEDDING_ROW: missingEmbeddingRows } : {},
    issues: Array.from({ length: missingEmbeddingRows }, (_, index) => issue(300 + index, 30 + index)),
  };
}

function parentChildAudit(chunkCount, missingEmbeddingRows) {
  return {
    qualitySummary: [{ target: "law", currentChunks: chunkCount }],
    metadataGaps: [{ target: "law", chunks: chunkCount, missingTitle: 0, missingHash: 0, notIndexed: missingEmbeddingRows }],
    embeddingStatus: [
      { target: "law", vectorStore: "(none)", status: "NO_EMBED", chunks: missingEmbeddingRows },
      { target: "law", vectorStore: "law_chunks", status: "INDEXED", chunks: chunkCount - missingEmbeddingRows },
    ],
  };
}

function runtimeInfo(lawIndexedCount) {
  return {
    runtimeInstanceId: "instance-a",
    indexRevision: "revision-after-write",
    qdrantReady: true,
    qdrantSearchFailureCount: 0,
    lawQdrantExactPointCount: lawIndexedCount,
    lawDatabaseIndexedCount: lawIndexedCount,
    ragQdrantExactPointCount: 9,
    ragDatabaseIndexedCount: 9,
  };
}
