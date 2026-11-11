# Reviewed PDF Source Staging Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for inline implementation. This continues the approved source-version/retrieval work; it does not authorize loosening the existing same-original recovery API.

**Goal:** Prepare a byte-bound replacement original and reviewed multi-page units outside searchable state, then integrate an independently tested source-aware transition.

**Architecture:** Keep the preserved original, active rows and existing recovery source identity unchanged. Use a separate quarantine journal for replacement bytes, baseline identity, product identity, complete review manifest and replay receipt. Never let a request bless its own review digest. Existing per-request generation capture remains authoritative; source replacement cannot activate until metadata, source retrieval, vectors, lexical assets and rollback agree on the captured source.

**Tech Stack:** Existing Java 17, bounded SourcePdfWorker, MariaDB transaction infrastructure and generation-aware retrieval.

**Spec:** Original source-version preservation requirements and docs/superpowers/plans/2026-11-04-retrieval-accuracy-focus.md. Reviewed replay primitive committed as dbb1e8a7355c7a3910ee0706768168e5be586dcb; full 2067/f0/e0/skip80.

## Global constraints

- Atlas 18080, shared main, paused reservation unchanged; no Qdrant stop.
- No failed evaluation replay, arbitrary source substitutions or verifier/oracle relaxation.
- Preserve RagRecoverySourceValidator's HWPX gate and same-original semantics.
- Reviewed coordinates mean physical pages and normalized-extraction line ordinals, not native tags or geometry.
- A matching source/review digest proves identity, not legal applicability or review authority.
- No production corpus write before isolated database and namespace validation.

## Review focus

- A self-computed review hash cannot be used as trusted approval.
- New source preparation cannot overwrite or relabel the preserved source.
- Superseded source metadata must not leak into new-source citations or vice versa.
- The original fully retained document and the reviewed units must regenerate deterministically; adding one good unit cannot waive whole-source validation.
- Rollback restores original source metadata and assets, not only chunk IDs.

## Task 1: Immutable quarantine input, no searchable writes

Files: new rag/recovery/RagPdfDraftStore.java; schema.sql; new RagPdfDraftStoreDatabaseTests.java.

Interface: stage(ReviewedPdfDraft draft, Path original) returns a quarantine identity. Obtain trusted review digest from server-owned review configuration/record, never a stage request field. Require exact draft product SHA and locked baseline hash via RagRecoveryCandidateService.lockedStoredBaselineHash. Validate original and all reviewed units through the capped worker before the short serializable insertion transaction; recheck baseline under lock.

- [x] RED: unapproved digest, stale baseline, changed source and changed product create no journal row; searchable baseline is unchanged.
- [x] Add an immutable quarantine table with unique review identity, baseline/document/source/product/review/replayed hashes and bounded serialized input. No searchable-document FK activation side effect.
- [x] Retain bounded original bytes atomically in the quarantine row, not a separate file store (ledgered ruling); insert-only review identity, exact repeated-input verification, no original-path overwrite.
- [x] GREEN on an owned isolated database: idempotency, trust/refusal, retained-byte tamper, deterministic concurrent read/restage and actual transaction rollback. Host Pandora was not used.

## Task 2: Deterministic replacement source candidate

Files: new RagPdfDraftPreparation.java and its tests; bounded worker protocol only if full-document transport is needed.

- [x] Regenerate complete source and reviewed supplementary units from retained bytes; bind full extraction, quality dispositions, candidate manifest and review identity.
- [x] Preserve exact multi-page locators without fake single-page or native-tag proof. Every reviewed unit keeps its own heading/locator even for identical text.
- [x] Existing unit controls plus real-DB preparation tests cover line/review/source drift, original content retention and heading ownership; all input metadata contributes to the trusted digest.
- [x] Output remains an immutable in-memory plan, with no searchable row/index write or serving endpoint. It is not an INDEX_READY candidate.

## Task 3: Source-aware transition and rollback

Inspect RagRecoveryPromotionService, RagRecoveryRollbackService, RagRecoveryRoutingService, RagRecoveryBaselineService, RagOriginalDocumentStore, proof lookup and answer citation projection before implementing.

- [x] Derive an explicit replacement-source routing record; do not change current pointer sourceHash equality checks in isolation.
- [ ] Tests cover old captured requests, new captured requests, same-document original/materialization lookup, metadata/citation ownership, all search routes and cache identity.
- [ ] Prepare vector/lexical/corpus assets with immutable source identity; CAS source metadata and routing epoch only after independent readiness verification.
- [ ] Rollback must restore preserved original identity, baseline metadata and assets atomically; stale revisions and incomplete assets fail closed.
- [ ] Run isolated DB/Qdrant integration through normal permitted scripts. No security-policy bypass or terminal failure replay.

### Task 3 continuation: validate before asset preparation

Files: `rag/recovery/RagPdfGenerationStore.java`, `RagPdfDraftStoreDatabaseTests.java`, then `RagRecoveryIndexService.java` and `RagRecoveryReceiptService.java` in a distinct asset step. Consume the existing trusted retained draft, exact PENDING generation and complete `prepared_json`/chunk membership. Do not create another generation when validating a stale ID.

- [ ] RED: `RagPdfGenerationStore.validate(String generationId, String expectedPreparedManifest)` returns a `RecoveryGeneration` in VALIDATED only after exact regeneration; wrong manifest, stale pointer, unapproved review, bad stored binding/chunks or missing member must leave PENDING and the original pointer intact. No vector/lexical/preparation receipt is written.
- [ ] GREEN: outside the write transaction regenerate from retained trusted bytes; in one SERIALIZABLE transaction lock routing epoch, document/baseline, pointer, draft and generation in the established order. Require exact preview identity from the current pointer, complete stored member list and immutable source binding. Store a `reviewed-pdf-validation-v1` evidence receipt (generation, draft/review, product, source/baseline, preview, both manifests, extraction digest and count) with the existing `saveValidation` CAS. No legacy validator guard is removed.
- [ ] Asset step: read and independently recheck that exact PDF receipt before any embedding or Qdrant operation; use captured replacement title/locator and the unchanged original-side baseline proof. Bind corpus identity, full member manifest, vectors and lexical namespace. Keep the existing same-original path explicit and unchanged. Real Qdrant and rollback controls remain mandatory before publication.

## Task 4: Stable delivery and conditional quality ladder

### Pre-activation continuation: baseline registration and reviewed source authority

- Add a distinct baseline-only registration path for an unmanaged document. Capture the original bytes, exact active DB member/embedding/keyword identity and routing epoch under locks; independently check baseline Qdrant parity outside the transaction. Recheck the same snapshot before atomically storing the generation, memberships, designation, pointer and receipt. Registration must not create a replacement, alter active search results or change the routing epoch. Existing baseline or concurrent import must not be silently overwritten.
- Keep product SHA, reviewed input hash and original path server-owned, with the existing mutation authorization and default-off gate. Add no caller-controlled trust material. Candidate proof, readiness and transition remain separate operations.
- Before any real switch, cover captured old/new source lookups including multi-page unit locators, compare new corpus coverage against the original (not merely reviewed excerpts), and verify a real isolated baseline/vector/lexical restore. Newer source evidence is not a blanket scope or penalty claim.

- [ ] One focused final suite and one stable full backend suite after integration; reuse exact-tree evidence across commit/push.
- [ ] Independent scoped review; fix only Critical/Important scope violations.
- [ ] Commit/push, official host status and deployment/health with exact artifact identity; Atlas and reservation remain unchanged.
- [ ] Actual source update only after all replacement-source gates pass. Then a distinct eligible development checkpoint if authorized; penalty applicability still requires its own direct proof before penalty → Difficult12 → holdout57 → full1003.

No task above is marked complete merely by the offline replay tests. This plan does not claim source-refresh eligibility or full quality completion.
