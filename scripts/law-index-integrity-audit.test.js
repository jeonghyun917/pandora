const assert = require("node:assert/strict");
const test = require("node:test");
const { runtimeMetadata, sameRuntime, pageProgress, runAndWriteAudit } = require("./law-index-integrity-audit");

test("runtime metadata requires authoritative nonblank identity fields", () => {
  assert.deepEqual(runtimeMetadata({ runtimeInstanceId: "instance-a", indexRevision: "revision-a" }), {
    runtimeInstanceId: "instance-a",
    indexRevision: "revision-a",
  });
  assert.throws(() => runtimeMetadata({ runtimeInstanceId: "instance-a", indexRevision: " " }), /incomplete/);
});

test("runtime drift is detected across the audit window", () => {
  assert.equal(
	 sameRuntime(
      { runtimeInstanceId: "instance-a", indexRevision: "revision-a" },
      { runtimeInstanceId: "instance-a", indexRevision: "revision-a" },
    ),
    true,
  );
  assert.equal(
    sameRuntime(
      { runtimeInstanceId: "instance-a", indexRevision: "revision-a" },
      { runtimeInstanceId: "instance-a", indexRevision: "revision-b" },
    ),
    false,
  );
	assert.equal(
		sameRuntime(
			{ runtimeInstanceId: "instance-a", indexRevision: "revision-a" },
			{ runtimeInstanceId: "instance-a", indexRevision: "revision-a" },
			{ runtimeInstanceId: "instance-b", indexRevision: "revision-a" },
		),
		false,
	);
});

test("full audit pages require a strictly advancing cursor", () => {
  assert.deepEqual(
    pageProgress({ scannedRows: 10_000, lastScannedChunkId: 55 }, 10, 10_000),
    { complete: false, afterChunkId: 55 },
  );
  assert.deepEqual(
    pageProgress({ scannedRows: 3, lastScannedChunkId: 58 }, 55, 10_000),
    { complete: true, afterChunkId: 58 },
  );
  assert.throws(
    () => pageProgress({ scannedRows: 10_000, lastScannedChunkId: 10 }, 10, 10_000),
    /advance/,
  );
});

test("full audit aggregates multiple bounded pages before writing one artifact", async () => {
  const { artifact, writes } = await executePages([
    report({ scannedRows: 2, lastScannedChunkId: 2, issues: [issue("QDRANT_POINT_MISSING", 1)] }),
    report({ scannedRows: 1, lastScannedChunkId: 3, issues: [issue("MISSING_EMBEDDING_ROW", 3)] }),
  ]);

  assert.equal(writes.length, 1);
  assert.equal(artifact.pages, 2);
  assert.equal(artifact.scannedRows, 3);
  assert.deepEqual(artifact.causeCounts, {
    QDRANT_POINT_MISSING: 1,
    MISSING_EMBEDDING_ROW: 1,
  });
  assert.deepEqual(artifact.issues.map((value) => value.chunkId), [1, 3]);
});

test("full audit requests an empty terminal page after an exact-limit page", async () => {
  const { artifact, writes, auditPaths } = await executePages([
    report({ scannedRows: 2, lastScannedChunkId: 2 }),
    report({ scannedRows: 0, lastScannedChunkId: 2 }),
  ]);

  assert.equal(writes.length, 1);
  assert.equal(artifact.pages, 2);
  assert.equal(artifact.scannedRows, 2);
  assert.match(auditPaths[0], /afterChunkId=0/);
  assert.match(auditPaths[1], /afterChunkId=2/);
});

test("full audit rejects later-page runtime drift without writing an artifact", async () => {
  const writes = await rejectedPages(
    [
      report({ scannedRows: 2, lastScannedChunkId: 2 }),
      report({ scannedRows: 1, lastScannedChunkId: 3, indexRevision: "revision-b" }),
    ],
    /drifted/,
  );

  assert.deepEqual(writes, []);
});

test("full audit rejects malformed or mismatched pages without writing an artifact", async (t) => {
  for (const [name, page, message] of [
    ["lower limit", report({ limit: 1, scannedRows: 1, lastScannedChunkId: 1 }), /limit/],
    ["target mismatch", report({ target: "admrul", scannedRows: 1, lastScannedChunkId: 1 }), /target/],
    ["invalid issue", report({ scannedRows: 1, lastScannedChunkId: 1, issues: [{ cause: "UNKNOWN", chunkId: 1, chunkContentHash: null, embeddingContentHash: null }] }), /cause/],
    ["invalid issue id", report({ scannedRows: 1, lastScannedChunkId: 1, issues: [issue("QDRANT_POINT_MISSING", 0)] }), /chunkId/],
    ["invalid issue hash", report({ scannedRows: 1, lastScannedChunkId: 1, issues: [{ ...issue("QDRANT_POINT_MISSING", 1), chunkContentHash: 42 }] }), /chunkContentHash/],
    ["cause mismatch", report({ scannedRows: 1, lastScannedChunkId: 1, issues: [issue("QDRANT_POINT_MISSING", 1)], causeCounts: {} }), /causeCounts/],
  ]) {
    await t.test(name, async () => {
      assert.deepEqual(await rejectedPages([page], message), []);
    });
  }
});

function issue(cause, chunkId) {
  return { cause, chunkId, chunkContentHash: null, embeddingContentHash: null };
}

function report({
  target = "law",
  limit = 2,
  scannedRows,
  lastScannedChunkId,
  issues = [],
  causeCounts = Object.fromEntries(issues.map((value) => [value.cause, 1])),
  runtimeInstanceId = "instance-a",
  indexRevision = "revision-a",
}) {
  return { target, limit, scannedRows, lastScannedChunkId, issues, causeCounts, runtimeInstanceId, indexRevision };
}

async function executePages(pages) {
  const writes = [];
  const auditPaths = [];
  const artifact = await runAndWriteAudit({
    requestedTarget: "law",
    requestedLimit: 2,
    fetchJson: async (pathname) => {
      if (pathname === "/api/law-data/ai/debug/runtime-info") {
        return { runtimeInstanceId: "instance-a", indexRevision: "revision-a" };
      }
      auditPaths.push(pathname);
      const next = pages.shift();
      if (!next) throw new Error("Unexpected audit request");
      return next;
    },
    writeArtifact: (value) => writes.push(value),
  }).catch((error) => {
    error.writes = writes;
    throw error;
  });
  return { artifact, writes, auditPaths };
}

async function rejectedPages(pages, message) {
  try {
    await executePages(pages);
    assert.fail("Expected the audit to reject");
  } catch (error) {
    assert.match(error.message, message);
    return error.writes;
  }
}
