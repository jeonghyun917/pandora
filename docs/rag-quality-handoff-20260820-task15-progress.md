# Task 15 progress handoff (2026-08-20)

## Current state

- Task 6 law embedding repair: `4272/4272` complete.
- Tasks 7-14: implemented.
- Task 15 remains shadow-only. Do not enable RRF or semantic authoritative mode until the acceptance gates pass.
- App-dev: port `8080`, runtime instance `4560c8c7-c75e-4cf0-bd82-67bb1063462e`.
- Runtime JAR SHA-256: `f271876d994c6d8d8a97053b906c07d45f2c4519ecc652caf7c67c9be0c097a7`.
- Runtime config SHA-256: `8674f478300aa0f2cf49213f00c7b27c8d29ee096f687b442e2b459cdff0d85d`.
- Index revision: `4ec3206a7a954f56259ba19cb719444a1208635500cc37cefc7df35e041dd21b`.
- Lexical revision: `da8d51cecea3bd10ce9ba7eb40c2a25015d2166e983d836018616377de9bb9aa`.
- Law DB/Qdrant: `211548/211548`; RAG DB/Qdrant: `84248/84248`; Qdrant ready; both collections green with optimizer status `ok`; search failures `0`.
- Port `18080` remains absent. The untracked `output/` directory was not touched.

## Changes in this checkpoint

1. BM25 consumes the bounded question plan in addition to the raw question. After the first difficult-12 run exposed noisy planner-term displacement, `focusedKeywords()` are now ordered before the remaining `lexicalKeywords()`. The existing maximum 24 query terms, 6 posting terms, and 4,000-document posting budget remain unchanged.
2. Semantic shadow matching now allows an action-bearing partial Korean claim with an implicit subject to use normal slot alignment. Partial label/value text without an action remains exact-text-only.
3. Regression tests were added for both behaviors using red/green TDD.
4. The bounded retrieval trace now retains the best-ranked untouched candidates across vector and BM25 sources instead of letting the source recorded first consume the entire trace budget. Candidates that have entered later stages, received a loss reason, or been selected are never evicted.
5. Candidate-loss extraction now includes the BM25 and fused shadow stages, so a direct oracle candidate visible only in shadow retrieval is no longer omitted from the diagnostic report.

## Verification evidence

- Focused BM25/planner/evidence-gate tests: `93/93` pass.
- Final backend suite after the retrieval-trace diagnostic fix: `1207` tests, `0` failures, `0` errors, `18` environment-dependent skips.
- Retrieval evaluation script suite: `37/37` pass.
- Final selector/evaluator/provenance Node suite: `94/94` pass.
- Final backend suite after restoring baseline RRF weights: `1208` tests,
  `0` failures, `0` errors, `18` environment-dependent skips.
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

## Leakage-safe RRF weight-selection outcome (2026-08-21)

- A fixed 24-case metadata-stratified training manifest was added. It excludes
  the difficult-12 and the remaining 57 explicit-oracle holdout cases. Manifest
  SHA-256: `4915b3cbf9a59a75c2c74c1f78097a5e9ab454bf1569663b8297393531274c37`.
- The exact approved 24 questions were evaluated twice with K `30`, rank
  capture `100`, and concurrency `1`. Both runs completed `24/24`, request
  errors `0`, under identical runtime/JAR/config/index/lexical provenance.
  Artifacts: `logs/task15-rrf-training-run1.json` and
  `logs/task15-rrf-training-run2.json`.
- BM25 ranks repeated exactly. Qdrant approximate vector search reordered
  near-tied candidates in 12 cases despite stable provenance. The selector was
  changed with red/green tests to replay both captures independently and require
  the same guarded winning weights in both; divergent winners fail closed.
- Both captures independently selected vector weight `1.0`, lexical weight
  `0.75`. Training all-required improved `7/24 -> 8/24`; any-required remained
  `14/24`; no baseline-passing case regressed. Selection artifact:
  `logs/task15-rrf-weight-selection.json`.
- The recommendation was deployed only to app-dev `8080`; RRF and semantic
  authoritative flags remained false. The difficult-12 was then evaluated
  twice under one stable runtime. Both runs completed `12/12`, errors `0`, and
  produced the same explicit-oracle fused all-required result: `5/8` (`62.5%`).
- The recommendation improved `privacy-integrated-guide-purpose` but regressed
  `egov-preliminary-review-target`; `pre-consultation-plan-stage` and
  `whistleblower-disadvantage` still failed. The required `7/8` (`80%`) gate
  was not met, so holdout and release evaluations were not consumed.
- Per the frozen promotion ladder, weights were restored to verified baseline
  `1.0/1.0`, a baseline JAR was rebuilt and redeployed only to app-dev `8080`,
  and authoritative flags remain false. Final runtime/Qdrant/listener fences
  are recorded in Current state above.
- Difficult artifacts: `logs/task15-rrf-difficult-run1.json` and
  `logs/task15-rrf-difficult-run2.json` (plus matching Markdown reports).
- Conclusion: global two-source RRF weight tuning alone cannot safely meet the
  remaining direct-ground gate. Do not tune again on difficult or holdout
  outcomes. The next bounded design must address candidate/group coverage or a
  general document-sibling/coverage-aware fusion rule on an independent
  training split before another promotion attempt.

## Remaining-failure diagnosis

- `privacy-integrated-guide-purpose`: BM25 finds required group 0 at rank 23 (`official_doc:118568`) and groups 0/1 overall, while vector top 30 finds neither. Fused top 30 retains only group 1.
- `pre-consultation-plan-stage`: vector, BM25, and fused top 30 all retain only group 1; group 0 is absent before fusion and therefore cannot be recovered by changing RRF weights alone.
- `whistleblower-disadvantage`: vector top 30 contains all three required groups; `law:11115402` at vector rank 28 covers groups 0 and 2. BM25 contains only group 1, and pure chunk-level RRF pushes that law candidate to fused rank 64.
- The common fusion symptom is that cross-source overlap can outrank a required sibling chunk from a document that already has a strong fused anchor. However, the current design requires pure weighted RRF and requires its initial weights to be selected on an independent training subset. The difficult-12 acceptance set must not be used for per-case or global weight tuning.
- No declared training split currently exists. Define and freeze an independent, stratified training manifest before implementing or selecting new RRF weights. Keep the difficult-12 and holdout sets untouched until the selected configuration is final.

## Evaluation CLI incident

`node scripts/rag-eval-gate.js --help` does not implement a help option and started the default full evaluation. It printed `batch 1/101 (10 cases)` before PID `24304` was identified and terminated. No later batch was allowed to run. Do not pass CLI flags to this script; it is configured only through `RAG_EVAL_*` environment variables.

## Coverage-aware fusion implementation checkpoint (2026-08-21)

- Feature branch: `codex/coverage-aware-fusion`; implementation commits through
  `7d8d4b62`.
- A bounded document-sibling rescue stage now runs after pure RRF using only the
  already hydrated candidate union. It performs no additional OpenAI, Qdrant,
  or MariaDB request.
- The production ceiling is two global rescues and one rescue per
  `target:documentId`. The fixed offline grid is baseline, `1/1/20`, `1/1/30`,
  `2/1/20`, and `2/1/30` (`maxRescues/maxPerDocument/sourceRankLimit`).
- The runtime keeps pure RRF and coverage-aware orders separately. Debug output
  now includes `coverageFused`, pure and coverage ranks, rescue anchor/reason,
  and `coverage-fused` candidate-loss reasons without exposing new candidate
  text or secrets.
- Runtime authority remains unchanged: `rrf-authoritative=false`,
  `coverage-aware.enabled=false`, and both semantic authoritative flags are
  false.
- Full local verification on the exact implementation tree:
  - Node selector/evaluator/provenance suite: `106/106` pass.
  - Maven suite: `1236` tests, `0` failures, `0` errors, `18` environment-only
    skips.
  - Candidate JAR SHA-256:
    `8c86337616de1c2c89baad8acc03019c47a46cc0c9529e483ee04e7f67761763`.
- Existing app-dev runtime was inspected read-only and was not restarted:
  runtime `4560c8c7-c75e-4cf0-bd82-67bb1063462e`, JAR
  `f271876d994c6d8d8a97053b906c07d45f2c4519ecc652caf7c67c9be0c097a7`,
  config `8674f478300aa0f2cf49213f00c7b27c8d29ee096f687b442e2b459cdff0d85d`,
  index `4ec3206a7a954f56259ba19cb719444a1208635500cc37cefc7df35e041dd21b`,
  lexical `da8d51cecea3bd10ce9ba7eb40c2a25015d2166e983d836018616377de9bb9aa`.
  Law and RAG DB/Qdrant parity remain `211548/211548` and `84248/84248`;
  Qdrant is ready with search failures `0`; no `18080` listener exists.
- The current exact training-manifest byte hash is
  `3c5cc394524389d9c05c0e72e602232a8eb98a297f25a55c4f0c71a85ae2b2db`.
  This supersedes the older handoff hash because request provenance is locked to
  the current exact file bytes.
- Ignored dry-run execution manifest:
  `logs/task15-coverage-training-execution-manifest.json`, SHA-256
  `eb73c11363b09d05501f4c3f8d088b4ada6537c8d2faf4993b628c20a87ef9fe`.
  It freezes the same ordered 24 training IDs, two runs, K `30`, rank capture
  `100`, concurrency `1`, 48 total OpenAI Embedding requests, and read-only
  Qdrant/MariaDB access. Difficult-12 overlap is zero.
- The exact execution manifest above was explicitly approved and executed once.
  Both independent 24-case runs completed without request errors:
  - run 1 JSON SHA-256:
    `fe317137d5c88aecd6ad68ca9413712ac20a8c327b05c8bbd90e3ad1be697144`;
  - run 2 JSON SHA-256:
    `ec7f76cd106853c5cede842a853af5e6511e5fc5269d1ee5780c39db03e56f4b`.
- The offline selector artifact
  `logs/task15-coverage-policy-selection.json` has SHA-256
  `34c08265b18ef06d38c022c5ddeed83ae4c5f2fb6356e94112508681fc147719`
  and terminal status `NO_COVERAGE_IMPROVEMENT`.
- Baseline training performance was `7/24` all-required cases, `14/24`
  any-required cases, and `23` total matched required groups. Bounded rescue
  policies changed ranks but did not improve the all-required count in either
  independent run (`7/7` versus baseline in both selectors). The selected
  policy is therefore the disabled baseline.
- Fail-closed outcome: the difficult-12 and holdout sets were not consumed;
  `law-ai.retrieval.rrf-authoritative=false`,
  `law-ai.retrieval.coverage-aware.enabled=false`,
  `law-ai.retrieval.coverage-aware.max-rescues=0`, and both semantic
  authoritative flags remain false.
- Post-selection runtime fences remained healthy on app-dev `8080`: runtime
  `3e9c0f0f-f88c-453d-a960-b857a93aa700`, candidate JAR
  `8c86337616de1c2c89baad8acc03019c47a46cc0c9529e483ee04e7f67761763`,
  config
  `4e0943f88ee5ba07bd2ef4a5f0940cdd2015debf545d7bb9be2d1264272df429`,
  final index revision
  `f97cb99b9e5526ae566564a4a52b47266849e89b4d6dd4c4194af63cb0894814`,
  law and RAG DB/Qdrant parity `211548/211548` and `84248/84248`, Qdrant
  ready, search failures `0`, and no `18080` listener.

## Document-first candidate expansion verification checkpoint (2026-08-24)

- Coverage-aware training ended `NO_COVERAGE_IMPROVEMENT`, so the next bounded
  candidate-entry experiment is a document-first MariaDB read path keyed only
  by strong title/provision anchors. It is implemented on
  `codex/document-first-candidate-expansion` and remains shadow-only.
- Verified production/test code commit:
  `04dbf342c3f113419b67735358d1f3de0748cfd1`; tree:
  `70c21dca46a9414d76fd0dc1b9e1c6449dd9d145`.
- Committed bounds are three documents, eight chunks per document, and 24
  unique chunks globally. `document-expansion.authoritative=false`,
  `rrf-authoritative=false`, coverage-aware remains disabled, and both semantic
  authority flags remain false.
- Verification: focused Maven `107/107`, relevant Node `111/111`, and full
  Maven `1301` tests with `0` failures, `0` errors, and `18` opt-in MariaDB
  integration skips.
- No OpenAI/Qdrant evaluation or mutation ran, no candidate runtime was
  deployed, and port `18080` plus `output/` were untouched. The stopped app-dev
  state made a safe live MyBatis/MariaDB mapper invocation unavailable; this is
  retained as a Task 9 pre-evaluation fence.
- Full evidence:
  `docs/superpowers/specs/2026-08-24-document-first-candidate-expansion-verification.md`.
- Task 9 must prepare a fresh immutable 24-case manifest and stop for exact
  OpenAI payload approval before any question leaves the machine. Difficult and
  holdout cases remain unconsumed.
