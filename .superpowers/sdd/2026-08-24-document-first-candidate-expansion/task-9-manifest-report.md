# Task 9 pre-approval manifest report

Date: 2026-08-24 (Asia/Seoul)

## Outcome

The first approved manifest is immutable terminal `NOT_EVALUABLE` evidence and
must never be reused. Evaluator fixes `1d9d43d8` and `cffa0258` are now built
and deployed on candidate app-dev 8080. A new v2 execution manifest passed every
pre-execution fence and is marked `READY_FOR_EXACT_APPROVAL`. No OpenAI request,
evaluation run, Qdrant mutation, MariaDB indexing mutation, or durable run
registration occurred while preparing v2.

The new approval-eligible canonical SHA-256 is:

`d2f1ed37ed067806e426b306fd7856d6d01c84c865edab038561eeee0ae8a047`

The earlier blocked canonical hash
`ca3b780d04527e2042990a1ed566b52a8fd0e0780065fe1e3e2d6edfed2cb4c6`
is permanently invalid. The fresh approval-eligible canonical SHA-256 is:

`4a5925f62213791998836e8739b02c2f42c9c37c995e08a40e5061eeb0923b38`

That hash was later consumed by the aborted run documented below and is no
longer approval-eligible.

## Candidate artifact

- Build: `.\mvnw.cmd -DskipTests package`
- Result: `BUILD SUCCESS`; tests intentionally skipped for packaging.
- JAR: `target/pandora-0.0.1-SNAPSHOT.jar`
- Size: `67,447,016` bytes
- SHA-256:
  `aaf560bc15b6a7ffea7792ae918374e2929c32fd98d6c2c42d56f99eb4e95784`
- Git commit: `0bcf0a34afcedff9938c23ab0568810a9dbf201f`
- Git tree: `a5a8097ff581722d98975d7ecacbbb2f7efb9a0d`

The candidate was not promoted to or deployed on port 18080.

## Exact frozen payload

- Manifest:
  `.superpowers/sdd/2026-07-30-rag-retrieval-evidence-shadow-migration/task-15-document-expansion-training-manifest.json`
- Canonical approval SHA-256 (excluding its own stored field):
  `4a5925f62213791998836e8739b02c2f42c9c37c995e08a40e5061eeb0923b38`
- Canonical-hash self-check: match.
- Ordered cases: `24`, unique IDs: `24`.
- Runs: `2`; K `30`; capture `100`; concurrency `1`.
- Expected OpenAI calls: `48` Embedding API calls and `0` Answer API
  calls.
- External destination/model: OpenAI Embedding API,
  `text-embedding-3-small`.
- Purpose: obtain one query embedding for each exact frozen Korean question in
  each independent run, then compare bounded document-expansion shadow recall
  with the frozen control.
- Qdrant destinations: local read-only searches of `law_chunks` and
  `rag_chunks_v4`.
- MariaDB effect: bounded read-only retrieval only.
- Planned local effects: only the explicitly listed run, Markdown, stdout, and
  selector evidence files after a valid exact approval.

## Runtime fence evidence

Read-only `scripts/status-pandora.ps1`, two runtime-info reads, listener
inspection, MariaDB service inspection, and both Qdrant collection reads
established:

- app-dev 8080: candidate JAR listening on PID `14884`;
- runtime instance: `5e60e115-32ac-405c-af3a-c827509cfaea`;
- runtime artifact SHA-256:
  `aaf560bc15b6a7ffea7792ae918374e2929c32fd98d6c2c42d56f99eb4e95784`;
- runtime config SHA-256:
  `c4c561172e2864f6215698bc002095ebe73b636a06d86057bc0dfc086620504c`;
- index revision:
  `f374cacbc316b227f2e1b1f2e8331e8d1ed090a50ac836747aab129200743c42`;
- lexical revision:
  `da8d51cecea3bd10ce9ba7eb40c2a25015d2166e983d836018616377de9bb9aa`;
- two runtime reads matched for instance, artifact, config, index, and lexical
  identity;
- batch-runner 18080: not listening; service not installed;
- Qdrant 6333: PID `22036`, ready, search failures `0`;
- `law_chunks`: green, optimizer `ok`, update queue `0`, points `211,548`;
- `rag_chunks_v4`: green, optimizer `ok`, update queue `0`, points `84,248`;
- law DB/Qdrant parity: `211,548/211,548`;
- RAG DB/Qdrant parity: `84,248/84,248`;
- MariaDB service: running;
- committed candidate authority flags: document expansion false, RRF false,
  coverage-aware disabled, both semantic authority flags false;
- expected committed-default config SHA-256:
  `c4c561172e2864f6215698bc002095ebe73b636a06d86057bc0dfc086620504c`.

The deferred live mapper gap was closed with the exact candidate mapper XML and
interfaces on the candidate test classpath against the live MariaDB. The
session used autocommit `false`, executed only the four new SELECT statements,
and was explicitly rolled back. Result: law documents `3`, law chunks `17`,
RAG documents `1`, RAG chunks `8`; configured overfetch/global bounds held.
The transient preflight test source was removed immediately after the passing
run, leaving no production or test-code delta.

## Exact approval stop

All pre-execution fences pass. Task 9 is stopped only for exact external
approval of manifest hash
`4a5925f62213791998836e8739b02c2f42c9c37c995e08a40e5061eeb0923b38`.
After approval, every identity/parity/health fence must be rechecked immediately
before the one-time 48-call execution. Any drift invalidates this hash.

Port 18080 and `output/` were not accessed or changed.

## Approved execution terminal result

The user exactly approved manifest
`4a5925f62213791998836e8739b02c2f42c9c37c995e08a40e5061eeb0923b38`.
Every fence was rechecked without drift immediately before launch. Run 1 was
then launched exactly once.

- OpenAI Embedding API calls consumed: `24`.
- Debug responses received: `24/24`.
- Accepted measurements: `0/24`.
- Capture validation errors: `24/24`, all identical:
  `documentExpansionFused must contain at most 24 items`.
- Run 1 process exit: `1`, complete `false`.
- Run 2: not launched.
- Selector: not launched.
- Difficult and holdout evaluations: not consumed.

Root cause is a deterministic local evaluator-schema defect. The server returns
the full shadow-fused ranking and serializes nullable document-expansion fields
on every item. `hasDocumentExpansionMetadata` checks field ownership rather
than non-null expansion membership, classifies the full fused list as expansion
hits, and applies the 24 source-hit ceiling to the wrong list.

The approved requests were not retried. Post-failure runtime/JAR/config/index/
lexical identity remained unchanged; parity stayed `211548/211548` and
`84248/84248`; Qdrant stayed ready/green with failures `0`; 18080 remained
absent. Control/shadow recall metrics and a selector policy are not available,
so the terminal promotion outcome is `NOT_EVALUABLE`. All authority flags stay
false.

Evidence:

- `task-15-document-expansion-training-run1.json`, SHA-256
  `61efe52be1b42a979c058295595891840869e6d6b46ef004041ccd1fde1749f6`;
- `task-15-document-expansion-training-run1.md`, SHA-256
  `68695dda6705a8902fbc986349aec540b3b28e86da7f1fda3368178404c438fd`;
- `task-15-document-expansion-training-run1.stdout.log`;
- `task-15-document-expansion-training-abort.json`;
- `task-15-document-expansion-training-abort.md`.

Next: fix and independently verify the capture predicate, build a new JAR,
generate a new manifest/hash, and obtain new exact approval. This manifest and
run must never be reused.

## Evaluator-fixed v2 pre-approval manifest

- Fix commits: `1d9d43d841f95f919d790d1056315f1d238b96eb` and
  `cffa0258f1f4992e6d9505f3fa0cfeef6864f770`.
- Focused Node verification: `46/46` passing, failures `0`.
- Staged package and deployed candidate JAR: `67,447,016` bytes, SHA-256
  `0a705d296c2ad83796dfecbf1a74ea99c4fdeb36b1ae07c72703eb760a8db4ad`.
- Candidate runtime instance:
  `239acc3b-ae46-4888-9931-9db018645f45`; config
  `c4c561172e2864f6215698bc002095ebe73b636a06d86057bc0dfc086620504c`;
  index `4b4ef7ceab2f16492aae9931a2d19fda3c91acd5cffa1270726eae4bbc128614`;
  lexical `da8d51cecea3bd10ce9ba7eb40c2a25015d2166e983d836018616377de9bb9aa`.
- Two runtime reads matched. Law parity is `211548/211548`, RAG parity is
  `84248/84248`; both collections are green, optimizer `ok`, update queue `0`,
  Qdrant ready, and search failures `0`.
- Live candidate mapper preflight passed in a SELECT-only MyBatis transaction
  with explicit rollback: law documents/chunks `3/17`, RAG documents/chunks
  `1/8`; the transient test source was removed.
- Authorities remain false: document expansion, RRF, coverage-aware, semantic
  verification, and semantic selection. Document expansion and RRF shadow
  capture remain enabled only for evaluation.
- Frozen v2 manifest:
  `task-15-document-expansion-training-manifest-v2.json`; canonical SHA-256
  `d2f1ed37ed067806e426b306fd7856d6d01c84c865edab038561eeee0ae8a047`.
- It preserves the exact ordered 24 questions, two runs, K `30`, capture `100`,
  concurrency `1`, exactly `48` OpenAI Embedding API calls using
  `text-embedding-3-small`, and `0` Answer API calls. Qdrant and MariaDB effects
  are read-only. Every v2 evidence path is new and absent.
- No external evaluation was executed. A fresh exact approval for the v2 hash
  and an immediate no-drift fence recheck are required before one-time launch.

Port 18080 remained absent and `output/` was not accessed.

## Evaluator-fixed v2 terminal evaluation

The user exactly approved canonical manifest
`d2f1ed37ed067806e426b306fd7856d6d01c84c865edab038561eeee0ae8a047`.
Every runtime, artifact, config, index, lexical, parity, Qdrant, authority,
mapper, process, evidence-path, and 18080 fence matched immediately before the
one-time launch.

- Run 1: `24/24` completed, request errors `0`, Qdrant failures `0`.
- Run 2: `24/24` completed, request errors `0`, Qdrant failures `0`.
- OpenAI Embedding API calls: exactly `48`; Answer API calls: `0`.
- Both runs used identical immutable provenance and policy identity.
- Both runs produced identical recall: control `9/24` all-required, `16/24`
  any-required, `28` matched groups; expansion source `0/24`, `0/24`, `0`;
  shadow fused `8/24`, `16/24`, `27`.
- Shadow fusion lost the control-passing `pre-consultation-target` case and
  added no required-ground match from the document-expansion source.
- The selector ran exactly once and returned `BASELINE_REGRESSION`, eligible
  `false`, reason `control recall does not match the frozen training baseline`.
  The frozen baseline is `7/14/23`; the observed control is `9/16/28`.
- Fail closed: no difficult manifest, difficult call, holdout call, or authority
  change. Document expansion remains shadow-only.

Terminal evidence uses only the frozen v2 paths:

- `task-15-document-expansion-training-v2-run1.json`;
- `task-15-document-expansion-training-v2-run1.md`;
- `task-15-document-expansion-training-v2-run1.stdout.log`;
- `task-15-document-expansion-training-v2-run2.json`;
- `task-15-document-expansion-training-v2-run2.md`;
- `task-15-document-expansion-training-v2-run2.stdout.log`;
- `task-15-document-expansion-training-v2-selection.json`;
- `task-15-document-expansion-training-v2-selection.md`.

Post-run runtime/JAR/config/index/lexical identity and DB/Qdrant parity remained
unchanged; Qdrant remained ready/green with failures `0`; 18080 remained absent
and `output/` was not accessed.
