# Document-first Candidate Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a deterministic, bounded document-first candidate source that recovers law and RAG chunks when a question contains a strong document/provision anchor, while leaving the current answer path unchanged until independent evaluation proves an improvement.

**Architecture:** Derive a pure `DocumentSearchAnchor` from `QuestionSearchPlan`, query only active/current MariaDB documents and chunks through bounded mapper statements, rank and deduplicate the new source deterministically, and expose a separate shadow union/fused order. The existing vector, lexical, BM25, pure-RRF, and coverage-aware control orders remain unchanged while `authoritative=false`.

**Tech Stack:** Java 17, Spring Boot configuration properties, MyBatis/MariaDB, JUnit 5, AssertJ, Mockito, Node.js built-in test runner, and the existing Pandora retrieval evaluation scripts.

**Spec:** `docs/superpowers/specs/2026-08-24-document-first-candidate-expansion-design.md`

## Global Constraints

- Work only in `C:\dev\workspace-egov\pandora\.worktrees\document-first-candidate-expansion` on `codex/document-first-candidate-expansion`; keep the shared workspace on `main`.
- Preserve the stacked dependency on `codex/coverage-aware-fusion`. Merge that branch first, then update this branch from the resulting `main` before final review.
- Never start, stop, restart, promote to, or modify port `18080`.
- Never read, modify, move, or delete `output/`.
- Add no OpenAI request and no Qdrant request. The new source performs bounded MariaDB reads only.
- Use no evaluation case ID, expected evidence group, audit alias, hard-coded document ID, or question-specific allowlist in production retrieval.
- Maximum expansion is three documents, eight chunks per document, and 24 unique chunks globally.
- `law-ai.retrieval.document-expansion.authoritative=false` throughout implementation and training.
- Existing `searchedChunks`, pure-RRF, and coverage-aware control order must remain unchanged while authority is false.
- Database failure, malformed identity, ambiguity, invalid bounds, configuration drift, or provenance drift must return the baseline unchanged and emit a bounded reason code.
- Require a fresh immutable execution manifest and exact user approval before any evaluation sends question text to an OpenAI API.
- Do not consume difficult or holdout cases unless the frozen training gate passes twice.

---

### Task 1: Deterministic Strong-Anchor Extraction

**Files:**
- Create: `src/main/java/com/kaces/pandora/common/text/DocumentSearchAnchor.java`
- Create: `src/main/java/com/kaces/pandora/common/text/DocumentSearchAnchorExtractor.java`
- Modify: `src/main/java/com/kaces/pandora/common/text/QuestionSearchPlan.java`
- Create: `src/test/java/com/kaces/pandora/common/text/DocumentSearchAnchorExtractorTests.java`
- Modify: `src/test/java/com/kaces/pandora/common/text/QuestionSearchPlanTests.java`

**Interfaces:**

```java
public record DocumentSearchAnchor(
    List<String> titleTerms,
    List<String> provisionTerms,
    List<String> headingTerms,
    List<String> evidenceTerms,
    List<String> targets,
    AnchorType anchorType,
    Status status
) {
    public enum AnchorType { EXPLICIT_TITLE, STABLE_ALIAS, TITLE_WITH_PROVISION, NONE }
    public enum Status { ELIGIBLE, NO_STRONG_ANCHOR, INVALID }
    public boolean eligible() { return status == Status.ELIGIBLE; }
}
```

```java
public final class DocumentSearchAnchorExtractor {
    public static DocumentSearchAnchor extract(
        String question,
        QuestionIntentProfile profile,
        List<String> lexicalKeywords,
        List<String> focusedKeywords
    );
}
```

- [ ] **Step 1: Write failing anchor tests**

Cover Korean quoted titles, `법/시행령/시행규칙/규정/지침/고시` suffixes, dictionary-backed stable aliases, `제12조`, `제12조의2`, `별표 3`, and explicit section headings. Assert stable order, normalized distinct terms, and bounded evidence terms.

```java
DocumentSearchAnchor anchor = DocumentSearchAnchorExtractor.extract(
    "전자정부법 제67조의2에 따른 사전협의 대상은?",
    QuestionIntentProfile.from("전자정부법 제67조의2에 따른 사전협의 대상은?"),
    List.of("전자정부법", "사전협의", "대상"),
    List.of("사전협의", "대상")
);

assertThat(anchor.status()).isEqualTo(DocumentSearchAnchor.Status.ELIGIBLE);
assertThat(anchor.titleTerms()).containsExactly("전자정부법");
assertThat(anchor.provisionTerms()).containsExactly("제67조의2");
assertThat(anchor.anchorType()).isEqualTo(DocumentSearchAnchor.AnchorType.TITLE_WITH_PROVISION);
```

Also assert `NO_STRONG_ANCHOR` for generic questions such as “사전협의는 언제 하나요?”, short ambiguous fragments, topic terms without a title suffix, and aliases not present in the configured intent dictionary.

- [ ] **Step 2: Run focused tests and verify RED**

```powershell
.\mvnw.cmd -Dtest=DocumentSearchAnchorExtractorTests,QuestionSearchPlanTests test
```

Expected: compilation failure because the new types and `QuestionSearchPlan.documentSearchAnchor()` do not exist.

- [ ] **Step 3: Implement the minimal extractor**

Add this derived method without changing the record constructor shape:

```java
public DocumentSearchAnchor documentSearchAnchor() {
    return DocumentSearchAnchorExtractor.extract(
        question, profile, lexicalKeywords, focusedKeywords
    );
}
```

Use `KoreanQueryNormalizer.normalizeForMatch` for comparisons, preserve display-safe terms for mapper parameters, and cap lists at title `6`, provision `6`, heading `8`, and evidence `18`. Read stable aliases only through `QuestionIntentDictionary`; do not add an evaluation-specific dictionary key.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2. Expected: all tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/kaces/pandora/common/text/DocumentSearchAnchor.java src/main/java/com/kaces/pandora/common/text/DocumentSearchAnchorExtractor.java src/main/java/com/kaces/pandora/common/text/QuestionSearchPlan.java src/test/java/com/kaces/pandora/common/text/DocumentSearchAnchorExtractorTests.java src/test/java/com/kaces/pandora/common/text/QuestionSearchPlanTests.java
git commit -m "feat: derive strong document search anchors"
```

---

### Task 2: Fail-closed Configuration and Runtime Identity

**Files:**
- Create: `src/main/java/com/kaces/pandora/semantic/config/LawAiDocumentExpansionProperties.java`
- Create: `src/test/java/com/kaces/pandora/semantic/config/LawAiDocumentExpansionPropertiesTests.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/RuntimeConfigurationIdentity.java`
- Modify: `src/test/java/com/kaces/pandora/ai/answer/RuntimeConfigurationIdentityTests.java`
- Modify: `src/main/resources/application.properties`

**Interfaces:**

```java
@ConfigurationProperties(prefix = "law-ai.retrieval.document-expansion")
public record LawAiDocumentExpansionProperties(
    boolean enabled,
    boolean authoritative,
    int maxDocuments,
    int maxChunksPerDocument,
    int maxTotalChunks
) {
    public boolean validBounds();
    public Policy policy();
}
```

- [ ] **Step 1: Write failing property and identity tests**

Assert the configured default policy is `enabled=true`, `authoritative=false`, `3/8/24`; non-positive values produce `validBounds()==false`; values above `3/8/24` are rejected; and every field changes the runtime SHA-256 independently.

```java
LawAiDocumentExpansionProperties defaults =
    new LawAiDocumentExpansionProperties(true, false, 3, 8, 24);
assertThat(defaults.validBounds()).isTrue();
assertThat(defaults.policy()).isEqualTo(new DocumentCandidateExpansion.Policy(true, false, 3, 8, 24));
```

- [ ] **Step 2: Verify RED**

```powershell
.\mvnw.cmd -Dtest=LawAiDocumentExpansionPropertiesTests,RuntimeConfigurationIdentityTests test
```

Expected: missing property type and missing identity overload.

- [ ] **Step 3: Implement properties and identity fingerprinting**

Add a five-argument `RuntimeConfigurationIdentity.sha256(...)` overload accepting the new properties. Preserve existing overloads by supplying the disabled fallback `new LawAiDocumentExpansionProperties(false, false, 0, 0, 0)`.

Append these canonical keys:

```text
documentExpansion.enabled
documentExpansion.authoritative
documentExpansion.maxDocuments
documentExpansion.maxChunksPerDocument
documentExpansion.maxTotalChunks
```

Add exact defaults to `application.properties`:

```properties
law-ai.retrieval.document-expansion.enabled=true
law-ai.retrieval.document-expansion.authoritative=false
law-ai.retrieval.document-expansion.max-documents=3
law-ai.retrieval.document-expansion.max-chunks-per-document=8
law-ai.retrieval.document-expansion.max-total-chunks=24
```

- [ ] **Step 4: Verify GREEN**

Run the command from Step 2. Expected: all tests pass and the identity changes for each property mutation.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/kaces/pandora/semantic/config/LawAiDocumentExpansionProperties.java src/test/java/com/kaces/pandora/semantic/config/LawAiDocumentExpansionPropertiesTests.java src/main/java/com/kaces/pandora/ai/answer/RuntimeConfigurationIdentity.java src/test/java/com/kaces/pandora/ai/answer/RuntimeConfigurationIdentityTests.java src/main/resources/application.properties
git commit -m "feat: configure document candidate expansion"
```

---

### Task 3: Bounded Law and RAG Document Queries

**Files:**
- Create: `src/main/java/com/kaces/pandora/semantic/retrieval/DocumentIdentityCandidate.java`
- Modify: `src/main/java/com/kaces/pandora/lawdata/persistence/LawChunkMapper.java`
- Modify: `src/main/resources/mapper/law/LawChunkMapper.xml`
- Modify: `src/test/java/com/kaces/pandora/lawdata/persistence/LawChunkMapperXmlTests.java`
- Modify: `src/main/java/com/kaces/pandora/rag/persistence/RagDocumentMapper.java`
- Modify: `src/main/resources/mapper/law/RagDocumentMapper.xml`
- Modify: `src/test/java/com/kaces/pandora/rag/persistence/RagDocumentMapperXmlTests.java`

**Interfaces:**

```java
public record DocumentIdentityCandidate(
    long documentId,
    String target,
    String title,
    String normalizedTitle,
    int matchedTitleTermCount,
    boolean exactTitleMatch,
    boolean provisionAnchorMatch
) {}
```

Add equivalent methods to both mapper interfaces:

```java
List<DocumentIdentityCandidate> findDocumentExpansionDocuments(
    @Param("targets") List<String> targets,
    @Param("titleTerms") List<String> titleTerms,
    @Param("provisionTerms") List<String> provisionTerms,
    @Param("includeFuture") boolean includeFuture,
    @Param("limit") int limit
);

List<LawSemanticChunkRow> findDocumentExpansionChunks(
    @Param("documentIds") List<Long> documentIds,
    @Param("provisionTerms") List<String> provisionTerms,
    @Param("headingTerms") List<String> headingTerms,
    @Param("evidenceTerms") List<String> evidenceTerms,
    @Param("includeFuture") boolean includeFuture,
    @Param("perDocumentLimit") int perDocumentLimit,
    @Param("limit") int limit
);
```

The RAG interface omits `includeFuture` because RAG documents have no law effective-date semantics.

- [ ] **Step 1: Write failing mapper-contract tests**

For both XML mappers, assert:

- target/document-type isolation is parameterized;
- document and chunk `use_yn='Y'` filters are present;
- law rows apply the existing current/effective-date predicate when `includeFuture=false`;
- RAG chunks select only the latest active chunk version and quality `PASS/REVIEW`;
- every title term must match unless exact normalized title matches;
- document query limit is bound as `LIMIT ?` and called with `maxDocuments + 1` to detect ambiguity;
- chunk query uses `ROW_NUMBER() OVER (PARTITION BY document_id ORDER BY ...)` and filters `document_rank <= ?`;
- final order is stable by match class, document id, sort order, and chunk id;
- the global query limit is parameterized;
- `LawSemanticChunkRow` projections retain the exact canonical aliases and typed nulls.

- [ ] **Step 2: Verify mapper tests fail**

```powershell
.\mvnw.cmd -Dtest=LawChunkMapperXmlTests,RagDocumentMapperXmlTests test
```

Expected: mapper methods/statements are absent.

- [ ] **Step 3: Implement law document and chunk statements**

Use the existing law joins and effective-date expression from `findSemanticChunksByDocumentTitleAndText`. Return only distinct active documents. Compute title/provision match columns in SQL, but repeat deterministic validation in Java. Use a CTE for ranked chunks so at most `perDocumentLimit` rows per selected document and `limit` rows globally cross the JDBC boundary.

- [ ] **Step 4: Implement RAG document and chunk statements**

Use `rag_documents` plus `rag_document_chunks`, the latest active chunk-version predicate already used by `findSemanticChunksByHeadingText`, and the same projection and deterministic ranking contract. Do not scan `embedding_text`; body checks may use the existing exact-term search index or bounded rows belonging to already anchored documents.

- [ ] **Step 5: Verify GREEN and mapper completeness**

Run the command from Step 2. Expected: all mapper XML tests pass, including reflective `LawSemanticChunkRow` projection checks.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/kaces/pandora/semantic/retrieval/DocumentIdentityCandidate.java src/main/java/com/kaces/pandora/lawdata/persistence/LawChunkMapper.java src/main/resources/mapper/law/LawChunkMapper.xml src/test/java/com/kaces/pandora/lawdata/persistence/LawChunkMapperXmlTests.java src/main/java/com/kaces/pandora/rag/persistence/RagDocumentMapper.java src/main/resources/mapper/law/RagDocumentMapper.xml src/test/java/com/kaces/pandora/rag/persistence/RagDocumentMapperXmlTests.java
git commit -m "feat: add bounded document expansion queries"
```

---

### Task 4: Pure Document and Chunk Selection

**Files:**
- Create: `src/main/java/com/kaces/pandora/semantic/retrieval/DocumentCandidateExpansion.java`
- Create: `src/test/java/com/kaces/pandora/semantic/retrieval/DocumentCandidateExpansionTests.java`

**Interfaces:**

```java
@Component
public class DocumentCandidateExpansion {
    public DocumentSelection selectDocuments(
        DocumentSearchAnchor anchor,
        List<DocumentIdentityCandidate> candidates,
        Policy policy
    );

    public Result rankChunks(
        DocumentSearchAnchor anchor,
        DocumentSelection documents,
        List<LawSemanticChunkRow> candidates,
        Set<String> existingCandidateKeys,
        Policy policy
    );

    public record Policy(boolean enabled, boolean authoritative,
        int maxDocuments, int maxChunksPerDocument, int maxTotalChunks) {}
    public record Hit(String candidateKey, int sourceRank,
        String anchorType, boolean overlapsExistingSource, String reason) {}
    public record Result(List<LawSemanticChunkRow> chunks,
        List<Hit> hits, Status status, List<String> reasonCodes) {}
    public enum Status {
        DISABLED, NO_STRONG_ANCHOR, DOCUMENT_NOT_FOUND,
        DOCUMENT_MATCH_AMBIGUOUS, APPLIED, DB_FALLBACK_BASELINE,
        INVALID_BOUNDS, FALLBACK_BASELINE
    }
}
```

- [ ] **Step 1: Write failing pure-selection tests**

Cover exact title before all-term title, provision metadata before generic title matches, deterministic document-id ties, ambiguity when more than three equally eligible documents exist, target isolation, and rejection of malformed/non-positive document IDs.

For chunks cover exact provision, exact heading/parent heading, evidence-term count, stable `sortOrder/chunkId`, per-document limit eight, document limit three, global limit 24, duplicate candidate keys, overlap marking, and invalid-bound baseline fallback.

```java
DocumentCandidateExpansion.Result result = expansion.rankChunks(
    anchor,
    selectedDocuments,
    candidateChunks,
    Set.of("law:101"),
    new DocumentCandidateExpansion.Policy(true, false, 3, 8, 24)
);

assertThat(result.status()).isEqualTo(DocumentCandidateExpansion.Status.APPLIED);
assertThat(result.chunks()).hasSizeLessThanOrEqualTo(24);
assertThat(result.hits()).extracting(DocumentCandidateExpansion.Hit::candidateKey)
    .doesNotHaveDuplicates();
assertThat(result.hits().getFirst().overlapsExistingSource()).isTrue();
```

- [ ] **Step 2: Verify RED**

```powershell
.\mvnw.cmd -Dtest=DocumentCandidateExpansionTests test
```

Expected: component missing.

- [ ] **Step 3: Implement deterministic selection**

Keep this component pure: no mapper, executor, logging, clock, or network dependency. Normalize comparisons with `KoreanQueryNormalizer`, use explicit comparators, copy returned collections, and validate the final unique count and per-document counts before returning `APPLIED`.

Emit these bounded reason codes where applicable:

```text
DOCUMENT_NOT_ANCHORED
DOCUMENT_MATCH_AMBIGUOUS
DOCUMENT_LIMIT
DOCUMENT_CHUNK_LIMIT
DOCUMENT_GLOBAL_LIMIT
DOCUMENT_DUPLICATE_OVERLAP
INVALID_DOCUMENT_IDENTITY
```

- [ ] **Step 4: Verify GREEN**

Run the command from Step 2. Expected: all tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/kaces/pandora/semantic/retrieval/DocumentCandidateExpansion.java src/test/java/com/kaces/pandora/semantic/retrieval/DocumentCandidateExpansionTests.java
git commit -m "feat: rank bounded document expansion candidates"
```

---

### Task 5: Read-only Expansion Search Service

**Files:**
- Create: `src/main/java/com/kaces/pandora/semantic/retrieval/DocumentExpansionSearchService.java`
- Create: `src/test/java/com/kaces/pandora/semantic/retrieval/DocumentExpansionSearchServiceTests.java`

**Interfaces:**

```java
@Service
public class DocumentExpansionSearchService {
    public DocumentCandidateExpansion.Result search(
        DocumentSearchAnchor anchor,
        List<String> targets,
        boolean includeFuture,
        Set<String> existingCandidateKeys
    );
}
```

- [ ] **Step 1: Write failing orchestration tests with mocked mappers**

Assert:

- disabled/invalid properties and `NO_STRONG_ANCHOR` call neither mapper;
- law and RAG identity lookups run only for requested target families;
- the document lookup uses `maxDocuments + 1` and chunk lookup uses exact configured bounds;
- an ambiguous result performs no chunk query;
- mapper failure returns `DB_FALLBACK_BASELINE` with no partial expansion;
- a law failure plus successful RAG result still fails closed rather than mixing an incomplete snapshot;
- no OpenAI or Qdrant dependency exists in the constructor.

- [ ] **Step 2: Verify RED**

```powershell
.\mvnw.cmd -Dtest=DocumentExpansionSearchServiceTests test
```

Expected: service missing.

- [ ] **Step 3: Implement the service**

Inject `LawChunkMapper`, `RagDocumentMapper`, `DocumentCandidateExpansion`, and `LawAiDocumentExpansionProperties`. Split targets with the same law/RAG predicates used by `LawAiAnswerService`, combine mapper results once, select documents, fetch chunks from owning mappers, and return the pure component result. Log only status, target counts, document count, chunk count, and elapsed time—never question text or chunk text.

- [ ] **Step 4: Verify GREEN**

Run the command from Step 2. Expected: all tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/kaces/pandora/semantic/retrieval/DocumentExpansionSearchService.java src/test/java/com/kaces/pandora/semantic/retrieval/DocumentExpansionSearchServiceTests.java
git commit -m "feat: orchestrate read-only document expansion"
```

---

### Task 6: Integrate a Shadow-only Retrieval Source

**Files:**
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiDebugResponse.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/RetrievalCandidateTrace.java`
- Create: `src/test/java/com/kaces/pandora/ai/answer/LawAiAnswerServiceDocumentExpansionTests.java`
- Modify: `src/test/java/com/kaces/pandora/ai/answer/LawAiRuntimeInfoTests.java`

**Integration contract:**

- Start `DocumentExpansionSearchService.search(...)` after `QuestionSearchPlan` creation and in parallel with lexical/BM25/embedding work.
- Pass the final vector/lexical/BM25 candidate-key set for overlap annotation without changing that set.
- Add expansion chunks to `chunkById` only for shadow calculations.
- Preserve `controlChunks`, pure `fusedHits`, `coverage.ranking()`, and current authoritative selection exactly when `authoritative=false`.
- Compute and retain `documentExpansionHits` plus `documentExpansionFused` separately.
- Use expansion in the answer path only when both `document-expansion.authoritative=true` and the existing required RRF/semantic authority prerequisites are true.

- [ ] **Step 1: Write failing service-integration tests**

Build fixed vector, lexical, BM25, and expansion fixtures. Assert shadow mode:

```java
assertThat(result.searchedChunks()).extracting(LawSemanticChunkRow::chunkId)
    .containsExactlyElementsOf(controlChunkIds);
assertThat(result.hybrid().documentExpansionChunks())
    .extracting(LawSemanticChunkRow::chunkId)
    .containsExactly(901L, 902L);
assertThat(embeddingClientRequestCount()).isEqualTo(baselineEmbeddingRequestCount);
assertThat(qdrantSearchRequestCount()).isEqualTo(baselineQdrantRequestCount);
```

Also cover no-anchor/no-query, DB fallback, overlap, 24-hit truncation, timeout fallback, malformed expansion identity, and authority prerequisites. Assert no production text/snippet is added to new diagnostic metadata.

- [ ] **Step 2: Verify RED**

```powershell
.\mvnw.cmd -Dtest=LawAiAnswerServiceDocumentExpansionTests,LawAiRuntimeInfoTests test
```

Expected: constructor/debug/hybrid fields are missing.

- [ ] **Step 3: Add retrieval state without changing control order**

Extend `HybridRetrieval` with immutable fields:

```java
List<DocumentCandidateExpansion.Hit> documentExpansionHits,
List<LawSemanticChunkRow> documentExpansionChunks,
List<ReciprocalRankFusion.RrfHit> documentExpansionFusedHits,
List<LawSemanticChunkRow> documentExpansionFusedChunks,
DocumentCandidateExpansion.Status documentExpansionStatus
```

Build the shadow fused input deterministically from the existing RRF source ranks plus the new expansion rank. Do not alter `ReciprocalRankFusion.fuse(...)`; add a focused overload or a separate helper that accepts the third ranked source and preserves current two-source results byte-for-byte.

- [ ] **Step 4: Expose bounded debug and trace fields**

Add `documentExpansionHits` and `documentExpansionFused` arrays to `LawAiDebugResponse`. Extend `Item` with nullable `documentExpansionRank`, `documentExpansionAnchorType`, `documentExpansionReason`, and `documentExpansionOverlap`. Add trace stage `document-expansion` and reason constants:

```java
public static final String DOCUMENT_NOT_ANCHORED = "DOCUMENT_NOT_ANCHORED";
public static final String DOCUMENT_MATCH_AMBIGUOUS = "DOCUMENT_MATCH_AMBIGUOUS";
public static final String DOCUMENT_LIMIT = "DOCUMENT_LIMIT";
public static final String DOCUMENT_CHUNK_LIMIT = "DOCUMENT_CHUNK_LIMIT";
```

Keep snippets under the existing debug authorization and truncation rules; the new metadata fields must not contain chunk text.

- [ ] **Step 5: Include properties in the live runtime identity**

Inject `LawAiDocumentExpansionProperties` into `LawAiAnswerService`, supply the disabled fallback for direct unit construction, and pass it to `RuntimeConfigurationIdentity.sha256(...)`.

- [ ] **Step 6: Verify focused behavior**

```powershell
.\mvnw.cmd -Dtest=LawAiAnswerServiceDocumentExpansionTests,LawAiAnswerServiceCoverageAwareTests,LawAiRuntimeInfoTests,RuntimeConfigurationIdentityTests test
```

Expected: all pass; coverage-aware and control retrieval assertions remain unchanged.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java src/main/java/com/kaces/pandora/ai/answer/LawAiDebugResponse.java src/main/java/com/kaces/pandora/ai/answer/RetrievalCandidateTrace.java src/test/java/com/kaces/pandora/ai/answer/LawAiAnswerServiceDocumentExpansionTests.java src/test/java/com/kaces/pandora/ai/answer/LawAiRuntimeInfoTests.java
git commit -m "feat: expose document expansion shadow retrieval"
```

---

### Task 7: Capture Expansion Evidence in the Offline Evaluator

**Files:**
- Modify: `scripts/rag-retrieval-eval.js`
- Modify: `scripts/rag-retrieval-eval.test.js`
- Create: `scripts/lib/document-expansion-selection.js`
- Create: `scripts/document-expansion-selection.test.js`
- Create: `scripts/document-expansion-select.js`

**Interfaces:**

```javascript
export function assertDocumentExpansionCapture(response) {}
export function summarizeDocumentExpansionRun(run) {}
export function selectDocumentExpansionPolicy({ manifest, run1, run2, policies }) {}
```

- [ ] **Step 1: Write failing debug-schema and capture tests**

Require `documentExpansionHits` and `documentExpansionFused` arrays. Capture only:

```javascript
{
  candidateKey,
  documentId,
  rank,
  anchorType,
  reason,
  overlapsExistingSource,
  matchedAuditGroupIndexes,
}
```

Reject missing/duplicate candidate keys, invalid document IDs/ranks, unknown reason codes, more than 24 expansion hits, more than eight unique hits per document, or provenance/config mismatches.

- [ ] **Step 2: Verify RED**

```powershell
node --test .\scripts\rag-retrieval-eval.test.js .\scripts\document-expansion-selection.test.js
```

Expected: new arrays/module are missing.

- [ ] **Step 3: Implement bounded capture and summaries**

Extend per-case metrics with candidate-source presence, expansion-source presence, shadow-fused presence, first-drop stage, all-required hit, any-required hit, and matched required-group count. Keep control metrics separately so a candidate cannot hide a baseline regression.

- [ ] **Step 4: Implement the fail-closed selector**

Use frozen thresholds from the spec:

```javascript
const TRAINING_BASELINE = {
  allRequired: 7,
  anyRequired: 14,
  matchedGroups: 23,
  caseCount: 24,
};
```

Return `ELIGIBLE_FOR_DIFFICULT_EVAL` only when both runs exceed `7/24` all-required, preserve every baseline-passing case, keep any-required at least `14/24`, keep matched groups at least `23`, select the same policy, have request errors `0`, Qdrant failures `0`, and identical immutable provenance. Otherwise return a named fail-closed status such as `NO_DOCUMENT_EXPANSION_IMPROVEMENT`, `BASELINE_REGRESSION`, or `PROVENANCE_MISMATCH`.

- [ ] **Step 5: Verify GREEN**

```powershell
node --test .\scripts\rag-retrieval-eval.test.js .\scripts\document-expansion-selection.test.js .\scripts\coverage-aware-selection.test.js .\scripts\rrf-weight-selection.test.js
```

Expected: all Node tests pass and prior snapshot schemas remain readable.

- [ ] **Step 6: Commit**

```powershell
git add scripts/rag-retrieval-eval.js scripts/rag-retrieval-eval.test.js scripts/lib/document-expansion-selection.js scripts/document-expansion-selection.test.js scripts/document-expansion-select.js
git commit -m "feat: evaluate document expansion recall"
```

---

### Task 8: Focused, Full, and Independent Verification

**Files:**
- Modify: `docs/rag-quality-gate.md`
- Create: `docs/superpowers/specs/2026-08-24-document-first-candidate-expansion-verification.md`
- Modify: the existing Task 15 progress ledger under `.superpowers/sdd/2026-07-30-rag-retrieval-evidence-shadow-migration/`

- [ ] **Step 1: Run all focused backend tests**

```powershell
.\mvnw.cmd -Dtest=DocumentSearchAnchorExtractorTests,QuestionSearchPlanTests,LawAiDocumentExpansionPropertiesTests,RuntimeConfigurationIdentityTests,LawChunkMapperXmlTests,RagDocumentMapperXmlTests,DocumentCandidateExpansionTests,DocumentExpansionSearchServiceTests,LawAiAnswerServiceDocumentExpansionTests,LawAiAnswerServiceCoverageAwareTests,LawAiRuntimeInfoTests test
```

Expected: all pass, no skipped assertion caused by missing environment.

- [ ] **Step 2: Run all relevant Node tests**

```powershell
node --test .\scripts\rag-retrieval-eval.test.js .\scripts\document-expansion-selection.test.js .\scripts\coverage-aware-selection.test.js .\scripts\rrf-weight-selection.test.js .\scripts\rag-eval-provenance.test.js
```

Expected: all pass.

- [ ] **Step 3: Run the complete backend suite once for the exact candidate tree**

```powershell
.\mvnw.cmd test
```

Expected: zero failures and zero errors. Record legitimate environment skips separately.

- [ ] **Step 4: Verify repository and runtime-safety invariants**

```powershell
git diff --check
git status --short --branch
.\scripts\status-pandora.ps1
```

Expected: no whitespace errors, only intended branch changes, app-dev/Qdrant status observable, and no action against `18080` or `output/`.

- [ ] **Step 5: Perform one implementation review and fix only Critical/Important scope violations**

Review production independence from oracle fields, exact SQL bounds, control-order identity, external-call counts, trace privacy, failure fallback, and evaluation provenance. Allow at most one fix/re-review round unless an unresolved Critical or Important defect directly violates the spec.

- [ ] **Step 6: Document verification and commit**

Record exact commit, test counts, config identity inputs, authority flags, known skips, and the fact that no external evaluation ran yet.

```powershell
git add docs/rag-quality-gate.md docs/superpowers/specs/2026-08-24-document-first-candidate-expansion-verification.md .superpowers/sdd/2026-07-30-rag-retrieval-evidence-shadow-migration
git commit -m "docs: verify document expansion shadow"
```

---

### Task 9: Prepare and Run the Promotion Ladder Under Exact Approval

**Files:**
- Create: `.superpowers/sdd/2026-07-30-rag-retrieval-evidence-shadow-migration/task-15-document-expansion-training-manifest.json`
- Create after execution: two bounded training run JSON files and one selection JSON/Markdown report in the same ledger directory
- Create only after training passes: difficult-run and holdout-run manifests/reports in the same ledger directory
- Modify: both existing Task 15 progress ledgers

- [ ] **Step 1: Build a candidate JAR without changing app-dev or batch-runner**

```powershell
.\mvnw.cmd -DskipTests package
```

Compute and record the JAR SHA-256. Do not promote it to port `18080`.

- [ ] **Step 2: Prepare the frozen 24-case training execution manifest read-only**

Record exact case IDs/questions, two runs, K/capture/concurrency, candidate JAR hash, runtime instance, index revision, configuration identity, authority flags, collection names, model names, expected request counts, destination OpenAI Embedding API, and output evidence paths. Canonicalize and compute one immutable SHA-256 approval hash.

- [ ] **Step 3: Stop for exact external-execution approval**

Present the exact hash, number of questions and calls, destination, purpose, and mutation/read-only effects. Do not launch from broad advance approval. Approval must identify this manifest/hash and the OpenAI payload purpose.

- [ ] **Step 4: Recheck every fence immediately before a one-time launch**

Confirm runtime instance, index revision, config identity, candidate JAR hash, law/rag DB-Qdrant parity, Qdrant readiness/failure count, authority flags false, manifest hash uniqueness, no active conflicting run, and port `18080` untouched. Any drift is a hard stop requiring a new manifest/hash.

- [ ] **Step 5: Execute training twice and select once**

Run the exact manifest without changing questions or policies. Preserve raw rank captures and stdout. Run `scripts/document-expansion-select.js` once over both completed runs. Never retry a lost/ambiguous request blindly; inspect durable/read-only evidence and fail closed.

- [ ] **Step 6: Apply the training gate**

- If status is not `ELIGIBLE_FOR_DIFFICULT_EVAL`, keep authority false, archive evidence, update both ledgers, and stop.
- If it passes, prepare a new immutable difficult-12 manifest and obtain exact approval before its OpenAI calls.

- [ ] **Step 7: Run difficult-12 twice only after approval and apply existing gates**

Require recall/non-regression, false-ground, latency, repeatability, request-error, Qdrant-failure, and provenance gates already defined for Task 15. Failure keeps authority false and stops before holdout.

- [ ] **Step 8: Run untouched holdout twice only after a separate exact approval**

Reuse no holdout result during tuning. Require the existing holdout promotion gates and stable policy/provenance in both runs.

- [ ] **Step 9: Activate only after every gate passes**

Change only the selected bounded configuration. Keep an immediate configuration rollback path. Rebuild, rerun focused/full verification for the changed configuration identity, and document before/after evidence. If any gate fails, do not activate.

- [ ] **Step 10: Final commit and branch handoff**

```powershell
git add .superpowers/sdd/2026-07-30-rag-retrieval-evidence-shadow-migration docs/superpowers/specs/2026-08-24-document-first-candidate-expansion-verification.md src/main/resources/application.properties
git commit -m "docs: record document expansion evaluation"
git status --short --branch
```

Expected: clean feature worktree, immutable evidence linked from both ledgers, and an explicit `PROMOTED` or fail-closed non-promotion outcome.

