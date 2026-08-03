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
