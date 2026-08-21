# Coverage-Aware Fusion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a deterministic, bounded document-sibling rescue stage after pure RRF and promote it only if independent training and every existing Task 15 safety gate pass.

**Architecture:** Keep vector search, BM25, and pure RRF unchanged. Hydrate the existing fused union, run a pure coverage-aware reorderer with document identity and a fixed rescue policy, retain both baseline and coverage-aware orders in shadow diagnostics, and mirror the same algorithm in the offline selector. Runtime authority remains behind the existing RRF authority flag and the new coverage-aware enable flag.

**Tech Stack:** Java 17, Spring Boot configuration properties, JUnit 5, AssertJ, Node.js built-in test runner, existing Pandora retrieval evaluator and runtime scripts.

**Spec:** `docs/superpowers/specs/2026-08-21-coverage-aware-fusion-design.md`

## Global Constraints

- Pure RRF stays at weights `1.0/1.0`, `k=60`, and fused limit `100` unless a separately verified configuration says otherwise.
- Production ranking must not inspect question text, answer-oracle groups, audit aliases, or document-specific rules.
- Rescue uses only candidates already present in the hydrated fused union; it performs no new MariaDB, Qdrant, OpenAI, or other external request.
- Maximum production rescue budget is `2`, with at most `1` rescued sibling per `target:documentId`.
- `rrf-authoritative=false` and semantic authoritative mode remain false until the full promotion ladder passes.
- Never start, stop, restart, promote to, or modify port `18080`.
- Never read, modify, move, or delete the untracked `output/` directory.
- Require exact execution approval before sending any new evaluation payload to an OpenAI API.
- Fail closed on candidate identity, provenance, rank stability, database/Qdrant parity, runtime, artifact, or listener ambiguity.

---

### Task 1: Pure Coverage-Aware Reordering Component

**Files:**
- Create: `src/main/java/com/kaces/pandora/semantic/lexical/CoverageAwareFusion.java`
- Create: `src/test/java/com/kaces/pandora/semantic/lexical/CoverageAwareFusionTests.java`

**Interfaces:**
- Consumes: `List<ReciprocalRankFusion.RrfHit>`, `Map<String, Long>` keyed by `target:chunkId`, `Policy`, and top K.
- Produces: `CoverageAwareFusion.Result rerank(...)`, containing unchanged baseline, reordered ranking, rescue records, and a status.

- [ ] **Step 1: Write failing tests for the bounded rescue contract**

Add tests that create 32 deterministic RRF hits and assert:

```java
CoverageAwareFusion.Result result = new CoverageAwareFusion().rerank(
    baseline,
    documentIds,
    new CoverageAwareFusion.Policy(true, 2, 1, 30),
    30
);

assertThat(result.status()).isEqualTo(CoverageAwareFusion.Status.APPLIED);
assertThat(result.ranking()).hasSize(30);
assertThat(result.ranking()).extracting(ReciprocalRankFusion.RrfHit::candidateKey)
    .contains("law:31")
    .doesNotHaveDuplicates();
assertThat(result.rescues()).singleElement().satisfies(rescue -> {
    assertThat(rescue.anchorCandidateKey()).isEqualTo("law:1");
    assertThat(rescue.candidateKey()).isEqualTo("law:31");
    assertThat(rescue.reason()).isEqualTo("DOCUMENT_SIBLING_RESCUE");
});
```

Cover disabled/zero-budget identity, cross-target isolation, invalid/non-positive document IDs, source-rank limit 20 versus 30, one rescue per document, two-rescue global budget, deterministic ties, protected cross-source anchors, and duplicate/malformed fallback to baseline.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
.\mvnw.cmd -Dtest=CoverageAwareFusionTests test
```

Expected: compilation failure because `CoverageAwareFusion` does not exist.

- [ ] **Step 3: Implement the minimal pure component**

Use these public records and enum:

```java
@Component
public class CoverageAwareFusion {
    public Result rerank(
        List<ReciprocalRankFusion.RrfHit> baseline,
        Map<String, Long> documentIds,
        Policy policy,
        int topK
    ) {
        List<ReciprocalRankFusion.RrfHit> safe = baseline == null
            ? List.of() : List.copyOf(baseline);
        if (policy == null || !policy.enabled() || policy.maxRescues() == 0) {
            return new Result(safe, safe, List.of(), Status.DISABLED);
        }
        if (topK <= 0 || safe.size() < topK || documentIds == null
            || safe.stream().map(ReciprocalRankFusion.RrfHit::candidateKey).distinct().count() != safe.size()
            || safe.stream().anyMatch(hit -> documentIds.getOrDefault(hit.candidateKey(), 0L) <= 0)) {
            return new Result(safe, safe, List.of(), Status.FALLBACK_BASELINE);
        }
        Map<String, ReciprocalRankFusion.RrfHit> anchors = eligibleAnchors(safe, documentIds, topK);
        List<Proposal> proposals = eligibleProposals(safe, documentIds, anchors, policy, topK);
        List<Proposal> selected = selectWithinBudgets(proposals, policy);
        if (selected.isEmpty()) {
            return new Result(safe, safe, List.of(), Status.NO_ELIGIBLE_SIBLING);
        }
        return replaceTail(safe, documentIds, anchors, selected, topK);
    }

    public record Policy(
        boolean enabled,
        int maxRescues,
        int maxRescuesPerDocument,
        int sourceRankLimit
    ) {}

    public record Rescue(
        String candidateKey,
        String documentKey,
        String anchorCandidateKey,
        int baselineRank,
        int rescuedRank,
        String reason
    ) {}

    public record Result(
        List<ReciprocalRankFusion.RrfHit> baseline,
        List<ReciprocalRankFusion.RrfHit> ranking,
        List<Rescue> rescues,
        Status status
    ) {}

    public enum Status { DISABLED, NO_ELIGIBLE_SIBLING, APPLIED, FALLBACK_BASELINE }

    private record Proposal(
        ReciprocalRankFusion.RrfHit sibling,
        ReciprocalRankFusion.RrfHit anchor,
        long documentId,
        int anchorRank,
        int baselineRank,
        int bestSourceRank
    ) {}
}
```

Build anchors from baseline ranks `1..topK`, build eligible sibling proposals from ranks `topK+1..100`, sort exactly as the spec declares, remove replaceable tail entries from rank 30 upward, append selected siblings, and validate exactly K unique keys before returning `APPLIED`. Catch no broad exception inside the pure component; return `FALLBACK_BASELINE` for explicit invalid-input invariants.

Implement the four private helpers referenced above with these exact signatures:

```java
private Map<String, ReciprocalRankFusion.RrfHit> eligibleAnchors(
    List<ReciprocalRankFusion.RrfHit> baseline, Map<String, Long> documentIds, int topK);
private List<Proposal> eligibleProposals(
    List<ReciprocalRankFusion.RrfHit> baseline,
    Map<String, Long> documentIds,
    Map<String, ReciprocalRankFusion.RrfHit> anchors,
    Policy policy,
    int topK);
private List<Proposal> selectWithinBudgets(List<Proposal> proposals, Policy policy);
private Result replaceTail(
    List<ReciprocalRankFusion.RrfHit> baseline,
    Map<String, Long> documentIds,
    Map<String, ReciprocalRankFusion.RrfHit> anchors,
    List<Proposal> selected,
    int topK);
```

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2. Expected: all `CoverageAwareFusionTests` pass.

- [ ] **Step 5: Run adjacent RRF tests**

```powershell
.\mvnw.cmd -Dtest=ReciprocalRankFusionTests,CoverageAwareFusionTests test
```

Expected: both classes pass with pure RRF behavior unchanged.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/kaces/pandora/semantic/lexical/CoverageAwareFusion.java src/test/java/com/kaces/pandora/semantic/lexical/CoverageAwareFusionTests.java
git commit -m "feat: add bounded coverage-aware fusion"
```

---

### Task 2: Capture Document Identity and Mirror the Algorithm Offline

**Files:**
- Modify: `scripts/rag-retrieval-eval.js`
- Modify: `scripts/rag-retrieval-eval.test.js`
- Create: `scripts/lib/coverage-aware-selection.js`
- Create: `scripts/coverage-aware-selection.test.js`

**Interfaces:**
- Consumes: evaluator debug items with `candidateKey`, `documentId`, source rank, and `matchedAuditGroupIndexes`.
- Produces: rank snapshots with `documentId` and `rerankCoverage({ ranking, documentIdByCandidate, policy, topK })` outcome-equivalent to the Java component.

- [ ] **Step 1: Write failing rank-capture tests**

Extend the existing capture test to assert:

```javascript
assert.deepEqual(snapshot.vector[0], {
  candidateKey: 'law:101',
  documentId: 9001,
  rank: 1,
  matchedAuditGroupIndexes: [0],
});
```

Add rejection tests for missing, non-safe-integer, non-positive, or conflicting document IDs when a snapshot is used for coverage selection.

- [ ] **Step 2: Verify capture tests fail**

```powershell
node --test .\scripts\rag-retrieval-eval.test.js
```

Expected: the expected `documentId` is absent.

- [ ] **Step 3: Add `documentId` to bounded capture items**

Change `captureRankedItems` to emit:

```javascript
{
  candidateKey: candidateKey(item),
  documentId: Number(item?.documentId),
  rank: index + 1,
  matchedAuditGroupIndexes: uniqueSortedGroups(item),
}
```

Do not add title, snippet, source path, or question text.

- [ ] **Step 4: Write failing offline reordering tests**

Create tests that use the same fixtures and expected ranking as Task 1:

```javascript
const result = rerankCoverage({
  ranking,
  documentIdByCandidate,
  policy: { enabled: true, maxRescues: 2, maxRescuesPerDocument: 1, sourceRankLimit: 30 },
  topK: 30,
});
assert.equal(result.status, 'APPLIED');
assert.deepEqual(result.ranking.map((item) => item.candidateKey), expectedKeys);
```

Cover every Java test vector so Java and Node tie-breaking cannot drift.

- [ ] **Step 5: Verify offline tests fail**

```powershell
node --test .\scripts\coverage-aware-selection.test.js
```

Expected: module or exported function missing.

- [ ] **Step 6: Implement the Node mirror**

Export:

```javascript
module.exports = {
  COVERAGE_POLICY_GRID,
  rerankCoverage,
  validateDocumentIdentitySnapshot,
};
```

Declare the five policies exactly once in `COVERAGE_POLICY_GRID`: baseline,
`1/1/20`, `1/1/30`, `2/1/20`, and `2/1/30` (`maxRescues/maxPerDocument/sourceRankLimit`). Reuse `fuseRanks` from `scripts/lib/rrf-weight-selection.js`; do not duplicate the RRF formula.

- [ ] **Step 7: Run both Node test files**

```powershell
node --test .\scripts\rag-retrieval-eval.test.js .\scripts\coverage-aware-selection.test.js
```

Expected: all tests pass.

- [ ] **Step 8: Commit**

```powershell
git add scripts/rag-retrieval-eval.js scripts/rag-retrieval-eval.test.js scripts/lib/coverage-aware-selection.js scripts/coverage-aware-selection.test.js
git commit -m "feat: capture document identity for fusion replay"
```

---

### Task 3: Stable Independent-Run Policy Selector

**Files:**
- Modify: `scripts/lib/coverage-aware-selection.js`
- Modify: `scripts/coverage-aware-selection.test.js`
- Create: `scripts/coverage-aware-select.js`

**Interfaces:**
- Consumes: `manifestInfo`, two complete training run artifacts, `topK=30`, and `rrfK=60`.
- Produces: `selectCoveragePolicy(...)` artifact with status, provenance, baseline, candidates, winners by run, and recommendation.

- [ ] **Step 1: Write failing selector guard tests**

Add tests proving:

```javascript
const selection = selectCoveragePolicy({ manifestInfo, run1, run2, topK: 30, rrfK: 60 });
assert.equal(selection.status, 'RECOMMENDED');
assert.deepEqual(selection.winnersByRun.run1.policy, expectedPolicy);
assert.deepEqual(selection.winnersByRun.run2.policy, expectedPolicy);
assert.deepEqual(selection.recommendation.policy, expectedPolicy);
```

Also assert baseline fallback for divergent winners, improvement in one run only, baseline-case regression, any-required regression, total-group regression, provenance mismatch, manifest mismatch, reordered cases, incomplete capture, and document identity conflict.

- [ ] **Step 2: Verify selector tests fail**

```powershell
node --test .\scripts\coverage-aware-selection.test.js
```

Expected: `selectCoveragePolicy` is missing.

- [ ] **Step 3: Implement guarded selection**

Add:

```javascript
function selectCoveragePolicy({ manifestInfo, run1, run2, topK = 30, rrfK = 60 }) {
  // validate with the same manifest/provenance fences as selectWeights
  // independently evaluate COVERAGE_POLICY_GRID against pure RRF
  // require the same eligible winner in both runs
}
```

Return schema version 1 and one of `RECOMMENDED`, `NO_COVERAGE_IMPROVEMENT`, or `NO_STABLE_COVERAGE_IMPROVEMENT`. Tie-break by all-required, any-required, total groups, fewer rescues, lower source-rank limit, then grid order. Never mutate a runtime file.

- [ ] **Step 4: Add the CLI wrapper**

`scripts/coverage-aware-select.js` must require exact `--manifest`, `--run1`, `--run2`, and `--output` paths, load the existing case catalog, call `selectCoveragePolicy`, and write JSON with a terminal newline. Missing or extra arguments exit nonzero before writing.

- [ ] **Step 5: Run selector and existing weight-selector tests**

```powershell
node --test .\scripts\coverage-aware-selection.test.js .\scripts\rrf-weight-selection.test.js .\scripts\rag-eval-provenance.test.js
```

Expected: all pass and the existing weight selector remains unchanged.

- [ ] **Step 6: Commit**

```powershell
git add scripts/lib/coverage-aware-selection.js scripts/coverage-aware-selection.test.js scripts/coverage-aware-select.js
git commit -m "feat: select stable coverage-aware policy"
```

---

### Task 4: Runtime Configuration and Shadow Integration

**Files:**
- Create: `src/main/java/com/kaces/pandora/semantic/config/LawAiCoverageAwareProperties.java`
- Create: `src/test/java/com/kaces/pandora/semantic/config/LawAiCoverageAwarePropertiesTests.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/RuntimeConfigurationIdentity.java`
- Modify: `src/test/java/com/kaces/pandora/ai/answer/RuntimeConfigurationIdentityTests.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`
- Modify focused `LawAiAnswerService` tests under `src/test/java/com/kaces/pandora/ai/answer/`
- Modify: `src/main/resources/application.properties`

**Interfaces:**
- Consumes: `CoverageAwareFusion`, hydrated chunk map, RRF properties, and coverage properties.
- Produces: baseline fused chunks and coverage-aware fused chunks in `HybridRetrieval`; control order unchanged while RRF authority is false.

- [ ] **Step 1: Write failing property and configuration-hash tests**

Require defaults and validation:

```java
LawAiCoverageAwareProperties defaults = new LawAiCoverageAwareProperties(false, 0, 1, 30);
assertThat(defaults.enabled()).isFalse();
assertThat(defaults.policy()).isEqualTo(new CoverageAwareFusion.Policy(false, 0, 1, 30));
assertThatThrownBy(() -> new LawAiCoverageAwareProperties(true, 1, 2, 30))
    .isInstanceOf(IllegalArgumentException.class);
```

Assert `RuntimeConfigurationIdentity.sha256(...)` changes when any coverage policy value changes.

- [ ] **Step 2: Verify property tests fail**

```powershell
.\mvnw.cmd -Dtest=LawAiCoverageAwarePropertiesTests,RuntimeConfigurationIdentityTests test
```

Expected: missing properties type/signature.

- [ ] **Step 3: Implement properties and provenance**

Create:

```java
@ConfigurationProperties(prefix = "law-ai.retrieval.coverage-aware")
public record LawAiCoverageAwareProperties(
    boolean enabled,
    int maxRescues,
    int maxRescuesPerDocument,
    int sourceRankLimit
) {
    public CoverageAwareFusion.Policy policy() {
        return new CoverageAwareFusion.Policy(
            enabled,
            maxRescues,
            maxRescuesPerDocument,
            sourceRankLimit
        );
    }
}
```

Append all four canonical fields to `RuntimeConfigurationIdentity`. Add baseline properties:

```properties
law-ai.retrieval.coverage-aware.enabled=false
law-ai.retrieval.coverage-aware.max-rescues=0
law-ai.retrieval.coverage-aware.max-rescues-per-document=1
law-ai.retrieval.coverage-aware.source-rank-limit=30
```

- [ ] **Step 4: Write failing shadow-integration tests**

Use existing service fixtures to assert that an eligible sibling appears in the coverage-aware shadow list while `searchedChunks` retains the existing control order when `rrf-authoritative=false`. Verify the mapper, Qdrant client, and embedding client call counts do not increase relative to baseline.

- [ ] **Step 5: Verify integration tests fail**

Run only the named touched `LawAiAnswerService*Tests` classes. Expected: no coverage-aware order is currently produced.

- [ ] **Step 6: Integrate after hydration**

Inject `CoverageAwareFusion` and `LawAiCoverageAwareProperties`. Build:

```java
Map<String, Long> documentIds = chunkById.entrySet().stream()
    .collect(toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().documentId()));
CoverageAwareFusion.Result coverage = coverageAwareFusion.rerank(
    fusedHits,
    documentIds,
    coverageProperties.policy(),
    30
);
```

Extend `HybridRetrieval` to retain pure fused hits/chunks, coverage hits/chunks, and rescue records. Select coverage chunks only inside the RRF candidate branch and only when coverage is enabled; `selectCandidateOrder(..., rrfAuthoritative)` remains the final authority boundary.

- [ ] **Step 7: Run focused configuration and service tests**

Expected: all touched tests pass, and verified control-path tests remain outcome-identical.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/kaces/pandora/semantic/config/LawAiCoverageAwareProperties.java src/test/java/com/kaces/pandora/semantic/config/LawAiCoverageAwarePropertiesTests.java src/main/java/com/kaces/pandora/ai/answer/RuntimeConfigurationIdentity.java src/test/java/com/kaces/pandora/ai/answer/RuntimeConfigurationIdentityTests.java src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java src/test/java/com/kaces/pandora/ai/answer src/main/resources/application.properties
git commit -m "feat: integrate coverage-aware fusion in shadow"
```

---

### Task 5: Shadow Diagnostics and Candidate-Loss Trace

**Files:**
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiDebugResponse.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/RetrievalCandidateTrace.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/RetrievalTraceCollector.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`
- Modify relevant tests: `LawAiDebugResponseItemTests.java`, `RetrievalTraceCollectorTests.java`, and retrieval-eval tests.

**Interfaces:**
- Consumes: `CoverageAwareFusion.Result` and both fusion orders.
- Produces: `coverageFused` debug stage, coverage rank, anchor key, reason, and `coverage-fused` loss-stage data.

- [ ] **Step 1: Write failing debug serialization and trace tests**

Assert a rescued item exposes:

```java
assertThat(item.fusedRank()).isEqualTo(64);
assertThat(item.coverageFusedRank()).isEqualTo(30);
assertThat(item.coverageAnchorCandidateKey()).isEqualTo("law:anchor");
assertThat(item.coverageReason()).isEqualTo("DOCUMENT_SIBLING_RESCUE");
```

Assert candidate traces distinguish `ABSENT_FROM_SOURCE_UNION`, `SOURCE_RANK_LIMIT`, `INVALID_DOCUMENT_IDENTITY`, and `TOP_K_DISPLACED` at stage `coverage-fused`.

- [ ] **Step 2: Verify focused diagnostics tests fail**

Run the three focused test classes. Expected: new accessors/stage are missing.

- [ ] **Step 3: Add bounded diagnostics**

Add `List<Item> coverageFused` to `LawAiDebugResponse`. Add nullable `coverageFusedRank`, `coverageAnchorCandidateKey`, and `coverageReason` to `Item`. Build maps from rescue records; do not expose any new chunk body or secret.

Extend trace stage ordering with `coverage-fused` between `fused` and `merged`, preserving every existing stage and reason.

- [ ] **Step 4: Update evaluator extraction tests**

Verify `scripts/rag-retrieval-eval.js` includes coverage-stage counts and captures coverage ranks without changing existing source snapshots.

- [ ] **Step 5: Run focused Java and Node diagnostics tests**

```powershell
.\mvnw.cmd -Dtest=LawAiDebugResponseItemTests,RetrievalTraceCollectorTests test
node --test .\scripts\rag-retrieval-eval.test.js .\scripts\rag-eval-provenance.test.js
```

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/kaces/pandora/ai/answer/LawAiDebugResponse.java src/main/java/com/kaces/pandora/ai/answer/RetrievalCandidateTrace.java src/main/java/com/kaces/pandora/ai/answer/RetrievalTraceCollector.java src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java src/test/java/com/kaces/pandora/ai/answer/LawAiDebugResponseItemTests.java src/test/java/com/kaces/pandora/ai/answer/RetrievalTraceCollectorTests.java scripts/rag-retrieval-eval.js scripts/rag-retrieval-eval.test.js
git commit -m "feat: trace coverage-aware retrieval outcomes"
```

---

### Task 6: Local Verification and Shadow Deployment Preparation

**Files:**
- Modify: `docs/rag-quality-handoff-20260820-task15-progress.md`
- Create only under ignored `logs/`: local verification artifacts generated by existing scripts.

**Interfaces:**
- Consumes: completed implementation commits.
- Produces: one verified JAR and a dry-run evaluation manifest; no external evaluation request yet.

- [ ] **Step 1: Run the full focused Node suite**

```powershell
node --test .\scripts\rag-retrieval-eval.test.js .\scripts\rrf-weight-selection.test.js .\scripts\coverage-aware-selection.test.js .\scripts\rag-eval-provenance.test.js
```

Expected: all tests pass with zero failures.

- [ ] **Step 2: Run the full backend suite once**

```powershell
.\mvnw.cmd test
```

Expected: zero failures and zero errors; record the exact test and skip counts.

- [ ] **Step 3: Review implementation against the spec**

Confirm no new external/database request, no oracle input in production, budgets `2/1`, stable tie-breaking, pure-RRF fallback, both authoritative flags false, and no change under `output/` or to port `18080`.

- [ ] **Step 4: Build one JAR and inspect runtime status**

Use documented project scripts only:

```powershell
.\scripts\status-pandora.ps1
.\mvnw.cmd -DskipTests package
```

Do not stop or deploy yet. Compute the JAR SHA-256 and capture the current app-dev runtime/JAR/config/index/lexical/Qdrant/listener fences.

- [ ] **Step 5: Prepare the exact training dry-run**

Generate an ordered manifest for the same 24 training case IDs, K `30`, capture limit `100`, concurrency `1`, two runs, expected OpenAI Embedding destination, and output paths. Compute its immutable request hash. Confirm no difficult or holdout ID appears.

- [ ] **Step 6: Update the handoff and commit**

Record implementation commits, local test evidence, candidate configuration grid, current flags, dry-run hash, and the explicit external-approval blocker.

```powershell
git add docs/rag-quality-handoff-20260820-task15-progress.md
git commit -m "docs: prepare coverage-aware training evaluation"
```

---

### Task 7: Approved Training, Acceptance, and Promotion Gates

**Files:**
- Create ignored artifacts under `logs/` for two training captures, selection, difficult-12, holdout, and release evaluation.
- Modify: `src/main/resources/application.properties` only after a stable training recommendation.
- Modify: `docs/rag-quality-handoff-20260820-task15-progress.md` with each terminal gate.

**Interfaces:**
- Consumes: exact approved evaluation manifest/hash and verified JAR.
- Produces: either a fail-closed baseline outcome or a fully gated coverage-aware release candidate.

- [ ] **Step 1: Obtain exact external execution approval**

Present the immutable training request hash, the 24 ordered question IDs, two OpenAI Embedding requests per question, destination, expected Qdrant/MariaDB read-only behavior, runtime fences, and artifact paths. Do not send any payload until that exact approval is present.

- [ ] **Step 2: Recheck fences immediately before execution**

Require the same runtime/JAR/config/index/lexical identities, Qdrant ready with failure count zero, DB/Qdrant parity, no active conflicting evaluation, port `18080` untouched, and request hash unchanged. Fail closed on drift.

- [ ] **Step 3: Run the two training captures and selector**

Use environment variables only; do not pass unsupported CLI flags to `rag-eval-gate.js`. Require `24/24`, request errors `0`, complete document IDs, and matching provenance. Run `coverage-aware-select.js` and archive its JSON result.

- [ ] **Step 4: Stop on baseline recommendation**

If status is not `RECOMMENDED`, leave all coverage properties disabled, update the handoff with the reason, rerun no difficult or holdout case, and commit only evidence/docs.

- [ ] **Step 5: Configure the stable recommendation and deploy only to 8080**

Set only `coverage-aware.enabled`, `max-rescues`, `max-rescues-per-document`, and `source-rank-limit`. Keep `rrf-authoritative=false` and semantic authoritative false. Re-run focused tests and the full backend suite only if the configuration changes the built artifact, then use documented stop/start scripts for app-dev `8080`. Never promote to `18080`.

- [ ] **Step 6: Obtain exact difficult-12 approval and run twice**

Present the 12 ordered IDs and immutable request hash. After approval, run K `30`, concurrency `1`, twice under identical fences. Require identical coverage ranks, `>=7/8` all-required recall, no baseline-case regression, errors `0`, false-ground regression `0`, and warm p95 `<=500ms`.

- [ ] **Step 7: Stop and restore baseline on any difficult-12 failure**

Set coverage properties back to disabled defaults, rebuild/redeploy only app-dev if needed, verify fences, update the handoff, and do not consume holdout.

- [ ] **Step 8: Obtain exact untouched-holdout approval and run twice**

Only after difficult-12 passes, present the exact ordered holdout IDs/hash and obtain approval. Run twice and require existing recall, safety, provenance, repeatability, latency, and false-ground gates.

- [ ] **Step 9: Continue existing 85-case and full release gates**

Use the predeclared Task 15 ladder without changing policy grid or tuning from acceptance outcomes. Enable `rrf-authoritative=true` only after every release gate passes. Enable semantic authoritative mode only after its independent unsafe-disagreement gate also passes.

- [ ] **Step 10: Final verification and commit**

Run the focused Node suite, full Maven suite, runtime smoke, Qdrant health/parity, configuration identity, and listener checks once on the final exact commit/JAR. Update the handoff and commit the selected configuration or baseline restoration.

```powershell
git add src/main/resources/application.properties docs/rag-quality-handoff-20260820-task15-progress.md
git commit -m "feat: finalize gated coverage-aware fusion"
```
