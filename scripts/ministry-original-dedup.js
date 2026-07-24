const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const {
  buildPlan,
  normalizePath,
  validateManifest,
  verifyManifestFiles,
} = require("./lib/ministry-original-dedup");

const args = parseArgs(process.argv.slice(2));
const command = args._[0];
const repository = path.resolve(__dirname, "..");
const root = path.resolve(args.root || path.join(repository, "data", "rag-upload", "ministry_docs"));
const mysql = process.env.MARIADB_EXE || "C:\\Program Files\\MariaDB 12.2\\bin\\mariadb.exe";

if (!["plan", "apply"].includes(command)) {
  fail("Usage: node scripts/ministry-original-dedup.js <plan|apply> [--root PATH] [--manifest FILE] [--output DIR]");
}

if (command === "plan") {
  runPlan().catch(fail);
} else {
  runApply().catch(fail);
}

async function runPlan() {
  const references = loadDatabaseReferences();
  const manifest = await buildPlan(root, references, {
    concurrency: Number(args.concurrency || process.env.PANDORA_DEDUP_HASH_CONCURRENCY || 4),
  });
  const outputDirectory = path.resolve(
    args.output || path.join(repository, "logs", "storage-dedup", timestamp()),
  );
  fs.mkdirSync(outputDirectory, { recursive: true });
  const jsonPath = path.join(outputDirectory, "ministry-original-dedup-plan.json");
  const csvPath = path.join(outputDirectory, "ministry-original-dedup-plan.csv");
  fs.writeFileSync(jsonPath, JSON.stringify(manifest, null, 2), "utf8");
  fs.writeFileSync(csvPath, toCsv(manifest), "utf8");
  printSummary("PLAN", manifest);
  console.log(`Manifest JSON: ${jsonPath}`);
  console.log(`Manifest CSV:  ${csvPath}`);
}

async function runApply() {
  if (!args.manifest) fail("apply requires --manifest FILE");
  const manifestPath = path.resolve(args.manifest);
  const manifest = validateManifest(JSON.parse(fs.readFileSync(manifestPath, "utf8")));
  if (path.resolve(manifest.root) !== root) {
    fail(`Manifest root differs from requested root. manifest=${manifest.root} requested=${root}`);
  }
  const running = Number(dbScalar(
    "SELECT COUNT(*) FROM rag_collection_runs WHERE status = 'RUNNING'",
  ));
  if (running > 0) {
    fail(`Refusing cleanup while ${running} ministry collection run(s) are RUNNING.`);
  }
  await verifyManifestFiles(manifest);

  const currentReferences = loadDatabaseReferences();
  const pathPairs = manifest.groups.flatMap((group) => group.duplicates.map((duplicate) => ({
    oldPath: duplicate.path,
    canonicalPath: group.canonicalPath,
    references: requireUnchangedReferences(currentReferences, duplicate),
  })));
  updateDatabaseReferences(pathPairs);
  const remaining = loadReferencesForPaths(
    pathPairs
      .filter((pair) => totalReferences(pair.references) > 0)
      .map((pair) => pair.oldPath),
  );
  if (remaining.length > 0) {
    fail(`Database still references ${remaining.length} duplicate path(s); no files were deleted.`);
  }

  const results = [];
  let reclaimedBytes = 0;
  for (const group of manifest.groups) {
    for (const duplicate of group.duplicates) {
      const result = {
        canonicalPath: group.canonicalPath,
        duplicatePath: duplicate.path,
        sidecarPath: duplicate.sidecarPath,
        sha256: group.sha256,
        size: duplicate.size,
        status: "PENDING",
      };
      try {
        assertDeletablePath(root, duplicate.path);
        fs.rmSync(duplicate.path);
        reclaimedBytes += duplicate.size;
        if (duplicate.sidecarPath && fs.existsSync(duplicate.sidecarPath)) {
          assertDeletablePath(root, duplicate.sidecarPath);
          fs.rmSync(duplicate.sidecarPath);
        }
        result.status = "DELETED";
      } catch (error) {
        result.status = "DELETE_FAILED";
        result.error = error.message;
      }
      results.push(result);
    }
  }

  const resultPath = path.join(
    path.dirname(manifestPath),
    "ministry-original-dedup-apply-result.json",
  );
  fs.writeFileSync(resultPath, JSON.stringify({
    appliedAt: new Date().toISOString(),
    manifestPath,
    manifestDigest: manifest.digest,
    deletedFiles: results.filter((result) => result.status === "DELETED").length,
    failedFiles: results.filter((result) => result.status !== "DELETED").length,
    reclaimedBytes,
    results,
  }, null, 2), "utf8");
  console.log(`Deleted files: ${results.filter((result) => result.status === "DELETED").length}`);
  console.log(`Delete failures: ${results.filter((result) => result.status !== "DELETED").length}`);
  console.log(`Reclaimed bytes: ${reclaimedBytes}`);
  console.log(`Apply result: ${resultPath}`);
  if (results.some((result) => result.status !== "DELETED")) process.exitCode = 2;
}

function loadDatabaseReferences() {
  const sql = `
    SELECT HEX(path_value), SUM(document_count), SUM(attachment_count),
           SUM(imported_attachment_count), SUM(chunk_count)
    FROM (
      SELECT file_path path_value, COUNT(*) document_count, 0 attachment_count,
             0 imported_attachment_count, 0 chunk_count
      FROM rag_documents
      WHERE file_path IS NOT NULL AND file_path != ''
      GROUP BY file_path
      UNION ALL
      SELECT local_path, 0, COUNT(*), SUM(status = 'IMPORTED'), 0
      FROM rag_source_attachments
      WHERE local_path IS NOT NULL AND local_path != ''
      GROUP BY local_path
      UNION ALL
      SELECT source_path, 0, 0, 0, COUNT(*)
      FROM rag_document_chunks
      WHERE source_path IS NOT NULL AND source_path != ''
      GROUP BY source_path
    ) refs
    GROUP BY path_value
  `;
  const references = new Map();
  for (const row of dbRows(sql)) {
    const filePath = Buffer.from(row[0], "hex").toString("utf8");
    references.set(normalizePath(filePath), {
      document: Number(row[1] || 0),
      attachment: Number(row[2] || 0),
      importedAttachment: Number(row[3] || 0),
      chunk: Number(row[4] || 0),
    });
  }
  return references;
}

function updateDatabaseReferences(pathPairs) {
  const referencedPairs = pathPairs.filter((pair) => totalReferences(pair.references) > 0);
  if (referencedPairs.length === 0) return;
  const values = referencedPairs.map((pair) =>
    `(UNHEX(SHA2(${sqlHex(pair.oldPath)},256)),${sqlHex(pair.oldPath)},${sqlHex(pair.canonicalPath)})`
  ).join(",\n");
  const attachmentRefs = referencedPairs.reduce(
    (sum, pair) => sum + Number(pair.references?.attachment || 0),
    0,
  );
  const documentRefs = referencedPairs.reduce(
    (sum, pair) => sum + Number(pair.references?.document || 0),
    0,
  );
  const chunkRefs = referencedPairs.reduce(
    (sum, pair) => sum + Number(pair.references?.chunk || 0),
    0,
  );
  const updates = [];
  if (attachmentRefs > 0) {
    updates.push(`
      UPDATE rag_source_attachments target
      JOIN pandora_dedup_paths mapping
        ON mapping.old_hash = UNHEX(SHA2(target.local_path,256))
       AND target.local_path = mapping.old_path
      SET target.local_path = mapping.canonical_path
    `);
  }
  if (documentRefs > 0) {
    updates.push(`
      UPDATE rag_documents target
      JOIN pandora_dedup_paths mapping
        ON mapping.old_hash = UNHEX(SHA2(target.file_path,256))
       AND target.file_path = mapping.old_path
      SET target.file_path = mapping.canonical_path
    `);
  }
  if (chunkRefs > 0) {
    updates.push(`
      UPDATE rag_document_chunks target
      JOIN pandora_dedup_paths mapping
        ON mapping.old_hash = UNHEX(SHA2(target.source_path,256))
       AND target.source_path = mapping.old_path
      SET target.source_path = mapping.canonical_path
    `);
  }
  dbExecute(`
    CREATE TEMPORARY TABLE pandora_dedup_paths (
      old_hash BINARY(32) NOT NULL PRIMARY KEY,
      old_path TEXT NOT NULL,
      canonical_path TEXT NOT NULL
    );
    INSERT INTO pandora_dedup_paths (old_hash, old_path, canonical_path) VALUES
    ${values};
    START TRANSACTION;
    ${updates.join(";\n")};
    COMMIT;
  `);
}

function loadReferencesForPaths(filePaths) {
  if (filePaths.length === 0) return [];
  const values = filePaths.map(sqlHex).join(",");
  return dbRows(`
    SELECT 'rag_source_attachments', HEX(local_path), COUNT(*)
    FROM rag_source_attachments WHERE local_path IN (${values}) GROUP BY local_path
    UNION ALL
    SELECT 'rag_documents', HEX(file_path), COUNT(*)
    FROM rag_documents WHERE file_path IN (${values}) GROUP BY file_path
    UNION ALL
    SELECT 'rag_document_chunks', HEX(source_path), COUNT(*)
    FROM rag_document_chunks WHERE source_path IN (${values}) GROUP BY source_path
  `);
}

function dbRows(sql) {
  const output = dbCommand(sql, true).trim();
  return output ? output.split(/\r?\n/).map((line) => line.split("\t")) : [];
}

function dbScalar(sql) {
  const rows = dbRows(sql);
  return rows[0]?.[0] || "0";
}

function dbExecute(sql) {
  dbCommand(sql, false);
}

function dbCommand(sql, skipColumnNames) {
  const host = process.env.MYSQLHOST || "localhost";
  const port = process.env.MYSQLPORT || "3306";
  const user = process.env.SPRING_DATASOURCE_USERNAME || process.env.MYSQLUSER || "pandora";
  const database = process.env.MYSQLDATABASE || "pandora";
  const password = process.env.SPRING_DATASOURCE_PASSWORD || process.env.MYSQLPASSWORD || "pandora";
  const commandArgs = [
    "--ssl=0",
    "-h", host,
    "-P", String(port),
    `-u${user}`,
    "--batch",
    "--raw",
    "--default-character-set=utf8mb4",
  ];
  if (skipColumnNames) commandArgs.push("--skip-column-names");
  commandArgs.push(database);
  const result = spawnSync(mysql, commandArgs, {
    cwd: repository,
    encoding: "utf8",
    maxBuffer: 256 * 1024 * 1024,
    windowsHide: true,
    env: { ...process.env, MYSQL_PWD: password },
    input: sql,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error((result.stderr || result.stdout || `MariaDB exited ${result.status}`).trim());
  }
  return result.stdout || "";
}

function sqlHex(value) {
  return `CONVERT(UNHEX('${Buffer.from(String(value), "utf8").toString("hex")}') USING utf8mb4)`;
}

function assertDeletablePath(cleanupRoot, candidate) {
  const resolvedRoot = path.resolve(cleanupRoot);
  const resolved = path.resolve(candidate);
  const relative = path.relative(resolvedRoot, resolved);
  if (!relative || relative.startsWith("..") || path.isAbsolute(relative)) {
    throw new Error(`Refusing to delete path outside cleanup root: ${resolved}`);
  }
  const stat = fs.lstatSync(resolved);
  if (!stat.isFile() || stat.isSymbolicLink()) {
    throw new Error(`Refusing to delete non-regular file: ${resolved}`);
  }
}

function toCsv(manifest) {
  const lines = [[
    "canonical_path", "duplicate_path", "sidecar_path", "sha256", "size",
    "document_refs", "attachment_refs", "imported_attachment_refs", "chunk_refs",
  ]];
  for (const group of manifest.groups) {
    for (const duplicate of group.duplicates) {
      lines.push([
        group.canonicalPath,
        duplicate.path,
        duplicate.sidecarPath || "",
        group.sha256,
        duplicate.size,
        duplicate.references.document,
        duplicate.references.attachment,
        duplicate.references.importedAttachment,
        duplicate.references.chunk,
      ]);
    }
  }
  return lines.map((row) => row.map(csvCell).join(",")).join("\r\n") + "\r\n";
}

function csvCell(value) {
  const text = String(value ?? "");
  return `"${text.replace(/"/g, '""')}"`;
}

function printSummary(label, manifest) {
  console.log(`${label} root: ${manifest.root}`);
  console.log(`Scanned files: ${manifest.scannedFiles}`);
  console.log(`Duplicate groups: ${manifest.duplicateGroups}`);
  console.log(`Duplicate files: ${manifest.duplicateFiles}`);
  console.log(`Recoverable bytes: ${manifest.recoverableBytes}`);
  console.log(`Cross-directory duplicate groups (report only): ${manifest.crossDirectoryDuplicateGroups}`);
  console.log(`Manifest digest: ${manifest.digest}`);
}

function totalReferences(references) {
  return Number(references?.document || 0)
    + Number(references?.attachment || 0)
    + Number(references?.chunk || 0);
}

function requireUnchangedReferences(currentReferences, duplicate) {
  const current = currentReferences.get(normalizePath(duplicate.path)) || {
    document: 0,
    attachment: 0,
    importedAttachment: 0,
    chunk: 0,
  };
  const planned = duplicate.references || {};
  for (const key of ["document", "attachment", "importedAttachment", "chunk"]) {
    if (Number(current[key] || 0) !== Number(planned[key] || 0)) {
      throw new Error(`Database references changed after planning: ${duplicate.path} (${key})`);
    }
  }
  return current;
}

function timestamp() {
  return new Date().toISOString().replace(/[-:]/g, "").replace(/\..+/, "").replace("T", "-");
}

function parseArgs(values) {
  const result = { _: [] };
  for (let index = 0; index < values.length; index++) {
    const value = values[index];
    if (!value.startsWith("--")) {
      result._.push(value);
      continue;
    }
    const key = value.slice(2);
    const next = values[index + 1];
    if (!next || next.startsWith("--")) result[key] = true;
    else {
      result[key] = next;
      index++;
    }
  }
  return result;
}

function fail(error) {
  const message = error instanceof Error ? error.message : String(error);
  console.error(message);
  process.exitCode = 1;
}
