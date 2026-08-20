# Task 15 progress handoff (2026-08-20)

## Current state

- Task 6 law embedding repair: `4272/4272` complete.
- Tasks 7-14: implemented.
- Task 15 remains shadow-only. Do not enable RRF or semantic authoritative mode until the acceptance gates pass.
- App-dev: port `8080`, runtime instance `c77d3b5d-6181-480b-b315-4d17496a6974`.
- Runtime JAR SHA-256: `133747579250ab1391bd1ed1eaa6a992f5162f89a92d06b78b781c7825601b4d`.
- Runtime config SHA-256: `8674f478300aa0f2cf49213f00c7b27c8d29ee096f687b442e2b459cdff0d85d`.
- Index revision: `3c0f52cd2806db2ec003b8a7c72c24a700e71a3051e41609b21f5b8a8a75c8fc`.
- Lexical revision: `da8d51cecea3bd10ce9ba7eb40c2a25015d2166e983d836018616377de9bb9aa`.
- Law DB/Qdrant: `211548/211548`; RAG DB/Qdrant: `84248/84248`; Qdrant ready; both collections green with optimizer status `ok`; search failures `0`.
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

- Pre-change artifacts: `logs/task15-difficult12-planned-bm25-run1.json` and `logs/task15-difficult12-planned-bm25-run2.json` (plus matching Markdown reports).
- Focused-first rerun artifacts: `logs/task15-difficult12-focused-first-run1.json` and `logs/task15-difficult12-focused-first-run2.json` (plus matching Markdown reports).
- Both focused-first runs completed `12/12` with request errors `0` and false grounds `0` under the same runtime, JAR, config, index revision, and lexical revision.
- Explicit-oracle cases: `8`; BM25 all-required recall improved from `2/8` (`25%`) to `3/8` (`37.5%`); fused all-required recall improved from `4/8` (`50%`) to `5/8` (`62.5%`).
- BM25 any-required recall is `6/8`; fused any-required recall is `8/8`.
- BM25 and fused ranks both repeated exactly `12/12` across the two focused-first runs.
- Warm BM25 latency across the latest 24 samples: p95 `291ms`, maximum `388ms`.
- Rank repeatability, latency, request-error, and false-ground gates pass. Acceptance still fails the required `80%` all-required direct-ground recall, so RRF and semantic authoritative flags remain off.
- The remaining fused all-required failures are `privacy-integrated-guide-purpose`, `pre-consultation-plan-stage`, and `whistleblower-disadvantage`. Continue with a bounded local diagnosis of candidate coverage and fusion loss before proposing another retrieval change or consuming another external evaluation allowance.

## Evaluation CLI incident

`node scripts/rag-eval-gate.js --help` does not implement a help option and started the default full evaluation. It printed `batch 1/101 (10 cases)` before PID `24304` was identified and terminated. No later batch was allowed to run. Do not pass CLI flags to this script; it is configured only through `RAG_EVAL_*` environment variables.
