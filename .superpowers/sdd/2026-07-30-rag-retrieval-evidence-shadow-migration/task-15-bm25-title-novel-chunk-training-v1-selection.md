# Task 15 BM25 title novel-chunk training selection

Date: 2026-08-25 (Asia/Seoul)

## Decision

- Status: `BASELINE_REGRESSION`
- Eligible: `false`
- Reason: the control capture does not exactly match the frozen training
  baseline, so the candidate is not eligible for difficult or holdout
  evaluation.
- No authority flag is changed.

## Approved execution

- Immutable approval SHA-256:
  `202b0981abe9f360c7f2d3cdda98f0b61470c980e4b31a191b68f347c45f62df`
- Two independent runs completed `24/24` with request errors `0`.
- External use was exactly `48` OpenAI Embedding API calls and `0` Answer API
  calls.
- Both runs used the same runtime, artifact, config, index, lexical, training
  manifest, dataset, and selection hashes. Qdrant readiness stayed true and
  search failures stayed `0`.

## Retrieval measurements

Both runs produced the same document-expansion measurements:

| Path | All required | Any required | Matched groups |
| --- | ---: | ---: | ---: |
| Control fused | 7/24 | 14/24 | 23 |
| Expansion source | 0/24 | 0/24 | 0 |
| Shadow fused | 7/24 | 14/24 | 23 |

- The frozen selector baseline is `7/24`, `14/24`, `22` matched groups.
  Current control gained one group rather than losing recall, but exact
  baseline equality is a fail-closed provenance requirement.
- The only changed control measurement was `security-review-procedure`, whose
  matched required groups changed from `[0]` in the prior frozen capture to
  `[0,1]` in this capture.
- Expansion applied to `irm-faithfulness` and `ai-law-enforcement-date`, but
  expansion-source recall remained zero and shadow fusion added no required
  group over its control.

## Evidence integrity

- Run 1 JSON SHA-256:
  `c2ad7db982593c1de9467ff9e26cdc6b275d33a485616440f377f051e208dcf0`
- Run 2 JSON SHA-256:
  `c42cbd80ea3673c86cda0191cb4be621709c8aab6705832f1016779b9a09bc3b`
- Selection JSON SHA-256:
  `1cc8062ee291345502597e7d43a6e3fb206681281a0fad2a65ec5366aec739d0`

The candidate did not demonstrate document-expansion improvement. Do not run
the difficult set or holdout from this result. The next safe task is a
read-only diagnosis of the control-rank drift before changing a selector floor
or retrieval policy.
