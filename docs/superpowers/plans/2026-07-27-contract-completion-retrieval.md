# Contract Completion Retrieval and Administrative-Rule Chunking Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Do not use parallel agents because the parser, index, and evaluation steps share state.

**Goal:** Make Pandora reliably recover the current legal and administrative-rule grounds for contract-completion questions while preserving fail-closed answers when those grounds do not directly decide whether early submission is allowed.

**Architecture:** First reproduce the exact stage loss with immutable runtime provenance. Then preserve article boundaries during administrative-rule parsing, add normalized title/document diagnostics, and only if the replay proves it necessary add a configurable contract-completion concept bridge. Retrieval success and answerability remain separate gates: procedure evidence may be supporting evidence without proving a categorical yes/no answer.

**Tech Stack:** Java 17, Spring Boot, MyBatis, MariaDB, Qdrant, Node.js evaluation scripts, JUnit 5, AssertJ.

## Confirmed Baseline

- The recorded failure for `과업지시서 용역기간이 안끝났는데 결과보고해도 되나?` reached 100 Qdrant hits and 93 merged/ranked candidates, but produced zero judged/direct grounds and failed closed as `JUDGE_NO_DIRECT_EVIDENCE`.
- Qdrant contains a current point for `국가를 당사자로 하는 계약에 관한 법률 시행령` Article 55. Therefore the earlier conclusion “the law is absent” was not supported.
- Qdrant contains the current document `(계약예규) 용역계약일반조건`, but its 20 stored points are labeled `제1조 (1/19)` through `제1조 (19/19)` plus `부칙`. Article 27 is not represented as an independently addressable article.
- `LawOpenApiPayloadParser` currently handles `AdmRulService.조문내용` as one generic long-text field, and `LawSemanticChunkPlanner` propagates the first inferred heading to its size-based children.
- The current search plan has generic `period` and `procedure` concepts but no maintained contract-completion bridge from `결과보고` to `완료통지`, `검사`, `대가지급`, and related concepts.
- The fail-closed result is correct safety behavior. The defect is that the pipeline cannot yet distinguish “relevant procedure recovered but not decisive” from “no relevant direct ground recovered.”

## Global Constraints

- Keep `C:\dev\workspace-egov\pandora` on `main`; implement in the existing isolated `codex/rag-direct-evidence-recovery` worktree.
- Preserve all pre-existing changes. Do not reset, revert, clean, or move another worktree.
- Do not stop, restart, promote, or otherwise mutate port 18080.
- Use repository runtime scripts and restart only 8080 when runtime verification is reached.
- Do not hard-code the exact question or make Article 55/27 unconditional answer authorities.
- Prefer the current effective document version while retaining version provenance.
- Every code task follows: cause analysis, RED test, minimal generalized fix, GREEN focused test, self-review, full test.

---

### Task 1: Reproduce the exact pipeline loss

**Files:**
- Read: `scripts/status-pandora.ps1`
- Read: `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`
- Write evidence only: `logs/contract-completion-debug-20260727.json`

- [ ] Run `scripts/status-pandora.ps1` and record the 8080 artifact SHA, configuration SHA, instance ID, Qdrant collection revisions, and active index revision.
- [ ] Re-run only the exact question through the search/debug path. This step sends the question to the configured OpenAI embedding model; do not call the answer model unless search-stage evidence is insufficient to diagnose the loss.
- [ ] Capture candidate IDs and ranks at vector, lexical, merged, reranked, topic-aligned, Judge-candidate, judged, selected-direct, and selected-supporting stages.
- [ ] Explicitly check for the current Article 55 point and every point belonging to the current `(계약예규) 용역계약일반조건` document.
- [ ] Classify the first actual loss boundary as exactly one of: candidate retrieval, reranking/topic alignment, Judge relation, ground selection, or final answer verification.
- [ ] Do not modify production code until this classification is backed by captured stage data.

**Decision rule:** Task 3 is required regardless because the administrative-rule article structure is demonstrably lost. Task 4 is performed only if the correct completion concepts/articles are absent before Judge. Task 5 is performed only if correct article candidates reach Judge but are rejected or incorrectly treated as decisive.

---

### Task 2: Make document-presence diagnostics title- and version-aware

**Files:**
- Create: `scripts/rag-document-presence-audit.js`
- Create: `scripts/rag-document-presence-audit.test.js`
- Modify only if reusable code is justified: `scripts/lib/rag-eval-cases.js`

- [ ] Write RED tests for canonical-title matching:
  - `국가계약법 시행령` resolves to `국가를 당사자로 하는 계약에 관한 법률 시행령`.
  - `용역계약일반조건` matches `(계약예규) 용역계약일반조건`.
  - Similar but different titles do not collapse into one identity.
- [ ] Make the audit report separate rows for DB document presence, DB chunk/article presence, Qdrant point presence, current/past status, source date, and title match mode.
- [ ] Keep exact match, canonical match, and free-text match visibly distinct; never infer “document absent” from one failed exact-title filter.
- [ ] Run `node --test scripts/rag-document-presence-audit.test.js`.
- [ ] Self-review normalization for false aliases and version ambiguity.
- [ ] Run all Node tests under `scripts/*.test.js`.

---

### Task 3: Preserve administrative-rule article boundaries

**Files:**
- Modify: `src/main/java/com/kaces/pandora/lawdata/sync/LawOpenApiPayloadParser.java`
- Modify: `src/main/java/com/kaces/pandora/lawdata/sync/LawSemanticChunkPlanner.java`
- Test: `src/test/java/com/kaces/pandora/lawdata/sync/LawOpenApiPayloadParserTests.java`
- Test: `src/test/java/com/kaces/pandora/lawdata/sync/LawSemanticChunkPlannerTests.java`

- [ ] Add a RED parser fixture whose `AdmRulService.조문내용` contains Articles 1, 20, and 27, including an inline cross-reference to Article 27 that must not start a new section.
- [ ] Assert that line-start legal headings produce separate `SyncDetailSection` values with stable article number, heading, body, source path, and source order.
- [ ] Add a RED planner test proving that a long Article 27 may split by size but every child retains Article 27 identity; no Article 20 or 27 child may inherit Article 1.
- [ ] Implement a bounded administrative-rule article splitter invoked only for the known `조문내용` payload shape. Match structural headings at line/block boundaries, preserve preamble and appendix/부칙 sections, and fall back to existing generic text handling when no reliable boundary exists.
- [ ] Keep paragraph text and cross-references inside their owning article.
- [ ] Run:
  - `.\mvnw.cmd -Dtest=LawOpenApiPayloadParserTests,LawSemanticChunkPlannerTests test`
  - all `lawdata.sync` tests.
- [ ] Self-review for false splits, lost text, reordered text, duplicate text, source-path stability, and oversized articles.
- [ ] Run `.\mvnw.cmd test`.

---

### Task 4: Add a generalized contract-completion concept bridge only if replay requires it

**Files:**
- Modify: `src/main/java/com/kaces/pandora/common/text/QuestionSearchPlan.java`
- Modify: the maintained intent/entity dictionary used by `QuestionSearchPlan`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`
- Test: `src/test/java/com/kaces/pandora/common/text/QuestionSearchPlanTests.java`
- Test: the focused direct-evidence recovery test class covering `findDirectEvidenceFallbackChunks`

- [ ] Add RED tests showing that a contract-completion question with `용역/과업` plus `결과보고/완료보고` produces bounded groups for completion notice, inspection/acceptance, payment, service period, and delay consequences.
- [ ] Add negative controls proving that a generic non-contract “결과 보고” question does not receive contract-law expansion.
- [ ] Store the bridge as maintained concept data, not an exact-question branch.
- [ ] Use concepts as retrieval hints only. Do not require a fixed title or article number and do not let hints bypass ordinary reranking, Judge, or current-version preference.
- [ ] Run focused common-text and answer-service tests.
- [ ] Self-review expansion breadth, candidate explosion, and contamination of unrelated domains.
- [ ] Run `.\mvnw.cmd test`.

---

### Task 5: Separate procedural relevance from categorical answerability

**Files:**
- Modify only if Task 1 proves Judge loss: `src/main/java/com/kaces/pandora/ai/answer/EvidenceJudge.java`
- Modify only if needed: `src/main/java/com/kaces/pandora/ai/answer/ClaimEvidenceMatcher.java`
- Test: `src/test/java/com/kaces/pandora/ai/answer/EvidenceJudgeTests.java`
- Test: the matching `ClaimEvidenceMatcher` test class
- Test: `src/test/java/com/kaces/pandora/ai/answer/LawAiAnswerServiceEvidenceGateTests.java`

- [ ] Add atomic evidence fixtures for:
  - completion notice followed by inspection;
  - payment claim after completion and inspection;
  - a contract/task clause that expressly permits or prohibits early final-report submission.
- [ ] Assert that the first two are relevant procedural/supporting evidence but do not alone support “기간 전 제출 가능” or “기간 전 제출 금지.”
- [ ] Assert that only an express clause or evidence of actual completion plus applicable contract terms may support a categorical conclusion.
- [ ] Make the minimum generalized role/relation correction at the first proven failing boundary.
- [ ] Keep whole-answer final verification and `JUDGE_NO_DIRECT_EVIDENCE` fail-closed behavior unchanged.
- [ ] Run focused Judge, matcher, and evidence-gate tests; self-review both unsafe acceptance and excessive refusal.
- [ ] Run `.\mvnw.cmd test`.

---

### Task 6: Preview and rebuild only the affected current document

**Files:**
- Use: the existing rebuild-preview and document-ID-scoped rebuild APIs/scripts
- Record: `logs/contract-general-conditions-rebuild-preview-20260727.json`

- [ ] Identify the current DB document ID by canonical title plus current source date; do not rely on a hard-coded ID from another runtime.
- [ ] Run document-ID-scoped rebuild preview and compare:
  - total source characters before/after;
  - article numbers and headings;
  - duplicate/lost text;
  - maximum chunk size;
  - current/past version identity.
- [ ] Require Articles 20 and 27 to be independently addressable in the preview before applying.
- [ ] Apply rebuild and re-index only that current document through the existing replacement path; verify stale point removal and current point insertion.
- [ ] Do not mutate unrelated documents or 18080.
- [ ] Re-run the presence audit and exact debug replay against the new index revision.

---

### Task 7: Add retrieval and safe-answer regressions

**Files:**
- Modify: `src/main/resources/rag-evaluation-cases.tsv`
- Modify: `src/main/resources/rag-answer-evaluation-oracles.tsv`
- Test: `src/test/java/com/kaces/pandora/ai/answer/LawAiEvaluationCaseCatalogTests.java`
- Test: `scripts/rag-retrieval-eval.test.js`

- [ ] Add the exact question and these paraphrase/control cases:
  - `용역기간이 끝나기 전에 결과보고서를 제출해도 되나?`
  - `과업을 모두 마쳤으면 계약기간 전 완료보고가 가능한가?`
  - `업무가 남아 있는데 결과보고서만 먼저 내도 되나?`
- [ ] Retrieval oracles must prefer the current full-title law and current contract-general-conditions document, and require completion/inspection/payment or delay-related terms appropriate to each question.
- [ ] Answer oracles must require the confirmed procedural sequence and the condition that the contract/task terms and actual completion status control early submission.
- [ ] Forbid unconditional `가능하다`, `금지된다`, and invented deadlines unless directly supported by selected evidence.
- [ ] Run catalog and Node parser/evaluation tests.
- [ ] Run the three new cases, then the difficult 12-case set, then all 85 answer-verification cases twice.
- [ ] Only after stable runtime provenance and zero unsafe control answers, run the complete 1,004-case gate.
- [ ] Report retrieval recall, first loss stage, safe-answer pass rate, refusal count, and overall score separately.

---

### Task 8: Final verification and review

- [ ] Review the complete diff for question-specific branches, title over-normalization, stale-version leakage, article-text loss, duplicate indexing, and 18080 isolation.
- [ ] Run `.\mvnw.cmd test` and all Node tests.
- [ ] Package one main jar, verify its SHA-256, and deploy/restart only 8080 using repository scripts.
- [ ] Verify runtime artifact/config/index provenance matches the evaluated runtime.
- [ ] Repeat the exact live question and inspect selected grounds and answer wording, not only the pass flag.
- [ ] Record residual risks: contract-specific special conditions may still be unavailable, and Articles 55/27 may establish procedure without deciding every early-submission scenario.
