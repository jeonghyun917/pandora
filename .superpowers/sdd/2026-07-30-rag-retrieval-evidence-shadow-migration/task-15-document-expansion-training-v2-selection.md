# Task 15 document-expansion training v2 selection

Date: 2026-08-24 (Asia/Seoul)

## Outcome

- Status: `BASELINE_REGRESSION`
- Eligible for difficult evaluation: `false`
- Selector reason: `control recall does not match the frozen training baseline`
- Authority change: none; every retrieval and semantic authority flag remains false.
- Difficult and holdout evaluations: not prepared or executed.

## Exact execution

- Approved canonical manifest SHA-256:
  `d2f1ed37ed067806e426b306fd7856d6d01c84c865edab038561eeee0ae8a047`
- Ordered questions: `24`; runs: `2`; OpenAI Embedding API calls: `48`;
  Answer API calls: `0`.
- Run 1: selected/completed `24/24`, request errors `0`, Qdrant search
  failures `0`.
- Run 2: selected/completed `24/24`, request errors `0`, Qdrant search
  failures `0`.
- Immutable provenance matched across both runs.

## Recall comparison

Both runs were identical:

| Path | All required | Any required | Matched groups |
| --- | ---: | ---: | ---: |
| Control | 9/24 | 16/24 | 28 |
| Expansion source | 0/24 | 0/24 | 0 |
| Shadow fused | 8/24 | 16/24 | 27 |

The shadow-fused path lost the control-passing `pre-consultation-target` case.
It added no required-ground match from the document-expansion source. This is
not eligible for difficult evaluation even aside from the selector's frozen
baseline identity mismatch (`7/14/23` expected versus `9/16/28` observed).

## Fail-closed action

No difficult manifest was created, no additional external request was sent,
and no authority configuration was changed. The candidate remains shadow-only.
Port 18080 and `output/` were not touched.
