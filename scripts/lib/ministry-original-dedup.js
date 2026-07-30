const crypto = require("node:crypto");
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const path = require("node:path");

const ALGORITHM_VERSION = 1;
const CONTENT_EXTENSIONS = new Set([".pdf", ".hwpx", ".docx", ".hwp", ".doc", ".txt", ".md"]);

async function buildPlan(rootInput, references = new Map(), options = {}) {
  const root = path.resolve(rootInput);
  const concurrency = Math.max(1, Number(options.concurrency || 4));
  const files = await listContentFiles(root);
  const hashed = await mapLimit(files, concurrency, async (file) => ({
    ...file,
    sha256: await sha256File(file.path),
  }));

  const byDirectoryAndHash = new Map();
  const byHash = new Map();
  for (const file of hashed) {
    const directoryKey = `${normalizePath(path.dirname(file.path))}\0${file.sha256}`;
    pushMap(byDirectoryAndHash, directoryKey, file);
    pushMap(byHash, file.sha256, file);
  }

  const groups = [];
  for (const candidates of byDirectoryAndHash.values()) {
    if (candidates.length < 2) continue;
    candidates.sort(canonicalComparator(references));
    const [canonical, ...duplicates] = candidates;
    groups.push({
      directory: path.dirname(canonical.path),
      sha256: canonical.sha256,
      canonicalPath: canonical.path,
      canonicalSize: canonical.size,
      canonicalReferences: referenceCounts(references, canonical.path),
      duplicates: duplicates.map((duplicate) => ({
        path: duplicate.path,
        sha256: duplicate.sha256,
        size: duplicate.size,
        sidecarPath: matchingSidecar(duplicate.path),
        references: referenceCounts(references, duplicate.path),
      })),
    });
  }
  groups.sort((left, right) => left.canonicalPath.localeCompare(right.canonicalPath, "en"));

  const crossDirectoryDuplicateGroups = [...byHash.values()].filter((candidates) => {
    const directories = new Set(candidates.map((file) => normalizePath(path.dirname(file.path))));
    return directories.size > 1;
  }).length;
  const recoverableBytes = groups.reduce(
    (sum, group) => sum + group.duplicates.reduce((groupSum, duplicate) => groupSum + duplicate.size, 0),
    0,
  );
  const manifest = {
    algorithmVersion: ALGORITHM_VERSION,
    createdAt: new Date().toISOString(),
    root,
    scannedFiles: hashed.length,
    scannedBytes: hashed.reduce((sum, file) => sum + file.size, 0),
    duplicateGroups: groups.length,
    duplicateFiles: groups.reduce((sum, group) => sum + group.duplicates.length, 0),
    recoverableBytes,
    crossDirectoryDuplicateGroups,
    groups,
  };
  manifest.digest = manifestDigest(manifest);
  return manifest;
}

function canonicalComparator(references) {
  return (left, right) => {
    const leftRefs = referenceCounts(references, left.path);
    const rightRefs = referenceCounts(references, right.path);
    const rankings = [
      compareDescending(leftRefs.document, rightRefs.document),
      compareDescending(leftRefs.importedAttachment, rightRefs.importedAttachment),
      compareDescending(left.unsuffixed ? 1 : 0, right.unsuffixed ? 1 : 0),
      Number(left.mtimeMs || 0) - Number(right.mtimeMs || 0),
      left.path.localeCompare(right.path, "en"),
    ];
    return rankings.find((value) => value !== 0) || 0;
  };
}

function manifestDigest(manifest) {
  const copy = JSON.parse(JSON.stringify(manifest));
  delete copy.digest;
  return crypto.createHash("sha256").update(stableJson(copy)).digest("hex");
}

function validateManifest(manifest) {
  if (!manifest || manifest.algorithmVersion !== ALGORITHM_VERSION) {
    throw new Error("Unsupported deduplication manifest algorithm version.");
  }
  if (!manifest.root || !Array.isArray(manifest.groups)) {
    throw new Error("Invalid deduplication manifest.");
  }
  if (!manifest.digest || manifest.digest !== manifestDigest(manifest)) {
    throw new Error("Manifest digest does not match its contents.");
  }
  const root = path.resolve(manifest.root);
  for (const group of manifest.groups) {
    assertBelowRoot(root, group.canonicalPath);
    for (const duplicate of group.duplicates || []) {
      assertBelowRoot(root, duplicate.path);
      if (duplicate.sidecarPath) assertBelowRoot(root, duplicate.sidecarPath);
      if (duplicate.sha256 !== group.sha256) {
        throw new Error(`Duplicate hash differs from canonical group: ${duplicate.path}`);
      }
    }
  }
  return manifest;
}

async function verifyManifestFiles(manifest) {
  validateManifest(manifest);
  for (const group of manifest.groups) {
    await assertRegularFile(group.canonicalPath);
    if (await sha256File(group.canonicalPath) !== group.sha256) {
      throw new Error(`Canonical file changed after planning: ${group.canonicalPath}`);
    }
    for (const duplicate of group.duplicates) {
      await assertRegularFile(duplicate.path);
      if (await sha256File(duplicate.path) !== group.sha256) {
        throw new Error(`Duplicate file changed after planning: ${duplicate.path}`);
      }
    }
  }
}

async function listContentFiles(root) {
  const result = [];
  async function visit(directory) {
    const entries = await fsp.readdir(directory, { withFileTypes: true });
    for (const entry of entries) {
      const candidate = path.resolve(directory, entry.name);
      assertBelowRoot(root, candidate);
      if (entry.isSymbolicLink()) {
        throw new Error(`Symbolic link or junction is not allowed below cleanup root: ${candidate}`);
      }
      if (entry.isDirectory()) {
        await visit(candidate);
      } else if (entry.isFile() && CONTENT_EXTENSIONS.has(path.extname(entry.name).toLowerCase())) {
        const stat = await fsp.lstat(candidate);
        result.push({
          path: candidate,
          size: stat.size,
          mtimeMs: stat.mtimeMs,
          unsuffixed: !/-\d+(?=\.[^.]+$)/.test(entry.name),
        });
      }
    }
  }
  await visit(path.resolve(root));
  return result;
}

async function sha256File(file) {
  const hash = crypto.createHash("sha256");
  const stream = fs.createReadStream(file);
  for await (const chunk of stream) hash.update(chunk);
  return hash.digest("hex");
}

function matchingSidecar(contentPath) {
  const extension = path.extname(contentPath);
  const sidecar = contentPath.slice(0, -extension.length) + ".meta.json";
  if (!fs.existsSync(sidecar)) return null;
  const stat = fs.lstatSync(sidecar);
  return stat.isFile() && !stat.isSymbolicLink() ? sidecar : null;
}

function assertBelowRoot(rootInput, candidateInput) {
  const root = path.resolve(rootInput);
  const candidate = path.resolve(candidateInput);
  const relative = path.relative(root, candidate);
  if (!relative || relative.startsWith("..") || path.isAbsolute(relative)) {
    throw new Error(`Path is outside cleanup root or points at the root itself: ${candidate}`);
  }
}

async function assertRegularFile(file) {
  const stat = await fsp.lstat(file);
  if (!stat.isFile() || stat.isSymbolicLink()) {
    throw new Error(`Manifest path is not a regular file: ${file}`);
  }
}

function referenceCounts(references, filePath) {
  const direct = references.get(filePath) || references.get(normalizePath(filePath)) || {};
  return {
    document: Number(direct.document || 0),
    attachment: Number(direct.attachment || 0),
    importedAttachment: Number(direct.importedAttachment || 0),
    chunk: Number(direct.chunk || 0),
  };
}

function normalizePath(value) {
  return path.resolve(value).toLowerCase();
}

function stableJson(value) {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableJson(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function pushMap(map, key, value) {
  if (!map.has(key)) map.set(key, []);
  map.get(key).push(value);
}

function compareDescending(left, right) {
  return Number(right || 0) - Number(left || 0);
}

async function mapLimit(values, concurrency, worker) {
  const results = new Array(values.length);
  let cursor = 0;
  async function run() {
    while (true) {
      const index = cursor++;
      if (index >= values.length) return;
      results[index] = await worker(values[index], index);
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, values.length || 1) }, run));
  return results;
}

module.exports = {
  ALGORITHM_VERSION,
  buildPlan,
  canonicalComparator,
  manifestDigest,
  normalizePath,
  sha256File,
  validateManifest,
  verifyManifestFiles,
};
