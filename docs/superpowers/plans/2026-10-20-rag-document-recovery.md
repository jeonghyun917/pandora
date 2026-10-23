# RAG Document Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve the baseline document chunks and searchable assets while validating, promoting and rolling back an isolated replacement generation.

**Architecture:** MariaDB owns a document generation pointer and revision. Candidate chunks, Q collections and lexical assets remain isolated until prepared. Search captures one immutable routing snapshot per request; promotion and rollback change only the authoritative DB pointer transactionally.

**Tech Stack:** Java/Spring, MyBatis/MariaDB, existing Qdrant client, existing lexical implementations, React administration UI; no new dependency.

**Spec:** `docs/superpowers/specs/2026-10-20-rag-document-recovery-design.md` (approved by user continuation on 2026-10-20).

## Current execution checkpoint — 2026-10-23

Tasks 1–5 implementation and local/fenced-DB verification are committed and pushed as
`bf04f5cc0f2d86295692cbd2b194f1bfd1c64457`. Independent high-risk review was completed;
five Important findings were fixed and tested. Final backend1785/f0/e0/skipped21,
frontend22/build pass, and official app8080 closed-gate deployment are recorded in
`docs/evidence/rag-quality/document-recovery-20261023.md`.

Task 3 real-Q integration now passed unchanged strict checks (09785c/97ff2f).
Live preparation exposed fingerprint heap duplication (fixed52d1bfd), global-statistic
query timeout (fixed8727bf2), and request posting-count timeout (fixed56d91b4).
Latest stable backend: fresh135XML1788/f0/e0/skipped21; independent cardinality review
findings fixed with actual fenced DB counterexamples. Product56d91b4 is pushed and
officially deployed with JAR396ba4bfd9e921e43d33bdea2b98b7021974d4e734570ff81a85417d67b0cd4d.
Current app33180/runtimec58d0afd-9e87-4052-acd0-b4bcfe751d2a is supervised by77655;
shared Q17204 remains ready, DB-Q211658/84248 exact. New inactive candidate
6601b516-4736-440e-8c5b-368a22fb951f passed original validation and is preparing once.
The first two failed candidates remain unchanged. Pointer revision0/baseline4 remain active;
no promotion yet. Task6 actual full preparation/readiness, promotion/consumer verification,
then condition-proof and penalty→Difficult12→holdout57→full1003 remain open.
Automation stays PAUSED. Do not repeat passed same-SHA suites for push/deployment,
replay terminal quality failures, or treat original-fidelity 4→17 validation as
actual semantic/release PASS. Resume from the concrete external conditions in the
evidence document, not from the old unchecked implementation bullets below.

## Global Constraints

- Keep automation PAUSED; shared main, 18080 and existing Q process remain unchanged.
- No forced mapping, proof/verifier weakening, question hardcoding or destructive reimport.
- Baseline chunk IDs/text/embedding/Q points/keyword assets are preserved.
- Source SHA, product SHA, manifest and active revision bind all validation evidence.
- External embedding transmission only within existing source approval and fresh runtime/quota/network checks.
- No promotion before every retrieval route obeys the captured generation snapshot.
- Product branches contain no special case for document 1765 or expected count 17.

## Review Focus

- Legacy documents without a generation pointer retain identical retrieval behavior (Tasks 1, 4).
- Concurrent create/promote/rollback or source changes must never publish a stale candidate (Tasks 1, 5).
- Candidate/retired vectors must not consume baseline top-k slots (Tasks 3, 4).
- Context expansion, title retrieval and answer caches must not mix revisions during promotion (Task 4).
- Prepared external assets disappearing after validation must produce explicit unavailability, never false readiness (Tasks 3, 5).

## Task 1: Generation persistence and inactive candidate creation

**Files:** Create package `src/main/java/com/kaces/pandora/rag/recovery/` with `RecoveryGeneration.java`, `RecoverySnapshot.java`, `RagRecoveryMapper.java`, `RagRecoveryCandidateService.java`; create `src/main/resources/mapper/law/RagRecoveryMapper.xml`; modify `src/main/resources/schema.sql`; tests `src/test/java/com/kaces/pandora/rag/recovery/RagRecoveryCandidateTests.java`, `RagRecoveryDatabaseTests.java`.

**Interfaces:** `RecoverySnapshot(long documentId,long revision,String activeGeneration,String sourceHash,String baselineHash)`; `RecoveryGeneration(String id,long documentId,String sourceHash,String productSha,String baselineHash,String manifest,String state)`; `preview(long documentId,String productSha)` returns immutable source/baseline/candidate manifest; `create(long documentId,String expectedPreviewHash,String productSha)` returns generation. UUID identifies generation; do not misuse chunk_version as generation.

- [ ] Write tests: `candidateCreationPreservesBaseline` compares every baseline field and embedding row; `legacyReadIsUnchanged`; `sameManifestCreateIsIdempotent`; `sourceOrBaselineChangeRejectsCreate`; `midInsertFailureRollsBackAllCandidateRows`.
- [ ] Run `mvnw.cmd -Dtest=RagRecoveryCandidateTests,RagRecoveryDatabaseTests test` and observe missing-feature failure; isolated DB test must assert fenced URL/datadir before DDL.
- [ ] Add recovery tables (generation, generation membership, document pointer). Candidate chunks use new IDs and `use_yn='N'`; baseline membership records reference existing IDs without updating their rows. Unique document+manifest prevents duplicates; pointer revision starts 0. Store canonical manifest including all chunk fields, proof and source/product identity. Use document lock plus snapshot recomputation and REQUIRES_NEW transaction for create. Do not call import/rebuildDocument.
- [ ] Run focused tests, verify no baseline mutation and no candidate search visibility, self-review, commit exact files.

## Task 2: Source-owned candidate validation

**Files:** Create `rag/recovery/RagRecoveryValidationService.java`, `RecoveryValidation.java`; tests `rag/recovery/RagRecoveryValidationTests.java`; reuse `RagTextExtractor`, `RagChunker`, `RagChunkQualityGate`, `SourceTableBindings` unchanged unless a reproduced bug requires a separate test/fix.

**Interfaces:** `validate(String generationId)` returns `RecoveryValidation(String generationId,String manifest,String sourceHash,String productSha,boolean passed,List<String> failures)`. A successful result persists VALIDATED only by exact manifest/source/product CAS. Failures cannot be overridden by request input.

- [ ] Test original literal coverage, numeric section order, repeated headers, rowspan, split-row exact coverage, wrong source/table/date/institution, deleted premise or ending caveat, stale manifest and malformed proof. Assert modified candidates are rejected and existing final semantic guards are unchanged.
- [ ] Observe RED with `mvnw.cmd -Dtest=RagRecoveryValidationTests test`.
- [ ] Re-materialize original, hash before/after; regenerate and compare exact manifest. Check all nonblank source text is preserved with an explicit normalization contract; do not compare heading-enriched text as byte-identical. Validate structural proof via existing original-coordinate validator and independently preserve non-table ending paragraphs. Missing required proof/coverage is a failure, not a skipped check. Persist evidence identifiers, not a user-provided passed flag.
- [ ] Run tests including existing source proof suite; commit verified changes.

## Task 3: Isolated vector and lexical preparation

**Files:** Create `rag/recovery/RagRecoveryIndexService.java`, `RecoveryAssets.java`; modify `infra/qdrant/QdrantClient.java` through dedicated recovery methods; create `rag/recovery/RecoveryLexicalAssets.java`; tests `RagRecoveryIndexTests.java` and fenced `RagRecoveryIndexIntegrationTests.java`.

**Interfaces:** `prepare(String generationId)` returns `RecoveryAssets(String generationId,String manifest,String vectorCollection,String vectorFingerprint,String lexicalFingerprint,int chunkCount)`; `verify(RecoveryAssets assets)` checks actual asset parity. Collection naming `rag_recovery_` + validated UUID without hyphens. Lexical generation namespace derives from the same UUID; baseline keyword rows are not deleted.

- [ ] RED tests: partial vector write never READY, missing/changed/extra ID or vector model/hash fails parity, duplicate prepare queries uncertain result before retry, crash recovery does not delete baseline, asset deletion fails readiness.
- [ ] Prepare embedding work only for validated new IDs with configured model; use candidate-specific selection, not active-only batch lookup. Persist status per ID. Build isolated vector/lexical assets, compare full ID/content-hash/model/proof manifest, then CAS VALIDATED→INDEX_READY. Cleanup may address only the exact candidate namespace and never baseline assets.
- [ ] Isolated integration tests use dedicated temporary Q collection and fenced DB; never stop shared Q or reuse production collection. Focused tests pass, commit.

## Task 4: Request-scoped generation-aware retrieval

**Files:** Create `rag/recovery/RecoveryRoutingSnapshot.java`, `RagRecoveryRoutingService.java`; modify `ai/answer/LawAiAnswerService.java`, `ai/answer/SemanticVectorSearchService.java`, `semantic/search/LawSemanticSearchService.java`, `semantic/retrieval/DocumentExpansionSearchService.java`, `rag/persistence/RagDocumentMapper.java`, its XML, `semantic/lexical/SemanticLexicalMapper.java` and mapper XML, `SemanticLexicalIndexService.java`, `KoreanBm25SearchService.java`, `GroupBalancedBm25SearchService.java`; tests `RagRecoveryRoutingTests.java`, `RagRecoverySearchIntegrationTests.java`.

**Interfaces:** `capture()` returns immutable `RecoveryRoutingSnapshot(long revision,Map<Long,RecoverySnapshot> documents,Map<String,RecoveryAssets> assets)`; overload retrieval entry points with that snapshot and thread it explicitly through vector, title/heading, keyword, parent/context, hydration and evidence caches. Unmanaged documents follow baseline path. Avoid implicit thread-local state.

- [ ] RED tests: dense candidate scores do not affect old top-k; retired IDs cannot surface after promotion; baseline in-flight request completes on old generation; new request sees only new generation; title/context and caches never mix; legacy results unchanged.
- [ ] Filter managed documents out of baseline Q query before top-k, search only snapshot-active isolated collections and merge using existing ranking policy. Baseline generation continues existing collection until replacement. All DB selection paths scope generation membership from the same snapshot; candidate preparation uses separate explicit APIs. Revision/manifest joins cache keys. Build/publish immutable lexical generation snapshots without deleting prior snapshots. Missing assets fail closed with an explicit unavailable stage.
- [ ] Enumerate every RAG mapper/lexical/vector caller via `rg` and add coverage for each route; no endpoint can promote while this capability is incomplete. Run focused and retrieval regression suites; commit.

## Task 5: Atomic pointer promotion and rollback, authenticated administration

**Files:** Create `rag/recovery/RagRecoveryPromotionService.java`, `RagRecoveryController.java`; modify existing security route configuration; create `frontend/src/components/DocumentRecoveryPanel.jsx`, state reducer/tests, add to `AdminPage.jsx` and `frontend/src/api/lawApi.js`; tests `RagRecoveryPromotionTests.java`, `RagRecoveryAccessTests.java`, extend DB/search integration tests.

**Interfaces:** `promote(String generationId,long expectedRevision,String expectedManifest)` and `rollback(long documentId,String expectedActiveGeneration,long expectedRevision,String restoreGeneration)` return new `RecoverySnapshot`. Endpoints under `/api/law-data/rag-recovery`: GET preview/status and POST create/validate/prepare/promote/rollback with separate typed requests. Server derives readiness; clients cannot set state/evidence.

- [ ] RED: unauthorized/CSRF rejection; unprepared/tampered candidate rejected; stale rollback rejected; two concurrent promotions yield exactly one success; injected pointer/state update failure restores all DB rows; rollback restores baseline routing and leaves assets unchanged.
- [ ] Check external assets before transaction and produce an internal manifest-bound readiness receipt; no network call inside pointer transaction. Lock document, current/candidate generations; recheck source/baseline/manifest/revision and route capability; CAS pointer+states together. Asset disappearance later is handled by routing fail-closed, not claimed as distributed atomicity. Rollback validates preserved baseline assets and increments revision. UI requires fresh snapshot, disables repeated submission, displays each gate/failure and never auto-promotes.
- [ ] Run DB and routing integration plus UI tests/build; review error redaction and status accessibility. Commit.

## Task 6: Release verification and bounded live recovery

**Files:** Update `docs/evidence/rag-quality/source-list-scope-20260929.md`, recovery evidence document, `docs/operations-runtime.md`; existing official stage/deploy scripts only.

- [ ] Run full `mvnw.cmd test` once on stable implementation, focused JS tests and frontend build. Record exact SHA, failures/skips and isolated DB/Q evidence. Request independent high-risk review of generation routing, migration and rollback; fix Critical/Important in scope and verify affected tests.
- [ ] Verify branch/worktrees/dirty state, commit only tested task files; push approved branch to existing remote. Do not rerun unchanged-SHA suites for push. Official status→stage→documented stop/deploy if needed→health/fingerprint; do not modify main/18080 or terminate Q.
- [ ] For document 1765 only, fresh runtime/DB-Q/quota/network and dataset approval check; preview/source/baseline snapshot→create inactive candidate→validate→prepare/parity. Compare all baseline fields/points and keyword assets before/after preparation; candidate must remain nonsearchable.
- [ ] Promote only when every preceding gate passes. Verify new-request generation, all 17 generated chunks and original-coordinate proof, DB-Q/keyword fingerprints, and baseline preservation. Exercise rollback first in isolated integration; live rollback only upon actual health/search failure, never to manufacture activity. Any unsafe/unavailable condition blocks only live branch and leaves baseline active.
- [ ] Record actual source-consumer diagnostics separately from quality evaluation. General business scope/condition proof must exist before new penalty checkpoint. Actual penalty PASS precedes Difficult12→holdout57→full1003 unique checkpoints; no replay of terminal FAIL artifacts. Keep automation PAUSED unless user separately requests resumption.

## Self-review and execution handoff

All spec sections map to Tasks 1–6. Top-k isolation is Task 4, not deferred to hydration. Concurrent/stale CAS and baseline migration are Tasks 1/5; missing external assets Tasks 3/4/5; each uses actual tests. `RecoveryAssets`, `RecoverySnapshot` and request methods are defined once above and reused. No implementation relies on changing the final verifier or source proof criteria. Native inline execution is recommended because state, storage and routing interfaces are sequentially coupled; do not spawn independent implementers sharing these files. Implementation begins after the user reviews this plan, per writing-plans workflow.
