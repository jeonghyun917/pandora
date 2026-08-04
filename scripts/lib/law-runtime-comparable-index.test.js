const assert = require("node:assert/strict");
const test = require("node:test");
const {
  runtimeComparableIndexedQuery,
  groupRuntimeComparableIndexed,
} = require("./law-runtime-comparable-index");

const options = { embeddingModel: "text-embedding-3-small", vectorStore: "law_chunks" };

test("runtime-comparable query retains every runtime snapshot predicate", () => {
  const query = runtimeComparableIndexedQuery(options);

  for (const predicate of [
    "doc.use_yn='Y'",
    "c.use_yn='Y'",
    "e.embedding_model='text-embedding-3-small'",
    "e.vector_store='law_chunks'",
    "e.status='INDEXED'",
    "e.content_hash = c.content_hash",
    "c.content_hash REGEXP '^[0-9A-Fa-f]{64}$'",
    "c.activation_status='ACTIVE'",
  ]) {
    assert.match(query, new RegExp(escapeRegExp(predicate)));
  }
});

test("runtime-comparable grouping includes only current active matching law and admrul rows", () => {
  const rows = [
    current("law"),
    current("law"),
    current("admrul"),
    { ...current("law"), embeddingContentHash: "b".repeat(64) },
    { ...current("law"), documentUseYn: "N" },
    { ...current("law"), chunkUseYn: "N" },
    { ...current("law"), activationStatus: "CANDIDATE" },
    { ...current("law"), chunkContentHash: "not-a-hash", embeddingContentHash: "not-a-hash" },
    { ...current("law"), embeddingModel: "text-embedding-3-large" },
    { ...current("law"), vectorStore: "other_law_chunks" },
    { ...current("law"), status: "PENDING" },
  ];

  assert.deepEqual(groupRuntimeComparableIndexed(rows, options), [
    { target: "admrul", chunks: 1 },
    { target: "law", chunks: 2 },
  ]);
});

function current(target) {
  return {
    target,
    documentUseYn: "Y",
    chunkUseYn: "Y",
    activationStatus: "ACTIVE",
    embeddingModel: options.embeddingModel,
    vectorStore: options.vectorStore,
    status: "INDEXED",
    chunkContentHash: "a".repeat(64),
    embeddingContentHash: "a".repeat(64),
  };
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
