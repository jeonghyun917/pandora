# RAG Direct Evidence Recovery Design

## Goal

Recover direct evidence that currently disappears behind slow unindexed lexical
queries, while preserving Pandora's fail-closed answer verification policy.

## Confirmed Failure

The question `이메일 만으로도 개인정보라고 볼수있나?` produced 100 merged
candidates and one concept-relevant ground, but no direct evidence. The RAG
lexical query exceeded its 1.5 second budget. The database nevertheless contains
an official direct-evidence chunk stating that an email address is personal
information in the described collection context. Answer repair then correctly
failed closed with `NO_ALIGNED_SUPPORTED_ATOM`.

## Architecture

### Normalized lexical terms

`KoreanQueryNormalizer` removes attached particles and question endings and
rejects low-information tokens. The same normalizer feeds both query planning
and the RAG lexical index.

### RAG inverted index

Add `rag_chunk_search_terms` with normalized term, chunk id, document id, chunk
version, field kind, and weight. A B-tree on `(term, chunk_version, chunk_id)`
replaces broad `%LIKE%` scans for normal RAG lexical retrieval. Chunk import
updates this index transactionally; a bounded maintenance service backfills
existing searchable chunks.

The existing LIKE query remains only as a temporary compatibility fallback when
the index has not yet been populated. It is not used after indexed rows exist
for the requested terms.

### Direct-evidence recovery

When the first Evidence Judge pass produces zero direct grounds, the pipeline
performs one bounded recovery search using the highest-value normalized subject
and relation terms. Recovery candidates pass through the same reranker, Judge,
ground builder, Claim Verifier, and Answer Guard. No second retry is allowed.

### Ground roles

Grounds expose an evidence role:

- `direct`: directly answers the question.
- `supporting`: supports an already selected direct ground.
- `related_definition`: relevant definition or criteria without direct support.

`related_definition` may be displayed but is never sufficient to generate or
verify a categorical answer.

### PDF response headers

Document preview responses use an ASCII-safe fallback filename and RFC 5987
`filename*=UTF-8''...` encoding. Raw Korean filenames are never written directly
to an HTTP header.

## Error Handling

- Search-index misses fall back only while the index is incomplete.
- A recovery timeout returns the existing fail-closed response.
- Search-index maintenance is idempotent by chunk id, term, and field kind.
- Disabled, superseded, or quality-rejected chunks are excluded.
- Index maintenance failures do not delete existing index rows.

## Verification

- Unit tests cover Korean suffix removal and low-information token rejection.
- Mapper integration tests prove indexed retrieval finds the email evidence.
- Service tests prove direct-evidence recovery runs once and remains fail closed
  when no direct ground exists.
- Response tests distinguish related definitions from direct grounds.
- Controller tests verify non-ASCII PDF filenames produce valid headers.
- Add the email question to the RAG regression evaluation set.
- Run focused tests, full Maven tests, frontend build, targeted RAG evaluation,
  then restart only the 8080 app-dev instance and verify the real question.

## Non-goals

- Weakening Claim Verifier or Answer Guard.
- Restarting or modifying the 18080 batch runner.
- Introducing OpenSearch or another external search service.
- Rechunking or re-embedding documents.
