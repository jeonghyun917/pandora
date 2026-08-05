# Durable Missing-Embedding Repair Operation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to execute this plan task-by-task with a fresh implementer and a fresh reviewer for each task.

**Goal:** Replace the long synchronous missing-embedding repair request with a MariaDB-persisted, one-item-per-request operation that can recover the exact committed outcome after a lost HTTP response without duplicating mutations.

**Architecture:** Registration performs the existing bounded-wave preflight once and atomically stores an immutable ordered candidate set. A short `step` request claims one item with a lease/CAS, revalidates its content/classification and the operation's trusted runtime fence, performs the existing exact one-chunk indexing, verifies it, and durably records the outcome plus the new index revision. The Node runner treats transport failures as unknown outcomes and resolves them through `GET`; it declares a wave successful only after the existing full integrity, parent/child, short-chunk, collection, and runtime gates pass.

**Tech Stack:** Java 21, Spring Boot MVC/JDBC transactions, MyBatis/MariaDB, Node.js built-in test runner and fetch, Qdrant through the existing `LawSemanticIndexService`.

**Safety invariants:** Never use 18080. Never mutate `output/`. Preserve the existing fail-closed runtime, content-hash, classification, document-count, candidate-count, and post-wave audit fences. Do not retry an unresolved mutating request; reconcile it through durable operation state first.

---

### Task 1: Add durable operation persistence and schema

**Files:**
- Create: `src/main/java/com/kaces/pandora/semantic/integrity/LawMissingEmbeddingRepairOperation.java`
- Create: `src/main/java/com/kaces/pandora/semantic/integrity/LawMissingEmbeddingRepairOperationMapper.java`
- Create: `src/main/resources/mapper/law/LawMissingEmbeddingRepairOperationMapper.xml`
- Modify: `src/main/java/com/kaces/pandora/lawdata/persistence/LawApiSchemaMaintenance.java`
- Create: `src/test/java/com/kaces/pandora/semantic/integrity/LawMissingEmbeddingRepairOperationMapperTests.java`
- Modify: `src/test/java/com/kaces/pandora/lawdata/persistence/LawApiSchemaMaintenanceTests.java`

**Step 1: Write failing schema and mapper tests**

Add tests proving that startup creates both tables and their constraints/indexes:

```sql
law_missing_embedding_repair_operations(
  operation_id CHAR(36) PRIMARY KEY,
  idempotency_key CHAR(64) UNIQUE,
  normalized_request LONGTEXT,
  request_hash CHAR(64),
  target VARCHAR(20),
  runtime_instance_id CHAR(36),
  trusted_index_revision CHAR(64),
  status VARCHAR(32),
  candidate_count INT,
  document_count INT,
  indexed_count INT,
  failed_count INT,
  lease_owner CHAR(36),
  lease_expires_at DATETIME,
  last_error TEXT,
  created_at DATETIME,
  updated_at DATETIME
)

law_missing_embedding_repair_items(
  operation_id CHAR(36),
  ordinal INT,
  chunk_id BIGINT,
  document_id BIGINT,
  expected_content_hash CHAR(64),
  state VARCHAR(32),
  lease_owner CHAR(36),
  lease_expires_at DATETIME,
  before_index_revision CHAR(64),
  after_index_revision CHAR(64),
  detail TEXT,
  created_at DATETIME,
  updated_at DATETIME,
  PRIMARY KEY(operation_id, ordinal),
  UNIQUE(operation_id, chunk_id)
)
```

Add mapper contract tests for: atomic insert/read, idempotency-key lookup with normalized-request collision detection, ordered item reads, READY→PROCESSING claim only when the operation's runtime/revision fence matches, lease renewal/expiry reclaim, item completion, aggregate counters, trusted-revision advancement, and fail-closed operation failure. Verify a second claimant cannot acquire the same item.

**Step 2: Run the tests and confirm RED**

Run:

```powershell
.\mvnw.cmd -Dtest=LawApiSchemaMaintenanceTests,LawMissingEmbeddingRepairOperationMapperTests test
```

Expected: FAIL because the tables, domain records, mapper interface, and SQL do not exist.

**Step 3: Implement the minimum persistence layer**

Use enums with only these operation states: `READY`, `RUNNING`, `INDEXING_COMPLETE`, `FAILED`; and item states: `READY`, `PROCESSING`, `INDEXED`, `FAILED`, `NOT_ATTEMPTED`.

Keep immutable request fields separate from mutable progress fields. Store candidates in caller order. Use conditional SQL updates and affected-row counts for CAS rather than JVM locks. Schema maintenance must be idempotent and must not alter existing embedding/chunk tables beyond creating the two operation tables.

**Step 4: Run focused tests and self-review**

Run the command from Step 2, then:

```powershell
git diff --check
git diff -- src/main/java/com/kaces/pandora/semantic/integrity src/main/java/com/kaces/pandora/lawdata/persistence/LawApiSchemaMaintenance.java src/main/resources/mapper/law src/test/java/com/kaces/pandora
```

Confirm foreign keys, unique keys, state constraints, UTC-compatible timestamps, deterministic order, and CAS predicates are present.

**Step 5: Independent review and commit**

Have a fresh reviewer compare Task 1 against the design and tests. Resolve every correctness finding, rerun focused tests, and commit only Task 1 files.

---

### Task 2: Register and inspect a bounded repair operation

**Files:**
- Create: `src/main/java/com/kaces/pandora/semantic/integrity/LawMissingEmbeddingRepairOperationService.java`
- Create: `src/main/java/com/kaces/pandora/semantic/integrity/LawMissingEmbeddingRepairOperationController.java`
- Modify: `src/main/java/com/kaces/pandora/semantic/integrity/LawMissingEmbeddingRepairService.java`
- Create: `src/test/java/com/kaces/pandora/semantic/integrity/LawMissingEmbeddingRepairOperationServiceTests.java`
- Create: `src/test/java/com/kaces/pandora/semantic/integrity/LawMissingEmbeddingRepairOperationControllerTests.java`

**Step 1: Write failing registration tests**

Prove that `POST /api/admin/law-index-integrity/missing-embedding-repair-operations`:

- accepts only `target=law`, `apply=true`, 1..1000 unique candidates, and 1..50 unique expected document IDs;
- requires exact runtime instance ID and 64-hex index revision;
- reuses the existing preflight rules for active chunk, content hash, document set, and `MISSING_EMBEDDING_ROW` classification;
- computes a canonical SHA-256 request hash independent of JSON property formatting but sensitive to candidate order and every fence value;
- inserts operation plus items in one transaction and returns `202 Accepted` immediately;
- returns the existing operation for an identical request hash without duplicating items;
- rejects a hash collision/mismatched payload, runtime drift, classification drift, or document-set drift without persisting a runnable operation.

Prove that `GET /.../{operationId}` returns the operation and ordered items, and returns 404 for an unknown ID.

**Step 2: Run the tests and confirm RED**

```powershell
.\mvnw.cmd -Dtest=LawMissingEmbeddingRepairOperationServiceTests,LawMissingEmbeddingRepairOperationControllerTests test
```

Expected: FAIL because the operation service and endpoints do not exist.

**Step 3: Extract and reuse exact preflight logic**

Move only the reusable validation/classification code from `LawMissingEmbeddingRepairService` into package-visible helpers or a small validator owned by the new operation service. Preserve the legacy synchronous endpoint for compatibility, and make both paths apply identical bounds and preflight semantics.

Implement these controller methods:

```java
@PostMapping("/missing-embedding-repair-operations")
ResponseEntity<OperationView> registerOperation(@RequestBody RepairRequest request)

@GetMapping("/missing-embedding-repair-operations/{operationId}")
ResponseEntity<OperationView> getOperation(@PathVariable UUID operationId)
```

Registration must use a Spring transaction. Do not start indexing during registration.

**Step 4: Focused verification and self-review**

Run Step 2 tests and the existing `LawMissingEmbeddingRepairServiceTests`. Inspect for duplicated preflight rules, transaction gaps, nondeterministic hashing, exception detail leaks, and any path that turns an invalid operation into READY.

**Step 5: Independent review and commit**

Have a fresh reviewer verify API status codes, idempotency, immutable candidate ordering, and exact reuse of existing safety checks. Fix findings, rerun focused tests, and commit Task 2 files.

---

### Task 3: Process exactly one durable item per step

**Files:**
- Modify: `src/main/java/com/kaces/pandora/semantic/integrity/LawMissingEmbeddingRepairOperationService.java`
- Modify: `src/main/java/com/kaces/pandora/semantic/integrity/LawMissingEmbeddingRepairOperationMapper.java`
- Modify: `src/main/resources/mapper/law/LawMissingEmbeddingRepairOperationMapper.xml`
- Modify: `src/main/java/com/kaces/pandora/semantic/integrity/LawMissingEmbeddingRepairOperationController.java`
- Modify: `src/test/java/com/kaces/pandora/semantic/integrity/LawMissingEmbeddingRepairOperationServiceTests.java`
- Modify: `src/test/java/com/kaces/pandora/semantic/integrity/LawMissingEmbeddingRepairOperationControllerTests.java`

**Step 1: Write failing step/recovery tests**

Cover these transitions:

1. READY item is atomically claimed as PROCESSING with a short lease.
2. A step indexes at most one chunk and verifies that exact chunk.
3. Successful verification stores INDEXED and advances `trusted_index_revision` to the post-write revision while preserving the runtime instance ID.
4. The last success changes the operation to INDEXING_COMPLETE.
5. Content/classification/runtime drift marks the item FAILED, remaining READY items NOT_ATTEMPTED, and operation FAILED.
6. An indexing exception has the same fail-closed terminal behavior and stores only a sanitized reason.
7. A live lease prevents a second worker from processing the item.
8. On expired PROCESSING, reconciliation first audits the exact chunk: already repaired becomes INDEXED; still missing becomes READY; any ambiguous result fails the operation.
9. Restarted runtime instance never silently rebinds an operation.
10. Replaying `step` after a lost response returns current durable state and never indexes an INDEXED item again.

**Step 2: Run tests and confirm RED**

```powershell
.\mvnw.cmd -Dtest=LawMissingEmbeddingRepairOperationServiceTests,LawMissingEmbeddingRepairOperationControllerTests test
```

**Step 3: Implement one-item step with explicit transaction boundaries**

Add:

```java
@PostMapping("/missing-embedding-repair-operations/{operationId}/step")
ResponseEntity<OperationView> stepOperation(@PathVariable UUID operationId)
```

Use short transactions for claim and completion; do not hold a database transaction across embedding/Qdrant I/O. Before mutation, require the operation's stored runtime instance and trusted revision to equal the live values. After exact indexing and exact audit, require the instance to remain equal, then persist the evolved revision. If the response is lost after completion, GET must expose INDEXED and the next step must claim a different item.

**Step 4: Focused verification and self-review**

Run Step 2 tests plus existing synchronous repair tests. Inspect every state edge for double-indexing, lease theft, stale revision acceptance, broad exception retries, and DB transactions spanning remote I/O.

**Step 5: Independent review and commit**

Require a fresh reviewer to reason through crash points before claim, after claim, after Qdrant write, after DB embedding status write, after exact audit, and after durable completion. Resolve findings and commit Task 3 files.

---

### Task 4: Make the Node wave runner operation-aware

**Files:**
- Modify: `scripts/law-missing-embedding-repair-wave.js`
- Modify: `scripts/law-missing-embedding-repair-wave.test.js`

**Step 1: Write failing Node tests**

Add injected-fetch tests proving:

- apply mode registers once, polls GET, calls step sequentially, and never has more than one mutation request in flight;
- an identical registration response resumes an existing operation;
- a lost register response is reconciled by repeating idempotent registration;
- a lost step response triggers GET before any further step;
- GET showing the previous item INDEXED continues safely; GET showing PROCESSING waits/polls; terminal FAILED aborts;
- HTTP 4xx, semantic FAILED, runtime drift, and unknown state are not retried;
- transport retry history is recorded without treating it as a quality result;
- `assertSuccessfulApply` requires all exact planned IDs INDEXED and operation `INDEXING_COMPLETE`;
- the existing `runPostWaveAudits` gates still run and remain unchanged in strictness.

**Step 2: Run tests and confirm RED**

```powershell
node --test scripts/law-missing-embedding-repair-wave.test.js
```

**Step 3: Implement register/GET/step orchestration**

Extract small exported functions for deterministic unit tests. Use bounded polling and explicit request timeouts. On an unknown mutating outcome, query operation state; never blindly replay a step. Keep preview mode read-only. Preserve output fields consumed by existing audit/manifest tooling and add `operationId`, state counts, and transport-attempt history.

**Step 4: Focused verification and self-review**

Run:

```powershell
node --test scripts/law-missing-embedding-repair-wave.test.js
node --check scripts/law-missing-embedding-repair-wave.js
git diff --check
```

Review for unbounded loops, overlapping requests, swallowed HTTP failures, accidental 18080 usage, premature success, and weakened post-wave assertions.

**Step 5: Independent review and commit**

Have a fresh reviewer trace lost-response cases and verify the client can only progress from observed durable state. Resolve findings, rerun tests, and commit Task 4 files.

---

### Task 5: Full verification, deploy only 8080, and run a bounded live canary

**Files:**
- Modify: `.superpowers/sdd/2026-07-30-rag-retrieval-evidence-shadow-migration/task-6-report.md`
- Modify: `.superpowers/sdd/2026-07-30-rag-retrieval-evidence-shadow-migration/progress.md`
- Create: `logs/law-index-integrity-audit-durable-operation-canary-pre-<timestamp>.json`
- Create: `logs/law-missing-embedding-repair-durable-operation-canary-<timestamp>.json`

**Step 1: Run all focused suites and backend full tests**

```powershell
.\mvnw.cmd -Dtest=LawApiSchemaMaintenanceTests,LawMissingEmbeddingRepairOperationMapperTests,LawMissingEmbeddingRepairOperationServiceTests,LawMissingEmbeddingRepairServiceTests,LawMissingEmbeddingRepairOperationControllerTests,LawIndexIntegrityControllerTests test
node --test scripts/law-missing-embedding-repair-wave.test.js
.\mvnw.cmd test
```

Expected: all pass with zero failures/errors.

**Step 2: Final independent code review**

Review the whole range from `4cc54c2b` through HEAD against the design. Resolve every High/Medium correctness issue. Repeat focused and full tests after any production change.

**Step 3: Build and deploy only app-dev 8080**

Use repository scripts only:

```powershell
.\scripts\status-pandora.ps1
.\mvnw.cmd -DskipTests package
.\scripts\deploy-pandora-app8080.ps1
```

Verify runtime instance ID, JAR SHA-256/bytes, Qdrant readiness and collection counts. Confirm 18080 remains untouched.

**Step 4: Fresh preflight and a 10-item live canary**

Generate a fresh full integrity audit and baseline manifest against the newly deployed 8080. Preview 10 candidates and require all READY, no short-chunk apply, stable rag collection, and exact runtime/index identity. Run apply through the durable operation API.

Expected live invariants:

- operation reaches INDEXING_COMPLETE;
- exactly 10 planned chunk IDs move from MISSING_EMBEDDING_ROW to clean;
- backlog decreases by exactly 10;
- law DB indexed count and Qdrant law point count increase by exactly 10 and remain equal;
- rag DB/Qdrant counts do not change;
- no Qdrant search failure or runtime-instance drift occurs;
- full integrity, parent/child, short-chunk dry-run, manifest, and byte-identity gates pass.

If any invariant fails, stop further waves, preserve operation/audit artifacts, and mark Task 6 BLOCKED. Do not auto-compensate or start another operation.

**Step 5: Record evidence and continue only after verified success**

Append exact commands, hashes, counts, operation ID, runtime/JAR/index identities, and review/test results to the Task 6 report and progress ledger. After the 10-item canary passes, subsequent repair operations remain bounded to at most 100 candidates and 50 documents, with a fresh preflight and full post-wave gates each time.
