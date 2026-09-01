# Deterministic Retrieval Promotion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce restart-stable, deterministic retrieval evidence and promote only a generalized, independently verified retrieval improvement.

**Architecture:** Canonicalize bounded BM25 hydration before database reads, base semantic revision identity on content fingerprints rather than operational timestamps, then evaluate title matching candidates through a one-way promotion ladder. Authority flags change only after training, Difficult-12, holdout, answer, and full release gates pass.

**Tech Stack:** Java 17, Spring Boot, MyBatis/MariaDB, Qdrant, JUnit 5, AssertJ, Mockito, Node.js evaluation scripts, PowerShell runtime scripts.

**Spec:** `docs/superpowers/specs/2026-09-01-deterministic-retrieval-promotion-design.md`

## Global Constraints

- Keep shared `C:\dev\workspace-egov\pandora` on `main`; work only in the existing isolated diagnostics worktree.
- Preserve the dirty Task 15 evidence in shared `main`.
- Never stop, restart, promote, or otherwise mutate port `18080`.
- Never read, modify, move, or delete `output/`.
- Keep RRF and semantic authority false until every required gate passes.
- Fail closed on provenance drift, request failure, Qdrant failure, or regression.

---

### Task 1: Canonical BM25 hydration

**Files:**
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`
- Modify: `src/main/java/com/kaces/pandora/semantic/retrieval/Bm25TitleDocumentSeedSelector.java`
- Test: `src/test/java/com/kaces/pandora/ai/answer/LawAiAnswerServiceDocumentExpansionTests.java`
- Test: `src/test/java/com/kaces/pandora/semantic/retrieval/Bm25TitleDocumentSeedSelectorTests.java`

**Interfaces:**
- Consumes: `List<LexicalSearchHit>` and hydrated `LawSemanticChunkRow` values.
- Produces: a permutation-invariant bounded candidate order and sorted law/RAG chunk-ID reads.

- [ ] Write a failing test that permutes equal/tied BM25 inputs and asserts identical inspected candidates, hydration requests, diagnostics, and seeds.
- [ ] Run the two focused test classes and confirm the new test fails for caller-order dependence.
- [ ] Add one total comparator and canonicalize/deduplicate hits before bounded inspection; sort per-family IDs before mapper reads.
- [ ] Re-run focused tests and confirm zero failures.

### Task 2: Restart-stable index revision

**Files:**
- Modify: `src/main/java/com/kaces/pandora/semantic/provenance/IndexRevisionCalculator.java`
- Modify: `src/main/java/com/kaces/pandora/semantic/provenance/IndexContentSnapshot.java`
- Test: `src/test/java/com/kaces/pandora/semantic/provenance/IndexRevisionCalculatorTests.java`
- Test: `src/test/java/com/kaces/pandora/semantic/provenance/IndexRevisionMapperXmlTests.java`

**Interfaces:**
- Consumes: content fingerprint/count plus stable Qdrant shape and exact count.
- Produces: schema-v2 revision unaffected by an `updatedWatermark`-only change.

- [ ] Write a failing test asserting equal revisions for two snapshots differing only in watermark and a passing safety assertion for same-count content replacement.
- [ ] Run provenance tests and confirm the watermark-only assertion fails.
- [ ] Remove watermark from the canonical hash, relax snapshot usability to fingerprint/count, and advance the revision schema version.
- [ ] Re-run provenance tests and confirm zero failures.

### Task 3: Backend regression verification

**Files:**
- Evidence: `.superpowers/sdd/2026-07-30-rag-retrieval-evidence-shadow-migration/`

**Interfaces:**
- Consumes: Tasks 1-2 exact source tree.
- Produces: focused and full Maven test evidence.

- [ ] Run focused retrieval/provenance tests.
- [ ] Run `.\mvnw.cmd test` once on the stable tree.
- [ ] Record counts, duration, commit/diff identity, and any skipped tests in the Task 15 ledger.

### Task 4: Generalized title matching candidates

**Files:**
- Modify: `src/main/java/com/kaces/pandora/common/text/KoreanQueryNormalizer.java`
- Modify: `src/main/java/com/kaces/pandora/semantic/retrieval/Bm25TitleDocumentSeedSelector.java`
- Test: corresponding focused normalizer and selector tests.

**Interfaces:**
- Consumes: planned search terms and document titles only.
- Produces: canonical title tokens and a bounded, fail-closed match decision.

- [ ] Add RED tests for legal suffixes, whitespace/punctuation, Korean token boundaries, substantive-token preservation, unrelated-title rejection, and ambiguous candidates.
- [ ] Implement the smallest canonicalization candidate without case-specific dictionaries.
- [ ] Run focused tests and freeze each candidate behind shadow-only configuration.

### Task 5: Independent training selection

**Files:**
- Create: immutable training manifests and two-run rank captures under the Task 15 evidence directory.
- Modify: Task 15 progress ledger.

**Interfaces:**
- Consumes: frozen 24-case training IDs and exact candidate artifact.
- Produces: an eligibility decision with per-case deltas and reproducibility comparison.

- [ ] Validate manifest, runtime, artifact, config, revisions, parity, and destinations.
- [ ] Run two independent training captures.
- [ ] Select a candidate only when both runs agree, no existing required group regresses, and the candidate adds required groups or improves the predeclared rank metric.

### Task 6: Difficult-12, holdout, and answer gates

**Files:**
- Create: immutable manifests and result summaries under the Task 15 evidence directory.

**Interfaces:**
- Consumes: the single training-selected candidate.
- Produces: three sequential pass/fail gate records.

- [ ] Run Difficult-12 and stop on regression or unexplained variance.
- [ ] Run untouched holdout and stop on regression.
- [ ] Run answer evaluation, requiring grounded-answer and claim-verifier thresholds with no unsupported-answer regression.

### Task 7: Conditional authority activation

**Files:**
- Modify: `src/main/resources/application.properties` or the existing authoritative retrieval configuration source.
- Test: affected runtime-info, configuration-hash, and answer-service tests.

**Interfaces:**
- Consumes: signed gate results from Task 6.
- Produces: authoritative RRF and semantic matching only when eligible.

- [ ] Write RED tests for the exact promoted flag state and configuration hash.
- [ ] Change only the approved authority flags; do not change ranking weights simultaneously.
- [ ] Run focused and full backend tests, build the exact JAR, deploy/restart only app-dev 8080 through repository scripts, and verify stable runtime provenance twice.

### Task 8: Full release evaluation

**Files:**
- Create: full-evaluation manifest, checkpoint/result files, and final comparison summary.

**Interfaces:**
- Consumes: exact promoted JAR/config/index identity.
- Produces: complete approximately 1,004-case release decision.

- [ ] Validate exact ID completeness and duplicate absence before launch.
- [ ] Execute the full gate from zero on one stable provenance segment unless the evaluator's tested segmented-resume contract applies.
- [ ] Verify request counts, failures, Qdrant health, direct-ground metrics, answer metrics, and baseline deltas.
- [ ] Revert authority flags before release if any mandatory gate fails.

### Task 9: Evidence and handoff

**Files:**
- Modify: Task 15 progress ledger.
- Create or modify: final RAG quality handoff under `docs/`.

**Interfaces:**
- Consumes: all test and evaluation evidence.
- Produces: reviewable commit(s), pushed branch, and final operator handoff.

- [ ] Hash and inventory every final evidence artifact.
- [ ] Review the diff for case-specific behavior, fail-open paths, secret leakage, `18080` mutation, and `output/` access.
- [ ] Commit verified changes, push the feature branch, and report commit SHA plus exact remaining risks.
