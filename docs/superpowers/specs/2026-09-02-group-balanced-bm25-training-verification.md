# Group-Balanced BM25 Training Verification

Date: 2026-09-02 (Asia/Seoul)

## Candidate

- Code commit at capture: `b92628a57800b3514ab35bb25609a12b60702e86`
- Runtime JAR SHA-256: `84d029471157a445a2a30fef106d86a47044a208c5e1731c9943e4766abaa02e`
- Runtime instance: `82ae43b3-b163-4311-afe4-d845a4f8b8dd`
- Runtime config SHA-256: `e7b08ced10e7fd56f1dbfda7822dccb019aec056cf31ea16fca24604cbd1576a`
- Index revision: `726f3c4dd53d09a99bb277ec85cae47270b7613618d23300c34a6c197eac7285`
- Training manifest SHA-256: `3c5cc394524389d9c05c0e72e602232a8eb98a297f25a55c4f0c71a85ae2b2db`
- Selection SHA-256: `4e5546b95f079888138e7ef5d921b2a77c7f309aad16f2d269e8b83b9908bf22`

Authority remained disabled. The candidate ran as an audit-only shadow.

## Verification

- Full Maven suite: 1,347 tests, 0 failures, 0 errors, 18 skipped.
- Node evaluation and selector suites: 52 tests, 52 passed.
- Law DB/Qdrant: 211,548 / 211,548.
- RAG DB/Qdrant: 84,248 / 84,248.
- Qdrant search failures: 0.
- Both 24-case captures: complete, request errors 0, 24 `APPLIED` shadow results.
- Both captures used the same runtime, JAR, config, index revision, lexical revision,
  manifest, dataset, and ordered selection.

## Training Decision

The deterministic selector returned `SELECTED` in both-run comparison:

| Metric | Control | Shadow |
| --- | ---: | ---: |
| All required groups | 7/24 | 9/24 |
| Any required group | 16/24 | 16/24 |
| Matched required groups | 25 | 27 |

The shadow added one missing required group in each of
`security-review-procedure` and `pipc-pseudonym-additional-info`, with no lost
control group.

## Timeout Diagnostic

The first diagnostic capture on the earlier JAR recorded one fail-closed
`VARIANT_ASYNC_TIMEOUT` for `personal-info-purpose`. The root cause was a
1.25-second single-query timeout reused for four bounded sequential variants.
A RED/GREEN regression test established a three-second variant-only bound while
preserving the existing control BM25 timeout. The corrected candidate then
returned `APPLIED` for the same case and all 24 training cases.

## Evidence

- `docs/evidence/rag-quality/group-balanced-bm25-training-run-1-b92628a5.json`
- `docs/evidence/rag-quality/group-balanced-bm25-training-run-2-b92628a5.json`
- `docs/evidence/rag-quality/group-balanced-bm25-training-selection-b92628a5.json`
- `docs/evidence/rag-quality/group-balanced-bm25-timeout-check-af8e1aab.json`
- `docs/evidence/rag-quality/group-balanced-bm25-training-run-1-ff4a8c7b.json`

The candidate may advance to Difficult-12. It is not eligible for authority or
release until Difficult-12, holdout, Answer API, and the full release gate pass.
