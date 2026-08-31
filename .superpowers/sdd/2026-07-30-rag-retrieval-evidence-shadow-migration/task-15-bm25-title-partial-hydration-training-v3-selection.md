# Task 15 BM25 partial-hydration training selection

Date: 2026-08-31 (Asia/Seoul)

## Decision

- Status: `NO_DOCUMENT_EXPANSION_IMPROVEMENT`
- Eligible: `false`
- Reason: shadow-fused all-required recall did not exceed the frozen `7/24`
  baseline.
- No difficult or holdout evaluation is authorized by this result.
- No retrieval authority flag changes.

## Approved execution

- Immutable approval SHA-256:
  `8d6fe589bf58d627a1c8956c0492da3581c9c5a64931f8d57af0cf3ebdd53f97`
- Two independent runs completed `24/24` with request errors `0`.
- External use was exactly `48` successful OpenAI Embedding API calls and `0`
  Answer API calls.
- Runtime, artifact, configuration, index, lexical, training-manifest, dataset,
  selection, and Qdrant provenance matched across both runs.

## Retrieval measurements

Both runs produced the same document-expansion measurements:

| Path | All required | Any required | Matched groups |
| --- | ---: | ---: | ---: |
| Control fused | 7/24 | 14/24 | 22 |
| Expansion source | 0/24 | 0/24 | 0 |
| Shadow fused | 7/24 | 14/24 | 22 |

- Expansion applied to `irm-faithfulness` and `ai-law-enforcement-date`, but
  neither supplied a required oracle group.
- The other 22 cases returned `BM25_TITLE_NO_MATCH`.
- Shadow fusion neither gained nor lost a required group relative to control.
- The incomplete-candidate isolation change removed the prior whole-candidate
  invalidation failure mode, but it did not improve recall on this frozen set.

## Evidence integrity

- Run 1 JSON SHA-256:
  `fb0011ef211b3360aadb23a6344f608dbccfcbad1aaf89dc397221daa4127cca`
- Run 2 JSON SHA-256:
  `34bfd3993c3b98aa9c3c4418e3cfeb2db0d0ea3f17a7ce10920cbb9bfe0cd1df`
- Selection JSON SHA-256:
  `2573039c8c673bf75b503041aad359a77a2b5c5453c28490048b69cd803309e4`

The candidate remains shadow-only and is not promoted.
