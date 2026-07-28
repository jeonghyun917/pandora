# Failed Proposition Presence Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Identify the first search stage at which every failed case loses its required proposition or condition, without exposing chunk bodies or weakening verification.

**Architecture:** The protected debug request carries bounded oracle alias groups. Java matches those aliases against complete chunk bodies and returns only group indexes and matched aliases per stage item. Existing Node retrieval metrics aggregate those matches and a pure report builder joins them to the authoritative evaluation failure categories.

**Tech Stack:** Java 17, Spring Boot records, JUnit 5, AssertJ, Node.js test runner, existing RAG debug and provenance scripts.

## Global Constraints

- Work only in `C:\dev\workspace-egov\pandora\.worktrees\rag-direct-evidence-recovery`.
- Preserve every existing modification; do not reset, revert, clean, or switch the shared workspace from `main`.
- Do not weaken Evidence Judge, answer verification, answer oracles, or fail-closed behavior.
- Do not expose complete chunk bodies in debug responses or report artifacts.
- Do not touch 18080. Restart only 8080 through repository scripts after full local verification.
- Treat literal non-match as "not confirmed", not proof that a semantic paraphrase is absent.

---

### Task 1: Add a bounded body-presence matcher

**Files:**
- Create: `src/main/java/com/kaces/pandora/ai/answer/RetrievalAuditTermMatcher.java`
- Create: `src/test/java/com/kaces/pandora/ai/answer/RetrievalAuditTermMatcherTests.java`

**Interfaces:**
- Consumes: `List<List<String>> auditTermGroups`, `String chunkText`
- Produces: `List<RetrievalAuditTermMatcher.GroupMatch> matchGroups(...)`

- [ ] **Step 1: Write RED tests**

Add tests proving that aliases are OR within a group, groups retain stable
zero-based indexes, normalization handles Korean punctuation and whitespace,
metadata outside `chunkText` cannot create a match, and oversized inputs throw
`IllegalArgumentException`.

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\mvnw.cmd -Dtest=RetrievalAuditTermMatcherTests test
```

Expected: compilation or assertion failure because the matcher does not exist.

- [ ] **Step 3: Implement the minimum matcher**

Normalize with NFKC, lowercase, and removal of punctuation, symbols, and
whitespace. Return only group index and the first matching configured alias.
Enforce 32 groups, 16 aliases per group, and 160 characters per alias.

- [ ] **Step 4: Verify GREEN**

Run the focused test above and require all tests to pass.

### Task 2: Expose match metadata through debug search

**Files:**
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiDebugRequest.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiDebugResponse.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`
- Modify: `src/test/java/com/kaces/pandora/ai/answer/LawAiDebugResponseItemTests.java`
- Test: `src/test/java/com/kaces/pandora/ai/answer/RetrievalAuditTermMatcherTests.java`

**Interfaces:**
- Consumes: optional JSON `auditTermGroups`
- Produces on each debug item: `matchedAuditGroupIndexes`,
  `matchedAuditAliases`

- [ ] **Step 1: Write RED response-contract tests**

Require both fields on `LawAiDebugResponse.Item` and verify that a chunk body
match is returned while title-only text is not.

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\mvnw.cmd -Dtest=LawAiDebugResponseItemTests,RetrievalAuditTermMatcherTests test
```

- [ ] **Step 3: Wire the matcher into debug serialization**

Pass the validated groups only from `debug(...)` to `toDebugResponse(...)`.
Do not store them in retrieval state and do not alter ranking, Judge, grounds,
or answer generation.

- [ ] **Step 4: Verify GREEN and self-review**

Run the focused tests. Review response compatibility, null/empty requests,
payload bounds, and absence of full chunk text.

### Task 3: Aggregate stage presence and failure categories

**Files:**
- Modify: `scripts/rag-retrieval-eval.js`
- Modify: `scripts/lib/rag-retrieval-metrics.js`
- Modify: `scripts/rag-retrieval-eval.test.js`
- Create: `scripts/lib/rag-failure-presence-report.js`
- Create: `scripts/rag-failure-presence-report.test.js`
- Create: `scripts/rag-failure-presence-report.js`

**Interfaces:**
- `measureRetrievalCase(evalCase, response, k)` adds `oraclePresence`
- `buildFailurePresenceReport(evaluationReport, retrievalReport)` returns a
  deterministic JSON-ready audit object

- [ ] **Step 1: Write RED Node tests**

Use hand-written fixtures to prove all-groups selected, downstream group loss,
partial candidate presence, no confirmed candidate presence, and explicit
`NO_EXPLICIT_ORACLE`. Add failure fixtures for unsupported answer, missing
answer, no grounds, forbidden evidence, and transient empty answer.

- [ ] **Step 2: Verify RED**

Run:

```powershell
node --test scripts/rag-retrieval-eval.test.js scripts/rag-failure-presence-report.test.js
```

- [ ] **Step 3: Implement minimum aggregation**

Send proposition groups followed by condition groups in debug requests.
Aggregate returned group indexes for candidate sources and every downstream
stage. Preserve the proposition/condition boundary and record the first stage
where completeness is lost.

- [ ] **Step 4: Implement deterministic report output**

Accept:

```text
node scripts/rag-failure-presence-report.js \
  --evaluation logs/rag-eval-gate-full-direct-policy-final-20260728.json \
  --retrieval logs/rag-retrieval-eval-failed86-proposition-20260728.json \
  --output logs/rag-failure-presence-audit-20260728.json
```

Write the JSON report and a Markdown sibling with counts and IDs by failure
category and presence classification.

- [ ] **Step 5: Verify GREEN and self-review**

Run the two focused Node test files and inspect mutations for off-by-one group
indexes, conflation of OR aliases with AND groups, and accidental use of
retrieval terms as answer propositions.

### Task 4: Full local verification and live audit

**Files:**
- Generated: `logs/rag-retrieval-eval-failed86-proposition-20260728.json`
- Generated: `logs/rag-retrieval-eval-failed86-proposition-20260728.md`
- Generated: `logs/rag-failure-presence-audit-20260728.json`
- Generated: `logs/rag-failure-presence-audit-20260728.md`

- [ ] Run every Node test under `scripts/*.test.js`.
- [ ] Run `.\mvnw.cmd test`.
- [ ] Run `git diff --check` and self-review only the new diagnostic slice.
- [ ] Package one JAR and record its SHA-256.
- [ ] Check runtime status, restart only 8080 with the official scripts, and
  confirm 18080 state is unchanged.
- [ ] Re-run the 86 failed IDs at K=30 with stable runtime/index provenance.
- [ ] Build the joined failure-presence report and manually inspect at least
  one representative from every classification.

### Task 5: Select the first generalized repair slice

- [ ] Within the 48 unsupported-answer cases, choose the largest group sharing
  the same confirmed first-loss boundary.
- [ ] If the required groups are already selected, inspect generation, atomic
  repair, and final verification; do not change retrieval.
- [ ] If groups enter candidates but disappear before selection, inspect only
  the first losing component.
- [ ] If groups are not confirmed in candidates, inspect index/document
  presence before adding search expansion.
- [ ] Start a fresh RED-GREEN cycle for that one root cause, then run focused
  tests, self-review, full Maven/Node tests, and the representative evaluation
  cases before continuing to the 19 and 17 groups.

