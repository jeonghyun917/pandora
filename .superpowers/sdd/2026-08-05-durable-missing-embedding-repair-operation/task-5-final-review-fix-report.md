# Task 5 final-review fix report

## Status

PASS. The three final-review findings and the four required mutation boundaries were closed in one bounded fix round. No runtime, shared database, Qdrant collection, port 8080/18080 process, deployment, live canary, or untracked `output/` artifact was changed.

Source/test commit: `88a80bb1` (`fix: close durable repair mutation races`). This report is committed separately so it can name the verified source commit.

## Root causes

1. The last exact runtime/revision comparison happened before embedding. The callback immediately before Qdrant checked only an eventually updated in-memory ownership flag, so same-instance unrelated revision drift could be absorbed into the operation's next trusted revision.
2. Foreground checkpoints did not synchronously consult durable ownership. `renewItemLease` also lacked live-expiry predicates, allowing an expired owner to renew before a reclaimer changed the owner token.
3. `law_api_chunk_embeddings` upsert and `law_api_document_chunks.index_status` update were independent mapper calls. A crash or second-write failure could leave the embedding row committed and let recovery classify the item as clean.
4. The Node apply path did not capture a pre-registration runtime-info baseline, so post-wave equality checks could not distinguish the planned law delta from unrelated coherent law/rag mutations.

## State-transition fixes

- Added a distinct post-embedding, final pre-Qdrant callback. It synchronously renews/proves the durable owner and requires the live runtime instance and index revision to equal the item's trusted pre-write fence. Drift returns `REJECTED_RUNTIME_FENCE` before Qdrant or relational mutation.
- `StepLease.assertOwned()` now synchronously renews/proves item and operation ownership at every foreground checkpoint. Completion/failure CAS paths renew first. Heartbeat remains supplementary.
- `renewItemLease` now requires both item and operation leases to be unexpired according to `CURRENT_TIMESTAMP(6)` before extending them. Each successful proof renews the documented 600-second lease before the next bounded downstream call.
- Added Spring-proxied `LawSemanticIndexStatusPersistenceService`. Its `@Transactional` atomic unit performs the embedding-row upsert and chunk-status update, rejects an affected-row count other than one, and preserves the existing retry loop around the whole atomic unit. Qdrant I/O remains outside the transaction.
- The runner now loads full runtime-info before registration, matches that baseline to the request and before-audit identity, and preserves it in structured failure evidence. Post-wave validation requires law DB and Qdrant counts to increase by exactly the planned candidate count, rag DB and Qdrant counts to remain exactly unchanged, baseline/post collection identity to match, and all prior gates to pass.

## RED/GREEN evidence

RED was observed before production changes:

- Focused Java test compilation failed because `LawSemanticIndexStatusPersistenceService` did not exist.
- Node suite: 23 passed / 2 failed; failures proved the baseline was loaded after the runner and that exact law/rag deltas were not enforced.
- Refined final-boundary test: 1 failed because post-embedding drift was reported as `INDEX_FAILED` instead of `REJECTED_RUNTIME_FENCE`; the mutation-negative assertions already proved Qdrant/DB were not reached after the fence was implemented.

Final GREEN commands and results:

- `& .\mvnw.cmd '-Dtest=LawSemanticIndexServiceTests,LawMissingEmbeddingRepairOperationMapperTests,LawMissingEmbeddingRepairOperationServiceTests,LawMissingEmbeddingRepairServiceTests,LawMissingEmbeddingRepairOperationControllerTests' test`
  - 63 tests, 0 failures, 0 errors.
- `node --test scripts\law-missing-embedding-repair-wave.test.js`
  - 25 tests, 25 passed.
- `& .\mvnw.cmd '-Dpandora.mariadb.it=true' '-Dtest=LawMissingEmbeddingRepairOperationMariaDbIntegrationTests#productionSemanticStatusTransactionRollsBackEmbeddingWhenChunkStatusWriteFails' test`
  - 1 test passed; actual Spring AOP proxy plus MariaDB trigger proved second-write failure rolls the embedding upsert back.
- `& .\mvnw.cmd '-Dpandora.mariadb.it=true' '-Dtest=LawMissingEmbeddingRepairOperationMariaDbIntegrationTests' test`
  - 18 tests, 0 failures, 0 errors. This includes expiry renewal, lease/recovery concurrency, and production transaction graph transitions.
- `node --check scripts\law-missing-embedding-repair-wave.js`
  - exit 0.
- `git diff --check` and `git diff --cached --check`
  - exit 0 (line-ending conversion warnings only).
- MariaDB cleanup query against `information_schema.SCHEMATA` for `pandora_repair_it_%`
  - `0` leftover disposable databases.

The first gated MariaDB attempt exposed an incomplete disposable test fixture (`law_api_chunk_embeddings` and chunk status columns were absent), not a production transaction failure. The fixture was completed with the production-compatible minimal schema, the intended trigger failure was observed, and both the focused proof and full gated class then passed. Every test database used a UUID name and was dropped.

## Self-review and concerns

- Reviewed the exact source/test diff for mutation ordering, owner/expiry predicates, proxy injection, retry scope, transaction placement, structured failure evidence, and negative controls.
- Mutation order is now: durable owner renewal/proof -> bounded remote call -> final owner+revision proof -> Qdrant -> owner proof -> atomic relational status transaction -> owner proof/CAS durable completion.
- Full Maven tests, deployment, runtime restart, and live canary were intentionally not run; those remain for the controller after re-review, as required by the brief.
- No live/runtime concern was introduced or observed. The only remaining risk is integration outside the focused/affected suites, to be covered by the controller's full-suite and live-canary phase.
