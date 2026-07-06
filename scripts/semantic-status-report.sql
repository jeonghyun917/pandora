SELECT NOW() AS db_now;

SELECT
  target,
  status,
  COUNT(*) AS jobs,
  SUM(submitted_count) AS submitted,
  SUM(completed_count) AS completed,
  SUM(failed_count) AS failed,
  SUM(ingested_count) AS ingested
FROM semantic_batch_jobs
GROUP BY target, status
ORDER BY target, status;

SELECT
  doc.target,
  COALESCE(e.status, 'NO_EMBED') AS status,
  COUNT(*) AS chunks
FROM law_api_document_chunks c
JOIN law_api_documents doc ON doc.document_id = c.document_id
LEFT JOIN law_api_chunk_embeddings e ON e.chunk_id = c.chunk_id
WHERE c.use_yn = 'Y'
  AND doc.use_yn = 'Y'
GROUP BY doc.target, COALESCE(e.status, 'NO_EMBED')
ORDER BY doc.target, status;

SELECT
  d.document_type,
  COALESCE(e.status, 'NO_EMBED') AS status,
  COUNT(*) AS chunks
FROM rag_document_chunks c
JOIN rag_documents d ON d.document_id = c.document_id
LEFT JOIN rag_chunk_embeddings e
  ON e.chunk_id = c.chunk_id
  AND e.embedding_model = 'text-embedding-3-small'
  AND e.vector_store = 'rag_chunks_v4'
WHERE c.use_yn = 'Y'
  AND d.use_yn = 'Y'
  AND c.chunk_version = (
    SELECT MAX(c2.chunk_version)
    FROM rag_document_chunks c2
    WHERE c2.document_id = c.document_id
      AND c2.use_yn = 'Y'
  )
GROUP BY d.document_type, COALESCE(e.status, 'NO_EMBED')
ORDER BY d.document_type, status;

SELECT
  'law_api' AS source,
  COUNT(*) AS remaining_candidates
FROM law_api_document_chunks c
JOIN law_api_documents doc ON doc.document_id = c.document_id
LEFT JOIN law_api_chunk_embeddings e
  ON e.chunk_id = c.chunk_id
  AND e.embedding_model = 'text-embedding-3-small'
  AND e.vector_store = 'law_chunks'
WHERE c.use_yn = 'Y'
  AND doc.use_yn = 'Y'
  AND (
    e.chunk_id IS NULL
    OR e.status IN ('FAILED', 'ERROR')
    OR COALESCE(e.content_hash, '') != COALESCE(c.content_hash, '')
  )
UNION ALL
SELECT
  'rag' AS source,
  COUNT(*) AS remaining_candidates
FROM rag_document_chunks c
JOIN rag_documents d ON d.document_id = c.document_id
LEFT JOIN rag_chunk_embeddings e
  ON e.chunk_id = c.chunk_id
  AND e.embedding_model = 'text-embedding-3-small'
  AND e.vector_store = 'rag_chunks_v4'
WHERE c.use_yn = 'Y'
  AND d.use_yn = 'Y'
  AND c.chunk_version = (
    SELECT MAX(c2.chunk_version)
    FROM rag_document_chunks c2
    WHERE c2.document_id = c.document_id
      AND c2.use_yn = 'Y'
  )
  AND (
    e.chunk_id IS NULL
    OR e.status IN ('FAILED', 'ERROR')
    OR COALESCE(e.content_hash, '') != COALESCE(c.content_hash, '')
  );
