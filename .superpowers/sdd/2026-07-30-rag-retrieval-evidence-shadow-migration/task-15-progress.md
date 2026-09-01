# Task 15 document-first expansion checkpoint

Date: 2026-08-24 (Asia/Seoul)

- Candidate branch: `codex/document-first-candidate-expansion`
- Verified code commit/tree:
  `04dbf342c3f113419b67735358d1f3de0748cfd1` /
  `70c21dca46a9414d76fd0dc1b9e1c6449dd9d145`
- Verification: focused Maven `107/107`; Node `111/111`; full Maven `1301`
  tests, failures/errors `0/0`, environment-only skips `18`.
- Document expansion is enabled only as a bounded `3/8/24` shadow;
  document-expansion, RRF, coverage-aware, and semantic authority remain off.
- No external evaluation, OpenAI request, Qdrant mutation, deployment, or
  service lifecycle action was performed. Port `18080` and `output/` were not
  touched.
- Live MyBatis/MariaDB mapper execution remains a Task 9 pre-evaluation fence
  because app-dev was stopped and no safe read-only mapper harness was present.
- Evidence:
  `docs/superpowers/specs/2026-08-24-document-first-candidate-expansion-verification.md`.
- Next: prepare an immutable Task 15 training manifest, obtain exact external
  payload approval, recheck all runtime/index/config/parity fences, then launch
  at most once.

## Task 9 blocked manifest checkpoint

Date: 2026-08-24 (Asia/Seoul)

- Candidate JAR built successfully without tests or deployment: SHA-256
  `aaf560bc15b6a7ffea7792ae918374e2929c32fd98d6c2c42d56f99eb4e95784`,
  size `67,447,016` bytes, commit `0bcf0a34`.
- A 24-case/two-run blocked draft was frozen at
  `task-15-document-expansion-training-manifest.json`; canonical SHA-256
  `ca3b780d04527e2042990a1ed566b52a8fd0e0780065fe1e3e2d6edfed2cb4c6`.
  It specifies 48 OpenAI Embedding API calls, K `30`, capture `100`, and
  concurrency `1`, but is not approval-eligible because live runtime fields
  are null.
- Read-only status showed app-dev 8080 and Qdrant 6333 absent, MariaDB running,
  and no 18080 listener. Therefore runtime/JAR/config/index/lexical identity,
  Qdrant health/failure count, DB/Qdrant parity, and the deferred live mapper
  execution could not all be established.
- Fail-closed result: no OpenAI/Qdrant evaluation call, no MariaDB/Qdrant
  mutation, and no run registration. Restore the documented candidate runtime
  fences, then generate a new manifest/hash and obtain exact approval.

## Task 9 approval-ready checkpoint

Date: 2026-08-24 (Asia/Seoul)

- The blocked hash above remains invalid. Qdrant was restored by the runtime
  owner and only candidate app-dev 8080 was started through the documented
  script. Port 18080 remained absent.
- Fresh runtime fences: instance
  `5e60e115-32ac-405c-af3a-c827509cfaea`, candidate JAR
  `aaf560bc15b6a7ffea7792ae918374e2929c32fd98d6c2c42d56f99eb4e95784`,
  config `c4c561172e2864f6215698bc002095ebe73b636a06d86057bc0dfc086620504c`,
  index `f374cacbc316b227f2e1b1f2e8331e8d1ed090a50ac836747aab129200743c42`,
  lexical `da8d51cecea3bd10ce9ba7eb40c2a25015d2166e983d836018616377de9bb9aa`.
- Law DB/Qdrant parity `211548/211548`; RAG parity `84248/84248`;
  both collections green/optimizer `ok`/update queue `0`; Qdrant ready and
  search failures `0`.
- Live SELECT-only MyBatis preflight passed with rollback: law documents `3`,
  law chunks `17`, RAG documents `1`, RAG chunks `8`; mapper bounds held.
- Fresh exact approval hash:
  `4a5925f62213791998836e8739b02c2f42c9c37c995e08a40e5061eeb0923b38`.
  It freezes 24 ordered questions, two runs, 48 OpenAI Embedding API calls,
  K `30`, capture `100`, concurrency `1`, and no Answer API call.
- No external evaluation or storage mutation has run. Stop for exact approval,
  then recheck every fence once before launch.

## Task 9 approved-run fail-closed outcome

Date: 2026-08-24 (Asia/Seoul)

- Exact approval for manifest `4a5925f...` was received and every runtime,
  artifact, config, index, lexical, parity, Qdrant, authority, mapper, conflict,
  and 18080 fence matched immediately before launch.
- Run 1 sent the 24 approved questions once to the OpenAI Embedding API and
  received `24/24` debug responses. All 24 then failed local capture validation
  with `documentExpansionFused must contain at most 24 items`; accepted
  measurements were `0/24`, process exit `1`.
- Root cause: nullable document-expansion metadata fields exist on every full
  shadow-fused item, while `hasDocumentExpansionMetadata` checks property
  ownership. The evaluator therefore mistakes the full fused list for at most
  24 expansion-source hits.
- Per no-blind-retry policy, run 1 was not retried, run 2 and the selector were
  not launched, and difficult/holdout were not consumed. Training outcome is
  `NOT_EVALUABLE`; no control/shadow recall or policy result is publishable.
- Post-failure runtime identity and DB/Qdrant parity remained unchanged;
  Qdrant remained ready/green with failures `0`; authority flags remain false;
  18080 and `output/` remained untouched.
- Evidence: run1 JSON SHA-256
  `61efe52be1b42a979c058295595891840869e6d6b46ef004041ccd1fde1749f6`,
  run1 Markdown SHA-256
  `68695dda6705a8902fbc986349aec540b3b28e86da7f1fda3368178404c438fd`,
  stdout log, and terminal abort JSON/Markdown in this directory.
- Next requires an evaluator fix, independent verification, new JAR, new
  immutable manifest/hash, and new exact approval. Never reuse this manifest.

## Task 9 evaluator-fixed v2 approval-ready checkpoint

Date: 2026-08-24 (Asia/Seoul)

- The capture fixes are independently committed as `1d9d43d8` and `cffa0258`;
  focused evaluator/selector Node tests pass `46/46`.
- The fixed candidate JAR is `67,447,016` bytes with SHA-256
  `0a705d296c2ad83796dfecbf1a74ea99c4fdeb36b1ae07c72703eb760a8db4ad`.
  Candidate app-dev 8080 runtime is
  `239acc3b-ae46-4888-9931-9db018645f45`; config
  `c4c561172e2864f6215698bc002095ebe73b636a06d86057bc0dfc086620504c`;
  index `4b4ef7ceab2f16492aae9931a2d19fda3c91acd5cffa1270726eae4bbc128614`;
  lexical `da8d51cecea3bd10ce9ba7eb40c2a25015d2166e983d836018616377de9bb9aa`.
- Law and RAG DB/Qdrant parity are `211548/211548` and `84248/84248`.
  Both collections are green, optimizer `ok`, update queue `0`; Qdrant is
  ready with search failures `0`.
- Live SELECT-only mapper preflight passed and rolled back: law documents/chunks
  `3/17`, RAG documents/chunks `1/8`. All authority flags remain false.
- New immutable manifest
  `task-15-document-expansion-training-manifest-v2.json` freezes the unchanged
  ordered 24 cases twice, exactly 48 OpenAI Embedding API calls, zero Answer API
  calls, K `30`, capture `100`, concurrency `1`, and read-only Qdrant/MariaDB.
  Canonical SHA-256:
  `d2f1ed37ed067806e426b306fd7856d6d01c84c865edab038561eeee0ae8a047`.
- All v2 evidence paths are unique and absent. No external call or evaluation
  ran during preparation. The prior `4a5925f...` manifest and abort evidence
  remain immutable and non-reusable. Port 18080 and `output/` were untouched.

## Task 9 evaluator-fixed v2 terminal outcome

Date: 2026-08-24 (Asia/Seoul)

- Exact manifest `d2f1ed37...` was approved. All frozen fences matched before
  execution.
- Two independent runs completed `24/24` with request errors `0` and Qdrant
  search failures `0`: exactly 48 OpenAI Embedding API calls total and zero
  Answer API calls. Immutable provenance matched across runs.
- Both runs measured control `9/24` all-required, `16/24` any-required, `28`
  matched groups; expansion source `0/24`, `0/24`, `0`; shadow fused `8/24`,
  `16/24`, `27`.
- Shadow fusion lost `pre-consultation-target`, which passed control, and the
  expansion source added no required-ground match.
- The one-time selector returned `BASELINE_REGRESSION`, eligible `false`, with
  reason `control recall does not match the frozen training baseline`.
- Fail closed: authority remains false; no difficult/holdout manifest or call
  was made. Runtime/parity/Qdrant fences remained stable; 18080 and `output/`
  were untouched. Exact run, stdout, and selector JSON/Markdown evidence is
  archived under the v2 filenames in this directory.

## Task 9 corrected v3 terminal outcome

Date: 2026-08-24 (Asia/Seoul)

- The fused-to-fused comparator and strict multi-token Korean title extraction
  were corrected and verified at source commit `f9d47f66`; the deployed
  candidate JAR SHA-256 was
  `e4b035429f2686d191675282e984884ede9c3334c844d4d45ec006a75553db00`.
- Exact manifest `094b9aa8...` was approved. Both independent runs completed
  `24/24` with request errors `0`, stable immutable provenance, and Qdrant
  search failures `0`: exactly `48` OpenAI Embedding API calls and `0` Answer
  API calls.
- Both runs measured fused control `7/24` all-required, `14/24` any-required,
  `22` matched groups; expansion source `0/24`, `0/24`, `0`; shadow fused
  `7/24`, `14/24`, `22`.
- Expansion applied for `irm-faithfulness` and `ai-law-enforcement-date`, but
  added no required-ground match. The other 22 cases had no strong anchor.
- The historical selector floor `7/14/23` came from the invalid source-union
  comparator. TDD reproduced the mismatch and corrected only the fused-control
  group floor to `7/14/22`; selector tests passed `3/3` and the related Node
  suite passed `113/113`.
- The one-time corrected selector returned
  `NO_DOCUMENT_EXPANSION_IMPROVEMENT`, eligible `false`. No difficult or
  holdout evaluation ran, and no authority flag changed.
- Qdrant/MariaDB remained read only; port 18080 and `output/` were untouched.
  Complete v3 run, stdout, policy, and selector JSON/Markdown evidence is
  archived in this directory.

## BM25 title-seeded expansion implementation checkpoint

Date: 2026-08-25 (Asia/Seoul)

- Code commit/tree: `0bd36c2b54dc63f6f11ff5e1541cb33ec46bf47d` /
  `30ea575d435e458ba58a84f3547ec48a6468b8f3` on
  `codex/document-first-candidate-expansion`.
- Added a bounded BM25-title document-seed fallback only when strong anchor
  extraction returns `NO_STRONG_ANCHOR`; a strong `APPLIED` anchor still wins.
- Policy: enabled, max hits `100`, minimum title terms `2`, ambiguity ratio
  `0.05`, and existing expansion limits `3/8/24`.
- Authority remains false. The BM25 path is capture/shadow evidence only and
  cannot alter final answer grounds or the existing control orders.
- Debug/capture evidence includes seed term count, finite positive score, and
  bounded rank. Offline validation checks seed status, identity, bounds, and
  selection-policy/runtime-config hash equality.
- Verification: focused Maven `69/69`; related Node `114/114`; fresh full Maven
  `1324` tests with failures/errors `0/0` and opt-in MariaDB skips `18`.
- The first full run found a configuration-record constructor binding error.
  `@ConstructorBinding` on the canonical constructor fixed the root cause;
  focused application-context regression tests passed `5/5` before the clean
  full-suite rerun.
- Read-only status: Qdrant `6333` and app-dev `8080` were listening; `18080`
  was absent with a stale PID file only. No service lifecycle action, OpenAI
  call, Qdrant/MariaDB mutation, or `output/` access occurred.
- The live 8080 runtime is not the new candidate. Candidate deployment, a live
  read-only mapper fence, and a new immutable 24-case manifest remain required
  before any external evaluation. Do not reuse the historical `094b9aa8...`
  approval or evidence.

## BM25 title-seeded expansion training result

Date: 2026-08-25 (Asia/Seoul)

- Merged candidate commit `cb4d499d41c4787c39fb2a88bc196a3671909ee9`
  was packaged as JAR SHA-256 `a05d6411e0673705921843f4959f49dadfe07a80071ce516c3e70370650ec965`
  and deployed to app-dev 8080 only. Runtime instance was
  `895a548c-0ab7-455e-9c3a-7032015af9e0`; 18080 remained absent.
- Fresh live mapper preflight passed with explicit rollback: law documents/chunks
  3/14 and RAG documents/chunks 3/20. No database mutation occurred.
- Exact manifest `7dcd1601...` was approved. An initial missing case-ID argument
  failed before runtime retrieval or external calls; the corrected frozen scope
  then completed two independent 24/24 runs with 48 total OpenAI Embedding API
  calls, 0 Answer API calls, request errors 0, and Qdrant failures 0.
- Both runs reproduced control `7/24` all-required, `14/24` any-required, and
  `22` matched groups. Expansion source was `0/24`, `0/24`, `0`; shadow fused
  remained `7/24`, `14/24`, `22`.
- BM25 title seeding applied to `project-review-hardware-exclusion` and
  `msit-tving-investigation`, but all emitted chunks overlapped existing source
  candidates and added no required-oracle group.
- Selector result is `NO_DOCUMENT_EXPANSION_IMPROVEMENT`, eligible `false`.
  No difficult/holdout evaluation ran and no authority flag changed.
- Law DB/Qdrant stayed 211,548/211,548; RAG DB/Qdrant stayed 84,248/84,248.
  Port 18080 and `output/` were untouched.

## BM25 novel-chunk expansion approval-ready checkpoint

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

## BM25 novel-chunk expansion terminal outcome

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

## BM25 partial-hydration isolation terminal outcome

Date: 2026-08-31 (Asia/Seoul)

- The approved v2 execution was aborted before OpenAI receipt because app-dev
  8080 inherited the Codex sandbox network restriction. All 24 connections
  failed locally with `Permission denied: getsockopt`; run 2 was not started,
  successful embedding calls were `0`, and the failed evidence was preserved.
- App-dev 8080 was redeployed through the official user-process path with the
  same verified JAR/config. Exact replacement manifest SHA-256
  `8d6fe589bf58d627a1c8956c0492da3581c9c5a64931f8d57af0cf3ebdd53f97`
  was approved after fresh runtime, index, parity, Qdrant, and destination
  reachability checks.
- Both independent replacement runs completed `24/24` with request errors `0`,
  stable immutable provenance, and Qdrant search failures `0`: exactly `48`
  successful OpenAI Embedding API calls and `0` Answer API calls.
- Both runs reproduced the frozen control exactly: `7/24` all-required,
  `14/24` any-required, and `22` matched groups. Expansion source remained
  `0/24`, `0/24`, `0`; shadow fused remained `7/24`, `14/24`, `22`.
- Expansion applied to `irm-faithfulness` and `ai-law-enforcement-date` but
  added no required oracle group. The remaining 22 cases reported
  `BM25_TITLE_NO_MATCH`.
- The selector returned `NO_DOCUMENT_EXPANSION_IMPROVEMENT`, eligible `false`.
  The incomplete-candidate isolation fix removed the earlier whole-set
  invalidation failure mode, but did not improve frozen-set recall. No difficult
  or holdout evaluation ran and no authority flag changed.
- Run 1, run 2, and selector JSON SHA-256 values are respectively
  `fb0011ef211b3360aadb23a6344f608dbccfcbad1aaf89dc397221daa4127cca`,
  `34bfd3993c3b98aa9c3c4418e3cfeb2db0d0ea3f17a7ce10920cbb9bfe0cd1df`,
  and `2573039c8c673bf75b503041aad359a77a2b5c5453c28490048b69cd803309e4`.
  Port 18080 and `output/` were untouched.

## BM25 title no-match diagnostics approval-ready checkpoint

Date: 2026-09-01 (Asia/Seoul)

- Diagnostic-only commit `fdc79dd7` records the normalized planned-term count,
  inspected BM25 candidate count, hydrated candidate count, maximum matched
  title-term count, and concrete no-match reason. Retrieval ranking and all
  authority flags remain unchanged.
- The verified candidate JAR is `67,636,692` bytes with SHA-256
  `3c63bd78e1efb8e5210cff7634592d529cfede81abf1db1a31f75b566137160c`.
  App-dev 8080 runtime is `887f92d5-1460-4d52-8e11-4b2a91abb64b`;
  config, index, and lexical identities were stable across two reads.
- Law DB/Qdrant parity is `211548/211548`; RAG parity is `84248/84248`.
  Both collections are green with optimizer `ok`; Qdrant readiness is true and
  search failures are `0`. OpenAI API TLS reachability and credential presence
  passed without sending evaluation text.
- The new exact scope freezes the existing ordered 24-question training set for
  two runs: exactly `48` OpenAI Embedding API calls, `0` Answer API calls, and
  read-only Qdrant/MariaDB access. Canonical SHA-256 is
  `efa2d4b8783acd0d610f07ec5f1efa475ad3e082561b183105dad453c111ae0f`.
- No evaluation request has launched and all new evidence paths are absent.
  Port 18080 and `output/` were untouched. Exact payload approval and one final
  no-drift fence recheck are required before the one-time execution.

## BM25 title no-match diagnostics replacement approval checkpoint

Date: 2026-09-01 (Asia/Seoul)

- The exact `efa2d4b...` payload was approved, but its supervised app-dev 8080
  session had ended before the launch fence check. The evaluation therefore
  stopped before any OpenAI request; generated evidence paths remain absent.
- Official app-dev deployment restored the same verified JAR and configuration.
  Two runtime reads are stable under instance
  `9fa6d3db-c229-494a-a99c-843bd454f9e7`; the restarted index revision is
  `bd1cf1b42dc73dfaf36a2784ccfe09a16955db808c6fc4c517fb3001eb4d4853`.
  Law parity remains `211548/211548`, RAG parity `84248/84248`, Qdrant is ready,
  and search failures remain `0`.
- The question payload, model, destinations, code artifact, database counts,
  and read-only effects are unchanged. The replacement canonical SHA-256 is
  `4a5b40911e3c9ef08f94578ef0459db38491281ebc0815614718749076652095`.
  Exact replacement approval is required before the one-time 48-call run.
- Port 18080 and `output/` remain untouched.

## BM25 title no-match diagnostics terminal result

Date: 2026-09-01 (Asia/Seoul)

- Replacement approval hash
  `4a5b40911e3c9ef08f94578ef0459db38491281ebc0815614718749076652095`
  executed as two complete ordered 24-case runs: exactly `48` successful
  OpenAI Embedding API calls and `0` Answer API calls. Both runs completed
  `24/24` with request errors `0`, runtime-end verification true, and Qdrant
  search failures `0`.
- The no-match classification was stable: `20` `TITLE_MISMATCH`, `0`
  insufficient planned terms, `0` no-valid-candidate, and `2`
  `BM25_TITLE_NO_NOVEL_CHUNK`; `2` cases were `APPLIED`. Every fully
  diagnosed miss inspected 100 candidates but matched at most one title term.
- One hydration counter varied (`pre-consultation-when`, `99` versus `100`).
  Retrieval also varied in `security-review-procedure` and
  `public-data-db-standard`: run 1 control/shadow fused was `7/24`, `14/24`,
  `22` groups, while run 2 was `7/24`, `14/24`, `23` groups. Expansion source
  stayed `0/24`, `0/24`, `0`.
- The JAR, configuration, lexical revision, content fingerprints, and exact
  DB/Qdrant counts stayed fixed, but app restarts changed index revision because
  its canonical input includes `updatedWatermark`. The next safe slice is to
  make hydration ordering and watermark provenance deterministic before testing
  a title canonicalization or threshold change.
- Qdrant and MariaDB access was read-only. Port `18080` and `output/` were
  untouched. Detailed evidence is in the matching `run1`, `run2`, and `summary`
  JSON/Markdown files.
