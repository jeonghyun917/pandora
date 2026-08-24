# Document Expansion Evaluation Integrity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct the document-expansion promotion comparator, persist structured expansion outcomes, and support strict multi-token Korean document-title variants.

**Architecture:** The debug API exposes the already-computed expansion status and bounded reasons. The evaluator measures both control and shadow from top-K fused arrays, while retaining source-union metrics only for diagnosis. Strong anchors remain mandatory; multi-word explicit titles become an AND-list of normalized terms consumed by the existing bounded SQL and Java selectors.

**Tech Stack:** Java 17, Spring Boot records, Node.js built-in test runner, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-24-document-expansion-evaluation-integrity-design.md`

## Global Constraints

- Work only in `C:\dev\workspace-egov\pandora\.worktrees\document-first-candidate-expansion` on `codex/document-first-candidate-expansion`.
- Keep `law-ai.retrieval.document-expansion.authoritative=false`.
- Do not call OpenAI, Qdrant, or mutate MariaDB.
- Never touch port `18080` or `output/`.
- Preserve the existing `3/8/24` document-expansion bounds and fail-closed behavior.

---

### Task 1: Like-for-like fused evaluation and structured outcomes

**Files:**
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiDebugResponse.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`
- Modify: `src/test/java/com/kaces/pandora/ai/answer/LawAiAnswerServiceDocumentExpansionTests.java`
- Modify: `scripts/rag-retrieval-eval.js`
- Modify: `scripts/rag-retrieval-eval.test.js`
- Modify: `scripts/lib/document-expansion-selection.js`
- Modify: `scripts/document-expansion-selection.test.js`

**Interfaces:**
- Debug response produces `documentExpansionStatus: String` and `documentExpansionReasonCodes: List<String>`.
- Per-case evaluation produces `controlFusedPresence`, `candidateSourcePresence`, `expansionSourcePresence`, and `shadowFusedPresence`.
- The selector summarizes `controlFusedPresence` as its control and rejects captures that omit it.

- [x] **Step 1: Write failing Node and Java tests**

Add a Node fixture where the source union contains groups `[0,1]` but both `fused` and `documentExpansionFused` contain only `[0]`. Assert control and shadow are identical, `firstDropStage` is `controlFused`, and no `BASELINE_REGRESSION` is reported. Add Java assertions for the structured status and reason fields.

- [x] **Step 2: Run tests and verify RED**

```powershell
node --test .\scripts\rag-retrieval-eval.test.js .\scripts\document-expansion-selection.test.js
.\mvnw.cmd -Dtest=LawAiAnswerServiceDocumentExpansionTests test
```

Expected: missing `controlFusedPresence` and missing structured debug fields.

- [x] **Step 3: Implement minimal debug and evaluator changes**

Pass `options.k` to `measureDocumentExpansionCase`, use `topK(response.fused, k)` and `topK(response.documentExpansionFused, k)`, and persist the validated status/reason values. Keep source metrics separate. Set first drop with this order: `candidateSources`, `controlFused`, actual `documentExpansionFused` regression, otherwise `null`.

- [x] **Step 4: Implement selector fail-closed semantics**

Summarize `controlFusedPresence`; do not silently fall back to the old source-union `control`. Preserve all existing immutable provenance and quality floors.

- [x] **Step 5: Run focused tests and verify GREEN**

Run the commands from Step 2 and require zero failures.

---

### Task 2: Strict multi-token explicit document titles

**Files:**
- Modify: `src/main/java/com/kaces/pandora/common/text/DocumentSearchAnchorExtractor.java`
- Modify: `src/test/java/com/kaces/pandora/common/text/DocumentSearchAnchorExtractorTests.java`
- Modify: `src/test/java/com/kaces/pandora/semantic/retrieval/DocumentCandidateExpansionTests.java`

**Interfaces:**
- `DocumentSearchAnchor.titleTerms()` returns ordered distinct words for multi-word explicit titles and the existing single value for compact titles.
- `DocumentCandidateExpansion.selectDocuments(...)` continues to require every returned title term.

- [x] **Step 1: Write failing extraction and selection tests**

Assert `인공지능 데이터 기반 행정 활성화 법` yields exactly `인공지능`, `데이터`, `기반`, `행정`, `활성화`, `법`, and matches the formal title `인공지능 및 데이터 기반 행정 활성화에 관한 법률`. Assert a title missing any substantive term is rejected.

- [x] **Step 2: Run tests and verify RED**

```powershell
.\mvnw.cmd -Dtest=DocumentSearchAnchorExtractorTests,DocumentCandidateExpansionTests test
```

Expected: the extractor returns one phrase and the formal-title selection is `DOCUMENT_NOT_FOUND`.

- [x] **Step 3: Implement minimal ordered tokenization**

Split only explicit title phrases containing whitespace, normalize/distinct each token, retain at most six terms, and keep the strong suffix-derived anchor requirement. Do not add aliases or case-specific text.

- [x] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2 and require zero failures.

---

### Task 3: Integration verification

**Files:**
- Modify only if needed: `docs/superpowers/specs/2026-08-24-document-first-candidate-expansion-verification.md`

- [x] **Step 1: Run relevant Node tests**

```powershell
node --test .\scripts\rag-retrieval-eval.test.js .\scripts\document-expansion-selection.test.js .\scripts\coverage-aware-selection.test.js .\scripts\rrf-weight-selection.test.js .\scripts\rag-eval-provenance.test.js
```

- [x] **Step 2: Run focused backend tests**

```powershell
.\mvnw.cmd -Dtest=DocumentSearchAnchorExtractorTests,DocumentCandidateExpansionTests,LawAiAnswerServiceDocumentExpansionTests,LawAiRuntimeInfoTests test
```

- [x] **Step 3: Run the complete backend suite once**

```powershell
.\mvnw.cmd test
```

- [x] **Step 4: Verify repository invariants**

```powershell
git diff --check
git status --short --branch
```

Expected: zero test failures, no whitespace errors, and only intended branch changes. No external evaluation is executed.

## Execution evidence

- Relevant Node suite: 113 passed, 0 failed.
- Focused backend suite: 49 passed, 0 failed.
- Complete backend suite: 1,303 passed, 0 failed, 18 skipped integration tests.
- `git diff --check`: no whitespace errors.
- No OpenAI/Qdrant/MariaDB mutation, port 18080 action, or external evaluation was performed.
