# Task 15 progress handoff (2026-08-20)

## Current state

- Task 6 law embedding repair: `4272/4272` complete.
- Tasks 7-14: implemented.
- Task 15 remains shadow-only. Do not enable RRF or semantic authoritative mode until the acceptance gates pass.
- App-dev: port `8080`, runtime instance `b8581a12-85f9-4e2f-884f-0ecb15f2328f`.
- Runtime JAR SHA-256: `133747579250ab1391bd1ed1eaa6a992f5162f89a92d06b78b781c7825601b4d`.
- Index revision: `4ed8606c64dd489395c9cffec7bc7e6b002c85817b2a689a7452071cc458b5e1`.
- Lexical revision: `da8d51cecea3bd10ce9ba7eb40c2a25015d2166e983d836018616377de9bb9aa`.
- Law DB/Qdrant: `211548/211548`; RAG DB/Qdrant: `84248/84248`; Qdrant ready; search failures `0`.
- Port `18080` remains absent. The untracked `output/` directory was not touched.

## Changes in this checkpoint

1. BM25 consumes the bounded question plan in addition to the raw question. After the first difficult-12 run exposed noisy planner-term displacement, `focusedKeywords()` are now ordered before the remaining `lexicalKeywords()`. The existing maximum 24 query terms, 6 posting terms, and 4,000-document posting budget remain unchanged.
2. Semantic shadow matching now allows an action-bearing partial Korean claim with an implicit subject to use normal slot alignment. Partial label/value text without an action remains exact-text-only.
3. Regression tests were added for both behaviors using red/green TDD.

## Verification evidence

- Focused BM25/planner/evidence-gate tests: `93/93` pass.
- Final backend suite after the focused-first BM25 change: `1206` tests, `0` failures, `0` errors, `18` environment-dependent skips.
- Exact approved Answer API evaluation:
  - `rfp-required-items`: pass, unsupported claims `0`.
  - `pre-consultation-when`: pass, unsupported claims `0`.
  - `pre-consultation-central-agency`: pass, unsupported claims `0`.
  - Semantic shadow disagreements: `0`; unsafe disagreements: `0`.
  - Artifact: `logs/rag-eval-gate-targeted-latest.json`.

## Difficult-12 retrieval result

The exact approved 12 questions were sent twice with `K=30` and concurrency `1`:

- `egov-preliminary-review-target`
- `irm-user-auth-guide`
- `noise-irm-menu-user-auth`
- `performance-measure-when`
- `whistleblower-protection-scope`
- `privacy-integrated-guide-purpose`
- `pre-consultation-plan-stage`
- `privacy-consent-refusal`
- `whistleblower-disadvantage`
- `mois-disaster-field-support`
- `mois-national-safety-plan`
- `official-find-pipc-ai-privacy`

- Artifacts: `logs/task15-difficult12-planned-bm25-run1.json` and `logs/task15-difficult12-planned-bm25-run2.json` (plus matching Markdown reports).
- Both runs completed `12/12` with request errors `0` and false grounds `0`.
- Explicit-oracle cases: `8`; BM25 all-required recall `2/8` (`25%`); fused all-required recall `4/8` (`50%`).
- BM25 ranks repeated exactly `12/12`; fused ranks differed in `3/12` cases.
- Warm BM25 latency across 24 samples: p95 `444ms`, maximum `534ms`.
- Acceptance failed on the required `80%` direct-ground recall and exact fused-rank repeatability. RRF and semantic authoritative flags therefore remain off.
- Case comparison showed useful gains for preliminary-review and consent-refusal queries, but a BM25 regression for whistleblower disadvantage. The focused-first planner-term change is deployed locally and fully unit-tested, but has not yet received a new external difficult-12 rerun. A further 12-question × 2-run external evaluation requires a new exact approval because the prior two-run allowance was consumed.

## Evaluation CLI incident

`node scripts/rag-eval-gate.js --help` does not implement a help option and started the default full evaluation. It printed `batch 1/101 (10 cases)` before PID `24304` was identified and terminated. No later batch was allowed to run. Do not pass CLI flags to this script; it is configured only through `RAG_EVAL_*` environment variables.
