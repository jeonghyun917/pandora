# Group-Balanced BM25 Retrieval Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Use superpowers:test-driven-development for every production behavior change and superpowers:verification-before-completion before claiming success.

**Goal:** Add a bounded, deterministic, shadow-only BM25 multi-query candidate that gives distinct entity, intent, condition, and synonym groups independent posting budgets, without changing current answer authority.

**Architecture:** `QuestionSearchPlan` creates at most four deterministic lexical variants. `GroupBalancedBm25SearchService` runs the existing BM25 service once per variant and delegates strict validation and reciprocal-rank fusion to `LexicalVariantFusion`. `LawAiAnswerService` captures the resulting candidate separately in debug output while `variantAuthoritative=false` keeps all control retrieval, grounds, and answers unchanged.

**Tech Stack:** Java 21, Spring Boot configuration properties, JUnit 5, AssertJ, Mockito where the database boundary must be isolated, Node.js evaluation scripts.

---

### Task 1: Generate deterministic lexical variants

**Files:**
- Modify: `src/main/java/com/kaces/pandora/common/text/QuestionSearchPlan.java`
- Modify: `src/test/java/com/kaces/pandora/common/text/QuestionSearchPlanTests.java`

- [ ] Add a failing test proving `bm25Variants()` returns the original/focused variant first, then entity-intent, direct-evidence, and synonym-intent variants in deterministic order.
- [ ] Run `./mvnw.cmd -Dtest=QuestionSearchPlanTests test` and confirm the missing method fails compilation for the expected reason.
- [ ] Add `QuestionSearchPlan.LexicalVariant(String id, String query, List<String> plannedKeywords, String tokenSetHash)` and `List<LexicalVariant> bm25Variants()`.
- [ ] Build each variant only from the existing question/profile fields, normalize whitespace, reject weak/empty variants, deduplicate by sorted normalized token set, and cap the result at four.
- [ ] Add failing edge-case tests for duplicate token sets, short/empty questions, and the four-variant bound, then implement the minimum behavior to pass.
- [ ] Re-run the focused test class and keep the existing planner tests green.

### Task 2: Fuse independent variant rankings fail-closed

**Files:**
- Create: `src/main/java/com/kaces/pandora/semantic/lexical/LexicalVariantFusion.java`
- Create: `src/test/java/com/kaces/pandora/semantic/lexical/LexicalVariantFusionTests.java`

- [ ] Write RED tests for `fuse(List<VariantHits>, double rrfK, int limit)` using hand-calculated `1 / (60 + rank)` scores.
- [ ] Cover deterministic ties: fused score descending, best source rank ascending, target ascending, chunk ID ascending, document ID ascending.
- [ ] Cover candidate deduplication, sorted matched-term union, and contributing variant ranks.
- [ ] Cover fail-closed rejection of more than four variants, duplicate variant IDs, duplicate candidates within one variant, non-positive identities/ranks, and non-finite or non-positive scores.
- [ ] Implement `Status { APPLIED, EMPTY, INVALID_INPUT }`, immutable `VariantHits`, `Hit`, and `Result` records with no partial output on invalid input.
- [ ] Run `./mvnw.cmd -Dtest=LexicalVariantFusionTests test` after each RED/GREEN cycle.

### Task 3: Orchestrate independent BM25 searches behind safe configuration

**Files:**
- Create: `src/main/java/com/kaces/pandora/semantic/config/LawAiLexicalVariantProperties.java`
- Create: `src/main/java/com/kaces/pandora/semantic/lexical/GroupBalancedBm25SearchService.java`
- Create: `src/test/java/com/kaces/pandora/semantic/lexical/GroupBalancedBm25SearchServiceTests.java`
- Modify: `src/main/resources/application.properties`

- [ ] Write RED tests proving disabled shadow mode executes no searches and returns `DISABLED`.
- [ ] Write a RED test proving each distinct planner variant calls `KoreanBm25SearchService.search` independently with its own query/keyword list and the same target/limit bounds.
- [ ] Write RED tests proving one thrown variant search, one malformed fusion input, or invalid configuration returns an empty candidate with a bounded reason code.
- [ ] Implement properties under `law-ai.retrieval.lexical-variant`: `shadowEnabled=false`, `authoritative=false`, `maxVariants=4`, `rrfK=60` with invalid bounds disabling the candidate.
- [ ] Implement the service result with status, reason codes, generated variant hashes, per-variant hit counts, fused hits, and elapsed planning/search/fusion times.
- [ ] Keep `authoritative=false` in committed configuration and add a test that observes this consumer-facing property.
- [ ] Run the two lexical focused test classes.

### Task 4: Integrate a shadow-only candidate into answer diagnostics

**Files:**
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiDebugResponse.java`
- Create: `src/test/java/com/kaces/pandora/ai/answer/LawAiAnswerServiceLexicalVariantTests.java`
- Modify: existing constructor fixtures under `src/test/java/com/kaces/pandora/ai/answer/` only as required by the new injected dependency.

- [ ] Write a RED service test proving shadow hits appear in a separate `bm25VariantHits` debug stage while the control `bm25Hits`, merged candidates, grounds, and selected items remain byte-for-byte equivalent when authority is false.
- [ ] Write a RED failure test proving an empty/failed variant result adds no control candidate and does not change the answer path.
- [ ] Inject `GroupBalancedBm25SearchService`, start its future alongside the existing control BM25 future, hydrate only bounded shadow IDs, and store shadow data in `HybridRetrieval`.
- [ ] Extend debug output with status/reason codes, variant hashes/counts/timings, `bm25VariantHits`, and per-candidate contributing ranks. Do not expose raw queries or evaluation oracle data.
- [ ] Add the shadow stage to candidate traces without including it in any control transition or authority decision.
- [ ] Run the new test plus `LawAiAnswerServiceDocumentExpansionTests` and `LawAiAnswerServiceCoverageAwareTests`.

### Task 5: Capture and select the shadow candidate in offline evaluation

**Files:**
- Modify: `scripts/rag-retrieval-eval.js`
- Modify: `scripts/rag-retrieval-eval.test.js`
- Create: `scripts/lib/group-balanced-bm25-selection.js`
- Create: `scripts/group-balanced-bm25-select.js`
- Create: `scripts/group-balanced-bm25-selection.test.js`
- Create or update: immutable training manifest/evidence files under `docs/evidence/rag-quality/` (never `output/`).

- [ ] Write Node RED tests requiring bounded `bm25VariantHits` capture and rejecting malformed status, hashes, ranks, identities, or unbounded lists.
- [ ] Add evaluation-only required-group presence for control and shadow, ensuring oracle aliases never enter a request or production rank.
- [ ] Write selector RED tests requiring two complete, error-free captures with identical manifests/provenance and deterministic shadow ranks.
- [ ] Implement terminal decisions `SELECTED`, `NO_IMPROVEMENT`, and `INVALID`; reject any lost control group or inconsistent added group.
- [ ] Run `node --test scripts/rag-retrieval-eval.test.js scripts/group-balanced-bm25-selection.test.js`.

### Task 6: Verify and run the promotion ladder conditionally

**Files:**
- Modify only evidence/ledger files produced by the documented evaluation workflow.

- [ ] Run all focused Java and Node tests.
- [ ] Run `./mvnw.cmd test` once on the stable candidate commit and record totals and exact commit SHA.
- [ ] Check runtime with `scripts/status-pandora.ps1`; use app-dev `8080` only, never touch `18080`, and never write `output/`.
- [ ] Freeze the ordered 24-case training payload and provenance, review its exact hash/destination, then run two independent captures only when the required exact external-evaluation approval is present.
- [ ] Run the selector. If `NO_IMPROVEMENT`, keep authority false, archive evidence, and stop later gates. If `SELECTED`, run Difficult-12, holdout, Answer API evaluation, then the approximately 1,004-case release gate in that order.
- [ ] Enable authority only in a separate commit after every prior gate passes; otherwise leave every authority flag false.
- [ ] Use `superpowers:verification-before-completion`, self-review the exact diff, commit, push the feature branch, and report what changed, verification evidence, and any remaining risk.

