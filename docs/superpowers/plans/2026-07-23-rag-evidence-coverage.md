# RAG Evidence Coverage Measurement and Atom Priority Implementation Plan

> **For Codex:** Execute this plan task by task with systematic debugging,
> test-driven development, and verification-before-completion. Do not change
> 18080.

**Goal:** Measure where the 85 explicit answer-oracle cases lose required
proposition and condition evidence, then apply only the smallest production
priority fix justified by that measurement.

**Architecture:** A protected debug-only request flag exposes the complete
matched-child text to the retrieval evaluator. A Node metrics module measures
each oracle AND-group independently at every retrieval stage and joins
runtime-compatible answer-eval results for supported-evidence and verified-answer
coverage. Production ranking is changed only if the report proves supported,
aligned evidence is lost at the repair atom boundary.

**Tech Stack:** Java 17, Spring Boot, Jackson, JUnit 5/AssertJ, Node.js built-in
test runner, PowerShell runtime scripts.

---

## Task 1: Expose complete child text only for measurement requests

**Files:**

- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiDebugRequest.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiDebugResponse.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`
- Test: `src/test/java/com/kaces/pandora/ai/answer/LawAiDebugResponseItemTests.java`
- Test: `src/test/java/com/kaces/pandora/ai/answer/LawAiAnswerServiceEvidenceGateTests.java`

1. Add failing tests that the request flag defaults to false and that debug
   items omit matched-child text unless the flag is true.
2. Run:
   `.\mvnw.cmd -Dtest=LawAiDebugResponseItemTests,LawAiAnswerServiceEvidenceGateTests test`
   and confirm the new tests fail for the intended missing contract.
3. Add nullable `matchedChildText` to the protected debug item, pass the flag
   through `toDebugResponse` and `toDebugItems`, and populate it from
   `LawSemanticChunkRow.chunkText()` only when explicitly requested.
4. Rerun the focused tests and confirm all pass.
5. Review serialization size, null/omission behavior, and the default UI request.
6. Commit: `feat: expose matched child text for RAG measurement`

## Task 2: Implement explicit oracle matching for measurement

**Files:**

- Create: `scripts/lib/rag-explicit-oracle-matcher.js`
- Create: `scripts/rag-evidence-coverage.test.js`

1. Add failing Node tests for:
   - OR aliases inside an AND group;
   - all material tokens in one text;
   - tokens split across separate items do not count;
   - positive and negative polarity do not satisfy each other;
   - Korean punctuation and spacing normalization.
2. Run `node --test scripts/rag-evidence-coverage.test.js` and confirm RED.
3. Implement the smallest evaluation-only matcher satisfying those tests.
4. Rerun the focused Node test and confirm GREEN.
5. Compare representative fixtures with `AnswerOracleMatcherTests`.
6. Commit: `test: add explicit oracle coverage matcher`

## Task 3: Measure oracle-group survival at every retrieval stage

**Files:**

- Create: `scripts/lib/rag-evidence-coverage.js`
- Modify: `scripts/lib/rag-retrieval-metrics.js`
- Modify: `scripts/rag-evidence-coverage.test.js`

1. Add failing tests for proposition and condition group coverage at
   `candidateSources`, `merged`, `reranked`, `intentFiltered`,
   `judgeCandidates`, `judged`, and `selected`.
2. Add failing tests for the first loss stage and a group absent from the first
   candidate sources.
3. Run `node --test scripts/rag-evidence-coverage.test.js` and confirm RED.
4. Implement per-item coverage, candidate-source union deduplication, stable
   group identifiers, first-loss classification, and aggregate summaries.
5. Rerun the focused Node tests and confirm GREEN.
6. Review that no cross-item token concatenation is possible.
7. Commit: `feat: measure retrieval evidence coverage by stage`

## Task 4: Join supported evidence and verified answer coverage safely

**Files:**

- Modify: `scripts/lib/rag-evidence-coverage.js`
- Modify: `scripts/rag-retrieval-eval.js`
- Modify: `scripts/rag-retrieval-eval.test.js`
- Modify: `scripts/rag-evidence-coverage.test.js`

1. Add failing tests for `--answer-eval`, debug request
   `includeMatchedChildText: true`, supported-evidence extraction, verified
   answer measurement, and provenance mismatch rejection.
2. Run:
   `node --test scripts/rag-retrieval-eval.test.js scripts/rag-evidence-coverage.test.js`
   and confirm RED.
3. Add the answer-eval option and join results by case ID. Compare base URL,
   runtime artifact SHA-256, runtime instance ID, index revision, dataset hash,
   and selection hash before joining.
4. Extend JSON and Markdown output with stage coverage, first-loss counts, and
   case-level missing group details.
5. Keep stages 8 and 9 explicitly `not_measured` when no answer-eval is passed.
6. Rerun both Node test files and confirm GREEN.
7. Commit: `feat: report end-to-end RAG evidence coverage`

## Task 5: Build and collect the 85-case diagnosis

**Files:**

- Output: `logs/rag-evidence-coverage-85-20260723.json`
- Output: `logs/rag-evidence-coverage-85-20260723.md`
- Output: a new targeted answer-eval artifact under `logs/`

1. Run focused Java and Node tests once more.
2. Run `scripts/status-pandora.ps1` and record 8080, 18080, and 6333 identities.
3. Build the worktree jar with `.\mvnw.cmd -DskipTests package`.
4. Verify the jar SHA-256 and restart only 8080 through the official
   `scripts/stop-pandora.ps1` / `scripts/start-pandora.ps1` contract.
5. Confirm 18080 PID and batch artifact SHA-256 are unchanged.
6. Run the targeted 85 answer gate against the new 8080 and require zero request
   errors and stable runtime provenance.
7. Run the retrieval evaluator for the same 85 IDs with the answer-eval artifact
   and `K=10`.
8. Review the report and classify the dominant first-loss boundary.

## Task 6: Apply only the measurement-justified generalized fix

**Files:** Determined by Task 5.

1. If evidence is absent from `candidateSources`, stop production changes and
   document retrieval recall as the next task.
2. If evidence is lost mainly at `intentFiltered` or `judged`, write one focused
   failing regression test for the dominant generalized rule, implement the
   smallest filter/judge fix, and rerun Task 5.
3. If evidence survives through `selected`/`supportedEvidence` but is lost at
   repair selection, add failing tests in
   `GroundedAnswerRepairServiceTests.java` proving:
   - a more directly question-aligned supported atom enters the six-atom limit;
   - unsupported or misaligned atoms remain excluded;
   - ties keep ground/source order.
4. Implement a stable priority score based only on question subject, relation,
   condition, and atomicity signals after individual verification.
5. Run:
   `.\mvnw.cmd -Dtest=GroundedAnswerRepairServiceTests,AnswerQuestionAlignmentVerifierTests test`
   and confirm GREEN.
6. Commit the minimal production fix separately from measurement tooling.

## Task 7: Self-review and complete verification

**Files:**

- Modify: `docs/rag-quality-handoff-20260723-evidence-coverage.md`
- Update generated log/report artifacts as appropriate.

1. Inspect `git diff --check`, `git diff --stat`, and every changed hunk.
2. Check explicitly for oracle leakage, case-ID special casing, safety-gate
   weakening, unbounded debug payloads, provenance bypass, and 18080 changes.
3. Run all Node RAG tests relevant to retrieval and evaluation.
4. Run `.\mvnw.cmd test`.
5. If Task 6 changed runtime behavior, rebuild/restart only 8080 and rerun the
   targeted 85 answer gate plus coverage report.
6. Record exact pass/fail counts, proposition/condition stage coverage, unsafe
   claim counts, runtime identity, index revision, and commands not run.
7. Commit: `docs: record RAG evidence coverage verification`

## Task 8: Merge verified work without moving the shared workspace

1. Confirm the shared workspace is still clean and on `main`.
2. From the shared workspace, merge `codex/rag-evidence-coverage` into `main`
   without switching the shared workspace.
3. Confirm `main` status and log.
4. Confirm 18080 remains unchanged and report whether the full 1,004-case gate
   remains pending.
