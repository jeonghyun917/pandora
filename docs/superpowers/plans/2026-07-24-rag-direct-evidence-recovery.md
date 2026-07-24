# RAG Direct Evidence Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace timeout-prone RAG lexical scans with normalized indexed lookup and recover direct evidence before answer generation.

**Architecture:** Query and chunk text share a Korean lexical-term normalizer. RAG chunks are mapped to an indexed term table, and a single bounded recovery pass runs when the initial Judge result has no direct evidence. Evidence roles remain explicit and Claim Verifier stays strict.

**Tech Stack:** Java 17, Spring Boot, MyBatis, MariaDB, React, JUnit 5, AssertJ.

## Global Constraints

- Keep the shared workspace on `main`; implement in an isolated worktree.
- Do not stop or restart port 18080.
- Fail closed when direct evidence remains absent.
- Do not rechunk or re-embed documents.

---

### Task 1: Query lexical normalization

**Files:**
- Modify: `src/main/java/com/kaces/pandora/common/text/KoreanQueryNormalizer.java`
- Modify: `src/main/java/com/kaces/pandora/common/text/QuestionSearchPlan.java`
- Test: `src/test/java/com/kaces/pandora/common/text/KoreanQueryNormalizerTests.java`

**Interfaces:**
- Produces: `normalizeQueryTerm(String)` returning `개인정보` for `개인정보라고`.
- Produces: lexical plans that omit standalone `만으로`.

- [ ] Write tests for `개인정보라고 -> 개인정보` and omission of `만으로`.
- [ ] Run the focused test and confirm both new assertions fail for the expected reason.
- [ ] Add generalized predicate-particle stripping and low-information filtering.
- [ ] Run the focused test and all common-text tests.
- [ ] Review for over-stripping compound nouns and preserve existing cases.

### Task 2: Normalized RAG lexical index

**Files:**
- Modify: `src/main/resources/schema.sql`
- Create: `src/main/java/com/kaces/pandora/rag/search/RagLexicalTermExtractor.java`
- Create: `src/main/java/com/kaces/pandora/rag/search/RagLexicalIndexService.java`
- Modify: `src/main/java/com/kaces/pandora/rag/persistence/RagDocumentMapper.java`
- Modify: `src/main/resources/mapper/law/RagDocumentMapper.xml`
- Modify: `src/main/java/com/kaces/pandora/rag/importing/RagImportService.java`
- Test: `src/test/java/com/kaces/pandora/rag/search/RagLexicalTermExtractorTests.java`
- Test: `src/test/java/com/kaces/pandora/rag/search/RagLexicalIndexServiceTests.java`

**Interfaces:**
- Produces: `extract(String title, String section, String text)` returning weighted normalized terms.
- Produces: `findSemanticChunksBySearchTerms(documentTypes, terms, limit)`.
- Consumes: retained V4 chunks after the quality gate.

- [ ] Write failing extractor tests for Korean terms, deduplication, and field weights.
- [ ] Write a failing mapper/service test proving indexed `이메일 + 개인정보` retrieval.
- [ ] Add `rag_chunk_search_terms` and its B-tree indexes.
- [ ] Implement incremental replace-by-chunk indexing and indexed retrieval.
- [ ] Integrate index updates into chunk import after retained chunks are inserted.
- [ ] Add an idempotent bounded backfill method and verify repeat execution.
- [ ] Run focused persistence/import tests and inspect query plans for index use.

### Task 3: Direct-evidence recovery

**Files:**
- Create: `src/main/java/com/kaces/pandora/ai/answer/DirectEvidenceRecoveryService.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`
- Test: `src/test/java/com/kaces/pandora/ai/answer/DirectEvidenceRecoveryServiceTests.java`
- Test: `src/test/java/com/kaces/pandora/ai/answer/LawAiAnswerServiceEvidenceGateTests.java`

**Interfaces:**
- Consumes: normalized search plan, targets, initial selection diagnostics.
- Produces: zero or more candidates for one additional normal Judge pass.

- [ ] Write a failing test that recovery runs when direct count is zero.
- [ ] Write a failing test that recovery does not run when a direct ground exists.
- [ ] Write a failing test that only one recovery attempt is permitted.
- [ ] Implement bounded indexed recovery and reuse existing rerank/Judge/ground policies.
- [ ] Confirm no answer generation occurs after a failed recovery.
- [ ] Run focused answer-service tests and review timing/failure diagnostics.

### Task 4: Evidence-role response and UI

**Files:**
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerGround.java`
- Modify: `frontend/src/components/LawSearchPage.jsx`
- Modify: `frontend/src/styles.css`
- Test: `src/test/java/com/kaces/pandora/ai/answer/LawAiAnswerGroundTests.java`

**Interfaces:**
- Produces: `evidenceRole` values `direct`, `supporting`, or `related_definition`.

- [ ] Write a failing response-model test for `related_definition`.
- [ ] Populate evidence roles from selection diagnostics.
- [ ] Render `관련 정의` distinctly without numbering it as confirmed direct evidence.
- [ ] Run backend focused tests and the frontend production build.

### Task 5: PDF filename header

**Files:**
- Modify: the preview controller/service found by the failing controller test.
- Test: matching controller test under `src/test/java/com/kaces/pandora/rag/preview`.

**Interfaces:**
- Produces: valid `Content-Disposition` with ASCII fallback plus UTF-8 filename.

- [ ] Write a failing MockMvc test using a Korean PDF filename.
- [ ] Implement standards-compliant content-disposition construction.
- [ ] Run the controller test and confirm Tomcat no longer removes the header.

### Task 6: Regression evaluation and runtime verification

**Files:**
- Modify: the existing RAG evaluation dataset containing manual regression cases.

**Interfaces:**
- Adds: question `이메일 만으로도 개인정보라고 볼수있나?`.
- Expects: an official direct ground containing email address and personal-information classification; categorical overstatement remains forbidden.

- [ ] Add the regression case and confirm it fails before indexed backfill.
- [ ] Run the bounded lexical backfill for active RAG chunks.
- [ ] Run focused tests, `.\mvnw.cmd test`, frontend build, and targeted RAG evaluation.
- [ ] Package the jar and restart only port 8080 with repository scripts.
- [ ] Submit the real question and verify direct evidence, answer wording, timing, and PDF preview.
- [ ] Review the final diff for security, performance, maintainability, duplication, and 18080 isolation.
