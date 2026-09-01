# Deterministic Retrieval Promotion Design

## Goal

Make repeated Pandora retrieval evaluations attributable to code and configuration changes, select one generalized title-matching improvement on training data, and promote RRF plus semantic matching only after every independent gate passes without regression.

## Scope

This work continues Task 15 and covers nine ordered outcomes:

1. deterministic BM25 hydration candidates;
2. restart-stable index revision identity;
3. focused and full backend regression tests;
4. generalized Korean law/document title matching;
5. independent training selection;
6. Difficult-12, holdout, then answer evaluation;
7. conditional RRF and semantic authority promotion;
8. the complete approximately 1,004-case release gate; and
9. durable evidence, commit, push, and handoff.

Port `18080` and `output/` are out of scope and must remain untouched.

## Deterministic Candidate Hydration

BM25 hits are canonicalized before the bounded hydration read by this total order:

1. declared BM25 rank ascending;
2. score descending;
3. normalized target ascending;
4. chunk ID ascending; and
5. document ID ascending.

The first occurrence of a candidate identity wins. Law and RAG chunk ID request lists are separately sorted ascending before MyBatis reads. The title seed selector applies the same total order rather than trusting caller iteration order. Invalid ranks, identities, targets, or scores continue to fail closed.

## Stable Index Revision

The authoritative semantic content identity is the existing materialized fingerprint: an XOR aggregation of `SHA-256(chunkId:contentHash)` for every current indexed embedding, combined with current indexed count and exact Qdrant collection metadata. This detects same-count content replacement.

`updatedWatermark` is operational metadata. Startup and maintenance paths can change document, chunk, or embedding `updated_at` values without changing searchable content. It therefore remains available for diagnostics but is removed from the canonical `indexRevision` hash and from usability requirements. Count mismatch, malformed content fingerprint, non-green Qdrant, a non-idle update queue, model drift, vector-size drift, and distance drift still suppress revision issuance.

The revision schema version advances so new identities cannot be confused with historic watermark-sensitive identities.

## Generalized Title Matching

Title improvement remains question-agnostic and document-agnostic. Candidate approaches are evaluated in shadow mode:

- canonical Korean title normalization that removes only formatting and known legal-document suffix noise while preserving substantive tokens;
- token-boundary matching over canonical title tokens; and
- a bounded threshold change only when the best candidate is unambiguous.

No question ID, oracle title, agency-specific exception, or hard-coded document name may enter production ranking. The smallest candidate that improves the frozen training set without losing an existing required group is selected. If no candidate qualifies, title behavior remains unchanged.

## Evaluation Ladder

Every evaluation records manifest hash, artifact SHA-256, configuration hash, runtime instance, stable index revision, lexical revision, DB/Qdrant parity, request counts, and per-case rank captures.

The gates run strictly in this order:

1. two independent training runs;
2. Difficult-12;
3. untouched holdout;
4. answer evaluation;
5. complete approximately 1,004-case release evaluation.

A later gate never runs after an unexplained variance, request failure, Qdrant failure, provenance drift, or regression. RRF and semantic authority flags remain false until all required retrieval and answer thresholds pass. Activation is a separately tested configuration change, followed by the full gate on the exact promoted artifact.

## Testing

Use TDD for each production behavior change. Focused tests cover permutation invariance, tie breaks, duplicate candidate identities, watermark-only changes, same-count content changes, malformed snapshots, normalization boundaries, ambiguity, and fail-closed behavior. Run the full Maven backend suite after focused tests pass. Evaluation scripts and manifest validators run before any external evaluation.

## Delivery

Evidence is stored under the existing Task 15 evidence directory and summarized in the Task 15 progress ledger and a final handoff. Only verified files are committed. The feature branch is pushed after the exact commit passes the applicable release gate; shared `main` is not switched or overwritten.
