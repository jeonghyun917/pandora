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

## Document-expansion Task 9 blocked manifest checkpoint (2026-08-24)

- The candidate JAR was built without deployment. SHA-256:
  `aaf560bc15b6a7ffea7792ae918374e2929c32fd98d6c2c42d56f99eb4e95784`;
  size `67,447,016` bytes; source commit `0bcf0a34`.
- The exact ordered 24-case/two-run payload draft is recorded in the Task 15
  ledger directory. Its canonical SHA-256 is
  `ca3b780d04527e2042990a1ed566b52a8fd0e0780065fe1e3e2d6edfed2cb4c6`,
  with 48 planned OpenAI Embedding API calls, K `30`, capture `100`, and
  concurrency `1`.
- This hash is not approval-eligible: app-dev 8080 and Qdrant 6333 were not
  listening, so the live runtime/config/index identity, collection health,
  zero-failure and parity fences, and the deferred Task 3 mapper execution
  could not be frozen. A fresh manifest/hash is required after restoration.
- MariaDB was running and 18080 remained absent. No OpenAI call, Qdrant request
  or mutation, MariaDB indexing mutation, evaluation run, or run registration
  occurred. `output/` was not accessed.

### Approval-ready refresh

- Qdrant 6333 was restored through its official lifecycle, and only the
  candidate app-dev 8080 was started. Candidate runtime identity is
  `5e60e115-32ac-405c-af3a-c827509cfaea`; JAR
  `aaf560bc15b6a7ffea7792ae918374e2929c32fd98d6c2c42d56f99eb4e95784`;
  config `c4c561172e2864f6215698bc002095ebe73b636a06d86057bc0dfc086620504c`;
  index `f374cacbc316b227f2e1b1f2e8331e8d1ed090a50ac836747aab129200743c42`;
  lexical `da8d51cecea3bd10ce9ba7eb40c2a25015d2166e983d836018616377de9bb9aa`.
- Law parity is `211548/211548`, RAG parity `84248/84248`; both Qdrant
  collections are green with optimizer `ok`, update queue `0`, readiness true,
  and search failures `0`.
- A SELECT-only, autocommit-disabled MyBatis session executed both new document
  and chunk statements against live MariaDB and rolled back. It returned law
  documents/chunks `3/17` and RAG documents/chunks `1/8`; all bounds held.
- The prior blocked hash is invalid. Fresh approval-eligible manifest SHA-256:
  `4a5925f62213791998836e8739b02c2f42c9c37c995e08a40e5061eeb0923b38`.
  It covers the exact 24 ordered questions twice: 48 OpenAI Embedding API calls,
  K `30`, capture `100`, concurrency `1`, and zero Answer API calls.
- No external evaluation has launched. Port 18080 and `output/` remain
  untouched. Exact approval and an immediate no-drift fence recheck are the
  only remaining pre-launch gates.

### Approved document-expansion training abort

- Exact manifest `4a5925f...` was approved and all fences matched immediately
  before its one-time run 1 launch.
- The 24 approved question embeddings were requested exactly once and all 24
  debug responses returned, but every response failed local capture validation:
  `documentExpansionFused must contain at most 24 items`. Run 1 accepted `0/24`
  measurements and exited `1`.
- This is a deterministic evaluator mismatch: all full shadow-fused DTO items
  serialize nullable document-expansion fields, while the capture predicate
  treats field ownership as expansion membership and incorrectly applies the
  24 source-hit limit to the full fused ranking.
- Fail-closed: no retry, run 2, selector, difficult set, or holdout. No recall
  or policy recommendation is valid; terminal status is `NOT_EVALUABLE` and
  every authority flag remains false.
- Runtime identity and parity remained stable after failure, Qdrant remained
  green/ready with search failures `0`, and 18080 plus `output/` remained
  untouched. Immutable run/abort evidence is archived in the Task 15 ledger.
- Repair requires a separately reviewed evaluator fix, new artifact, new
  manifest/hash, and new exact approval; the consumed manifest cannot be reused.

### Evaluator-fixed v2 approval-ready refresh

- Capture fixes `1d9d43d8` and `cffa0258` pass the focused Node suite `46/46`.
  The rebuilt candidate JAR SHA-256 is
  `0a705d296c2ad83796dfecbf1a74ea99c4fdeb36b1ae07c72703eb760a8db4ad`
  (`67,447,016` bytes).
- Candidate app-dev 8080 runtime is
  `239acc3b-ae46-4888-9931-9db018645f45`; config
  `c4c561172e2864f6215698bc002095ebe73b636a06d86057bc0dfc086620504c`;
  index `4b4ef7ceab2f16492aae9931a2d19fda3c91acd5cffa1270726eae4bbc128614`;
  lexical `da8d51cecea3bd10ce9ba7eb40c2a25015d2166e983d836018616377de9bb9aa`.
- Law/RAG parity is `211548/211548` and `84248/84248`; Qdrant collections are
  green, optimizer `ok`, update queue `0`, readiness true, failures `0`.
  SELECT-only live mapper execution returned law documents/chunks `3/17` and
  RAG documents/chunks `1/8`, then explicitly rolled back.
- New approval-eligible v2 canonical SHA-256:
  `d2f1ed37ed067806e426b306fd7856d6d01c84c865edab038561eeee0ae8a047`.
  It freezes the same ordered 24 questions twice: exactly 48 OpenAI Embedding
  API calls, no Answer API call, K `30`, capture `100`, concurrency `1`, and
  read-only Qdrant/MariaDB effects. All local evidence paths are new.
- No external evaluation was launched. Authority remains false, 18080 remains
  absent, and `output/` was not accessed. The consumed `4a5925f...` attempt is
  immutable terminal evidence and may not be reused.

### Evaluator-fixed v2 terminal outcome

- Exact approved manifest `d2f1ed37...` executed twice without retry: each run
  completed `24/24`, request errors `0`, Qdrant failures `0`. Total external
  use was exactly 48 OpenAI Embedding API calls and zero Answer API calls.
- Both runs had identical provenance and results. Control recall was `9/24`
  all-required, `16/24` any-required, `28` matched groups. Expansion-source
  recall was `0/24`, `0/24`, `0`; shadow-fused recall was `8/24`, `16/24`,
  `27`.
- Shadow fusion lost the control-passing `pre-consultation-target` case and
  gained no required-ground match from document expansion.
- The selector ran once and returned `BASELINE_REGRESSION`, eligible `false`.
  No difficult or holdout evaluation was prepared or consumed and no authority
  flag changed. Runtime, parity, and Qdrant health remained stable; 18080 and
  `output/` were untouched. The v2 evidence set is archived in the Task 15
  ledger directory.

### BM25 novel-chunk expansion approval-ready checkpoint

Date: 2026-08-25 (Asia/Seoul)

- Merged implementation commit `5f3117a8ed3fb9cf478adb56637605d5f1cab9c1`
  (tree `3f5cd5b6be4449a2afcc88d6f20d61df46a50e5e`) makes the BM25
  title-seeded shadow path skip already-seen source candidates and continue
  scanning a bounded deeper pool for genuinely novel chunks. Strong-anchor
  behavior and all authority flags remain unchanged.
- Fresh merged-tree verification passed: Maven `1326` tests with failures and
  errors `0/0` (`18` opt-in MariaDB skips), plus related Node tests `114/114`.
- App-dev 8080 only was deployed with JAR SHA-256
  `f8ce7f16bf16ddbbb47efe10f102bb12ee248ec76744508d97f0668e65d05f67`.
  Runtime `d52ee990-4105-4b1b-b1c8-996ff2d8d1f7`, config
  `4ffc75b09dfbeaed48e2cda40ad88fe0725d3d1a3f61f3dedc651ae3af44333c`,
  index `d7f79c7f07e289e720170936572c1e300abb9b6c3202fd0b9d08f584fb6746da`,
  and lexical `da8d51cecea3bd10ce9ba7eb40c2a25015d2166e983d836018616377de9bb9aa`
  were stable across repeated reads.
- Law DB/Qdrant parity is `211548/211548`; RAG parity is `84248/84248`.
  Both collections are green, optimizer `ok`, update queue `0`, readiness true,
  and Qdrant search failures `0`.
- A live read-only mapper preflight ran inside an automatically rolled-back
  transaction. It returned law documents/chunks `3/49` and RAG
  documents/chunks `3/55`; the candidate pool limits `24` per document and
  `72` total held.
- New exact 24-question, two-run manifest canonical SHA-256 is
  `202b0981abe9f360c7f2d3cdda98f0b61470c980e4b31a191b68f347c45f62df`.
  It permits exactly `48` OpenAI Embedding API calls, zero Answer API calls,
  K `30`, capture `100`, concurrency `1`, and read-only Qdrant/MariaDB access.
  Its evidence paths are new and absent.
- No external evaluation has launched and no authority flag changed. Port
  18080 and `output/` were untouched. Exact payload approval and an immediate
  no-drift fence recheck remain required before the one-time evaluation.

### BM25 novel-chunk expansion terminal outcome

Date: 2026-08-25 (Asia/Seoul)

- Exact manifest SHA-256 `202b0981abe9f360c7f2d3cdda98f0b61470c980e4b31a191b68f347c45f62df`
  was approved and passed its immediate no-drift preflight.
- Both independent runs completed `24/24` without retry, with request errors
  `0` and Qdrant search failures `0`. External use was exactly `48` OpenAI
  Embedding API calls and zero Answer API calls.
- Both runs measured control fused `7/24` all-required, `14/24` any-required,
  `23` matched groups; expansion source `0/24`, `0/24`, `0`; shadow fused
  `7/24`, `14/24`, `23`.
- Expansion applied to `irm-faithfulness` and `ai-law-enforcement-date`, but it
  found no required oracle group and added no recall over control.
- The selector returned `BASELINE_REGRESSION`, eligible `false`, because the
  exact frozen control is `7/14/22` while this capture is `7/14/23`. The drift
  is localized to `security-review-procedure`, whose matched groups changed
  from `[0]` to `[0,1]`; it is a gain, but exact baseline equality is required.
- No difficult or holdout evaluation ran and no authority flag changed.
  Postflight runtime/JAR/config/index/lexical fences remained stable. Law
  DB/Qdrant stayed `211548/211548`; RAG stayed `84248/84248`; both collections
  remained green with optimizer `ok` and failures `0`.
- Run 1, run 2, and selector JSON SHA-256 values are respectively
  `c2ad7db982593c1de9467ff9e26cdc6b275d33a485616440f377f051e208dcf0`,
  `c42cbd80ea3673c86cda0191cb4be621709c8aab6705832f1016779b9a09bc3b`,
  and `1cc8062ee291345502597e7d43a6e3fb206681281a0fad2a65ec5366aec739d0`.
  Port 18080 and `output/` were untouched.
