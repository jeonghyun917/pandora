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
