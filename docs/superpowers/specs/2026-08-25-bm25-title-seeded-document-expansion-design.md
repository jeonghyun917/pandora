# BM25 Title-Seeded Document Expansion Design

Date: 2026-08-25 (Asia/Seoul)

## Goal

Recover relevant sibling chunks when a question does not contain an explicit
document title or stable alias, without weakening Pandora's fail-closed answer
path. The new lane must reuse the existing Korean BM25 search, verify that its
document metadata is a strong match, and remain shadow-only until repeated
evaluation proves a recall gain without a baseline loss.

## Evidence and problem statement

The corrected 24-case document-expansion evaluation completed twice with
identical results:

| Path | All required | Any required | Matched groups |
| --- | ---: | ---: | ---: |
| Fused control | 7/24 | 14/24 | 22 |
| Expansion source | 0/24 | 0/24 | 0 |
| Shadow fused | 7/24 | 14/24 | 22 |

The current lane ran for only two cases. The other 22 returned
`NO_STRONG_ANCHOR` because the question contained neither an explicit title nor
a configured stable alias. Broadening the title regular expression would turn
generic business phrases into document identities and create false-document
risk. The design therefore keeps the existing anchor lane unchanged and adds a
separate, measurable BM25-seeded fallback.

## Considered approaches

### 1. BM25 title-seeded fallback — selected

Reuse the already executed Korean BM25 ranking. Aggregate its bounded hits by
document, inspect the hydrated document title, and accept only documents whose
title matches enough distinct planned query terms. Then retrieve and rank
sibling chunks with the existing `3/8/24` expansion bounds.

This approach is selected because it uses the existing versioned lexical index,
whose highest-weight field is `document_title`, adds no second external call,
does not need a new schema, and can recover a relevant section when BM25 found a
different chunk from the correct document.

### 2. Existing-candidate sibling expansion only

Expand every document represented in the fused control. This is simpler, but it
can amplify vector-only false positives and cannot distinguish a document-title
match from an incidental body match. It is rejected as too weakly grounded.

### 3. New document-level BM25 index

Create separate document and document-term tables with document-level length
and frequency statistics. This could improve title recall, but it adds schema,
index-build, publication, rollback, and operational migration work before the
smaller reuse approach has been tested. It is deferred unless the selected lane
shows that correct documents consistently fall outside the current BM25 bound.

## Architecture

### Existing strong-anchor lane

`DocumentSearchAnchorExtractor` and the current explicit-title/stable-alias
path remain unchanged. An eligible strong anchor continues to take precedence.
The BM25 fallback must not replace, merge with, or relax this path.

### BM25 document seed selector

Add a focused pure component that consumes:

- the ordered `LexicalSearchHit` list already produced for RRF;
- the corresponding hydrated `LawSemanticChunkRow` values;
- `QuestionSearchPlan.bm25Keywords()` and preferred targets; and
- a bounded policy.

For each hit, the selector obtains the document identity from
`target/documentId`, normalizes the hydrated document title, and records only
distinct BM25 matched terms that also occur in that title. Body-only,
chunk-title-only, and parent-title-only matches do not establish document
identity.

Documents are ordered deterministically by:

1. distinct title-matched term count descending;
2. best BM25 score descending;
3. best BM25 rank ascending;
4. normalized target and numeric document ID ascending.

An eligible seed requires at least two distinct non-weak title terms. At the
selection boundary, documents with the same title-term count and a BM25 score
within five percent of each other are ambiguous; the lane returns no candidates
instead of choosing by ID. At most three documents are emitted. The selector
must reject invalid IDs, target mismatches, non-finite scores, duplicate
candidate identities, and a missing hydration row.

The initial policy is immutable and bounded:

```text
enabled=true
authoritative=false
maxBm25HitsInspected=100
minimumDistinctTitleTerms=2
ambiguityScoreRatio=0.05
maxDocuments=3
maxChunksPerDocument=8
maxTotalChunks=24
```

### Sibling chunk expansion

When the legacy result is `NO_STRONG_ANCHOR`, the answer service invokes the
pure selector after BM25 hits have been joined and hydrated. If the selector
returns eligible document identities, `DocumentExpansionSearchService` fetches
chunks for those exact active/current documents and delegates chunk ordering to
the existing `DocumentCandidateExpansion.rankChunks(...)` rules.

The fallback uses a separate anchor type and reason:

```text
anchorType=BM25_TITLE
reason=BM25_TITLE_SEED
```

It preserves exact provision, exact heading, evidence-term, and document-order
chunk priorities. It also preserves duplicate detection and the global
`3/8/24` trust-boundary validation in `LawAiAnswerService`.

## Data flow

1. Build `QuestionSearchPlan` and launch the existing vector, lexical, BM25,
   embedding, and strong-anchor shadow operations.
2. Join BM25 once under the existing timeout and hydrate those same chunk IDs.
3. Keep an `APPLIED` strong-anchor result unchanged.
4. Only when the strong-anchor result is `NO_STRONG_ANCHOR`, derive bounded
   document seeds from BM25 title matches.
5. Fetch sibling chunks for the accepted document identities with read-only
   MariaDB queries.
6. Add the expansion hits only to the shadow RRF input and capture structured
   status, reason, title-term count, BM25 score/rank, and document identity.
7. Keep `searchedChunks`, judge input, selected grounds, and answer generation
   on the existing control path while authority is false.

No additional OpenAI embedding or answer request and no additional Qdrant
request is introduced. The fallback consumes the existing BM25 result and uses
read-only MariaDB hydration/expansion queries.

## Status and diagnostics

The debug contract adds bounded structured values rather than free-form logs:

- `BM25_TITLE_APPLIED`
- `BM25_TITLE_NO_MATCH`
- `BM25_TITLE_AMBIGUOUS`
- `BM25_TITLE_INVALID_INPUT`
- `BM25_TITLE_DB_FALLBACK`

Each accepted hit exposes only identity/rank metadata already allowed by the
document-expansion capture. It must not expose candidate text, secrets, oracle
groups beyond the existing audit indexes, or evaluation case IDs to production
selection code.

## Failure handling

- Missing BM25 readiness, timeout, mapper failure, invalid hydration, ambiguous
  document selection, or an invalid bound returns the unmodified baseline.
- The new lane does not retry external or database operations.
- If the legacy strong-anchor lane is `APPLIED`, its result is authoritative for
  the shadow experiment and the BM25 fallback is not run.
- If either expansion result violates document/chunk bounds or identity
  consistency, all expansion candidates are discarded.
- `law-ai.retrieval.document-expansion.authoritative` remains `false` in every
  committed and deployed configuration used for implementation verification.

## Evaluation and promotion

Implementation verification is local and read-only: focused Java tests, the
related Node evaluator/selector tests, the full backend suite once, mapper XML
validation, and runtime configuration identity checks. It does not call OpenAI,
Qdrant, or mutate MariaDB.

After implementation is committed and independently reviewed, a fresh manifest
must freeze the same 24 ordered training questions, two runs, runtime/JAR/config/
index/lexical identities, evidence destinations, and exact external payload.
External execution requires exact manifest approval.

The lane is eligible for difficult evaluation only when both independent runs:

- exceed the corrected fused-control all-required baseline of `7/24`;
- preserve every fused-control passing case;
- keep any-required recall at least `14/24`;
- keep matched required groups at least `22`;
- select the same immutable policy;
- have request errors `0`, Qdrant search failures `0`, and identical immutable
  provenance.

No gain, disagreement, regression, timeout, provenance drift, or malformed
capture keeps the lane shadow-only. Difficult and holdout evaluation must not
run until the training selector returns `ELIGIBLE_FOR_DIFFICULT_EVAL`.

## Test strategy

- Pure selector tests cover title-only matching, body-only rejection, weak-term
  rejection, score/rank ordering, ambiguity, target filters, invalid rows, and
  `3`-document bounds.
- Search-service tests prove exact document IDs are used for sibling reads,
  mapper failures fall back, and per-document/global chunk bounds hold.
- Answer-service tests prove strong-anchor precedence, fallback-only execution,
  no extra Embedding/Qdrant/Answer call, unchanged control order, and
  non-authoritative behavior.
- Debug/evaluator tests validate new statuses and bounded metadata and reject
  partial or malformed captures.
- Selector regression tests retain the corrected `7/14/22` fused-control
  baseline and require repeated gains before promotion.

## Explicit non-goals

- No production authority activation.
- No answer-prompt, judge, semantic verifier, or ground-selector change.
- No new lexical schema or index rebuild format.
- No question-specific alias, evaluation case ID, oracle phrase, or document ID.
- No change to port `18080`, batch-runner artifacts, or `output/`.
