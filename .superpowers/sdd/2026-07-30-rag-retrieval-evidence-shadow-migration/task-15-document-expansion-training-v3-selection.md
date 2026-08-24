# Task 15 corrected document-expansion training selection

Date: 2026-08-24 (Asia/Seoul)

## Outcome

- Status: `NO_DOCUMENT_EXPANSION_IMPROVEMENT`
- Eligible for difficult evaluation: `false`
- Selector reason: `shadow fused all-required recall did not exceed 7/24`
- Authority change: none; document expansion and every other candidate retrieval
  path remain non-authoritative.
- Difficult and holdout evaluations: not prepared or executed.

## Exact approved execution

- Approved canonical manifest SHA-256:
  `094b9aa8ba4a2397edc335c10555cddbd346dece91fb0bcdbe474785f7d94066`
- Ordered questions: `24`; independent runs: `2`; OpenAI Embedding API
  calls: exactly `48`; Answer API calls: `0`.
- Run 1 and run 2 each completed `24/24` with request errors `0`, stable
  runtime provenance, and Qdrant search failures `0`.
- A first local invocation omitted `--case-ids` and was rejected before any
  runtime request or Embedding call. It produced no result file and is recorded
  in the run-1 stdout evidence; the corrected approved execution was not a
  network retry.

## Corrected fused-to-fused comparison

Both runs produced identical measurements:

| Path | All required | Any required | Matched groups |
| --- | ---: | ---: | ---: |
| Fused control | 7/24 | 14/24 | 22 |
| Expansion source | 0/24 | 0/24 | 0 |
| Shadow fused | 7/24 | 14/24 | 22 |

Document expansion was applied only for `irm-faithfulness` and
`ai-law-enforcement-date`. It returned bounded candidates in both cases, but
none matched an expected required-ground group. The other 22 cases had no
strong expansion anchor. Consequently the candidate neither improved nor
regressed the corrected fused control.

## Selector integrity correction

The selector still contained the historical source-union group count `23`.
A failing test reproduced that single mismatch, then the frozen fused-control
baseline was corrected to `7/14/22`. The focused selector tests passed `3/3`
and the related Node evaluation suite passed `113/113`. The selector then ran
exactly once over the two completed captures and returned the outcome above.

## Fail-closed action

No difficult/holdout request was sent and no authority configuration was
changed. Qdrant and MariaDB were read only throughout evaluation. Port `18080`
and `output/` were not touched.
