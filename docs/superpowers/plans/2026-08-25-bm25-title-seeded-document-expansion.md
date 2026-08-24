# BM25 Title-Seeded Document Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bounded, fail-closed BM25 title-seeded sibling-chunk expansion lane that remains shadow-only until repeated evaluation proves an improvement.

**Architecture:** Reuse the existing Korean BM25 result and hydrated chunk metadata instead of creating another index or external request. A pure selector converts strong document-title matches into bounded document seeds; the existing read-only document expansion service retrieves and ranks their sibling chunks, and the answer service feeds them only into shadow fusion.

**Tech Stack:** Java 17, Spring Boot configuration properties, MyBatis/MariaDB read mappers, JUnit 5, Mockito, AssertJ, Node.js built-in test runner.

**Spec:** `docs/superpowers/specs/2026-08-25-bm25-title-seeded-document-expansion-design.md`

## Global Constraints

- Work only in `C:\dev\workspace-egov\pandora\.worktrees\document-first-candidate-expansion` on `codex/document-first-candidate-expansion`.
- Keep `law-ai.retrieval.document-expansion.authoritative=false`.
- Preserve the hard bounds of three documents, eight chunks per document, 24 total chunks, and 100 inspected BM25 hits.
- Reuse the existing BM25 request; add no OpenAI Embedding, OpenAI Answer, or Qdrant call.
- Production selection code must not consume evaluation case IDs, oracle terms, expected evidence, or fixed document IDs.
- Implementation verification is read-only. Do not mutate MariaDB/Qdrant, touch port `18080`, or touch `output/`.
- Do not run the external 24-case evaluation until a new immutable manifest receives exact payload approval.

---

### Task 1: Bounded BM25 Title Seed Selector and Configuration

**Files:**
- Create: `src/main/java/com/kaces/pandora/semantic/retrieval/DocumentExpansionSeed.java`
- Create: `src/main/java/com/kaces/pandora/semantic/retrieval/Bm25TitleDocumentSeedSelector.java`
- Create: `src/test/java/com/kaces/pandora/semantic/retrieval/Bm25TitleDocumentSeedSelectorTests.java`
- Modify: `src/main/java/com/kaces/pandora/semantic/config/LawAiDocumentExpansionProperties.java`
- Modify: `src/test/java/com/kaces/pandora/semantic/config/LawAiDocumentExpansionPropertiesTests.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/RuntimeConfigurationIdentity.java`
- Modify: `src/test/java/com/kaces/pandora/ai/answer/RuntimeConfigurationIdentityTests.java`
- Modify: `src/main/resources/application.properties`

**Interfaces:**
- Produces `DocumentExpansionSeed(String target, long documentId, String title, List<String> matchedTitleTerms, double bm25Score, int bm25Rank, String anchorType, String reason)`.
- Produces `Bm25TitleDocumentSeedSelector.select(List<LexicalSearchHit>, List<LawSemanticChunkRow>, List<String>, List<String>, Policy): Selection`.
- Produces immutable `Policy(boolean enabled, int maxBm25HitsInspected, int minimumDistinctTitleTerms, double ambiguityScoreRatio, int maxDocuments)`.
- Extends `LawAiDocumentExpansionProperties` with `bm25TitleEnabled`, `bm25TitleMaxHits`, `bm25TitleMinimumTerms`, and `bm25TitleAmbiguityRatio` and exposes `bm25TitlePolicy()`.

- [ ] **Step 1: Write selector RED tests**

Create tests with fixed in-memory hits and hydrated rows. The main success case must prove that only terms occurring in `LawSemanticChunkRow.title()` count:

```java
@Test
void selectsOnlyDocumentsWithTwoDistinctBm25TermsInTheDocumentTitle() {
    Bm25TitleDocumentSeedSelector.Selection result = selector.select(
        List.of(
            hit("official_doc", 101, 10, 9.0, 1, "사전협의", "정보화사업"),
            hit("official_doc", 201, 20, 8.0, 2, "사전협의", "절차")
        ),
        List.of(
            chunk(101, 10, "official_doc", "정보화사업 사전협의 지침", ""),
            chunk(201, 20, "official_doc", "일반 행정 지침", "사전협의 절차")
        ),
        List.of("정보화사업", "사전협의", "절차"),
        List.of("official_doc"),
        policy()
    );

    assertThat(result.status()).isEqualTo(Status.APPLIED);
    assertThat(result.seeds()).extracting(DocumentExpansionSeed::documentId).containsExactly(10L);
    assertThat(result.seeds().get(0).matchedTitleTerms()).containsExactly("사전협의", "정보화사업");
}
```

Add cases for body-only rejection, weak-term rejection, non-finite/negative score rejection, missing hydration, target mismatch, duplicate chunk hits for one document, deterministic score/rank ordering, maximum 100 inspected hits, maximum three documents, and the five-percent ambiguity boundary.

- [ ] **Step 2: Run selector tests to verify RED**

Run:

```powershell
.\mvnw.cmd "-Dtest=Bm25TitleDocumentSeedSelectorTests,LawAiDocumentExpansionPropertiesTests,RuntimeConfigurationIdentityTests" test
```

Expected: compilation fails because the selector, seed record, policy fields, and runtime identity fields do not exist.

- [ ] **Step 3: Implement the minimal pure selector**

Implement records with defensive copies and selector validation. The core grouping must inspect at most the policy bound and count only a BM25 matched term found in the normalized document title:

```java
List<String> titleMatches = hit.matchedTerms().stream()
    .map(KoreanQueryNormalizer::normalizeForMatch)
    .filter(term -> !term.isBlank() && !KoreanQueryNormalizer.isWeakQuestionTerm(term))
    .filter(normalizedTitle::contains)
    .distinct()
    .toList();
```

Aggregate by normalized `target:documentId`, retain the best finite positive score and lowest rank, order by the approved comparator, and return no seeds when the first excluded document is tied at the configured ambiguity ratio.

- [ ] **Step 4: Add immutable configuration and identity fields**

Add these exact defaults to `application.properties`:

```properties
law-ai.retrieval.document-expansion.bm25-title-enabled=true
law-ai.retrieval.document-expansion.bm25-title-max-hits=100
law-ai.retrieval.document-expansion.bm25-title-minimum-terms=2
law-ai.retrieval.document-expansion.bm25-title-ambiguity-ratio=0.05
```

Validate `1..100`, `2..6`, and `0.0..0.25` in the properties record. Include every field in `RuntimeConfigurationIdentity.sha256(...)`, so any policy drift changes the runtime configuration hash.

- [ ] **Step 5: Run focused tests to verify GREEN**

Run the Step 2 command. Expected: all selected tests pass with zero failures.

- [ ] **Step 6: Commit Task 1**

```powershell
git add src/main/java/com/kaces/pandora/semantic/retrieval/DocumentExpansionSeed.java src/main/java/com/kaces/pandora/semantic/retrieval/Bm25TitleDocumentSeedSelector.java src/test/java/com/kaces/pandora/semantic/retrieval/Bm25TitleDocumentSeedSelectorTests.java src/main/java/com/kaces/pandora/semantic/config/LawAiDocumentExpansionProperties.java src/test/java/com/kaces/pandora/semantic/config/LawAiDocumentExpansionPropertiesTests.java src/main/java/com/kaces/pandora/ai/answer/RuntimeConfigurationIdentity.java src/test/java/com/kaces/pandora/ai/answer/RuntimeConfigurationIdentityTests.java src/main/resources/application.properties
git commit -m "feat: select bounded BM25 title document seeds"
```

---

### Task 2: Exact-Document Sibling Chunk Expansion

**Files:**
- Modify: `src/main/java/com/kaces/pandora/semantic/retrieval/DocumentCandidateExpansion.java`
- Modify: `src/test/java/com/kaces/pandora/semantic/retrieval/DocumentCandidateExpansionTests.java`
- Modify: `src/main/java/com/kaces/pandora/semantic/retrieval/DocumentExpansionSearchService.java`
- Modify: `src/test/java/com/kaces/pandora/semantic/retrieval/DocumentExpansionSearchServiceTests.java`

**Interfaces:**
- Produces `DocumentCandidateExpansion.rankSeededChunks(DocumentSearchAnchor, List<DocumentExpansionSeed>, List<LawSemanticChunkRow>, Set<String>, Policy): Result`.
- Produces `DocumentExpansionSearchService.searchBm25Seeded(DocumentSearchAnchor, List<DocumentExpansionSeed>, boolean, Set<String>): Result`.
- Extends `DocumentCandidateExpansion.Hit` with seed term count, BM25 score, and BM25 rank while preserving existing strong-anchor values as null.

- [ ] **Step 1: Write seeded-expansion RED tests**

Add a pure test that supplies two verified seeds and mixed candidate chunks:

```java
@Test
void ranksOnlyChunksBelongingToBoundedBm25TitleSeeds() {
    Result result = expansion.rankSeededChunks(
        evidenceAnchor(),
        List.of(seed("law", 10), seed("official_doc", 20)),
        List.of(chunk(101, 10, "law"), chunk(201, 20, "official_doc"), chunk(301, 30, "law")),
        Set.of("law:101"),
        policy
    );

    assertThat(result.status()).isEqualTo(Status.BM25_TITLE_APPLIED);
    assertThat(result.chunks()).extracting(LawSemanticChunkRow::documentId).containsOnly(10L, 20L);
    assertThat(result.hits()).allMatch(hit -> "BM25_TITLE".equals(hit.anchorType()));
}
```

Add service tests proving law/RAG mappers receive only the exact seed document IDs, active/future filtering remains unchanged, a family mapper failure discards all results, no document-identity search mapper is called, and invalid/empty/over-bound seeds perform no chunk read.

- [ ] **Step 2: Run seeded-expansion tests to verify RED**

```powershell
.\mvnw.cmd "-Dtest=DocumentCandidateExpansionTests,DocumentExpansionSearchServiceTests" test
```

Expected: compilation fails on the missing seeded methods/status/metadata.

- [ ] **Step 3: Implement independent seed validation and ranking**

Add statuses `BM25_TITLE_APPLIED`, `BM25_TITLE_NO_MATCH`, `BM25_TITLE_AMBIGUOUS`, `BM25_TITLE_INVALID_INPUT`, and `BM25_TITLE_DB_FALLBACK`. Do not call `selectDocuments(...)` for seeded input. Independently require unique valid `target/documentId` identities, no more than `maxDocuments`, allowed targets, and candidate rows whose identities belong to the supplied seeds.

Reuse the existing provision/heading/evidence comparator and per-document/global limits. Emit hits as:

```java
new Hit(
    candidateKey(row), rank, "BM25_TITLE", overlapsExisting,
    "BM25_TITLE_SEED", seed.matchedTitleTerms().size(), seed.bm25Score(), seed.bm25Rank()
)
```

Existing strong-anchor hits must set the three seed fields to null.

- [ ] **Step 4: Implement exact-document service reads**

`searchBm25Seeded(...)` partitions seed IDs by target family, calls only `findDocumentExpansionChunks(...)`, merges the rows, and delegates to `rankSeededChunks(...)`. It must return `BM25_TITLE_DB_FALLBACK` on either mapper exception and must not return a partial family result.

- [ ] **Step 5: Run focused tests to verify GREEN**

Run the Step 2 command. Expected: all selected tests pass with zero failures.

- [ ] **Step 6: Commit Task 2**

```powershell
git add src/main/java/com/kaces/pandora/semantic/retrieval/DocumentCandidateExpansion.java src/test/java/com/kaces/pandora/semantic/retrieval/DocumentCandidateExpansionTests.java src/main/java/com/kaces/pandora/semantic/retrieval/DocumentExpansionSearchService.java src/test/java/com/kaces/pandora/semantic/retrieval/DocumentExpansionSearchServiceTests.java
git commit -m "feat: expand chunks from verified BM25 document seeds"
```

---

### Task 3: Shadow-Only Answer-Service Orchestration

**Files:**
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiDebugResponse.java`
- Modify: `src/test/java/com/kaces/pandora/ai/answer/LawAiAnswerServiceDocumentExpansionTests.java`
- Modify: `src/test/java/com/kaces/pandora/ai/answer/LawAiRuntimeInfoTests.java`

**Interfaces:**
- `LawAiAnswerService` receives `Bm25TitleDocumentSeedSelector` through its dependency boundary.
- `LawAiDebugResponse.Item` exposes nullable `documentExpansionSeedTermCount`, `documentExpansionSeedBm25Score`, and `documentExpansionSeedBm25Rank`.
- Runtime info/config identity exposes the new immutable policy values without secrets.

- [ ] **Step 1: Write orchestration RED tests**

Add four focused cases:

```java
@Test
void usesBm25TitleFallbackOnlyWhenStrongAnchorIsAbsent() {
    try (Harness harness = Harness.bm25TitleFallback()) {
        LawAiDebugResponse result = harness.debug("정보화사업 사전협의는 언제 해야 해?");

        assertThat(result.documentExpansionStatus()).isEqualTo("BM25_TITLE_APPLIED");
        assertThat(result.documentExpansionHits()).isNotEmpty();
        assertThat(result.documentExpansionHits())
            .extracting(LawAiDebugResponse.Item::documentExpansionAnchorType)
            .containsOnly("BM25_TITLE");
        assertThat(harness.bm25TitleSelectorRequestCount()).isEqualTo(1);
    }
}

@Test
void preservesAppliedStrongAnchorWithoutRunningBm25TitleSelector() {
    try (Harness harness = Harness.strongAnchorApplied()) {
        LawAiDebugResponse result = harness.debug(QUESTION);

        assertThat(result.documentExpansionStatus()).isEqualTo("APPLIED");
        assertThat(harness.bm25TitleSelectorRequestCount()).isZero();
        assertThat(harness.bm25SeededSearchRequestCount()).isZero();
    }
}

@Test
void bm25TitleShadowDoesNotChangeSearchedChunksOrSelectedGrounds() {
    try (Harness baseline = Harness.bm25TitleDisabled();
         Harness shadow = Harness.bm25TitleFallback()) {
        LawAiDebugResponse baselineResult = baseline.debug("정보화사업 사전협의는 언제 해야 해?");
        LawAiDebugResponse shadowResult = shadow.debug("정보화사업 사전협의는 언제 해야 해?");

        assertThat(ids(shadowResult.merged()))
            .containsExactlyElementsOf(ids(baselineResult.merged()));
        assertThat(ids(shadowResult.selected()))
            .containsExactlyElementsOf(ids(baselineResult.selected()));
    }
}

@Test
void bm25TitleFallbackAddsNoEmbeddingAnswerOrQdrantRequest() {
    try (Harness harness = Harness.bm25TitleFallback()) {
        harness.debug("정보화사업 사전협의는 언제 해야 해?");

        assertThat(harness.embeddingRequestCount()).isEqualTo(1);
        assertThat(harness.qdrantSearchRequestCount()).isEqualTo(1);
        assertThat(harness.answerRequestCount()).isZero();
    }
}
```

Extend the existing `Harness` with deterministic BM25-title selector, seeded-search, and answer-client counters used above. Also assert bounded seed metadata appears on expansion debug items and remains null on ordinary vector/BM25/strong-anchor items.

- [ ] **Step 2: Run answer-service tests to verify RED**

```powershell
.\mvnw.cmd "-Dtest=LawAiAnswerServiceDocumentExpansionTests,LawAiRuntimeInfoTests" test
```

Expected: missing constructor dependency, orchestration path, debug fields, and runtime policy fields.

- [ ] **Step 3: Join and hydrate BM25 once**

Keep the existing BM25 future and timeout. After `bm25Hits` and their chunks are available, join the strong-anchor future once. Apply this exact decision order:

```java
DocumentCandidateExpansion.Result strong = finalizeDocumentExpansion(
    joinDocumentExpansion(documentExpansionFuture), finalControlCandidateKeys
);
DocumentCandidateExpansion.Result expansion = strong.status() == Status.NO_STRONG_ANCHOR
    ? finalizeDocumentExpansion(
        searchBm25TitleSeeds(
            anchor,
            bm25Hits,
            bm25Chunks,
            bm25Keywords,
            targets,
            activeOnly,
            finalControlCandidateKeys
        ),
        finalControlCandidateKeys
    )
    : strong;
```

The fallback receives the existing `bm25Hits`, hydrated rows, planned BM25 keywords, target list, and no external client. It must not run for `APPLIED`, `DOCUMENT_NOT_FOUND`, ambiguity, DB fallback, invalid bounds, or disabled status.

- [ ] **Step 4: Preserve the authority boundary**

Keep `documentExpansionAuthoritative(...)` limited to legacy `APPLIED`. A `BM25_TITLE_APPLIED` result remains non-authoritative even if someone incorrectly toggles the existing authority property before the later promotion implementation.

Populate debug metadata from the extended hit record and add a stage reason that names the BM25-title fallback without including query or candidate text.

- [ ] **Step 5: Run focused tests to verify GREEN**

Run the Step 2 command. Expected: all selected tests pass with zero failures and exact external-client interaction assertions.

- [ ] **Step 6: Commit Task 3**

```powershell
git add src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java src/main/java/com/kaces/pandora/ai/answer/LawAiDebugResponse.java src/test/java/com/kaces/pandora/ai/answer/LawAiAnswerServiceDocumentExpansionTests.java src/test/java/com/kaces/pandora/ai/answer/LawAiRuntimeInfoTests.java
git commit -m "feat: run BM25 title expansion in shadow retrieval"
```

---

### Task 4: Offline Capture and Fail-Closed Selection Contract

**Files:**
- Modify: `scripts/rag-retrieval-eval.js`
- Modify: `scripts/rag-retrieval-eval.test.js`
- Modify: `scripts/lib/document-expansion-selection.js`
- Modify: `scripts/document-expansion-selection.test.js`

**Interfaces:**
- The evaluator accepts the new statuses, `BM25_TITLE` anchor type, `BM25_TITLE_SEED` reason, and nullable seed metadata.
- Captured BM25 seed values are bounded: term count `2..6`, finite positive score, rank `1..100`.
- The selector retains the corrected fused-control baseline `{ allRequired: 7, anyRequired: 14, matchedGroups: 22, caseCount: 24 }`.

- [ ] **Step 1: Write evaluator RED tests**

Add a valid `BM25_TITLE_APPLIED` response and malformed variants:

```javascript
assert.deepEqual(capture.documentExpansionHits[0], {
  candidateKey: 'law:101',
  documentId: 10,
  rank: 1,
  anchorType: 'BM25_TITLE',
  reason: 'BM25_TITLE_SEED',
  overlapsExistingSource: false,
  seedTermCount: 2,
  seedBm25Score: 9.5,
  seedBm25Rank: 4,
  matchedAuditGroupIndexes: [0],
});
```

Reject missing seed metadata for `BM25_TITLE`, seed metadata on a legacy anchor, non-finite score, out-of-range term count/rank, unknown statuses/reasons, and more than the established document/chunk bounds.

- [ ] **Step 2: Run Node tests to verify RED**

```powershell
node --test .\scripts\rag-retrieval-eval.test.js .\scripts\document-expansion-selection.test.js
```

Expected: new status/anchor/reason/metadata validation is absent.

- [ ] **Step 3: Implement bounded capture and selector compatibility**

Extend only the fixed allowlists and capture fields. Do not persist candidate text or document titles. Require the same immutable policy/config hash in both runs and keep all prior provenance, error, Qdrant, baseline-case, and quality-floor checks.

- [ ] **Step 4: Run the relevant Node suite**

```powershell
node --test .\scripts\rag-retrieval-eval.test.js .\scripts\document-expansion-selection.test.js .\scripts\coverage-aware-selection.test.js .\scripts\rrf-weight-selection.test.js .\scripts\rag-eval-provenance.test.js
```

Expected: all tests pass, including the corrected `7/14/22` baseline regression test.

- [ ] **Step 5: Commit Task 4**

```powershell
git add scripts/rag-retrieval-eval.js scripts/rag-retrieval-eval.test.js scripts/lib/document-expansion-selection.js scripts/document-expansion-selection.test.js
git commit -m "feat: capture BM25 title expansion evidence"
```

---

### Task 5: Integration Verification and Evaluation Handoff

**Files:**
- Modify: `docs/rag-quality-gate.md`
- Modify: `docs/superpowers/specs/2026-08-24-document-first-candidate-expansion-verification.md`
- Modify: `.superpowers/sdd/2026-07-30-rag-retrieval-evidence-shadow-migration/task-15-progress.md`

**Interfaces:**
- Produces a verified implementation commit and a read-only evaluation-preparation checkpoint.
- Does not produce an external evaluation manifest until runtime deployment and exact fences can be observed.

- [ ] **Step 1: Run focused backend tests**

```powershell
.\mvnw.cmd "-Dtest=Bm25TitleDocumentSeedSelectorTests,LawAiDocumentExpansionPropertiesTests,RuntimeConfigurationIdentityTests,DocumentCandidateExpansionTests,DocumentExpansionSearchServiceTests,LawAiAnswerServiceDocumentExpansionTests,LawAiRuntimeInfoTests" test
```

Require zero failures and zero errors.

- [ ] **Step 2: Run the relevant Node suite**

```powershell
node --test .\scripts\rag-retrieval-eval.test.js .\scripts\document-expansion-selection.test.js .\scripts\coverage-aware-selection.test.js .\scripts\rrf-weight-selection.test.js .\scripts\rag-eval-provenance.test.js
```

Require zero failures, cancellations, skips, and todos.

- [ ] **Step 3: Run the complete backend suite once**

```powershell
.\mvnw.cmd test
```

Require zero failures and errors. Record opt-in integration-test skips
separately; do not enable database mutations solely to eliminate expected
skips.

- [ ] **Step 4: Verify repository and runtime safety invariants**

```powershell
git diff --check
git status --short --branch
.\scripts\status-pandora.ps1
```

Require the expected feature branch, only intended changes, no whitespace
errors, and no port `18080` action. Verify committed configuration keeps both
legacy and BM25-title expansion non-authoritative.

- [ ] **Step 5: Update durable documentation**

Record exact test counts, commit/JAR/config identity when available, the
shadow-only authority state, and the fact that no OpenAI/Qdrant/MariaDB
mutation or external evaluation occurred. Document any deferred runtime mapper
check as a pre-evaluation fence instead of silently omitting it.

- [ ] **Step 6: Perform one scoped implementation review**

Review only Critical or Important violations of the approved design: external
call multiplication, authority leakage, unbounded document/chunk reads,
body-only title seeding, evaluator/oracle leakage, invalid identity acceptance,
or fail-open behavior. Apply at most one fix/re-review round for confirmed
blocking findings.

- [ ] **Step 7: Commit verification evidence**

```powershell
git add docs/rag-quality-gate.md docs/superpowers/specs/2026-08-24-document-first-candidate-expansion-verification.md .superpowers/sdd/2026-07-30-rag-retrieval-evidence-shadow-migration/task-15-progress.md
git commit -m "docs: verify BM25 title expansion candidate"
```

- [ ] **Step 8: Stop before external evaluation**

Do not reuse manifest `094b9aa8...`. After a candidate JAR is built and deployed
through the documented app-dev 8080 workflow, prepare a new immutable manifest
with fresh runtime/JAR/config/index/lexical hashes and absent evidence paths.
Obtain exact approval before sending the 24 questions twice to the OpenAI
Embedding API.
