# Task 5: Safe Candidate Chunk Activation

## Root cause

`LawDocumentWriter.replaceChunks` deleted every existing law chunk before
creating replacements. A failed embedding or Qdrant write could therefore leave
the document without searchable direct evidence.

## TDD evidence

- RED: `replaceChunksKeepsExistingActiveChunksUntilCandidateIsVerified` failed
  because `deleteChunks(42)` was invoked from `LawDocumentWriter:129`.
- RED: `law-parent-child-rechunk-wave.test.js` failed because the wave script
  had no documented fail-closed candidate flow or safe help path.
- GREEN: focused Java tests passed (10 tests) and the Node wave test passed.

## Implementation

- Existing ACTIVE chunks remain searchable. Rebuilds create a CANDIDATE version
  with an expected chunk count; newly seen documents still create ACTIVE v1.
- Activation requires equal expected/candidate/indexed counts, no REVIEW or
  REJECT candidate, every candidate Qdrant point, and a stable collection whose
  configured vector size matches.
- Activation and rollback are transactional; old points are deleted only after
  transaction commit. Rollback first confirms the retired version's points.
- Search/read mapper paths filter to `activation_status = 'ACTIVE'`; a separate
  candidate-only index query supports pre-activation embedding.
- Rechunk scripts default to app-dev 8080, contain no 18080 reference, and use
  `preview -> create-candidate -> index -> verify -> activate`. Batch indexing
  is fail-closed for candidate activation rather than silently activating an
  unverified batch.

## Focused verification

```text
./mvnw.cmd -Dtest=LawDocumentWriterTests,LawOpenApiSyncServiceChunkPreviewTests,LawSemanticIndexServiceTests,LawChunkMapperXmlTests,LawApiSchemaMaintenanceTests test
Tests run: 10, Failures: 0, Errors: 0

node --test scripts/law-parent-child-rechunk-wave.test.js
pass 1, fail 0

node --check scripts/law-parent-child-rechunk-wave.js
node --check scripts/law-parent-child-rechunk-bulk.js
git diff --check
```

## Self-review

- Candidate verification is fail-closed for missing embedding state, point,
  collection snapshot, wrong vector size, review/reject quality, or count
  mismatch.
- Version-state rows include `expected_chunk_count`; state transitions retire
  prior ACTIVE rows and chunks in the same transaction.
- No live scripts, MariaDB/Qdrant mutation, runtime restart, or 18080 action
  was performed. `output/` remains untracked and untouched.

## Deferred checks

- The broad Spring-context Maven suite is intentionally deferred because it can
  connect to configured MariaDB and run schema-maintenance hooks.
- Candidate flow is not deployed or exercised against live 8080/Qdrant in this
  task. That must follow the Task 2 transport gate and runtime identity checks.

## Commit

Commit subject: `feat: activate law chunk versions safely` (the final SHA is
reported in the task handoff after the final schema consistency amendment).

## Isolation hardening follow-up

### Root cause and correction

- Candidate embeddings had been written into the production `law_chunks`
  collection before their database version became ACTIVE.  A failed cleanup
  could therefore consume retrieval top-K capacity even though database reads
  filtered inactive chunks.
- Candidate embeddings now go exclusively to derived
  `law_chunks_candidate`.  Activation copies verified points to production with
  `activationStatus=CANDIDATE`; only the transaction `afterCommit` callback
  marks them ACTIVE.  The old version is marked RETIRED before its best-effort
  deletion, and Qdrant searches exclude both CANDIDATE and RETIRED points.
- Candidate-version rows persist `preview_approved` and
  `unexplained_loss_span_count`.  The create endpoint requires an explicit
  `previewApproved=true` request to make a version activatable; any absent
  approval or nonzero unexplained loss fails the database gate.
- Generic semantic-index selection and the current indexed-source snapshot are
  now explicitly ACTIVE-only; candidate selection remains an explicit
  document/version path.

### Wave artifact contract

Each wave JSON now records a deterministic manifest identity (or the supplied
baseline manifest ID), old and new point IDs, old and new chunk versions, and
an executable PowerShell rollback command.  Candidate verification counts the
candidate collection before activation, then checks ACTIVE production points
after activation.  The script still uses only app-dev 8080 and contains no
18080/batch-runner path.

### Focused verification

```text
./mvnw.cmd -Dtest=LawDocumentWriterTests,LawOpenApiSyncServiceChunkPreviewTests,LawApiSchemaMaintenanceTests,QdrantClientTests,LawSemanticIndexServiceTests,LawChunkMapperXmlTests test
Tests run: 31, Failures: 0, Errors: 0, Skipped: 0

node --test scripts/law-parent-child-rechunk-wave.test.js
pass 2, fail 0

node --check scripts/law-parent-child-rechunk-wave.js
git diff --check
```

### Review residual risk

No live Qdrant, MariaDB, 8080, or 18080 action was performed.  The candidate
collection and promotion path must be exercised only under the later fixed
runtime/index identity gate; if a process dies after production copy but before
commit, its CANDIDATE points remain deliberately non-searchable and require
explicit cleanup rather than automatic promotion.

## Preview binding and durable activation follow-up

- Preview approval is now a deterministic SHA-256 token bound to target,
  document/detail identity, raw-source hash, exact normalized source and
  planned-content fingerprints, and measured loss spans.  Candidate creation
  recomputes it and rejects absent or stale values; the stored version row keeps
  the token hash, approval state, and loss count.
- Source coverage checks normalized section text against planned chunk text and
  reports each non-whitespace lost span.  Any loss blocks activation through the
  existing verification gate.
- Activation is now a separate transaction-backed saga: CANDIDATE becomes
  ACTIVATING while old chunks remain ACTIVE; idempotent Qdrant promotion,
  ACTIVE marking, and status verification run outside the DB flip; a second
  transaction atomically retires old DB chunks and activates the candidate.
  DB flip failure best-effort demotes Qdrant points and leaves ACTIVATING for a
  retry.  Cleanup failure persists ACTIVE_CLEANUP_PENDING and a repeated
  activation retries finalization without making the new DB version unavailable.
- `--apply=true` now rejects a missing real `RAG_BASELINE_MANIFEST_ID`; dry-run
  reports a clearly named selection fingerprint instead.

Focused fix2 verification: 37 Java tests passed, Node wave tests 2/0, both
wave scripts passed syntax checks, and `git diff --check` passed.  No live
runtime, DB, Qdrant, 8080, or 18080 operation was performed.

## Serialization and schema-parity follow-up

- Fresh schema and runtime migration now share the named activation-state CHECK
  set: CANDIDATE, ACTIVATING, ACTIVE_CLEANUP_PENDING, ACTIVE, and RETIRED.
  Runtime maintenance inspects and replaces divergent activation checks before
  adding the named canonical constraint.
- The saga claims CANDIDATE→ACTIVATING with an atomic owner CAS.  Only the
  owner can release or complete; a losing caller performs no Qdrant promotion,
  demotion, flip, or cleanup.  Old-point cleanup selects only actually ACTIVE
  chunks, not unrelated candidate or retired versions.
- Any Qdrant status verification miss or exception after marking a candidate
  ACTIVE compensates it back to CANDIDATE and releases the owned activation
  claim.  The tests cover incomplete verification, thrown verification, DB
  flip failure, Qdrant promotion failure, and a competing CAS loser.

Focused fix3 verification: 40 Java tests passed, Node wave tests 2/0, both
wave scripts passed syntax checks, and `git diff --check` passed.  No live
runtime, DB, Qdrant, 8080, or 18080 operation was performed.

## Durable document activation operation (fix4)

### Root cause and rejected design

The per-version `ACTIVATING` compare-and-set did not serialize different
candidate versions for the same document. A process crash could leave an
unrecoverable `ACTIVATING` version, and a failed Qdrant demotion could release
an owner even though the candidate's external state was unknown.

### Implementation

- Added one durable `law_api_document_activation_operations` row per document.
  It records the candidate version, random owner, lease, phase, exact prior
  active version and point IDs, exact candidate point IDs, and last error.
- Replaced the per-version claim/release path with owner-and-phase CAS mapper
  updates. A duplicate document-operation insert is a handled losing claim and
  does not issue a Qdrant mutation.
- Normal activation records `PREPARING`, moves the candidate to `ACTIVATING`,
  verifies Qdrant ACTIVE points, atomically flips database state to
  `DB_ACTIVE_CLEANUP_PENDING`, then cleans only the persisted prior snapshot
  before reaching `DONE`.
- Expired pre-flip leases are reclaimed into `RECOVERY_REQUIRED`, demoted and
  verified before the candidate is released. A failed demotion keeps
  `RECOVERY_REQUIRED`; expired DB-active operations resume cleanup only and
  never demote the active candidate.
- Fresh schema and runtime bootstrap now define the same activation-operation
  table and canonical version-status check name. Runtime check replacement is a
  no-op when the existing check is already canonical.

### TDD evidence

- RED: the new saga tests failed for expired pre-flip recovery, cleanup-only
  recovery, and demotion ambiguity against the intermediate operation-row
  implementation.
- RED: a simulated duplicate-key different-version operation claim escaped the
  saga; it now returns a blocked result without Qdrant mutation.
- RED: canonical CHECK maintenance unnecessarily re-added the canonical
  constraint; it now leaves it unchanged.

### Focused verification

```text
./mvnw.cmd -Dtest=LawDocumentWriterTests,LawOpenApiSyncServiceChunkPreviewTests,LawApiSchemaMaintenanceTests,QdrantClientTests,LawSemanticIndexServiceTests,LawChunkMapperXmlTests,LawChunkActivationSagaTests test
Tests run: 38, Failures: 0, Errors: 0, Skipped: 0

node --test scripts/law-parent-child-rechunk-wave.test.js
pass 2, fail 0

node --check scripts/law-parent-child-rechunk-wave.js
node --check scripts/law-parent-child-rechunk-bulk.js
git diff --check
```

### Self-review and deferred checks

- Cleanup and prior-version retirement consume only point/version snapshots
  stored with the claimed operation. Every saga database state mutation is
  guarded by operation owner and phase; the initial/replacement claim is
  serialized by the document primary key and expected `DONE` phase.
- No live MariaDB, Qdrant, 8080, or 18080 action was performed; `output/`
  remains untouched. The broad Spring-context suite remains intentionally
  deferred because it can execute schema-maintenance hooks against configured
  infrastructure.
- Residual risk: crash recovery and Qdrant idempotence are covered with unit
  doubles only. Exercise the operation table against a disposable database and
  isolated Qdrant collection before deployment.

### Commit

`fix: persist document activation operations` — SHA recorded in handoff.

## Runtime-fenced activation recovery (fix5)

### Review findings and correction

- Recovery had changed production points to `CANDIDATE` but then checked only
  the isolated staging collection. It now verifies the persisted candidate IDs
  in the production collection with `activationStatus=CANDIDATE` before
  releasing database state.
- Qdrant point lookups rejected more than 256 IDs. Candidate-staging existence
  and production ACTIVE/CANDIDATE status lookups now batch at 256 IDs in the
  client, preserving fail-closed union semantics.
- A lease alone cannot fence a delayed Qdrant writer. Operations now persist a
  JVM-stable `runtime_instance_id`, reusing `RuntimeConfigurationIdentity`.
  Under the single-8080 runtime contract, an expired `QDRANT_ACTIVATING`
  operation from the same runtime is blocked with zero Qdrant mutation; only a
  known different runtime may reclaim it into `RECOVERY_REQUIRED` and perform
  the demotion/release recovery. `RECOVERY_REQUIRED` and cleanup-pending
  phases retain normal owner-and-phase reclaim behavior.
- Reclaimed cleanup no longer depends on the old version row's owner token.
  The current operation owner and `DB_ACTIVE_CLEANUP_PENDING` phase are the
  authority for completing the already DB-active candidate, while Qdrant
  cleanup continues to use only persisted prior IDs.

### TDD evidence

- RED: same-runtime expired Qdrant activation attempted a reclaim; it now
  returns BLOCKED with no Qdrant interaction.
- RED: recovery never checked production CANDIDATE status, staging-only
  presence released an externally ACTIVE production point, and the >256 lookup
  threw its fixed bound exception.
- RED: cleanup completion required the stale version-owner token rather than
  the newly reclaimed operation authority.

### Focused verification

```text
./mvnw.cmd -Dtest=LawDocumentWriterTests,LawOpenApiSyncServiceChunkPreviewTests,LawApiSchemaMaintenanceTests,QdrantClientTests,LawSemanticIndexServiceTests,LawChunkMapperXmlTests,LawChunkActivationSagaTests,RuntimeConfigurationIdentityTests test
Tests run: 44, Failures: 0, Errors: 0, Skipped: 0

node --test scripts/law-parent-child-rechunk-wave.test.js
pass 2, fail 0

node --check scripts/law-parent-child-rechunk-wave.js
node --check scripts/law-parent-child-rechunk-bulk.js
git diff --check
```

### Residual risk

This fencing protocol intentionally does not claim support for concurrent app
instances. Legacy operation rows without a runtime ID are blocked rather than
guessed to be safe at the Qdrant-activating phase and need explicit recovery.
No live MariaDB, Qdrant, 8080, or 18080 action, and no broad Spring-context
suite, was run.

### Commit

`fix: fence document activation runtime` — SHA recorded in handoff.
