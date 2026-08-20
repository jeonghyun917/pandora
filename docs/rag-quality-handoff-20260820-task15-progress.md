# Task 15 progress handoff (2026-08-20)

## Current state

- Task 6 law embedding repair: `4272/4272` complete.
- Tasks 7-14: implemented.
- Task 15 remains shadow-only. Do not enable RRF or semantic authoritative mode until the acceptance gates pass.
- App-dev: port `8080`, runtime instance `acba7521-86aa-446c-ab48-5027cc3daaab`.
- Runtime JAR SHA-256: `b3f6e55fa3f17b76d00e2341f587be0307e8289931b9eca50ca53a9042fa2606`.
- Index revision: `ddf30b6713a930540acb9fbc295a87e507b25b709f6c795e69e5670fd7337615`.
- Lexical revision: `da8d51cecea3bd10ce9ba7eb40c2a25015d2166e983d836018616377de9bb9aa`.
- Law DB/Qdrant: `211548/211548`; RAG DB/Qdrant: `84248/84248`; Qdrant ready; search failures `0`.
- Port `18080` remains absent. The untracked `output/` directory was not touched.

## Changes in this checkpoint

1. BM25 now consumes the bounded `QuestionSearchPlan.lexicalKeywords()` in addition to the raw question. The existing maximum 24 query terms, 6 posting terms, and 4,000-document posting budget remain unchanged.
2. Semantic shadow matching now allows an action-bearing partial Korean claim with an implicit subject to use normal slot alignment. Partial label/value text without an action remains exact-text-only.
3. Regression tests were added for both behaviors using red/green TDD.

## Verification evidence

- Focused BM25/planner/evidence-gate tests: `93/93` pass.
- Final backend suite: `1205` tests, `0` failures, `0` errors, `18` environment-dependent skips.
- Exact approved Answer API evaluation:
  - `rfp-required-items`: pass, unsupported claims `0`.
  - `pre-consultation-when`: pass, unsupported claims `0`.
  - `pre-consultation-central-agency`: pass, unsupported claims `0`.
  - Semantic shadow disagreements: `0`; unsafe disagreements: `0`.
  - Artifact: `logs/rag-eval-gate-targeted-latest.json`.

## Remaining gate

The difficult-12 retrieval rerun is blocked only on exact approval to send these question texts to the OpenAI Embedding API:

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

After exact approval, run the retrieval evaluation twice with `K=30` and concurrency `1`, compare full BM25/fused ranks, direct-ground oracle presence, false grounds, and warm BM25 p95. Keep authoritative flags off unless every acceptance condition passes.

## Evaluation CLI incident

`node scripts/rag-eval-gate.js --help` does not implement a help option and started the default full evaluation. It printed `batch 1/101 (10 cases)` before PID `24304` was identified and terminated. No later batch was allowed to run. Do not pass CLI flags to this script; it is configured only through `RAG_EVAL_*` environment variables.
