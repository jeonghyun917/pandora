# Task 3 Report: Retrieval Evidence Coverage by Stage

## Scope

- Added the evaluation-only `scripts/lib/rag-evidence-coverage.js` module.
- Extended each `measureRetrievalCase(...)` result with `evidenceCoverage` and
  added the aggregate `evidenceCoverage` summary to
  `summarizeRetrievalCases(...)` without removing or changing legacy recall
  fields.
- No Java, runtime-process, or 18080 files/actions were involved.

## RED evidence

1. Added focused coverage tests to `scripts/rag-evidence-coverage.test.js`.
2. Ran `node --test scripts/rag-evidence-coverage.test.js` before adding the
   coverage implementation.
   - Result: exit 1; 19 passed, 2 failed.
   - Both failures were the intended missing `evidenceCoverage` result field:
     reading `coverage.stages` and `propositionGroups` from `undefined`.

## GREEN evidence

1. Ran `node --test scripts/rag-evidence-coverage.test.js` after the minimal
   implementation.
   - Result: exit 0; 21 passed, 0 failed.
   - Covers proposition and condition groups at all seven required stages,
     vector/lexical candidate union de-duplication, stable 1-based type IDs,
     downstream first loss, candidate-source absence, and type-separated
     aggregate summaries.
2. Ran `node --test scripts/rag-evidence-coverage.test.js
   scripts/rag-retrieval-eval.test.js`.
   - Result: exit 0; 42 passed, 0 failed.
   - Confirms the existing retrieval-metrics suite remains green alongside the
     new coverage assertions.
3. Ran `git diff --check`.
   - Result: exit 0; no whitespace errors.

## Implementation and self-review

- `candidateSources` is the top-K vector/lexical union, de-duplicated by the
  existing target/chunk identity rule. The legacy metrics module now imports
  the same `uniqueItems` helper, keeping both computations aligned.
- Each group is evaluated independently for each retrieval item. The only text
  passed to `matchOracleGroup` is one item's `title`, `chunkTitle`,
  `parentSectionTitle`, `matchedChildText`, and `snippet` joined together.
  Therefore text from different retrieval items cannot be concatenated into a
  synthetic match; same-item field contribution is preserved as required.
- The module reuses Task 2's `matchOracleGroup` and contains no matching-rule
  duplication or production import path.
- First loss is `candidateSources` when entry coverage is absent, otherwise
  the first absent downstream stage, or `survived` when selected coverage
  remains true.

## Files

- `scripts/lib/rag-evidence-coverage.js` (new)
- `scripts/lib/rag-retrieval-metrics.js`
- `scripts/rag-evidence-coverage.test.js`
- `.superpowers/sdd/task-3-report.md`

## Commit

- `feat: measure retrieval evidence coverage by stage`

## Concerns

- Verification is limited to standalone Node evaluation/measurement suites;
  no Maven suite was run because this task does not change Java or runtime
  behavior.
