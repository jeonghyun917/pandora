# Task 15 BM25 Title Expansion Training Selection

Date: 2026-08-25 (Asia/Seoul)

## Approved execution

- Immutable approval SHA-256: `7dcd160198ad57da32785b0b7a33eccdeeacfc9ea0e17914c190920f6eb1fa39`
- Frozen training questions: 24, in the manifest order
- Independent runs: 2
- OpenAI Embedding API calls: 48 (`text-embedding-3-small`)
- OpenAI Answer API calls: 0
- Qdrant and MariaDB access: read only
- Initial command argument mismatch stopped before runtime retrieval or any external call. Its stdout is retained as prelaunch evidence and was not counted as a run.

## Immutable execution result

- Run 1: 24/24 complete, request errors 0, Qdrant search failures 0
- Run 2: 24/24 complete, request errors 0, Qdrant search failures 0
- Runtime instance, artifact, configuration, index revision, lexical revision, dataset, selection, and training-manifest provenance were identical across both runs.
- Control in both runs: all-required 7/24, any-required 14/24, matched groups 22
- BM25-title expansion source in both runs: all-required 0/24, any-required 0/24, matched groups 0
- Shadow fused in both runs: all-required 7/24, any-required 14/24, matched groups 22

## Diagnostic finding

- `BM25_TITLE_APPLIED` occurred for `project-review-hardware-exclusion` and `msit-tving-investigation` in both runs.
- Every emitted BM25-title expansion chunk overlapped an existing source candidate: 8/8 and 3/3 respectively.
- The expansion added no required-oracle group. The candidate therefore preserved the control but created no measurable recall improvement.

## Decision

- Status: `NO_DOCUMENT_EXPANSION_IMPROVEMENT`
- Eligible: `false`
- Reason: shadow-fused all-required recall did not exceed 7/24.
- No difficult/holdout evaluation is authorized by this result.
- Document expansion remains shadow only; no authority flag is changed.

## Safety result

- Law DB/Qdrant remained at 211,548/211,548.
- RAG DB/Qdrant remained at 84,248/84,248.
- Qdrant remained ready with search failure count 0.
- Port 18080 and `output/` were untouched.

