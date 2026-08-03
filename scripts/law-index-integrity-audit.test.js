const assert = require("node:assert/strict");
const test = require("node:test");
const { runtimeMetadata, sameRuntime } = require("./law-index-integrity-audit");

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
