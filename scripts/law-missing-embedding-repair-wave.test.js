const assert = require("node:assert/strict");
const test = require("node:test");
const { planRepairWave, assertSuccessfulApply } = require("./law-missing-embedding-repair-wave");

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

function audit(issues) {
  return { target: "law", runtimeInstanceId: "instance-a", indexRevision: "revision-a", issues };
}

function issue(chunkId, documentId) {
  return { cause: "MISSING_EMBEDDING_ROW", chunkId, documentId, chunkContentHash: "a".repeat(64), embeddingContentHash: null };
}
