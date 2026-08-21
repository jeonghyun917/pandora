function runtimeComparableIndexedQuery({ embeddingModel, vectorStore }) {
  const options = normalizedOptions({ embeddingModel, vectorStore });
  return `
SELECT
  doc.target,
  COUNT(*) AS chunks
FROM law_api_chunk_embeddings e
JOIN law_api_document_chunks c ON c.chunk_id = e.chunk_id
JOIN law_api_documents doc ON doc.document_id = c.document_id
WHERE doc.use_yn='Y'
  AND c.use_yn='Y'
  AND e.embedding_model='${sql(options.embeddingModel)}'
  AND e.vector_store='${sql(options.vectorStore)}'
  AND e.status='INDEXED'
  AND e.content_hash = c.content_hash
  AND c.content_hash REGEXP '^[0-9A-Fa-f]{64}$'
  AND c.activation_status='ACTIVE'
GROUP BY doc.target
ORDER BY doc.target;
`;
}

function groupRuntimeComparableIndexed(rows, options) {
  if (!Array.isArray(rows)) {
    throw new Error("Runtime-comparable rows must be an array.");
  }
  const expected = normalizedOptions(options);
  const counts = new Map();
  for (const row of rows) {
    if (!isRuntimeComparableIndexed(row, expected)) continue;
    counts.set(row.target, (counts.get(row.target) || 0) + 1);
  }
  return [...counts.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([target, chunks]) => ({ target, chunks }));
}

function isRuntimeComparableIndexed(row, { embeddingModel, vectorStore }) {
  return row != null && nonBlank(row.target)
    && row.documentUseYn === "Y"
    && row.chunkUseYn === "Y"
    && row.activationStatus === "ACTIVE"
    && row.embeddingModel === embeddingModel
    && row.vectorStore === vectorStore
    && row.status === "INDEXED"
    && row.embeddingContentHash === row.chunkContentHash
    && isSha256(row.chunkContentHash);
}

function normalizedOptions({ embeddingModel, vectorStore } = {}) {
  if (!nonBlank(embeddingModel) || !nonBlank(vectorStore)) {
    throw new Error("Runtime-comparable query requires an embedding model and vector store.");
  }
  return { embeddingModel, vectorStore };
}

function nonBlank(value) {
  return typeof value === "string" && value.trim().length > 0;
}

function isSha256(value) {
  return typeof value === "string" && /^[0-9a-f]{64}$/i.test(value);
}

function sql(value) {
  return String(value).replace(/'/g, "''");
}

module.exports = { runtimeComparableIndexedQuery, groupRuntimeComparableIndexed };
