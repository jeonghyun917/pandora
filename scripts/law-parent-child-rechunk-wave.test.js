const test = require("node:test");
const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const path = require("node:path");

test("help describes the fail-closed candidate activation workflow", () => {
  const script = path.resolve(__dirname, "law-parent-child-rechunk-wave.js");
  const result = spawnSync(process.execPath, [script, "--help"], { encoding: "utf8" });

  assert.equal(result.status, 0);
  assert.match(result.stdout, /preview\s*->\s*create-candidate\s*->\s*index\s*->\s*verify\s*->\s*activate/i);
  assert.match(result.stdout, /--apply=false/);
});
