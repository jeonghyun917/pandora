# RAG Retrieval and Evidence Shadow Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and safely activate provenance-stable evaluation, versioned chunk/index integrity, common Korean BM25 plus RRF retrieval, candidate-loss tracing, EvidenceAtom semantic verification, and independent release gates.

**Architecture:** Keep Pandora's current retrieval and claim-verification paths authoritative while new corpus, lexical, fusion, trace, and semantic-matcher components run in shadow. Promote one boundary at a time only after deterministic focused and full gates pass; preserve old chunk versions and feature-flagged control paths for rollback.

**Tech Stack:** Java 21, Spring Boot, MyBatis, MariaDB, Qdrant REST API, Node.js evaluation scripts, PowerShell runtime scripts, JUnit 5, AssertJ, Mockito, MockWebServer.

## Global Constraints

- Work only in `C:\dev\workspace-egov\pandora\.worktrees\rag-direct-evidence-recovery` on `codex/rag-direct-evidence-recovery`.
- Keep `C:\dev\workspace-egov\pandora` on `main`; do not switch the shared workspace branch.
- Never stop, restart, promote, or reconfigure port 18080 or its batch runner.
- Inspect runtime with `scripts/status-pandora.ps1`; start or stop only 8080 with the official scripts.
- Preserve existing user changes and the untracked `output/` directory.
- Preserve `AnswerGuard`, `ClaimVerifier`, direct-ground requirements, and all fail-closed behavior.
- Any uncertain semantic parse or match returns `INSUFFICIENT`.
- Do not add runtime rules keyed by evaluation case ID or a literal full question.
- Every code slice follows failing test, minimal generalized implementation, focused pass, self-review, and full applicable tests.
- Full backend completion requires `.\mvnw.cmd test`.
- RAG behavior promotion requires difficult 12, 85 cases twice, and all 1,004 cases under one stable final runtime manifest.

---

## File and Responsibility Map

### Evaluation and provenance

- `scripts/lib/rag-eval-gates.js`: pure named-gate classification and blocking summaries.
- `scripts/lib/rag-baseline-manifest.js`: canonical baseline identity and before/after comparison.
- `scripts/rag-baseline-manifest.js`: capture and verify a runtime manifest.
- `scripts/rag-eval-gate.js`: consume named gates and exact manifest identity.
- `scripts/lib/rag-eval-provenance.js`: checkpoint/provenance schema including gate profile and lexical revision.
- `scripts/rag-eval-provenance.test.js`: provenance, manifest, named-gate, and resume regression tests.
- `scripts/rag-retrieval-eval.js`: record the same runtime/lexical identity for retrieval replay.

### Chunk and index integrity

- `src/main/java/com/kaces/pandora/semantic/integrity/LawIndexIntegrityIssue.java`: one classified law index mismatch.
- `src/main/java/com/kaces/pandora/semantic/integrity/LawIndexIntegrityReport.java`: bounded audit summary.
- `src/main/java/com/kaces/pandora/semantic/integrity/LawIndexIntegrityService.java`: classify database/embedding/Qdrant mismatch causes.
- `src/main/java/com/kaces/pandora/semantic/integrity/LawIndexIntegrityController.java`: protected preview and repair endpoints.
- `src/main/java/com/kaces/pandora/lawdata/chunk/LawChunkVersionRow.java`: active/candidate/retired version state.
- `src/main/java/com/kaces/pandora/lawdata/sync/PlannedLawChunk.java`: explicit parent and child metadata.
- `src/main/java/com/kaces/pandora/lawdata/sync/LawSemanticChunkPlanner.java`: parent-keyed, quality-classified candidate chunks.
- `src/main/java/com/kaces/pandora/lawdata/sync/LawDocumentWriter.java`: side-by-side candidate insertion and activation.
- `src/main/java/com/kaces/pandora/lawdata/persistence/LawApiSchemaMaintenance.java`: additive law chunk/version schema.
- `src/main/resources/schema.sql`: canonical schema for fresh databases.
- `src/main/resources/mapper/law/LawChunkMapper.xml`: active-version filters and candidate-version queries.
- `src/main/resources/mapper/law/LawDetailMapper.xml`: preview data and version-aware rebuild candidates.
- `scripts/law-parent-child-rechunk-wave.js`: preview, verify, activate, and rollback-recipe orchestration.
- `scripts/law-index-integrity-audit.js`: bounded audit and repair evidence artifact.

### Common lexical retrieval

- `src/main/java/com/kaces/pandora/semantic/lexical/KoreanLexicalTokenizer.java`: deterministic versioned Korean tokenizer.
- `src/main/java/com/kaces/pandora/semantic/lexical/LexicalChunkDocument.java`: common law/RAG lexical input.
- `src/main/java/com/kaces/pandora/semantic/lexical/LexicalSearchHit.java`: BM25 result with rank and matched terms.
- `src/main/java/com/kaces/pandora/semantic/lexical/SemanticLexicalIndexService.java`: common index build and revision.
- `src/main/java/com/kaces/pandora/semantic/lexical/KoreanBm25SearchService.java`: BM25 candidate retrieval.
- `src/main/java/com/kaces/pandora/semantic/lexical/ReciprocalRankFusion.java`: deterministic rank fusion.
- `src/main/java/com/kaces/pandora/semantic/lexical/SemanticLexicalSchemaMaintenance.java`: additive common lexical schema.
- `src/main/java/com/kaces/pandora/semantic/lexical/SemanticLexicalMapper.java`: lexical index persistence contract.
- `src/main/resources/mapper/law/SemanticLexicalMapper.xml`: common projection, BM25 statistics, and search SQL.
- `src/main/java/com/kaces/pandora/semantic/config/LawAiProperties.java`: shadow and activation flags plus RRF parameters.

### Candidate loss and semantic verification

- `src/main/java/com/kaces/pandora/ai/answer/RetrievalCandidateTrace.java`: immutable per-candidate stage trace.
- `src/main/java/com/kaces/pandora/ai/answer/RetrievalTraceCollector.java`: stage transition and first-loss recorder.
- `src/main/java/com/kaces/pandora/ai/answer/DirectEvidenceSelectionPolicy.java`: generalized preservation of aligned direct evidence.
- `src/main/java/com/kaces/pandora/ai/answer/EvidenceAtom.java`: stable proposition slots.
- `src/main/java/com/kaces/pandora/ai/answer/PropositionTemplate.java`: required semantic slots for a question/answer intent.
- `src/main/java/com/kaces/pandora/ai/answer/QuestionPropositionTemplateFactory.java`: build reusable templates from query intent metadata.
- `src/main/java/com/kaces/pandora/ai/answer/KoreanEvidenceAtomParser.java`: deterministic Korean semantic parser.
- `src/main/java/com/kaces/pandora/ai/answer/SemanticEvidenceMatcher.java`: proposition, slot, and polarity matcher.
- `src/main/java/com/kaces/pandora/ai/answer/ClaimMatcherShadowResult.java`: control/shadow disagreement record.
- `src/main/java/com/kaces/pandora/ai/answer/ClaimVerifier.java`: shadow invocation and optional reviewed activation.
- `src/main/java/com/kaces/pandora/ai/answer/LawAiDebugResponse.java`: bounded fusion, loss, and matcher trace fields.
- `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`: orchestration only; delegate new logic to focused components.

---

### Task 1: Independent Blocking Gates

**Files:**
- Create: `scripts/lib/rag-eval-gates.js`
- Modify: `scripts/lib/rag-eval-provenance.js`
- Modify: `scripts/rag-eval-gate.js`
- Test: `scripts/rag-eval-provenance.test.js`
- Test: `scripts/rag-retrieval-eval.test.js`

**Interfaces:**
- Consumes: evaluation result rows with `id`, `passed`, `expectedResultMsgs`, and `answerVerificationRequired`.
- Produces: `buildBlockingGates(results)` and `gateProfile` in checkpoint/provenance identity.

- [ ] **Step 1: Write failing named-gate unit tests**

Add tests that construct curated, generated, answer-oracle, and `NO_GROUNDS`
rows and assert:

```javascript
assert.deepEqual(buildBlockingGates(rows), {
  curated: {
    total: 3,
    passed: 2,
    failed: 1,
    passRate: 2 / 3,
    gatePassed: false,
    blockingFailureIds: ['curated-fail'],
  },
  answerOracle: {
    total: 2,
    passed: 1,
    failed: 1,
    passRate: 1 / 2,
    gatePassed: false,
    blockingFailureIds: ['oracle-fail'],
  },
  noGrounds: {
    total: 1,
    passed: 0,
    failed: 1,
    passRate: 0,
    gatePassed: false,
    blockingFailureIds: ['no-ground-fail'],
  },
});
```

Also assert checkpoint incompatibility when only `gateProfile` changes.

- [ ] **Step 2: Run tests and confirm the missing-module failure**

Run:

```powershell
node --test .\scripts\rag-eval-provenance.test.js .\scripts\rag-retrieval-eval.test.js
```

Expected: FAIL because `scripts/lib/rag-eval-gates.js` and `gateProfile` do not
exist.

- [ ] **Step 3: Implement pure named-gate classification**

Create:

```javascript
function summarize(name, rows) {
  const failedRows = rows.filter((row) => row.passed !== true);
  return {
    total: rows.length,
    passed: rows.length - failedRows.length,
    failed: failedRows.length,
    passRate: rows.length === 0 ? 0 : (rows.length - failedRows.length) / rows.length,
    gatePassed: rows.length > 0 && failedRows.length === 0,
    blockingFailureIds: failedRows.map((row) => row.id),
  };
}

function buildBlockingGates(results = []) {
  const curated = results.filter((row) => !String(row.id ?? '').startsWith('gen-'));
  const answerOracle = results.filter((row) => row.answerVerificationRequired === true);
  const noGrounds = results.filter((row) =>
    (row.expectedResultMsgs ?? []).includes('NO_GROUNDS')
      || String(row.id ?? '').startsWith('no-'));
  return {
    curated: summarize('curated', curated),
    answerOracle: summarize('answerOracle', answerOracle),
    noGrounds: summarize('noGrounds', noGrounds),
  };
}

module.exports = { buildBlockingGates };
```

Preserve `expectedResultMsgs` and `answerVerificationRequired` on recomputed
result rows so classification remains available after batched runs. Because the
server response does not have to echo case expectations, join every response
row to the selected input case by ID before calling `buildBlockingGates`.

- [ ] **Step 4: Make release status depend on all named gates**

In `recomputeGate`, calculate `blockingGates`. Set release `gatePassed` only
when every result passes and each non-empty named gate passes. Add
`RAG_EVAL_GATE_PROFILE` with values `release`, `curated`, `answer-oracle`, and
`no-grounds`. A profile selects cases before evaluation; it does not reinterpret
failed rows as passed.

- [ ] **Step 5: Run focused Node tests**

Run:

```powershell
node --test .\scripts\rag-eval-provenance.test.js .\scripts\rag-retrieval-eval.test.js
```

Expected: PASS with the named-gate and checkpoint-profile cases included.

- [ ] **Step 6: Self-review and commit**

Review:

```powershell
git diff --check
git diff -- scripts/lib/rag-eval-gates.js scripts/lib/rag-eval-provenance.js scripts/rag-eval-gate.js scripts/rag-eval-provenance.test.js scripts/rag-retrieval-eval.test.js
```

Commit:

```powershell
git add scripts/lib/rag-eval-gates.js scripts/lib/rag-eval-provenance.js scripts/rag-eval-gate.js scripts/rag-eval-provenance.test.js scripts/rag-retrieval-eval.test.js
git commit -m "feat: add independent RAG blocking gates"
```

---

### Task 2: Provenance-Stable Baseline Manifest

**Files:**
- Create: `scripts/lib/rag-baseline-manifest.js`
- Create: `scripts/rag-baseline-manifest.js`
- Modify: `scripts/lib/rag-eval-provenance.js`
- Modify: `scripts/rag-eval-gate.js`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiRuntimeInfo.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`
- Test: `scripts/rag-eval-provenance.test.js`
- Test: `src/test/java/com/kaces/pandora/ai/answer/LawAiRuntimeInfoTests.java`

**Interfaces:**
- Consumes: server runtime-info, Git identity, dataset paths, and selected case IDs.
- Produces: `BaselineManifest`, `manifestId`, `lexicalRevision`, and exact before/after verification.

- [ ] **Step 1: Write failing canonical-manifest tests**

Test this stable shape:

```javascript
const manifest = buildBaselineManifest({
  gitCommit: 'abc123',
  gitDirty: false,
  runtimeInfo: {
    runtimeArtifactSha256: 'jar-a',
    runtimeArtifactSize: 52000000,
    runtimeInstanceId: 'instance-a',
    runtimeConfigSha256: 'config-a',
    indexRevision: 'index-a',
    lexicalRevision: 'legacy-law-like-v1+rag-terms-v2-ready',
    qdrantReady: true,
    qdrantSearchFailureCount: 0,
  },
  datasetHash: 'dataset-a',
  selectionHash: 'selection-a',
});
assert.match(manifest.manifestId, /^[0-9a-f]{64}$/);
assert.equal(assertSameManifest(manifest, { ...manifest }), true);
assert.throws(
  () => assertSameManifest(manifest, { ...manifest, indexRevision: 'index-b' }),
  /indexRevision/,
);
```

- [ ] **Step 2: Run tests and confirm failure**

Run:

```powershell
node --test .\scripts\rag-eval-provenance.test.js
.\mvnw.cmd -Dtest=LawAiRuntimeInfoTests test
```

Expected: Node FAIL for missing manifest functions and Java FAIL for missing
`lexicalRevision`.

- [ ] **Step 3: Add lexical revision to runtime identity**

Extend `LawAiRuntimeInfo` with `String lexicalRevision`. Initially return:

```java
private String lexicalRevision() {
    if (ragChunkSearchIndexService == null) {
        return "legacy-law-like-v1+rag-terms-v2-unavailable";
    }
    return ragChunkSearchIndexService.isReady()
        ? "legacy-law-like-v1+rag-terms-v2-ready"
        : "legacy-law-like-v1+rag-terms-v2-building";
}
```

Task 7 replaces this constant-derived value with the common lexical index
revision.

- [ ] **Step 4: Implement canonical manifest hashing**

`buildBaselineManifest` must sort keys recursively, exclude `manifestId` from
the hash input, encode UTF-8 JSON, and SHA-256 the canonical bytes. Required
fields are commit, clean state, artifact hash/size, instance ID, configuration
hash, index revision, lexical revision, dataset hash, selection hash, Qdrant
ready, and zero search-failure delta.

- [ ] **Step 5: Require a supplied manifest during baseline evaluation**

Support:

```powershell
$manifestPath = node .\scripts\rag-baseline-manifest.js --write
$env:RAG_EVAL_BASELINE_MANIFEST=$manifestPath
node .\scripts\rag-eval-gate.js
```

The gate loads the file before evaluation and compares it with both the start
and end runtime identities. A mismatch exits with code 1 before writing a pass.

- [ ] **Step 6: Run focused tests**

Run:

```powershell
node --test .\scripts\rag-eval-provenance.test.js
.\mvnw.cmd -Dtest=LawAiRuntimeInfoTests test
```

Expected: PASS.

- [ ] **Step 7: Self-review and commit**

Run `git diff --check`, verify API JSON remains additive, and commit:

```powershell
git add scripts/lib/rag-baseline-manifest.js scripts/rag-baseline-manifest.js scripts/lib/rag-eval-provenance.js scripts/rag-eval-gate.js scripts/rag-eval-provenance.test.js src/main/java/com/kaces/pandora/ai/answer/LawAiRuntimeInfo.java src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java src/test/java/com/kaces/pandora/ai/answer/LawAiRuntimeInfoTests.java
git commit -m "feat: bind RAG evaluation to a baseline manifest"
```

- [ ] **Step 8: Regenerate the pre-change quality baseline**

Build one JAR, deploy it only to 8080, capture a manifest, and run focused,
difficult 12, 85-case, and full 1,004-case evaluations against the unchanged
index revision. The quality gate may exit 1 because this is a failing baseline;
accept the artifact only when the provenance/manifest checks pass and the
runtime identity is unchanged from start to finish. Archive the manifest and
all four results before any corpus mutation in Task 6.

---

### Task 3: Law Index Integrity Classifier

**Files:**
- Create: `src/main/java/com/kaces/pandora/semantic/integrity/LawIndexIntegrityIssue.java`
- Create: `src/main/java/com/kaces/pandora/semantic/integrity/LawIndexIntegrityReport.java`
- Create: `src/main/java/com/kaces/pandora/semantic/integrity/LawIndexIntegrityService.java`
- Create: `src/main/java/com/kaces/pandora/semantic/integrity/LawIndexIntegrityController.java`
- Modify: `src/main/java/com/kaces/pandora/infra/qdrant/QdrantClient.java`
- Modify: `src/main/java/com/kaces/pandora/lawdata/persistence/LawChunkMapper.java`
- Modify: `src/main/resources/mapper/law/LawChunkMapper.xml`
- Create: `scripts/law-index-integrity-audit.js`
- Test: `src/test/java/com/kaces/pandora/semantic/integrity/LawIndexIntegrityServiceTests.java`
- Test: `src/test/java/com/kaces/pandora/infra/qdrant/QdrantClientTests.java`

**Interfaces:**
- Consumes: bounded active law chunk/embedding rows and Qdrant point existence.
- Produces: `LawIndexIntegrityReport audit(String target, int limit)` with one cause per issue.

- [ ] **Step 1: Write failing classifier tests**

Cover these exact causes:

```java
enum Cause {
    MISSING_EMBEDDING_ROW,
    RETRYABLE_EMBEDDING_FAILURE,
    CONTENT_HASH_MISMATCH,
    QDRANT_POINT_MISSING,
    STALE_DATABASE_STATUS,
    INACTIVE_CHUNK_COUNTED
}
```

Assert classification precedence in the listed order and assert that a current
`INDEXED` row with an existing point produces no issue.

- [ ] **Step 2: Write failing Qdrant point lookup tests**

Using the existing MockWebServer pattern, assert:

```java
Set<Long> existing = client.findExistingLawPointIds(List.of(10L, 20L, 30L));
assertThat(existing).containsExactlyInAnyOrder(10L, 30L);
```

The request must be a bounded POST to
`/collections/law_chunks/points` with `with_payload=false` and
`with_vector=false`.

- [ ] **Step 3: Run focused tests and confirm missing-type failures**

Run:

```powershell
.\mvnw.cmd -Dtest=LawIndexIntegrityServiceTests,QdrantClientTests test
```

Expected: FAIL for missing service, records, and client method.

- [ ] **Step 4: Implement bounded data retrieval and classification**

Add mapper projection fields:

```java
record LawIndexIntegrityRow(
    long chunkId,
    boolean active,
    String chunkContentHash,
    String embeddingContentHash,
    String embeddingStatus,
    String vectorPointId
) {}
```

Fetch no more than 10,000 rows per request. Query Qdrant in ID batches of 256.
The controller exposes preview by default. Repair accepts explicit issue IDs and
cause, rejecting stale audit input when content hashes changed.

- [ ] **Step 5: Add a deterministic audit artifact**

`scripts/law-index-integrity-audit.js` writes:

- `logs/law-index-integrity-audit-latest.json`
- `logs/law-index-integrity-audit-latest.md`

The report includes cause counts, chunk IDs, content hashes, runtime instance,
index revision, and generated timestamp. It never includes vectors or API keys.

- [ ] **Step 6: Run focused and mapper tests**

Run:

```powershell
.\mvnw.cmd -Dtest=LawIndexIntegrityServiceTests,QdrantClientTests,LawChunkMapperXmlTests test
node --check .\scripts\law-index-integrity-audit.js
```

Expected: PASS.

- [ ] **Step 7: Self-review and commit**

Verify repair is explicit and idempotent, then commit:

```powershell
git add src/main/java/com/kaces/pandora/semantic/integrity src/main/java/com/kaces/pandora/infra/qdrant/QdrantClient.java src/main/java/com/kaces/pandora/lawdata/persistence/LawChunkMapper.java src/main/resources/mapper/law/LawChunkMapper.xml src/test/java/com/kaces/pandora/semantic/integrity/LawIndexIntegrityServiceTests.java src/test/java/com/kaces/pandora/infra/qdrant/QdrantClientTests.java scripts/law-index-integrity-audit.js
git commit -m "feat: classify law index integrity gaps"
```

---

### Task 4: Versioned Parent/Child Chunk Metadata

**Files:**
- Create: `src/main/java/com/kaces/pandora/lawdata/chunk/LawChunkVersionRow.java`
- Modify: `src/main/resources/schema.sql`
- Modify: `src/main/java/com/kaces/pandora/lawdata/persistence/LawApiSchemaMaintenance.java`
- Modify: `src/main/java/com/kaces/pandora/lawdata/persistence/LawChunkMapper.java`
- Modify: `src/main/resources/mapper/law/LawChunkMapper.xml`
- Modify: `src/main/java/com/kaces/pandora/lawdata/sync/PlannedLawChunk.java`
- Modify: `src/main/java/com/kaces/pandora/lawdata/sync/StoredChunk.java`
- Modify: `src/main/java/com/kaces/pandora/lawdata/sync/LawSemanticChunkPlanner.java`
- Test: `src/test/java/com/kaces/pandora/lawdata/sync/LawSemanticChunkPlannerTests.java`
- Create: `src/test/java/com/kaces/pandora/lawdata/persistence/LawApiSchemaMaintenanceTests.java`
- Test: `src/test/java/com/kaces/pandora/rag/persistence/RagDocumentMapperXmlTests.java`

**Interfaces:**
- Consumes: parsed `SyncDetailSection` source units.
- Produces: planned chunks with stable parent key, child order, embedding text, and quality decision.

- [ ] **Step 1: Write failing planner tests for stable parent identity**

Assert:

```java
assertThat(chunks).allSatisfy(chunk -> {
    assertThat(chunk.chunkSchemaVersion()).isEqualTo(2);
    assertThat(chunk.parentKey()).matches("[0-9a-f]{64}");
    assertThat(chunk.parentTitle()).isNotBlank();
    assertThat(chunk.childOrder()).isGreaterThanOrEqualTo(0);
    assertThat(chunk.qualityStatus()).isIn("PASS", "CONTEXT_ONLY", "REVIEW", "REJECT");
});
```

Add cases for one article split into multiple children, adjacent articles with
different parent keys, contained duplicates, a meaningful short exception, and
a decorative appendix tail.

- [ ] **Step 2: Run planner tests and confirm record mismatch**

Run:

```powershell
.\mvnw.cmd -Dtest=LawSemanticChunkPlannerTests test
```

Expected: FAIL because the planned chunk does not expose the new metadata.

- [ ] **Step 3: Add nullable-first schema**

Add law chunk columns:

```sql
chunk_schema_version INT NOT NULL DEFAULT 1,
chunk_version INT NOT NULL DEFAULT 1,
activation_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
parent_key CHAR(64) NULL,
parent_title VARCHAR(500) NULL,
parent_source_path VARCHAR(500) NULL,
child_order INT NOT NULL DEFAULT 0,
embedding_text LONGTEXT NULL,
quality_status VARCHAR(20) NOT NULL DEFAULT 'PASS',
quality_reason VARCHAR(100) NULL
```

Add indexes on `(document_id, activation_status, chunk_version, sort_order)` and
`(document_id, parent_key, child_order)`. Add a
`law_api_document_chunk_versions` table keyed by document/version with
`CANDIDATE`, `ACTIVE`, and `RETIRED` states.

- [ ] **Step 4: Implement stable planning metadata**

Calculate:

```java
String parentKey = sha256(
    documentTarget + "\n"
        + documentId + "\n"
        + canonicalParentSourcePath + "\n"
        + canonicalParentNumber
);
```

Where the planner lacks document ID, pass a `ChunkPlanningContext` from the
writer. `embeddingText` joins document title, parent number/title, child title,
section type, and child text. `content_hash` becomes the SHA-256 of
`embeddingText`, matching what is sent to the embedding API.

- [ ] **Step 5: Replace parent-title SQL heuristics with stored values**

Mapper projections use stored `parent_title` and `parent_key`. During migration,
`COALESCE(stored_value, existing_expression)` preserves legacy rows. New version
2 rows must have non-null stored values.

- [ ] **Step 6: Run focused schema, planner, and mapper tests**

Run:

```powershell
.\mvnw.cmd -Dtest=LawSemanticChunkPlannerTests,LawApiSchemaMaintenanceTests,RagDocumentMapperXmlTests,LawChunkMapperXmlTests test
```

Expected: PASS.

- [ ] **Step 7: Self-review and commit**

Check migration idempotency and that existing version 1 rows remain searchable.
Commit:

```powershell
git add src/main/resources/schema.sql src/main/java/com/kaces/pandora/lawdata/chunk/LawChunkVersionRow.java src/main/java/com/kaces/pandora/lawdata/persistence/LawApiSchemaMaintenance.java src/main/java/com/kaces/pandora/lawdata/persistence/LawChunkMapper.java src/main/resources/mapper/law/LawChunkMapper.xml src/main/java/com/kaces/pandora/lawdata/sync/PlannedLawChunk.java src/main/java/com/kaces/pandora/lawdata/sync/StoredChunk.java src/main/java/com/kaces/pandora/lawdata/sync/LawSemanticChunkPlanner.java src/test/java/com/kaces/pandora/lawdata/sync/LawSemanticChunkPlannerTests.java src/test/java/com/kaces/pandora/lawdata/persistence/LawApiSchemaMaintenanceTests.java src/test/java/com/kaces/pandora/rag/persistence/RagDocumentMapperXmlTests.java
git commit -m "feat: version law parent child chunks"
```

---

### Task 5: Safe Candidate Chunk Activation

**Files:**
- Modify: `src/main/java/com/kaces/pandora/lawdata/sync/LawDocumentWriter.java`
- Modify: `src/main/java/com/kaces/pandora/lawdata/sync/LawOpenApiSyncService.java`
- Modify: `src/main/java/com/kaces/pandora/lawdata/sync/LawSyncController.java`
- Modify: `src/main/java/com/kaces/pandora/semantic/indexing/LawSemanticIndexService.java`
- Modify: `src/main/java/com/kaces/pandora/lawdata/persistence/LawChunkMapper.java`
- Modify: `src/main/resources/mapper/law/LawChunkMapper.xml`
- Modify: `scripts/law-parent-child-rechunk-wave.js`
- Modify: `scripts/law-parent-child-rechunk-bulk.js`
- Create: `src/test/java/com/kaces/pandora/lawdata/sync/LawDocumentWriterTests.java`
- Create: `src/test/java/com/kaces/pandora/lawdata/sync/LawOpenApiSyncServiceChunkPreviewTests.java`
- Create: `src/test/java/com/kaces/pandora/semantic/indexing/LawSemanticIndexServiceTests.java`
- Create: `scripts/law-parent-child-rechunk-wave.test.js`

**Interfaces:**
- Consumes: a preview-approved document ID and candidate chunk version.
- Produces: `CandidateChunkVersionResult` and explicit `activate`/`rollback` operations.

- [ ] **Step 1: Write failing state-transition tests**

Assert the sequence:

```text
ACTIVE v1
  -> create CANDIDATE v2 while v1 stays ACTIVE
  -> index and verify every v2 point
  -> activate v2 and mark v1 RETIRED atomically
  -> delete v1 Qdrant points after commit
```

Also assert that failed point verification leaves v1 active and v2 candidate,
and that rollback reactivates v1 only after its points are confirmed.

- [ ] **Step 2: Run focused tests and confirm current delete-first failure**

Run:

```powershell
.\mvnw.cmd -Dtest=LawDocumentWriterTests,LawOpenApiSyncServiceChunkPreviewTests test
node --test .\scripts\law-parent-child-rechunk-wave.test.js
```

Expected: FAIL because `replaceChunks` deletes v1 before v2 verification.

- [ ] **Step 3: Implement candidate insertion**

Replace destructive `replaceChunks` use in rebuild flows with:

```java
CandidateChunkVersionResult createCandidateChunks(
    long documentId,
    long detailId,
    List<SyncDetailSection> sections,
    String sourceUrl
);

ChunkActivationResult activateCandidate(long documentId, int candidateVersion);

ChunkActivationResult rollbackToVersion(long documentId, int retiredVersion);
```

New sync of a previously unseen document may activate version 1 directly. A
rebuild always uses the candidate path.

- [ ] **Step 4: Verify candidate content and vectors before activation**

Activation requires:

- preview has zero unexplained loss spans;
- no `REVIEW` or `REJECT` chunk is marked searchable;
- every searchable candidate has current embedding status and content hash;
- every expected Qdrant point exists;
- vector size equals configured size; and
- candidate count matches the version-state row.

- [ ] **Step 5: Update wave scripts**

The wave script performs `preview -> create-candidate -> index -> verify ->
activate`. It writes document/version/chunk IDs, old/new point IDs, manifest
identity, and rollback command to its JSON artifact. `--apply=false` performs
only preview and never writes.

- [ ] **Step 6: Run focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=LawDocumentWriterTests,LawOpenApiSyncServiceChunkPreviewTests,LawSemanticIndexServiceTests test
node --test .\scripts\law-parent-child-rechunk-wave.test.js
```

Expected: PASS.

- [ ] **Step 7: Run backend suite, self-review, and commit**

Run:

```powershell
.\mvnw.cmd test
git diff --check
```

Confirm no 18080 command exists in the changed scripts. Commit:

```powershell
git add src/main/java/com/kaces/pandora/lawdata/sync/LawDocumentWriter.java src/main/java/com/kaces/pandora/lawdata/sync/LawOpenApiSyncService.java src/main/java/com/kaces/pandora/lawdata/sync/LawSyncController.java src/main/java/com/kaces/pandora/semantic/indexing/LawSemanticIndexService.java src/main/java/com/kaces/pandora/lawdata/persistence/LawChunkMapper.java src/main/resources/mapper/law/LawChunkMapper.xml scripts/law-parent-child-rechunk-wave.js scripts/law-parent-child-rechunk-bulk.js src/test/java/com/kaces/pandora/lawdata/sync/LawDocumentWriterTests.java src/test/java/com/kaces/pandora/lawdata/sync/LawOpenApiSyncServiceChunkPreviewTests.java scripts/law-parent-child-rechunk-wave.test.js
git commit -m "feat: activate law chunk versions safely"
```

---

### Task 6: Repair the Measured Corpus and Backlog

**Files:**
- Modify only when a generalized defect is proven: files owned by Tasks 3-5.
- Runtime artifacts: `logs/law-index-integrity-audit-latest.*`
- Runtime artifacts: `logs/law-parent-child-rechunk-wave-latest.*`
- Runtime artifacts: `logs/law-parent-child-chunk-audit-latest.*`
- Runtime artifacts: `logs/rag-short-chunk-audit-latest.*`

**Interfaces:**
- Consumes: stable Task 2 manifest, Task 3 issue classifications, Task 4 previews, and Task 5 safe activation.
- Produces: zero active embedding backlog and corpus integrity evidence without source loss.

- [ ] **Step 1: Verify runtime boundaries**

Run:

```powershell
.\scripts\status-pandora.ps1
```

Record 8080, 18080, Qdrant, MariaDB, app JAR hash, and batch JAR hash. Abort a
runtime mutation if 18080 ownership or state changed unexpectedly.

- [ ] **Step 2: Capture the pre-repair integrity manifest**

Build and deploy one 8080 JAR, then run:

```powershell
node .\scripts\rag-baseline-manifest.js --write
node .\scripts\law-index-integrity-audit.js --target=law --limit=10000
node .\scripts\law-parent-child-chunk-audit.js
node .\scripts\rag-short-chunk-audit.js
```

Expected: a stable integrity manifest and classified count reconciling the
measured 4,272 law gaps or documenting a newer exact count. The quality baseline
remains the archived Task 2 result and is not overwritten.

- [ ] **Step 3: Repair status-only and missing-point causes first**

For `STALE_DATABASE_STATUS`, update status only after point/hash verification.
For `QDRANT_POINT_MISSING`, re-upsert the existing vector source only when the
embedding content hash is current; otherwise classify it as content mismatch.
Run the integrity audit again and require those cause counts to reach zero.

- [ ] **Step 4: Preview chunk-changing documents**

Select documents implicated by short non-keep chunks, contained duplicates, or
ambiguous parent boundaries. Run:

```powershell
node .\scripts\law-parent-child-rechunk-bulk.js --apply=false --targets=law,admrul --candidate=tiny
```

Reject any preview with unexplained normalized text loss, a missing article
parent, child text over 2,500 characters, or `REVIEW` chunks.

- [ ] **Step 5: Apply bounded waves**

Apply no more than 50 documents per wave:

```powershell
node .\scripts\law-parent-child-rechunk-bulk.js --apply=true --targets=law,admrul --waves=1 --candidate=tiny --max-docs=50 --index=direct
```

After each wave, rerun integrity and chunk audits. Stop the next wave if active
backlog increases, index revision is unavailable, text coverage falls below
99.9%, or Qdrant/DB counts diverge.

- [ ] **Step 6: Repair remaining retryable embeddings**

Use direct indexing on 8080 for explicit classified chunk IDs in bounded
batches. Do not invoke or restart 18080. Repeat until active searchable backlog
is zero or every remaining row has a non-retryable reviewed cause.

- [ ] **Step 7: Capture the post-repair manifest and focused evaluation**

After the index is stable, capture a new manifest and run difficult retrieval
cases. A runtime or index revision change during the run invalidates it.

- [ ] **Step 8: Commit only generalized code corrections**

If a wave exposed a generalized code defect, add its failing test, implement the
minimal correction, run the focused and full suites, and commit the owned files.
Do not commit ignored runtime logs or the existing `output/` directory.

---

### Task 7: Common Korean Lexical Index

**Files:**
- Create: `src/main/java/com/kaces/pandora/semantic/lexical/KoreanLexicalTokenizer.java`
- Create: `src/main/java/com/kaces/pandora/semantic/lexical/LexicalChunkDocument.java`
- Create: `src/main/java/com/kaces/pandora/semantic/lexical/LexicalSearchHit.java`
- Create: `src/main/java/com/kaces/pandora/semantic/lexical/SemanticLexicalIndexService.java`
- Create: `src/main/java/com/kaces/pandora/semantic/lexical/SemanticLexicalSchemaMaintenance.java`
- Create: `src/main/java/com/kaces/pandora/semantic/lexical/SemanticLexicalMapper.java`
- Create: `src/main/resources/mapper/law/SemanticLexicalMapper.xml`
- Modify: `src/main/resources/schema.sql`
- Test: `src/test/java/com/kaces/pandora/semantic/lexical/KoreanLexicalTokenizerTests.java`
- Test: `src/test/java/com/kaces/pandora/semantic/lexical/SemanticLexicalIndexServiceTests.java`
- Test: `src/test/java/com/kaces/pandora/semantic/lexical/SemanticLexicalMapperXmlTests.java`

**Interfaces:**
- Consumes: active searchable law, admin-rule, official, internal, and reference chunks.
- Produces: versioned common term-frequency rows and `String currentRevision()`.

- [ ] **Step 1: Write failing tokenizer tests**

Assert deterministic tokens for:

```java
assertThat(tokenizer.tokenize("국가계약법 시행령 제55조 검사·완료 통지"))
    .containsEntry("국가계약법", 1)
    .containsEntry("시행령", 1)
    .containsEntry("제55조", 1)
    .containsEntry("검사", 1)
    .containsEntry("완료", 1)
    .containsEntry("통지", 1);
```

Also test Unicode normalization, repeated term frequency, weak question-word
removal, numeric/deadline tokens, and configured synonym separation.

- [ ] **Step 2: Run tests and confirm missing classes**

Run:

```powershell
.\mvnw.cmd -Dtest=KoreanLexicalTokenizerTests,SemanticLexicalIndexServiceTests,SemanticLexicalMapperXmlTests test
```

Expected: FAIL for missing lexical package and schema.

- [ ] **Step 3: Add common lexical schema**

Create:

```sql
semantic_lexical_chunks(
  target, chunk_id, document_id, parent_key, content_hash,
  weighted_length, index_version, build_status, completed_at
)
semantic_lexical_terms(
  target, chunk_id, term, field_kind, term_frequency, field_weight
)
semantic_lexical_term_stats(
  index_version, term, document_frequency
)
semantic_lexical_index_state(
  index_version, tokenizer_version, active_chunk_count,
  average_weighted_length, content_fingerprint, status, completed_at
)
```

Use `(target, chunk_id)` in every chunk identity and index
`(term, target, chunk_id)`.

- [ ] **Step 4: Implement tokenizer and index build**

Use tokenizer version `korean-lexical-v1`. Build a new index version in
`BUILDING`; populate chunks/terms in batches of 500; compute statistics and
fingerprint; then mark `READY` atomically. A failed build never replaces the
previous ready revision.

- [ ] **Step 5: Expose the dynamic lexical revision**

`SemanticLexicalIndexService.currentRevision()` returns the ready
`content_fingerprint`. Wire it into `LawAiRuntimeInfo.lexicalRevision`; retain
the Task 2 legacy string only when no common ready revision exists.

- [ ] **Step 6: Run focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=KoreanLexicalTokenizerTests,SemanticLexicalIndexServiceTests,SemanticLexicalMapperXmlTests,LawAiRuntimeInfoTests test
```

Expected: PASS.

- [ ] **Step 7: Self-review and commit**

Verify the build is side-by-side and target-qualified. Commit:

```powershell
git add src/main/java/com/kaces/pandora/semantic/lexical src/main/resources/mapper/law/SemanticLexicalMapper.xml src/main/resources/schema.sql src/main/java/com/kaces/pandora/ai/answer/LawAiRuntimeInfo.java src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java src/test/java/com/kaces/pandora/semantic/lexical src/test/java/com/kaces/pandora/ai/answer/LawAiRuntimeInfoTests.java
git commit -m "feat: build a common Korean lexical index"
```

---

### Task 8: Korean BM25 Search

**Files:**
- Create: `src/main/java/com/kaces/pandora/semantic/lexical/KoreanBm25SearchService.java`
- Modify: `src/main/java/com/kaces/pandora/semantic/lexical/SemanticLexicalMapper.java`
- Modify: `src/main/resources/mapper/law/SemanticLexicalMapper.xml`
- Modify: `src/main/java/com/kaces/pandora/semantic/config/LawAiProperties.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/kaces/pandora/semantic/lexical/KoreanBm25SearchServiceTests.java`
- Test: `src/test/java/com/kaces/pandora/semantic/config/LawAiPropertiesTests.java`

**Interfaces:**
- Consumes: normalized query, target filters, ready lexical revision, and result limit.
- Produces: `List<LexicalSearchHit> search(String query, List<String> targets, int limit)`.

- [ ] **Step 1: Write failing BM25 ranking tests**

Create a fixture where one chunk repeats the direct term, one matches only a
title, and one contains common noise. Assert direct provision ranks first and
all results expose matched terms and one-based rank.

- [ ] **Step 2: Run tests and confirm missing service**

Run:

```powershell
.\mvnw.cmd -Dtest=KoreanBm25SearchServiceTests,LawAiPropertiesTests test
```

Expected: FAIL.

- [ ] **Step 3: Implement documented BM25**

Use:

```text
idf(t) = ln(1 + (N - df(t) + 0.5) / (df(t) + 0.5))
score(t,d) = idf(t) * (tf'(k1 + 1)) /
             (tf' + k1 * (1 - b + b * len(d) / avgLen))
```

Defaults:

- `k1=1.2`
- `b=0.75`
- field weights: document title 8, parent title 6, chunk title 7, body 1
- maximum query terms 24
- maximum result limit 100

`tf'` is field-weighted term frequency. Parameters live under
`law-ai.retrieval.lexical`.

- [ ] **Step 4: Enforce ready-revision and latency bounds**

Return no shadow result when no ready revision exists. Mapper timeout is one
second. Log revision, term count, result count, and elapsed milliseconds without
logging source body text.

- [ ] **Step 5: Run focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=KoreanBm25SearchServiceTests,SemanticLexicalMapperXmlTests,LawAiPropertiesTests test
```

Expected: PASS.

- [ ] **Step 6: Self-review and commit**

Commit:

```powershell
git add src/main/java/com/kaces/pandora/semantic/lexical/KoreanBm25SearchService.java src/main/java/com/kaces/pandora/semantic/lexical/SemanticLexicalMapper.java src/main/resources/mapper/law/SemanticLexicalMapper.xml src/main/java/com/kaces/pandora/semantic/config/LawAiProperties.java src/main/resources/application.yml src/test/java/com/kaces/pandora/semantic/lexical/KoreanBm25SearchServiceTests.java src/test/java/com/kaces/pandora/semantic/config/LawAiPropertiesTests.java
git commit -m "feat: rank Korean lexical candidates with BM25"
```

---

### Task 9: Reciprocal Rank Fusion Shadow

**Files:**
- Create: `src/main/java/com/kaces/pandora/semantic/lexical/ReciprocalRankFusion.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiDebugResponse.java`
- Modify: `src/main/java/com/kaces/pandora/semantic/config/LawAiProperties.java`
- Test: `src/test/java/com/kaces/pandora/semantic/lexical/ReciprocalRankFusionTests.java`
- Test: `src/test/java/com/kaces/pandora/ai/answer/LawAiDebugResponseItemTests.java`
- Test: `src/test/java/com/kaces/pandora/ai/answer/LawAiAnswerServiceEvidenceGateTests.java`

**Interfaces:**
- Consumes: ordered vector hits and ordered BM25 hits.
- Produces: fused `RrfHit` rows and debug-visible control/shadow rank differences.

- [ ] **Step 1: Write failing deterministic fusion tests**

Assert:

```java
List<RrfHit> fused = fusion.fuse(vectorHits, lexicalHits, 60, 1.0, 1.0);
assertThat(fused).extracting(RrfHit::candidateKey)
    .containsExactly("law:20", "law:10", "admrul:30");
```

Tie-break by target then numeric chunk ID after RRF score and best source rank.

- [ ] **Step 2: Run tests and confirm missing fusion type**

Run:

```powershell
.\mvnw.cmd -Dtest=ReciprocalRankFusionTests,LawAiDebugResponseItemTests,LawAiAnswerServiceEvidenceGateTests test
```

Expected: FAIL.

- [ ] **Step 3: Implement pure RRF**

Use:

```java
score += sourceWeight / (k + oneBasedRank);
```

Defaults are `k=60`, vector weight `1.0`, lexical weight `1.0`, and fused limit
100.

- [ ] **Step 4: Integrate shadow execution**

Add flags:

```text
law-ai.retrieval.rrf-shadow-enabled=false
law-ai.retrieval.rrf-authoritative=false
```

When shadow is enabled, run BM25 and RRF, attach ranks/scores to debug output,
but pass the existing control order to rerank/Judge. When authoritative is true,
pass fused order while preserving all later fail-closed filters.

- [ ] **Step 5: Run focused and backend tests**

Run:

```powershell
.\mvnw.cmd -Dtest=ReciprocalRankFusionTests,LawAiDebugResponseItemTests,LawAiAnswerServiceEvidenceGateTests test
.\mvnw.cmd test
```

Expected: PASS with shadow disabled by default.

- [ ] **Step 6: Self-review and commit**

Confirm no control ordering changes with default configuration. Commit:

```powershell
git add src/main/java/com/kaces/pandora/semantic/lexical/ReciprocalRankFusion.java src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java src/main/java/com/kaces/pandora/ai/answer/LawAiDebugResponse.java src/main/java/com/kaces/pandora/semantic/config/LawAiProperties.java src/test/java/com/kaces/pandora/semantic/lexical/ReciprocalRankFusionTests.java src/test/java/com/kaces/pandora/ai/answer/LawAiDebugResponseItemTests.java src/test/java/com/kaces/pandora/ai/answer/LawAiAnswerServiceEvidenceGateTests.java
git commit -m "feat: shadow vector and BM25 rank fusion"
```

---

### Task 10: Candidate-Level Loss Trace

**Files:**
- Create: `src/main/java/com/kaces/pandora/ai/answer/RetrievalCandidateTrace.java`
- Create: `src/main/java/com/kaces/pandora/ai/answer/RetrievalTraceCollector.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiDebugResponse.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`
- Modify: `scripts/rag-retrieval-eval.js`
- Modify: `scripts/lib/rag-failure-presence-report.js`
- Test: `src/test/java/com/kaces/pandora/ai/answer/RetrievalTraceCollectorTests.java`
- Test: `scripts/rag-retrieval-eval.test.js`
- Test: `scripts/rag-failure-presence-report.test.js`

**Interfaces:**
- Consumes: candidate lists and decisions at every retrieval/selection stage.
- Produces: immutable traces with `firstLossStage` and `reasonCodes`.

- [ ] **Step 1: Write failing trace transition tests**

Assert:

```java
RetrievalCandidateTrace trace = collector.finish("law:55");
assertThat(trace.sourceRanks()).containsEntry("vector", 4);
assertThat(trace.enteredStages()).contains("merged", "reranked", "intent");
assertThat(trace.firstLossStage()).isEqualTo("judge");
assertThat(trace.reasonCodes()).contains("JUDGE_NOT_DIRECT");
```

Also assert selected candidates have no loss stage and that later updates cannot
overwrite the first loss.

- [ ] **Step 2: Run focused tests and confirm missing classes**

Run:

```powershell
.\mvnw.cmd -Dtest=RetrievalTraceCollectorTests test
node --test .\scripts\rag-retrieval-eval.test.js .\scripts\rag-failure-presence-report.test.js
```

Expected: FAIL.

- [ ] **Step 3: Implement bounded trace collection**

Record source rank, control/fused rank, intent reason, Judge decision, noise
filter, diversifier, ground, and answer-context status. Keep at most 100 traces
per debug request and never include full chunk body text.

- [ ] **Step 4: Export first-loss analysis**

Retrieval evaluation artifacts include:

```json
{
  "candidateKey": "law:55",
  "oraclePresenceStage": "judgeCandidates",
  "firstLossStage": "judge",
  "reasonCodes": ["JUDGE_NOT_DIRECT"]
}
```

The presence report aggregates by first loss stage and reason code.

- [ ] **Step 5: Run focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=RetrievalTraceCollectorTests,LawAiDebugResponseItemTests,LawAiAnswerServiceEvidenceGateTests test
node --test .\scripts\rag-retrieval-eval.test.js .\scripts\rag-failure-presence-report.test.js
```

Expected: PASS.

- [ ] **Step 6: Self-review and commit**

Commit:

```powershell
git add src/main/java/com/kaces/pandora/ai/answer/RetrievalCandidateTrace.java src/main/java/com/kaces/pandora/ai/answer/RetrievalTraceCollector.java src/main/java/com/kaces/pandora/ai/answer/LawAiDebugResponse.java src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java scripts/rag-retrieval-eval.js scripts/lib/rag-failure-presence-report.js src/test/java/com/kaces/pandora/ai/answer/RetrievalTraceCollectorTests.java scripts/rag-retrieval-eval.test.js scripts/rag-failure-presence-report.test.js
git commit -m "feat: trace direct evidence through selection"
```

---

### Task 11: EvidenceAtom Model and Parser

**Files:**
- Create: `src/main/java/com/kaces/pandora/ai/answer/EvidenceAtom.java`
- Create: `src/main/java/com/kaces/pandora/ai/answer/PropositionTemplate.java`
- Create: `src/main/java/com/kaces/pandora/ai/answer/QuestionPropositionTemplateFactory.java`
- Create: `src/main/java/com/kaces/pandora/ai/answer/KoreanEvidenceAtomParser.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/ClaimEvidenceAtomizer.java`
- Test: `src/test/java/com/kaces/pandora/ai/answer/KoreanEvidenceAtomParserTests.java`
- Test: `src/test/java/com/kaces/pandora/ai/answer/ClaimEvidenceAtomizerTests.java`

**Interfaces:**
- Consumes: one already atomized Korean claim or evidence clause.
- Produces: `EvidenceAtom parse(String sourceText)` and
  `PropositionTemplate from(String question, QuestionIntentProfile profile)`.

- [ ] **Step 1: Write failing slot extraction tests**

Use reviewed examples for:

- subject and object;
- permission versus prohibition;
- obligation versus no obligation;
- condition and exception;
- target inclusion versus exclusion;
- numeric deadline;
- two differently scoped populations; and
- ambiguous double negation.

Assert:

```java
EvidenceAtom atom = parser.parse("계약상대자는 이행을 완료하면 서면으로 통지해야 한다.");
assertThat(atom.subjects()).contains("계약상대자");
assertThat(atom.actions()).contains("통지");
assertThat(atom.conditions()).contains("이행완료");
assertThat(atom.modality()).isEqualTo(EvidenceAtom.Modality.REQUIRED);
assertThat(atom.polarity()).isEqualTo(EvidenceAtom.Polarity.POSITIVE);
assertThat(atom.parseStatus()).isEqualTo(EvidenceAtom.ParseStatus.COMPLETE);
```

- [ ] **Step 2: Run tests and confirm missing model/parser**

Run:

```powershell
.\mvnw.cmd -Dtest=KoreanEvidenceAtomParserTests,ClaimEvidenceAtomizerTests test
```

Expected: FAIL.

- [ ] **Step 3: Implement immutable atom slots**

Define records/enums for source span, role identities, actions, relations,
scope, conditions, exceptions, numeric anchors, modality, polarity, and parse
status. All collections are immutable and canonicalized through
`KoreanQueryNormalizer`.

- [ ] **Step 4: Implement reusable proposition templates**

Define:

```java
public record PropositionTemplate(
    Set<String> subjects,
    Set<String> actions,
    Set<String> relations,
    Set<String> targetScopes,
    Set<String> conditions,
    Set<RequiredSlot> requiredSlots
) {}
```

`QuestionPropositionTemplateFactory` consumes `QuestionIntentProfile` intent,
concept, direct-evidence, and required-condition groups. It returns an empty
template for discovery/list requests and a fail-closed template for
answer-oriented legal conclusions.

- [ ] **Step 5: Extract existing semantics without changing control matcher**

Move reusable parsing rules from private `ClaimSemantics` helpers into
`KoreanEvidenceAtomParser`. The control matcher continues using its current
logic in this task. Ambiguous attribution or double negation produces
`ParseStatus.AMBIGUOUS` with a reason code.

- [ ] **Step 6: Run focused matcher and atomizer suites**

Run:

```powershell
.\mvnw.cmd -Dtest=KoreanEvidenceAtomParserTests,ClaimEvidenceAtomizerTests,ClaimEvidenceMatcherRelationTests,ClaimEvidenceMatcherNumericTests test
```

Expected: PASS with no control matcher behavior change.

- [ ] **Step 7: Self-review and commit**

Commit:

```powershell
git add src/main/java/com/kaces/pandora/ai/answer/EvidenceAtom.java src/main/java/com/kaces/pandora/ai/answer/PropositionTemplate.java src/main/java/com/kaces/pandora/ai/answer/QuestionPropositionTemplateFactory.java src/main/java/com/kaces/pandora/ai/answer/KoreanEvidenceAtomParser.java src/main/java/com/kaces/pandora/ai/answer/ClaimEvidenceAtomizer.java src/test/java/com/kaces/pandora/ai/answer/KoreanEvidenceAtomParserTests.java src/test/java/com/kaces/pandora/ai/answer/ClaimEvidenceAtomizerTests.java
git commit -m "feat: parse claims and evidence into semantic atoms"
```

---

### Task 12: Semantic Matcher Shadow

**Files:**
- Create: `src/main/java/com/kaces/pandora/ai/answer/SemanticEvidenceMatcher.java`
- Create: `src/main/java/com/kaces/pandora/ai/answer/ClaimMatcherShadowResult.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/ClaimVerifier.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/ClaimEvidenceMatcher.java`
- Modify: `src/main/java/com/kaces/pandora/semantic/config/LawAiProperties.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiEvalResponse.java`
- Test: `src/test/java/com/kaces/pandora/ai/answer/SemanticEvidenceMatcherTests.java`
- Test: `src/test/java/com/kaces/pandora/ai/answer/ClaimVerifierTests.java`
- Test: `src/test/java/com/kaces/pandora/ai/answer/ClaimEvidenceMatcherCanonicalArtifactReplay.java`

**Interfaces:**
- Consumes: claim `EvidenceAtom` and indexed evidence atoms.
- Produces: semantic status, aligned slots, ground/sentence, and reviewed shadow disagreement through
  `match(EvidenceAtom, EvidenceIndex)` and
  `match(PropositionTemplate, EvidenceAtom)`.

- [ ] **Step 1: Write failing proposition-alignment tests**

Assert these policies:

```text
same proposition + same polarity + all required slots -> SUPPORTED
same proposition + opposite polarity -> CONTRADICTED
aligned positive and negative evidence -> CONFLICTED
different subject, relation, scope, or condition -> INSUFFICIENT
ambiguous parse -> INSUFFICIENT
```

Include the reviewed false-contradiction artifacts and all known real
contradiction controls.

- [ ] **Step 2: Run tests and confirm missing matcher**

Run:

```powershell
.\mvnw.cmd -Dtest=SemanticEvidenceMatcherTests,ClaimVerifierTests,ClaimEvidenceMatcherCanonicalArtifactReplay test
```

Expected: FAIL.

- [ ] **Step 3: Implement ordered semantic gates**

`SemanticEvidenceMatcher.match` applies:

1. parse completeness;
2. numeric ordering;
3. proposition action/relation alignment;
4. subject/object/recipient coverage;
5. target and population scope alignment;
6. condition and exception coverage;
7. modality and polarity comparison; and
8. minimum lexical coverage.

No polarity comparison occurs before gates 3-6 pass.

- [ ] **Step 4: Add control/shadow invocation**

Flags:

```text
law-ai.verification.semantic-shadow-enabled=false
law-ai.verification.semantic-authoritative=false
```

With shadow enabled, `ClaimVerifier` records control and semantic results. With
authoritative false, verified answer behavior remains unchanged. Evaluation
responses include bounded disagreements and unsafe-disagreement counts.

- [ ] **Step 5: Run focused and artifact replay tests**

Run:

```powershell
.\mvnw.cmd -Dtest=SemanticEvidenceMatcherTests,ClaimVerifierTests,ClaimEvidenceMatcherRelationTests,ClaimEvidenceMatcherNumericTests,ClaimEvidenceMatcherArtifactRegressionTests,ClaimEvidenceMatcherCanonicalArtifactReplay test
```

Expected: PASS and zero unexpected changes to control results.

- [ ] **Step 6: Self-review and commit**

Check every parser/matcher fallback returns `INSUFFICIENT`. Commit:

```powershell
git add src/main/java/com/kaces/pandora/ai/answer/SemanticEvidenceMatcher.java src/main/java/com/kaces/pandora/ai/answer/ClaimMatcherShadowResult.java src/main/java/com/kaces/pandora/ai/answer/ClaimVerifier.java src/main/java/com/kaces/pandora/ai/answer/ClaimEvidenceMatcher.java src/main/java/com/kaces/pandora/semantic/config/LawAiProperties.java src/main/java/com/kaces/pandora/ai/answer/LawAiEvalResponse.java src/test/java/com/kaces/pandora/ai/answer/SemanticEvidenceMatcherTests.java src/test/java/com/kaces/pandora/ai/answer/ClaimVerifierTests.java src/test/java/com/kaces/pandora/ai/answer/ClaimEvidenceMatcherCanonicalArtifactReplay.java
git commit -m "feat: shadow semantic claim evidence matching"
```

---

### Task 13: Direct-Evidence Selection Recovery

**Files:**
- Create: `src/main/java/com/kaces/pandora/ai/answer/DirectEvidenceSelectionPolicy.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/EvidenceJudge.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/RetrievalTraceCollector.java`
- Test: `src/test/java/com/kaces/pandora/ai/answer/DirectEvidenceSelectionPolicyTests.java`
- Test: `src/test/java/com/kaces/pandora/ai/answer/EvidenceJudgeTests.java`
- Test: `src/test/java/com/kaces/pandora/ai/answer/LawAiAnswerServiceEvidenceGateTests.java`

**Interfaces:**
- Consumes: question atoms, candidate evidence atoms, Judge result, combined ranks, and trace reasons.
- Produces: preserved direct evidence only when proposition and required slots align.

- [ ] **Step 1: Write failing candidate-present recovery tests**

Cover:

- correct direct provision arrives at Judge but is dropped behind a definition;
- title-like statement must not replace a conditional operative clause;
- different population must not be preserved;
- missing required condition remains rejected;
- actual contradiction remains rejected; and
- preserved evidence retains original candidate/ground identity.

- [ ] **Step 2: Run tests and confirm missing policy**

Run:

```powershell
.\mvnw.cmd -Dtest=DirectEvidenceSelectionPolicyTests,EvidenceJudgeTests,LawAiAnswerServiceEvidenceGateTests test
```

Expected: FAIL.

- [ ] **Step 3: Implement reusable preservation policy**

The policy may preserve a dropped candidate only when:

```java
semanticMatcher.match(questionTemplate, candidateAtom).status()
    == ClaimEvidenceMatcher.Status.SUPPORTED
```

and the candidate is not structural noise, obsolete version evidence, forbidden
target, or contradicted by an aligned current ground. Score boosts alone cannot
qualify a candidate.

- [ ] **Step 4: Replace special preservation branches at the boundary**

Call the policy once after Judge and before final noise/diversification. Keep
existing narrow safety policies until shadow comparison proves they are
redundant. Record `DIRECT_ATOM_PRESERVED` or an explicit rejection reason.

- [ ] **Step 5: Run focused and backend suites**

Run:

```powershell
.\mvnw.cmd -Dtest=DirectEvidenceSelectionPolicyTests,EvidenceJudgeTests,LawAiAnswerServiceEvidenceGateTests test
.\mvnw.cmd test
```

Expected: PASS.

- [ ] **Step 6: Self-review and commit**

Commit:

```powershell
git add src/main/java/com/kaces/pandora/ai/answer/DirectEvidenceSelectionPolicy.java src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java src/main/java/com/kaces/pandora/ai/answer/EvidenceJudge.java src/main/java/com/kaces/pandora/ai/answer/RetrievalTraceCollector.java src/test/java/com/kaces/pandora/ai/answer/DirectEvidenceSelectionPolicyTests.java src/test/java/com/kaces/pandora/ai/answer/EvidenceJudgeTests.java src/test/java/com/kaces/pandora/ai/answer/LawAiAnswerServiceEvidenceGateTests.java
git commit -m "fix: preserve aligned direct evidence through selection"
```

---

### Task 14: Evaluation Set and Shadow Acceptance Reports

**Files:**
- Modify: `src/main/resources/rag-evaluation-cases.tsv`
- Modify: `src/main/resources/rag-answer-evaluation-oracles.tsv`
- Modify: `scripts/lib/rag-eval-cases.js`
- Modify: `scripts/rag-eval-coverage-report.js`
- Modify: `scripts/search-quality-diagnostics.js`
- Test: `scripts/rag-retrieval-eval.test.js`
- Test: `scripts/rag-eval-provenance.test.js`

**Interfaces:**
- Consumes: reviewed candidate-loss, semantic-disagreement, and negative-control cases.
- Produces: explicit propositions, required conditions, forbidden expressions, and named-gate coverage diagnostics.

- [ ] **Step 1: Write failing coverage tests**

Assert the report rejects:

- a `NO_GROUNDS` case with no forbidden/domain distractor expectation;
- an answer-required case without proposition groups;
- duplicate normalized question/oracle combinations;
- a new failure case classified only as generic `기타`; and
- a release dataset with fewer than 30 `NO_GROUNDS` controls.

- [ ] **Step 2: Run Node tests and confirm failure**

Run:

```powershell
node --test .\scripts\rag-retrieval-eval.test.js .\scripts\rag-eval-provenance.test.js
```

Expected: FAIL under the stricter coverage contract.

- [ ] **Step 3: Add reviewed data cases**

Add contract completion, wrong-population, title-only, missing-condition,
obsolete-version, related-definition-only, unsupported numeric/deadline,
obligation, exception, sanction, and unrelated-domain controls. Every
answer-required case has proposition groups, condition groups or explicit `-`,
and forbidden expressions.

- [ ] **Step 4: Report named-gate and shadow coverage**

Coverage output includes:

- curated total;
- answer-oracle total;
- `NO_GROUNDS` total;
- explicit condition total;
- each failure taxonomy count;
- unsafe semantic disagreement count; and
- candidate-present first-loss coverage.

- [ ] **Step 5: Run Node suites**

Run:

```powershell
node --test .\scripts\rag-retrieval-eval.test.js .\scripts\rag-eval-provenance.test.js .\scripts\rag-failure-presence-report.test.js
node .\scripts\rag-eval-coverage-report.js
```

Expected: PASS and no unclassified coverage risk.

- [ ] **Step 6: Self-review and commit**

Commit:

```powershell
git add src/main/resources/rag-evaluation-cases.tsv src/main/resources/rag-answer-evaluation-oracles.tsv scripts/lib/rag-eval-cases.js scripts/rag-eval-coverage-report.js scripts/search-quality-diagnostics.js scripts/rag-retrieval-eval.test.js scripts/rag-eval-provenance.test.js
git commit -m "test: strengthen RAG release gate coverage"
```

---

### Task 15: Shadow Measurement and Controlled Activation

**Files:**
- Modify after measured acceptance: `src/main/resources/application.yml`
- Modify after measured acceptance: obsolete narrow rules proven redundant by traces.
- Create: `docs/rag-quality-handoff-20260730-shadow-migration-final.md`

**Interfaces:**
- Consumes: stable post-repair corpus, common lexical revision, RRF trace, candidate loss trace, and semantic disagreement report.
- Produces: reviewed feature activation and final release evidence.

- [ ] **Step 1: Build and deploy one verification JAR**

Run:

```powershell
.\mvnw.cmd test
.\mvnw.cmd -DskipTests package
.\scripts\deploy-pandora-app8080.ps1
.\scripts\status-pandora.ps1
```

Confirm 8080 reports the built JAR hash. Confirm 18080 state and batch JAR hash
are unchanged.

- [ ] **Step 2: Build the common lexical shadow index**

Build a side-by-side revision. Require 100% coverage of active searchable
chunks, ready status, non-empty fingerprint, and repeatable BM25 ranks.

- [ ] **Step 3: Capture a stable shadow manifest**

Capture commit, clean state, JAR/config/runtime/index/lexical identities and
dataset hashes. Do not mutate the corpus or either index until the shadow
evaluation ladder finishes.

- [ ] **Step 4: Run retrieval shadow acceptance**

Run failed-86 proposition replay, difficult 12, and retrieval holdout twice.
Require:

- top-30 explicit-oracle proposition presence at least 80%;
- no difficult or negative-control direct-evidence regression;
- warm local BM25 p95 at most 500 ms; and
- identical deterministic ranks across repeated replay.

- [ ] **Step 5: Activate RRF ordering**

Set `rrf-authoritative=true` only after Step 4. Rebuild/deploy one JAR and
capture a new stable manifest. Run targeted candidate-present cases before the
answer-generation ladder.

- [ ] **Step 6: Run semantic shadow acceptance**

Run focused matcher artifacts, difficult 12, 85 cases twice, and full evaluation
with semantic shadow enabled. Review every unsafe disagreement. Each reviewed
false control rejection becomes a parser/matcher test before correction.

- [ ] **Step 7: Activate semantic matcher**

Set `semantic-authoritative=true` only when unsafe disagreements are zero and
real contradiction controls remain rejected. Rebuild/deploy and capture a new
stable final manifest.

- [ ] **Step 8: Run the final release ladder**

Under the unchanged final manifest:

```powershell
.\mvnw.cmd test
node --test .\scripts\rag-eval-provenance.test.js .\scripts\rag-retrieval-eval.test.js .\scripts\rag-failure-presence-report.test.js
```

Then run:

1. focused changed cases;
2. difficult 12;
3. 85 cases;
4. the same 85 cases again;
5. all 1,004 cases; and
6. the independent curated, answer-oracle, and `NO_GROUNDS` gate summaries.

Every release gate must have zero failures. A quota, network, runtime identity,
or index identity failure is `UNVERIFIED`, not a quality pass.

- [ ] **Step 9: Remove only proven redundant narrow rules**

Use trace evidence to identify a narrow rule whose removal causes no control,
negative, difficult, 85-case, or full-gate regression. Remove one rule family
per commit with a failing redundancy/behavior test and rerun the full applicable
ladder.

- [ ] **Step 10: Write final handoff and commit**

Document:

- final commit and clean state;
- JAR/config/runtime/index/lexical identities;
- chunk and embedding counts;
- BM25/RRF metrics;
- candidate-present selection rate;
- semantic disagreement counts;
- named and full gate results;
- rollback flags, prior chunk versions, and lexical revision; and
- unchanged 18080 evidence.

Commit:

```powershell
git add src/main/resources/application.yml docs/rag-quality-handoff-20260730-shadow-migration-final.md
git commit -m "docs: record RAG shadow migration verification"
```

---

## Final Promotion Checklist

- [ ] Tracked worktree is clean except intentional commits; `output/` remains preserved and untracked.
- [ ] Active searchable law/RAG embedding backlog is zero.
- [ ] Database and Qdrant counts/fingerprints agree.
- [ ] Searchable provisions have explicit parent identity and bounded context.
- [ ] Common lexical index covers every active searchable chunk.
- [ ] RRF and semantic matcher passed shadow acceptance before activation.
- [ ] Candidate-present direct evidence selection is at least 80%.
- [ ] Curated gate has zero failures.
- [ ] Answer-oracle gate has zero failures.
- [ ] `NO_GROUNDS` gate has zero failures.
- [ ] Difficult 12 and both 85-case runs pass.
- [ ] Full 1,004-case gate passes under one stable final manifest.
- [ ] `.\mvnw.cmd test` passes.
- [ ] 18080 process state and batch JAR hash are unchanged.
- [ ] Rollback flags, chunk version, lexical revision, and prior JAR are recorded.
