# Document-first candidate expansion design

Date: 2026-08-24

## Context

The coverage-aware fusion training evaluation ended with
`NO_COVERAGE_IMPROVEMENT`. Baseline and every bounded rescue policy found all
required evidence for 7 of 24 training cases. Seventeen cases lacked at least
one required group in the pure fused top 30. Only one of those cases had all
required groups available across the existing source candidates; the remaining
failures were absent or partial at `candidateSources`.

The next change must therefore improve candidate entry rather than tune RRF or
move already-retrieved chunks. It must remain oracle-independent, bounded,
observable, and disabled by default until independent evaluation proves an
improvement.

## Goals

- Recover relevant chunks when a question contains an identifiable document,
  law, administrative-rule, article, or section anchor.
- Add no OpenAI request and no Qdrant request beyond the existing retrieval
  flow.
- Use only active, effective, already indexed MariaDB rows and existing query
  planning signals.
- Bound document and chunk expansion tightly enough to protect latency and
  avoid broad topical noise.
- Preserve vector, keyword, BM25, pure RRF, and coverage-aware rankings for
  control and diagnosis.
- Promote the new source only after two independent training runs and the
  existing difficult/holdout ladder pass.

## Non-goals

- No question-specific allowlist, evaluation-oracle lookup, or hard-coded
  answer document.
- No RRF weight retuning.
- No increase to the vector search limit or global BM25 posting budget.
- No LLM-generated query expansion.
- No runtime activation during implementation.
- No changes to batch-runner port `18080`, `output/`, indexing state, or stored
  vectors.

## Considered approaches

### 1. Document-first bounded expansion (selected)

Use explicit title and provision anchors from `QuestionSearchPlan` to identify
a small set of documents, then load a small number of matching chunks from
those documents. This directly addresses candidate-source misses, is
deterministic, and requires only bounded MariaDB reads.

### 2. Increase vector/BM25 limits

This is mechanically simple but expands noise for every question, increases
hydration and ranking cost, and does not use the strong document identity
signals already present in many Korean legal questions. It is rejected.

### 3. LLM query rewriting

This can generate aliases and paraphrases but adds cost, latency, external
requests, and run-to-run variance before deterministic retrieval gaps are
fixed. It is rejected for this phase.

## Proposed architecture

### Query anchors

Extend the existing deterministic question plan with a `DocumentSearchAnchor`
value containing:

- normalized document-title terms;
- normalized article/section/appendix terms;
- ordinary evidence terms used only to rank chunks inside an anchored
  document;
- target restrictions already derived for the request.

An anchor is eligible only when it contains a strong document identity signal:
an explicit quoted or suffixed title, a configured stable title alias, or a
law/admin-rule title paired with a provision marker such as `제N조`, `별표`,
or an explicit section heading. Generic topic keywords alone cannot trigger
expansion.

### Document identification

Add parallel read-only document-identity queries to `LawChunkMapper` and
`RagDocumentMapper`. Both return distinct active documents matching the
normalized title terms; the law query also reuses the current effective-date
filter. Each query accepts only the already selected targets. Results are
combined and ordered by:

1. normalized exact title match;
2. all title terms present;
3. provision-anchor presence in document or chunk metadata;
4. document id for deterministic ties.

At most three documents are eligible. Ambiguous matches that do not satisfy
the exact-title or all-title-terms rule produce no expansion.

### Chunk expansion

For each eligible document, load at most eight active chunks through the mapper
that owns its target. Reuse the existing document-title/title+text projections
and the document-title, heading, parent-title, chunk-title, chunk-number, and
chunk-text fields. Rank within the document by:

1. exact article/section/appendix match;
2. exact chunk or parent heading match;
3. count of evidence terms matched;
4. stable sort order and chunk id.

The global expansion ceiling is 24 unique chunks. Duplicate chunk keys already
present in vector, lexical, or BM25 sources are retained once and marked as
overlap rather than consuming an additional slot.

### Retrieval integration

Run document identification in parallel with existing lexical, BM25, and
embedding work. Hydrate expansion chunks before the source union is finalized.
Represent the result as a separate `documentExpansionHits` source with a
deterministic rank.

In shadow mode:

- existing `searchedChunks`, pure RRF, and coverage-aware control orders remain
  unchanged;
- a new shadow union and shadow fused order include the expansion source;
- debug output exposes candidate keys, target, document id, source rank, anchor
  type, and bounded reason code, but no new text or secret;
- candidate-loss tracing distinguishes `DOCUMENT_NOT_ANCHORED`,
  `DOCUMENT_MATCH_AMBIGUOUS`, `DOCUMENT_LIMIT`, `DOCUMENT_CHUNK_LIMIT`, and
  downstream loss stages.

The production path may use the new source only when both the new authority
flag and the existing required retrieval authority flags are enabled.

### Configuration

Add configuration with fail-closed defaults:

```properties
law-ai.retrieval.document-expansion.enabled=true
law-ai.retrieval.document-expansion.authoritative=false
law-ai.retrieval.document-expansion.max-documents=3
law-ai.retrieval.document-expansion.max-chunks-per-document=8
law-ai.retrieval.document-expansion.max-total-chunks=24
```

`enabled=true` permits shadow calculation and diagnostics. Authority remains
false through implementation and training. Invalid or non-positive bounds
disable the component and return the baseline unchanged.

Include every property in `RuntimeConfigurationIdentity` so evaluation runs
cannot cross configuration drift.

## Data flow

1. Normalize the question and build `QuestionSearchPlan`.
2. Derive a strong `DocumentSearchAnchor`, or return `NO_STRONG_ANCHOR`.
3. In parallel with existing sources, find up to three matching documents.
4. Load and rank up to eight chunks per document, with a global limit of 24.
5. Deduplicate the expansion results against the existing hydrated candidates.
6. Preserve the current control order and compute a separate shadow source
   union and shadow fused order.
7. Emit source ranks, decision status, bounds, and candidate-loss transitions.
8. Use the shadow order only in offline evaluation until the promotion ladder
   passes.

## Failure handling

- Database timeout or query error: log a bounded warning, return baseline, and
  record `DB_FALLBACK_BASELINE`.
- No strong anchor: perform no additional query.
- Ambiguous document match: return no expansion rather than guessing.
- Missing or inactive chunks: omit them and retain the control result.
- Duplicate, malformed, or over-limit results: deterministically deduplicate
  and truncate; invalid invariants return baseline.
- Runtime, manifest, configuration, index, or lexical revision drift during an
  evaluation: fail the evaluation closed and publish no recommendation.

## Testing

### Unit and mapper tests

- Anchor extraction for Korean document titles, aliases, article numbers,
  appendices, and headings.
- Rejection of generic topical questions and ambiguous title fragments.
- Target isolation and effective-date filtering.
- Deterministic document and chunk ordering.
- Bounds `3 x 8 <= 24`, deduplication, and invalid-bound fallback.
- Law and RAG mapper SQL tests for target isolation, active/current filtering,
  and stable ordering.
- No additional embedding or Qdrant call compared with baseline.
- Shadow mode leaves control order unchanged.
- Trace and debug fields contain source metadata and bounded reason codes.

### Evaluation

Reuse the frozen 24-case independent training manifest, but generate a new
execution manifest and immutable request hash for the new candidate JAR and
configuration. Run twice with the same K, capture limit, concurrency, and
runtime fences.

A candidate configuration is eligible only if both runs:

- improve all-required recall above the current `7/24` baseline;
- preserve every baseline-passing case;
- do not reduce any-required recall below `14/24`;
- do not reduce the total matched required-group count below `23`;
- select the same bounded policy and produce stable provenance;
- report request errors `0` and Qdrant search failures `0`.

If training does not pass, stop with all authority flags false and do not
consume difficult or holdout cases. If it passes, run the existing difficult-12
twice, then the untouched holdout twice, applying their existing recall,
false-ground, latency, repeatability, and provenance gates. Activate only after
all gates pass.

## Rollout and branch dependency

This branch is based on `codex/coverage-aware-fusion` because it reuses the
candidate-source diagnostics and evaluation capture added there. The
coverage-aware PR must merge first. After that merge, rebase or merge the
updated `main` into this branch before implementation review.

Implementation remains shadow-only. A failed evaluation requires only a
configuration-disabled baseline outcome and evidence documentation; no runtime
rollback or index mutation is needed.

## Acceptance criteria

- Strong anchors recover bounded document chunks without oracle data.
- Generic questions trigger no document-expansion query.
- Existing control retrieval and answer behavior remain byte-for-byte ordered
  while authority is false.
- External OpenAI and Qdrant call counts do not increase.
- MariaDB reads are bounded and observable.
- Focused tests and the full backend suite pass.
- The training/difficult/holdout ladder either proves improvement and permits
  activation or stops fail-closed with authority false.
