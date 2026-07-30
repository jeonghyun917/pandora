const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const {
  buildPlan,
  canonicalComparator,
  manifestDigest,
  validateManifest,
} = require("./lib/ministry-original-dedup");

function withTempDir(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "pandora-dedup-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  return root;
}

test("buildPlan groups only exact hashes in the same article directory", async (t) => {
  const root = withTempDir(t);
  const article = path.join(root, "msit", "2026", "42");
  const otherArticle = path.join(root, "msit", "2026", "43");
  fs.mkdirSync(article, { recursive: true });
  fs.mkdirSync(otherArticle, { recursive: true });
  fs.writeFileSync(path.join(article, "guide.hwpx"), "same");
  fs.writeFileSync(path.join(article, "guide-2.hwpx"), "same");
  fs.writeFileSync(path.join(article, "guide-3.hwpx"), "changed");
  fs.writeFileSync(path.join(otherArticle, "guide.hwpx"), "same");

  const plan = await buildPlan(root, new Map(), { concurrency: 2 });

  assert.equal(plan.groups.length, 1);
  assert.equal(plan.groups[0].duplicates.length, 1);
  assert.equal(plan.groups[0].canonicalPath, path.join(article, "guide.hwpx"));
  assert.equal(plan.recoverableBytes, 4);
  assert.equal(plan.crossDirectoryDuplicateGroups, 1);
});

test("canonical selection prefers active document reference over unsuffixed name", () => {
  const references = new Map([
    ["C:\\docs\\guide-2.hwpx", { document: 1, importedAttachment: 0, attachment: 0, chunk: 0 }],
  ]);
  const compare = canonicalComparator(references);
  const files = [
    { path: "C:\\docs\\guide.hwpx", unsuffixed: true, mtimeMs: 1 },
    { path: "C:\\docs\\guide-2.hwpx", unsuffixed: false, mtimeMs: 2 },
  ];

  files.sort(compare);

  assert.equal(files[0].path, "C:\\docs\\guide-2.hwpx");
});

test("plan pairs only the duplicate file matching sidecar", async (t) => {
  const root = withTempDir(t);
  const article = path.join(root, "mois", "2026", "7");
  fs.mkdirSync(article, { recursive: true });
  fs.writeFileSync(path.join(article, "guide.pdf"), "same");
  fs.writeFileSync(path.join(article, "guide-2.pdf"), "same");
  fs.writeFileSync(path.join(article, "guide.meta.json"), "{}");
  fs.writeFileSync(path.join(article, "guide-2.meta.json"), "{}");

  const plan = await buildPlan(root, new Map());

  assert.equal(plan.groups[0].duplicates[0].sidecarPath, path.join(article, "guide-2.meta.json"));
});

test("manifest validation rejects changed digest and paths outside root", () => {
  const root = path.resolve("C:\\safe\\ministry_docs");
  const manifest = {
    algorithmVersion: 1,
    root,
    groups: [{
      canonicalPath: path.join(root, "a", "guide.pdf"),
      sha256: "a".repeat(64),
      duplicates: [{
        path: "C:\\outside\\guide.pdf",
        sha256: "a".repeat(64),
        size: 4,
        sidecarPath: null,
      }],
    }],
  };
  manifest.digest = manifestDigest(manifest);

  assert.throws(() => validateManifest(manifest), /outside cleanup root/i);

  manifest.groups[0].duplicates[0].path = path.join(root, "a", "guide-2.pdf");
  assert.throws(() => validateManifest(manifest), /digest/i);
});

test("CLI help exits successfully without attempting a cleanup command", () => {
  const result = spawnSync(
    process.execPath,
    [path.join(__dirname, "ministry-original-dedup.js"), "--help"],
    { encoding: "utf8", windowsHide: true },
  );

  assert.equal(result.status, 0);
  assert.match(result.stdout, /Usage: node scripts\/ministry-original-dedup\.js/);
  assert.equal(result.stderr, "");
});
