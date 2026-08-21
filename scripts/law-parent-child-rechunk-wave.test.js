const test = require("node:test");
const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const path = require("node:path");
const { candidateArtifact, manifestIdentityForSelection } = require("./law-parent-child-rechunk-wave.js");

test("help describes the fail-closed candidate activation workflow", () => {
  const script = path.resolve(__dirname, "law-parent-child-rechunk-wave.js");
  const result = spawnSync(process.execPath, [script, "--help"], { encoding: "utf8" });

  assert.equal(result.status, 0);
  assert.match(result.stdout, /preview\s*->\s*create-candidate\s*->\s*index\s*->\s*verify\s*->\s*activate/i);
  assert.match(result.stdout, /--apply=false/);
});

test("candidate artifact binds a deterministic manifest, point identities, and executable rollback", () => {
  const selection = [{ documentId: 42, target: "law", projectedChunks: 3 }];
  const manifestIdentity = manifestIdentityForSelection(selection);
  const artifact = candidateArtifact({
    documentId: 42,
    target: "law",
    chunkVersion: 2,
    chunkIds: [202, 203, 204],
  }, { version: 1, pointIds: [101, 102] }, manifestIdentity);

  assert.match(manifestIdentity, /^selection-fingerprint:[0-9a-f]{64}$/);
  assert.deepEqual(artifact.oldPointIds, [101, 102]);
  assert.deepEqual(artifact.newPointIds, [202, 203, 204]);
  assert.equal(artifact.oldChunkVersion, 1);
  assert.equal(artifact.newChunkVersion, 2);
  assert.equal(artifact.rollbackApiPath, "/api/law-data/chunks/rollback-version?documentId=42&retiredVersion=1");
  assert.match(artifact.rollbackCommand, /^Invoke-RestMethod -Method Post -Uri 'http:\/\/127\.0\.0\.1:8080\/api\/law-data\/chunks\/rollback-version\?documentId=42&retiredVersion=1'$/);
});
