# RRF Training and Weight Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a provenance-locked two-run training capture and offline selector that recommends a generalized RRF vector/BM25 weight pair without evaluation leakage.

**Architecture:** Keep API retrieval and model calls in the existing evaluator, adding only an opt-in bounded rank snapshot. Put manifest validation, pure RRF replay, metrics, guardrails, and deterministic selection in a side-effect-free Node library with a thin CLI writer.

**Tech Stack:** Node.js CommonJS, `node:test`, existing Pandora retrieval evaluator and provenance helpers, JSON artifacts, Spring Boot runtime only for later capture.

**Spec:** `docs/superpowers/specs/2026-08-21-rrf-training-weight-selection-design.md`

## Global Constraints

- The training set is exactly the 24 ordered IDs in the spec and excludes every difficult-12 ID.
- The candidate grid is exactly `(1,0.5)`, `(1,0.75)`, `(1,1)`, `(0.75,1)`, `(0.5,1)` with `k=60` and top `30`.
- No difficult, holdout, or `NO_GROUNDS` outcome may influence weight selection.
- Selection never edits runtime configuration or enables authoritative flags.
- Never touch port `18080` or the untracked `output/` directory.

---

### Task 1: Freeze and validate the training manifest

**Files:**
- Create: `src/main/resources/rag-retrieval-training-manifest.json`
- Create: `scripts/lib/rrf-weight-selection.js`
- Create: `scripts/rrf-weight-selection.test.js`

**Interfaces:**
- Consumes: evaluation cases returned by `loadEvalCases(...)`.
- Produces: `loadTrainingManifest(path, allCases)` returning `{ manifest, manifestHash, trainingCases, holdoutCases }`.

- [ ] **Step 1: Write failing manifest tests**

Add real temporary-file tests that require exact count/order, reject duplicate or difficult IDs, reject cases without explicit proposition/condition groups, derive holdout as explicit-oracle cases outside training/difficult, and assert a literal SHA-256 fixture.

- [ ] **Step 2: Run the tests and verify RED**

Run: `node --test .\scripts\rrf-weight-selection.test.js`

Expected: FAIL because `scripts/lib/rrf-weight-selection.js` does not exist.

- [ ] **Step 3: Add the exact manifest and minimal loader**

Create the JSON fields `schemaVersion`, `splitName`, `expectedTrainingCount`, `selectionBasis`, `trainingCaseIds`, and `excludedDifficultCaseIds`. Implement strict parsing, exact validation, byte-level SHA-256, ordered case resolution, and holdout derivation.

- [ ] **Step 4: Run the tests and verify GREEN**

Run: `node --test .\scripts\rrf-weight-selection.test.js`

Expected: all Task 1 tests pass.

- [ ] **Step 5: Commit Task 1**

Stage only the manifest, library, test, spec, and plan. Commit as `Add leakage-safe RRF training split`.

### Task 2: Add opt-in bounded source-rank capture

**Files:**
- Modify: `scripts/rag-retrieval-eval.js`
- Modify: `scripts/rag-retrieval-eval.test.js`

**Interfaces:**
- Consumes: `RAG_RETRIEVAL_CAPTURE_RANK_LIMIT` or `--capture-rank-limit` in `[0,100]`.
- Produces: optional measurement property `sourceRankSnapshot` with `vector` and `bm25` arrays of `{ candidateKey, rank, matchedAuditGroupIndexes }`.

- [ ] **Step 1: Write failing option and snapshot tests**

Test default omission, limit validation, truncation to a literal number, candidate-key/rank preservation, sorted unique audit indexes, and absence of `chunkText`, `body`, and `snippet`.

- [ ] **Step 2: Run the focused evaluator tests and verify RED**

Run: `node --test .\scripts\rag-retrieval-eval.test.js`

Expected: FAIL because the new option and snapshot are absent.

- [ ] **Step 3: Implement minimal capture support**

Parse the option, create `captureSourceRankSnapshot(response, limit)`, and attach it only when the limit is positive. Export the helper for real behavior tests.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `node --test .\scripts\rag-retrieval-eval.test.js`

Expected: all evaluator tests pass.

- [ ] **Step 5: Commit Task 2**

Stage only the evaluator and its test. Commit as `Capture bounded retrieval ranks for RRF training`.

### Task 3: Implement deterministic offline replay and selection

**Files:**
- Modify: `scripts/lib/rrf-weight-selection.js`
- Modify: `scripts/rrf-weight-selection.test.js`
- Create: `scripts/rrf-weight-select.js`

**Interfaces:**
- Consumes: `selectWeights({ manifestInfo, run1, run2, topK: 30, rrfK: 60 })`.
- Produces: `{ status, baseline, recommendation, candidates, provenance }`; CLI accepts `--manifest`, `--run-1`, `--run-2`, and `--output`.

- [ ] **Step 1: Write failing pure-RRF tests**

Use hand-derived literal fixtures to prove score calculation, production tie-breaking, group aggregation across top 30, and stable numeric chunk-ID ordering.

- [ ] **Step 2: Run selector tests and verify RED**

Run: `node --test .\scripts\rrf-weight-selection.test.js`

Expected: FAIL because replay/measurement exports are absent.

- [ ] **Step 3: Implement pure replay and metrics**

Implement `fuseRanks(snapshot, weights, rrfK)`, `measureFused(cases, topK)`, and strict candidate-key parsing without file or network side effects.

- [ ] **Step 4: Run tests and verify GREEN**

Run: `node --test .\scripts\rrf-weight-selection.test.js`

Expected: pure replay tests pass.

- [ ] **Step 5: Write failing validation and selection tests**

Cover provenance mismatch, reordered/missing cases, bounded raw-rank drift, divergent guarded winners, baseline regression rejection, any-required regression rejection, training improvement selection, deterministic ties, and both no-improvement fallbacks.

- [ ] **Step 6: Run tests and verify RED**

Run: `node --test .\scripts\rrf-weight-selection.test.js`

Expected: FAIL because validation and selection are absent.

- [ ] **Step 7: Implement validation, grid selection, and CLI**

Implement strict two-run provenance comparison, independent replay, stable-winner comparison, and the exact guardrail/tie rules from the spec. The CLI writes JSON atomically to the requested path and refuses an existing path unless its contents are byte-identical.

- [ ] **Step 8: Run tests and verify GREEN**

Run: `node --test .\scripts\rrf-weight-selection.test.js .\scripts\rag-retrieval-eval.test.js`

Expected: all selector and evaluator tests pass.

- [ ] **Step 9: Commit Task 3**

Stage only selector library, CLI, and tests. Commit as `Select RRF weights from stable training ranks`.

### Task 4: Verify implementation and prepare exact training capture

**Files:**
- Modify: `docs/rag-quality-handoff-20260820-task15-progress.md`
- Create after execution: `logs/task15-rrf-training-run1.json`
- Create after execution: `logs/task15-rrf-training-run2.json`
- Create after execution: `logs/task15-rrf-weight-selection.json`

**Interfaces:**
- Consumes: verified scripts and the current stable app-dev runtime.
- Produces: two provenance-identical capture artifacts and one offline recommendation, or a fail-closed blocker report.

- [ ] **Step 1: Run all Node evaluation tests**

Run: `node --test .\scripts\rag-retrieval-eval.test.js .\scripts\rrf-weight-selection.test.js .\scripts\rag-eval-provenance.test.js`

Expected: zero failures.

- [ ] **Step 2: Run the backend suite**

Run: `.\mvnw.cmd test`

Expected: zero failures and zero errors; environment-dependent skips are reported.

- [ ] **Step 3: Verify repository and runtime fences**

Run `git diff --check`, `scripts/status-pandora.ps1`, runtime-info, Qdrant collection health, and listener inspection. Require app-dev `8080`, Qdrant `6333`, no `18080`, stable JAR/config/index/lexical hashes, parity, and zero Qdrant search failures.

- [ ] **Step 4: Build and deploy only if capture support is absent from runtime**

Build one verified main JAR, restart only app-dev `8080` using `scripts/stop-pandora.ps1 -Mode app-dev` and `scripts/start-pandora.ps1 -Mode app-dev`, then recheck every fence. Never promote or change batch-runner `18080`.

- [ ] **Step 5: Run exact training capture twice**

Set the 24 manifest IDs in `RAG_RETRIEVAL_CASE_IDS`, `RAG_RETRIEVAL_K=30`, `RAG_RETRIEVAL_CAPTURE_RANK_LIMIT=100`, `RAG_RETRIEVAL_CONCURRENCY=1`, and distinct log/report paths. Run `node .\scripts\rag-retrieval-eval.js` twice against unchanged runtime state.

- [ ] **Step 6: Run offline selection**

Run `node .\scripts\rrf-weight-select.js --manifest .\src\main\resources\rag-retrieval-training-manifest.json --run-1 .\logs\task15-rrf-training-run1.json --run-2 .\logs\task15-rrf-training-run2.json --output .\logs\task15-rrf-weight-selection.json`.

Expected: either a deterministic eligible recommendation or explicit `NO_TRAINING_IMPROVEMENT`.

- [ ] **Step 7: Update handoff and commit evidence**

Record exact provenance, calls, metrics, recommendation, constraints, and next promotion step. Stage only reviewed source, docs, and intended evidence; never stage `output/`.

### Task 5: Apply and evaluate an eligible recommendation

**Files:**
- Modify only when recommendation is eligible: `src/main/resources/application.properties`
- Modify only when recommendation is eligible: `src/test/java/com/kaces/pandora/semantic/config/LawAiRrfPropertiesTests.java`
- Modify: `docs/rag-quality-handoff-20260820-task15-progress.md`

**Interfaces:**
- Consumes: Task 4 recommendation with status `RECOMMENDED`.
- Produces: difficult-12 and holdout evidence under one stable runtime, or preserves baseline/shadow-only state.

- [ ] **Step 1: Stop if selection did not improve training**

If status is `NO_TRAINING_IMPROVEMENT`, do not modify configuration and report that fusion weights cannot safely resolve the remaining gap.

- [ ] **Step 2: Write a failing configuration expectation**

Update the focused configuration test to expect the exact recommended vector and lexical weights, then run it and confirm RED against baseline.

- [ ] **Step 3: Apply only the recommended values and verify GREEN**

Change the two properties, run focused tests, Node suites, and `.\mvnw.cmd test`; require zero failures.

- [ ] **Step 4: Deploy only to app-dev and verify provenance**

Use documented scripts, verify all runtime/Qdrant/listener fences, and preserve authoritative flags as false.

- [ ] **Step 5: Run difficult-12 twice**

Require identical ranks, at least 80% explicit-oracle fused all-required top-30 recall, request errors `0`, false grounds `0`, and warm p95 at most `500ms`.

- [ ] **Step 6: Run untouched holdout twice**

Require identical ranks and no all-required, any-required, false-ground, or request-error regression from the baseline artifact. Do not retune after seeing holdout results.

- [ ] **Step 7: Continue the existing promotion ladder or roll back**

On pass, run the existing two 85-case evaluations and full release gate before enabling flags. On failure, restore baseline weights, rebuild/restart only app-dev, archive evidence, and keep flags false.
