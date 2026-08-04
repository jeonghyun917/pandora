const assert = require("node:assert/strict");
const test = require("node:test");
const { runtimeMetadata, sameRuntime, pageProgress } = require("./law-index-integrity-audit");

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
