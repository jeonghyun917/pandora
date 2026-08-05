const assert = require("node:assert/strict");
const test = require("node:test");
const {
  planRepairWave,
  assertSuccessfulApply,
  assertPostWaveInvariants,
  runPostWaveAudits,
  runDurableRepairOperation,
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

test("durable runner registers once and steps exact candidates sequentially", async () => {
  const calls = [];
  let mutationsInFlight = 0;
  let peakMutationsInFlight = 0;
  const first = operationView("READY", [itemView(101, "READY"), itemView(102, "READY")]);
  const afterFirst = operationView("RUNNING", [itemView(101, "INDEXED"), itemView(102, "READY")]);
  const complete = operationView("INDEXING_COMPLETE", [itemView(101, "INDEXED"), itemView(102, "INDEXED")]);
  const views = [first, afterFirst, complete];
  let viewIndex = 0;

  const result = await runDurableRepairOperation(durableRequest([{ chunkId: 101 }, { chunkId: 102 }]), {
    fetch: async (url, options = {}) => {
      const mutating = options.method === "POST";
      if (mutating) {
        mutationsInFlight += 1;
        peakMutationsInFlight = Math.max(peakMutationsInFlight, mutationsInFlight);
      }
      calls.push(`${options.method || "GET"} ${url}`);
      try {
        return jsonResponse(mutating && url.endsWith("operations") ? 202 : 200, views[viewIndex++]);
      } finally {
        if (mutating) mutationsInFlight -= 1;
      }
    },
    sleep: async () => assert.fail("no poll expected"),
    timeoutMs: 100,
    maxPolls: 3,
  });

  assert.equal(peakMutationsInFlight, 1);
  assert.deepEqual(calls, [
    "POST http://127.0.0.1:8080/api/admin/law-index-integrity/missing-embedding-repair-operations",
    "POST http://127.0.0.1:8080/api/admin/law-index-integrity/missing-embedding-repair-operations/op-1/step",
    "POST http://127.0.0.1:8080/api/admin/law-index-integrity/missing-embedding-repair-operations/op-1/step",
  ]);
  assert.equal(result.operation.request.operationId, "op-1");
  assert.equal(result.transportAttempts.filter((attempt) => attempt.kind === "step").length, 2);
});

test("durable runner reconciles lost step response before any further step", async () => {
  const calls = [];
  const registered = operationView("READY", [itemView(101, "READY")]);
  const committed = operationView("INDEXING_COMPLETE", [itemView(101, "INDEXED")]);
  let stepCalls = 0;

  const result = await runDurableRepairOperation(durableRequest([{ chunkId: 101 }]), {
    fetch: async (url, options = {}) => {
      calls.push(`${options.method || "GET"} ${url}`);
      if (url.endsWith("operations")) return jsonResponse(202, registered);
      if (options.method === "POST") {
        stepCalls += 1;
        throw new TypeError("socket closed after commit");
      }
      return jsonResponse(200, committed);
    },
    sleep: async () => assert.fail("terminal GET needs no sleep"),
    timeoutMs: 100,
    maxPolls: 3,
  });

  assert.equal(stepCalls, 1);
  assert.deepEqual(calls, [
    "POST http://127.0.0.1:8080/api/admin/law-index-integrity/missing-embedding-repair-operations",
    "POST http://127.0.0.1:8080/api/admin/law-index-integrity/missing-embedding-repair-operations/op-1/step",
    "GET http://127.0.0.1:8080/api/admin/law-index-integrity/missing-embedding-repair-operations/op-1",
  ]);
  assert.equal(result.operation.progress.status, "INDEXING_COMPLETE");
  assert.equal(result.transportAttempts.find((attempt) => attempt.kind === "step").outcome, "transport-error");
});

test("durable runner continues from a GET-confirmed indexed item without replaying its step", async () => {
  const calls = [];
  const registered = operationView("READY", [itemView(101, "READY"), itemView(102, "READY")]);
  const firstIndexed = operationView("RUNNING", [itemView(101, "INDEXED"), itemView(102, "READY")]);
  const complete = operationView("INDEXING_COMPLETE", [itemView(101, "INDEXED"), itemView(102, "INDEXED")]);
  let stepCalls = 0;
  const result = await runDurableRepairOperation(durableRequest([{ chunkId: 101 }, { chunkId: 102 }]), {
    fetch: async (url, options = {}) => {
      calls.push(`${options.method || "GET"} ${url}`);
      if (url.endsWith("operations")) return jsonResponse(202, registered);
      if (options.method === "POST") {
        stepCalls += 1;
        if (stepCalls === 1) throw new TypeError("first step response lost");
        return jsonResponse(200, complete);
      }
      return jsonResponse(200, firstIndexed);
    },
    sleep: async () => assert.fail("indexed GET should advance directly"), timeoutMs: 100, maxPolls: 3,
  });

  assert.equal(stepCalls, 2);
  assert.deepEqual(calls.slice(0, 4), [
    "POST http://127.0.0.1:8080/api/admin/law-index-integrity/missing-embedding-repair-operations",
    "POST http://127.0.0.1:8080/api/admin/law-index-integrity/missing-embedding-repair-operations/op-1/step",
    "GET http://127.0.0.1:8080/api/admin/law-index-integrity/missing-embedding-repair-operations/op-1",
    "POST http://127.0.0.1:8080/api/admin/law-index-integrity/missing-embedding-repair-operations/op-1/step",
  ]);
  assert.equal(result.outcomes.filter((outcome) => outcome.state === "INDEXED").length, 2);
});

test("durable runner repeats only identical registration after a lost register response", async () => {
  const requests = [];
  const registered = operationView("INDEXING_COMPLETE", [itemView(101, "INDEXED")]);
  const result = await runDurableRepairOperation(durableRequest([{ chunkId: 101 }]), {
    fetch: async (url, options = {}) => {
      requests.push({ url, method: options.method, body: options.body });
      if (requests.length === 1) throw new TypeError("register response lost");
      return jsonResponse(202, registered);
    },
    sleep: async () => assert.fail("no poll expected"), timeoutMs: 100, maxPolls: 2,
  });

  assert.equal(requests.length, 2);
  assert.equal(requests[0].body, requests[1].body);
  assert.equal(result.transportAttempts.filter((attempt) => attempt.kind === "register").length, 2);
  assert.equal(result.transportAttempts[0].outcome, "transport-error");
});

test("durable runner polls a live processing item without issuing a second step", async () => {
  const calls = [];
  const processing = operationView("RUNNING", [itemView(101, "PROCESSING")]);
  const complete = operationView("INDEXING_COMPLETE", [itemView(101, "INDEXED")]);
  const result = await runDurableRepairOperation(durableRequest([{ chunkId: 101 }]), {
    fetch: async (url, options = {}) => {
      calls.push(`${options.method || "GET"} ${url}`);
      return jsonResponse(url.endsWith("operations") ? 202 : 200, calls.length === 1 ? processing : complete);
    },
    sleep: async () => {}, timeoutMs: 100, maxPolls: 2,
  });

  assert.equal(result.operation.progress.status, "INDEXING_COMPLETE");
  assert.equal(calls.filter((call) => call.startsWith("POST") && call.endsWith("/step")).length, 0);
  assert.equal(calls.filter((call) => call.startsWith("GET")).length, 1);
});

test("durable runner does not retry HTTP 4xx or runtime drift as transport failures", async () => {
  let calls = 0;
  await assert.rejects(() => runDurableRepairOperation(durableRequest([{ chunkId: 101 }]), {
    fetch: async () => { calls += 1; return { ok: false, status: 409, text: async () => "fence" }; },
    sleep: async () => {}, timeoutMs: 100, maxPolls: 2,
  }), /HTTP 409/);
  assert.equal(calls, 1);

  await assert.rejects(() => runDurableRepairOperation(durableRequest([{ chunkId: 101 }]), {
    fetch: async () => jsonResponse(202, {
      operation: { request: { operationId: "op-1", runtimeInstanceId: "different-instance" }, progress: { status: "READY" } },
      items: [itemView(101, "READY")],
    }),
    sleep: async () => {}, timeoutMs: 100, maxPolls: 2,
  }), /unknown or malformed/);
});

test("durable runner injects timeout signals and records malformed responses without retrying them", async () => {
  let timeoutCalls = 0;
  await assert.rejects(() => runDurableRepairOperation(durableRequest([{ chunkId: 101 }]), {
    fetch: async (_url, options) => {
      assert.deepEqual(options.signal, { injected: 100 });
      return { ok: true, status: 202, text: async () => "not-json" };
    },
    timeoutSignal: (milliseconds) => { timeoutCalls += 1; return { injected: milliseconds }; },
    sleep: async () => {}, timeoutMs: 100, maxPolls: 2,
  }), /malformed JSON/);
  assert.equal(timeoutCalls, 1);
});

test("durable runner fails closed for failed or unknown durable state", async () => {
  for (const state of ["FAILED", "ALIEN_STATE"]) {
    await assert.rejects(() => runDurableRepairOperation(durableRequest([{ chunkId: 101 }]), {
      fetch: async (url) => jsonResponse(url.endsWith("operations") ? 202 : 200,
        operationView(state, [itemView(101, state === "FAILED" ? "FAILED" : "READY")])),
      sleep: async () => {}, timeoutMs: 100, maxPolls: 2,
    }), /failed|unknown/i);
  }
});

test("successful durable apply requires exact planned IDs and indexing completion", () => {
  const complete = operationView("INDEXING_COMPLETE", [itemView(101, "INDEXED"), itemView(102, "INDEXED")]);
  assert.doesNotThrow(() => assertSuccessfulApply(complete, [{ chunkId: 101 }, { chunkId: 102 }]));
  assert.throws(() => assertSuccessfulApply(operationView("RUNNING", [itemView(101, "INDEXED"), itemView(102, "INDEXED")]), [{ chunkId: 101 }, { chunkId: 102 }]), /incomplete/);
  assert.throws(() => assertSuccessfulApply(operationView("INDEXING_COMPLETE", [itemView(101, "INDEXED"), itemView(103, "INDEXED")]), [{ chunkId: 101 }, { chunkId: 102 }]), /every requested/);
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

test("post-wave validation separates target-law coverage from mixed-target law collection totals", () => {
  const parentAudit = parentChildAudit(10, 0);
  parentAudit.embeddingStatus.push({ target: "admrul", vectorStore: "law_chunks", status: "INDEXED", chunks: 100 });
  parentAudit.runtimeComparableIndexed.rows.push({ target: "admrul", chunks: 100 });

  assert.doesNotThrow(() => assertPostWaveInvariants({
    beforeAudit: audit([issue(101, 11), issue(102, 11)]),
    result: successfulResult(101, 102),
    integrityAudit: fullIntegrityAudit(0),
    parentChildAudit: parentAudit,
    shortChunkAudit: { total: 3, summary: [], applyRequested: false, applyCompleted: false },
    runtimeInfo: runtimeInfo(110),
  }));
  assert.throws(() => assertPostWaveInvariants({
    beforeAudit: audit([issue(101, 11), issue(102, 11)]),
    result: successfulResult(101, 102),
    integrityAudit: fullIntegrityAudit(0),
    parentChildAudit: parentAudit,
    shortChunkAudit: { total: 3, summary: [], applyRequested: false, applyCompleted: false },
    runtimeInfo: runtimeInfo(111),
  }), /collection coverage/);
  const malformedParentAudit = parentChildAudit(10, 0);
  malformedParentAudit.runtimeComparableIndexed.rows.push({ target: "admrul", chunks: null });
  assert.throws(() => assertPostWaveInvariants({
    beforeAudit: audit([issue(101, 11), issue(102, 11)]),
    result: successfulResult(101, 102),
    integrityAudit: fullIntegrityAudit(0),
    parentChildAudit: malformedParentAudit,
    shortChunkAudit: { total: 3, summary: [], applyRequested: false, applyCompleted: false },
    runtimeInfo: runtimeInfo(10),
  }), /comparable/);
});

test("post-wave validation uses the runtime-comparable metric instead of raw stale or alternate embedding rows", () => {
  const parentAudit = parentChildAudit(10, 0);
  parentAudit.embeddingStatus.push(
    { target: "law", vectorStore: "law_chunks", status: "INDEXED", chunks: 2, reason: "stale-hash" },
    { target: "law", vectorStore: "law_chunks", status: "INDEXED", chunks: 3, reason: "inactive" },
    { target: "law", vectorStore: "law_chunks", status: "INDEXED", chunks: 5, reason: "alternate-model" },
    { target: "admrul", vectorStore: "law_chunks", status: "INDEXED", chunks: 100 }
  );
  parentAudit.runtimeComparableIndexed.rows.push({ target: "admrul", chunks: 100 });

  assert.doesNotThrow(() => assertPostWaveInvariants({
    beforeAudit: audit([issue(101, 11), issue(102, 11)]),
    result: successfulResult(101, 102),
    integrityAudit: fullIntegrityAudit(0),
    parentChildAudit: parentAudit,
    shortChunkAudit: { total: 3, summary: [], applyRequested: false, applyCompleted: false },
    runtimeInfo: runtimeInfo(110),
  }));
  assert.throws(() => assertPostWaveInvariants({
    beforeAudit: audit([issue(101, 11), issue(102, 11)]),
    result: successfulResult(101, 102),
    integrityAudit: fullIntegrityAudit(0),
    parentChildAudit: { ...parentAudit, runtimeComparableIndexed: null },
    shortChunkAudit: { total: 3, summary: [], applyRequested: false, applyCompleted: false },
    runtimeInfo: runtimeInfo(110),
  }), /comparable/);
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
    runtimeComparableIndexed: {
      embeddingModel: "text-embedding-3-small",
      vectorStore: "law_chunks",
      rows: [{ target: "law", chunks: chunkCount - missingEmbeddingRows }],
    },
  };
}

function runtimeInfo(lawIndexedCount) {
  return {
    runtimeInstanceId: "instance-a",
    indexRevision: "revision-after-write",
    qdrantReady: true,
    qdrantSearchFailureCount: 0,
    lawCollection: "law_chunks",
    embeddingModel: "text-embedding-3-small",
    lawQdrantExactPointCount: lawIndexedCount,
    lawDatabaseIndexedCount: lawIndexedCount,
    ragQdrantExactPointCount: 9,
    ragDatabaseIndexedCount: 9,
  };
}

function operationView(status, items) {
  return {
    operation: {
      request: { operationId: "op-1", runtimeInstanceId: "instance-a" },
      progress: { status, trustedIndexRevision: "revision-a" },
    },
    items,
  };
}

function itemView(chunkId, state) {
  return { chunkId, state };
}

function durableRequest(candidates) {
  return { target: "law", expectedRuntimeInstanceId: "instance-a", expectedIndexRevision: "revision-a", candidates };
}

function jsonResponse(status, value) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(value),
  };
}
