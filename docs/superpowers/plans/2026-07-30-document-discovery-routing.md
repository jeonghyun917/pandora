# Document Discovery Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route short related-law/document searches to a deterministic, source-prioritized document discovery response without weakening legal-answer verification.

**Architecture:** `QuestionIntentProfile` identifies discovery queries while preserving retrieval expansion. A focused `DocumentDiscoveryPolicy` supplies source preference and ground ordering, and `DocumentDiscoveryAnswerComposer` emits metadata-only answers. `LawAiAnswerService` uses the route before LLM answer generation and proposition verification.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, AssertJ, Mockito, Maven

## Global Constraints

- Preserve all existing dirty worktree changes.
- Keep the shared workspace on `main`.
- Do not stop or restart port 18080.
- Continue to fail closed when no grounds are selected.
- Do not add CCTV-question-specific answer branches.

---

### Task 1: Discovery intent classification

**Files:**
- Modify: `src/main/java/com/kaces/pandora/common/text/QuestionIntentProfile.java`
- Test: `src/test/java/com/kaces/pandora/common/text/QuestionIntentProfileDocumentLookupTests.java`

**Interfaces:**
- Produces: `QuestionIntentProfile.documentDiscoveryQuestion(): boolean`
- Preserves: entity aliases, focused keywords, and preferred targets

- [ ] **Step 1: Write failing positive and negative classification tests**

```java
assertThat(QuestionIntentProfile.from("CCTV 관련 법령").documentDiscoveryQuestion()).isTrue();
assertThat(QuestionIntentProfile.from("CCTV 관련 법령상 설치 조건은?").documentDiscoveryQuestion()).isFalse();
```

- [ ] **Step 2: Run the focused test and confirm compilation/test failure**

Run: `.\mvnw.cmd -Dtest=QuestionIntentProfileDocumentLookupTests test`

- [ ] **Step 3: Implement the minimal noun-phrase classifier**

Recognize a non-empty topic followed by a supported document-source noun and
an empty or lookup-only suffix. Keep existing document identity behavior
separate and suppress proposition-only intent requirements for discovery.

- [ ] **Step 4: Run the focused test and confirm it passes**

Run: `.\mvnw.cmd -Dtest=QuestionIntentProfileDocumentLookupTests test`

### Task 2: Source preference and metadata-only composition

**Files:**
- Create: `src/main/java/com/kaces/pandora/ai/answer/DocumentDiscoveryPolicy.java`
- Create: `src/main/java/com/kaces/pandora/ai/answer/DocumentDiscoveryAnswerComposer.java`
- Create: `src/test/java/com/kaces/pandora/ai/answer/DocumentDiscoveryPolicyTests.java`
- Create: `src/test/java/com/kaces/pandora/ai/answer/DocumentDiscoveryAnswerComposerTests.java`

**Interfaces:**
- Produces: `DocumentDiscoveryPolicy.scoreBoost(String, String): double`
- Produces: `DocumentDiscoveryPolicy.orderGrounds(String, List<LawAiAnswerGround>): List<LawAiAnswerGround>`
- Produces: `DocumentDiscoveryAnswerComposer.compose(String, List<LawAiAnswerGround>): String`

- [ ] **Step 1: Write failing source-ordering, renumbering, deduplication, and negative-control tests**

```java
assertThat(DocumentDiscoveryPolicy.orderGrounds("CCTV 관련 법령", grounds))
    .extracting(LawAiAnswerGround::target)
    .containsExactly("law", "admrul", "official_doc");
```

- [ ] **Step 2: Run both tests and confirm compilation failure**

Run: `.\mvnw.cmd -Dtest=DocumentDiscoveryPolicyTests,DocumentDiscoveryAnswerComposerTests test`

- [ ] **Step 3: Implement bounded source priority and deterministic composition**

Sort by requested source type, then existing score and order. Renumber copied
grounds. Deduplicate composer entries by document id or normalized title and
emit only selected-ground metadata.

- [ ] **Step 4: Run both tests and confirm they pass**

Run: `.\mvnw.cmd -Dtest=DocumentDiscoveryPolicyTests,DocumentDiscoveryAnswerComposerTests test`

### Task 3: Pipeline routing

**Files:**
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`
- Test: `src/test/java/com/kaces/pandora/ai/answer/LawAiAnswerServiceClaimOutcomeTests.java`

**Interfaces:**
- Consumes: the Task 1 profile classifier and Task 2 policy/composer
- Produces: identical deterministic behavior for synchronous and streaming endpoints

- [ ] **Step 1: Write failing service tests**

```java
LawAiAnswerResponse response = service.answer(discoveryRequest());
assertThat(response.resultMsg()).isEqualTo("OK");
verifyNoInteractions(answerClient, answerVerificationService);
```

- [ ] **Step 2: Run the focused service test and confirm failure**

Run: `.\mvnw.cmd -Dtest=LawAiAnswerServiceClaimOutcomeTests test`

- [ ] **Step 3: Add source score, final ground ordering, and pre-LLM discovery response routing**

Use deterministic output only when discovery is classified and grounds are
non-empty. Keep the existing generation, repair, and verification path
unchanged for every other query.

- [ ] **Step 4: Run focused service and adjacent RAG tests**

Run: `.\mvnw.cmd -Dtest=LawAiAnswerServiceClaimOutcomeTests,LawAiAnswerServiceEvidenceGateTests,QuestionIntentProfileDocumentLookupTests,DocumentDiscoveryPolicyTests,DocumentDiscoveryAnswerComposerTests test`

### Task 4: Review and full verification

**Files:**
- Review all files listed above

**Interfaces:**
- Produces: verified implementation and runtime reproduction evidence

- [ ] **Step 1: Inspect the scoped diff and check negative controls, fail-closed behavior, numbering, and stream parity**

Run: `git diff --check`

- [ ] **Step 2: Run the full backend suite**

Run: `.\mvnw.cmd test`

- [ ] **Step 3: Build the executable JAR**

Run: `.\mvnw.cmd -DskipTests package`

- [ ] **Step 4: Restart only 8080 with the official scripts and verify runtime identity**

Run: `.\scripts\status-pandora.ps1`, then the documented stop/start commands
for app-dev only.

- [ ] **Step 5: Reproduce `CCTV 관련 법령` and verify an OK metadata list with law-first ordering**

Confirm that the response does not contain the generic insufficient-evidence
message and that 18080 was unchanged.
