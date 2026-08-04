const fs = require("node:fs");
const path = require("node:path");

const workspace = path.resolve(__dirname, "..");
const apiBaseUrl = (process.env.PANDORA_API_BASE_URL || "http://127.0.0.1:8080").replace(/\/$/, "");
const adminToken = process.env.PANDORA_ADMIN_TOKEN || "";
const args = Object.fromEntries(process.argv.slice(2).map((arg) => {
  const [key, ...rest] = arg.replace(/^--/, "").split("=");
  return [key, rest.join("=") || "true"];
}));
const target = String(args.target || "").trim();
const limit = Math.max(1, Math.min(Number(args.limit || 1000), 10000));
const jsonPath = path.resolve(workspace, "logs", "law-index-integrity-audit-latest.json");
const markdownPath = path.resolve(workspace, "logs", "law-index-integrity-audit-latest.md");

function requestOptions() {
  return {
    headers: adminToken ? { "X-Pandora-Admin-Token": adminToken } : {},
    signal: AbortSignal.timeout(30000),
  };
}

function runtimeMetadata(runtimeInfo) {
  const runtimeInstanceId = String(runtimeInfo?.runtimeInstanceId || "").trim();
  const indexRevision = String(runtimeInfo?.indexRevision || "").trim();
  if (!runtimeInstanceId || !indexRevision) {
    throw new Error("Runtime metadata was incomplete: runtimeInstanceId and indexRevision are required.");
  }
  return { runtimeInstanceId, indexRevision };
}

function sameRuntime(...runtimeInfos) {
  return runtimeInfos.length > 0 && runtimeInfos.every((runtimeInfo) =>
    runtimeInfo.runtimeInstanceId === runtimeInfos[0].runtimeInstanceId
      && runtimeInfo.indexRevision === runtimeInfos[0].indexRevision
  );
}

function pageProgress(report, afterChunkId, pageLimit) {
  const scannedRows = Number(report?.scannedRows);
  const lastScannedChunkId = Number(report?.lastScannedChunkId);
  if (!Number.isInteger(scannedRows) || scannedRows < 0 || scannedRows > pageLimit) {
    throw new Error("Integrity audit page returned an invalid scannedRows value.");
  }
  if (scannedRows === 0) {
    return { complete: true, afterChunkId };
  }
  if (!Number.isSafeInteger(lastScannedChunkId) || lastScannedChunkId <= afterChunkId) {
    throw new Error("Integrity audit page did not advance its chunk cursor.");
  }
  return { complete: scannedRows < pageLimit, afterChunkId: lastScannedChunkId };
}

async function fetchJson(pathname) {
  const response = await fetch(`${apiBaseUrl}${pathname}`, requestOptions());
  if (!response.ok) {
    throw new Error(`Request failed for ${pathname}: HTTP ${response.status}`);
  }
  return response.json();
}

function markdown(artifact) {
  const rows = artifact.issues.map((issue) =>
    `| ${issue.cause} | ${issue.chunkId} | ${issue.chunkContentHash || ""} | ${issue.embeddingContentHash || ""} |`
  );
  const counts = Object.entries(artifact.causeCounts)
    .map(([cause, count]) => `- ${cause}: ${count}`)
    .join("\n") || "- none";
  return [
    "# Law Index Integrity Audit",
    "",
    `- Generated: ${artifact.generatedAt}`,
    `- Runtime instance: ${artifact.runtimeInstanceId}`,
    `- Index revision: ${artifact.indexRevision}`,
    `- Target: ${artifact.target || "all"}`,
    `- Limit: ${artifact.limit}`,
    `- Pages: ${artifact.pages}`,
    `- Scanned rows: ${artifact.scannedRows}`,
    "",
    "## Cause counts",
    "",
    counts,
    "",
    "## Issues",
    "",
    "| Cause | Chunk ID | Chunk content hash | Embedding content hash |",
    "| --- | ---: | --- | --- |",
    ...(rows.length ? rows : ["| none | | | |"]),
    "",
  ].join("\n");
}

async function main() {
  const runtimeBefore = runtimeMetadata(await fetchJson("/api/law-data/ai/debug/runtime-info"));
  const issues = [];
  const causeCounts = {};
  let afterChunkId = 0;
  let pages = 0;
  let scannedRows = 0;
  let runtimeDuringAudit = null;
  let reportedTarget = target;
  while (true) {
    const report = await fetchJson(
      `/api/admin/law-index-integrity/audit?target=${encodeURIComponent(target)}&limit=${limit}&afterChunkId=${afterChunkId}`
    );
    const pageRuntime = runtimeMetadata(report);
    if (!sameRuntime(runtimeBefore, pageRuntime)) {
      throw new Error("Runtime metadata drifted while the integrity audit was running.");
    }
    if (!Array.isArray(report.issues) || typeof report.causeCounts !== "object" || report.causeCounts === null) {
      throw new Error("Integrity audit response was malformed.");
    }
    const progress = pageProgress(report, afterChunkId, limit);
    pages += 1;
    scannedRows += Number(report.scannedRows);
    runtimeDuringAudit = pageRuntime;
    reportedTarget = report.target || "";
    for (const issue of report.issues) {
      issues.push(issue);
      causeCounts[issue.cause] = (causeCounts[issue.cause] || 0) + 1;
    }
    afterChunkId = progress.afterChunkId;
    if (progress.complete) break;
  }
  const runtimeAfter = runtimeMetadata(await fetchJson("/api/law-data/ai/debug/runtime-info"));
  if (!sameRuntime(runtimeBefore, runtimeDuringAudit, runtimeAfter)) {
    throw new Error("Runtime metadata drifted while the integrity audit was running.");
  }
  const artifact = {
    generatedAt: new Date().toISOString(),
    ...runtimeDuringAudit,
    target: reportedTarget,
    limit,
    pages,
    scannedRows,
    causeCounts,
    issues: issues.map((issue) => ({
      cause: issue.cause,
      chunkId: issue.chunkId,
      chunkContentHash: issue.chunkContentHash || "",
      embeddingContentHash: issue.embeddingContentHash || "",
    })),
  };
  fs.mkdirSync(path.dirname(jsonPath), { recursive: true });
  fs.writeFileSync(jsonPath, JSON.stringify(artifact, null, 2) + "\n", "utf8");
  fs.writeFileSync(markdownPath, markdown(artifact), "utf8");
  console.log(`Wrote ${path.relative(workspace, jsonPath)} and ${path.relative(workspace, markdownPath)}`);
}

if (require.main === module) {
  main().catch((error) => {
    console.error(error.stack || error.message);
    process.exitCode = 1;
  });
}

module.exports = { runtimeMetadata, sameRuntime, pageProgress };
