# Task 4 Report: End-to-end RAG Evidence Coverage

## Scope

- Added the optional answer-evaluation artifact input through
  `--answer-eval <json>` and `RAG_RETRIEVAL_ANSWER_EVAL`, with the CLI value
  taking precedence over the environment.
- Made every retrieval-evaluator debug search request explicitly request
  `includeMatchedChildText: true` and reject every stage item whose
  `matchedChildText` is missing or is not a string.
- Joined answer-evaluation results by case ID only after matching normalized
  base URL, runtime artifact SHA-256, runtime instance ID, index revision,
  dataset hash, and selection hash.
- Extended the Task 3 retrieval evidence coverage with `supportedEvidence` and
  `verifiedAnswer`, plus end-to-end summaries and Markdown reporting.
- Preserved the seven retrieval stages and all legacy retrieval output fields.
- No Java, production runtime behavior, or runtime process (including 18080)
  was changed or touched.

## TDD evidence

### Recorded RED

The interrupted first-GREEN checkpoint preserved the original RED result:

```powershell
node --test scripts/rag-retrieval-eval.test.js scripts/rag-evidence-coverage.test.js
```

- Exit code: 1.
- Legacy tests passing: 42.
- Intended new-contract failures: 6.
- The failures covered the absent answer-eval option, matched-child debug
  request/validation, safe provenance/ID join, end-to-end Markdown output,
  supported-evidence/final-answer measurement, and explicit unmeasured answer
  stages.

The production implementation was already intentionally uncommitted when this
task resumed, so the inherited RED result was audited rather than recreated by
discarding the first-GREEN work.

### Fresh GREEN

Command:

```powershell
node --test scripts/rag-retrieval-eval.test.js scripts/rag-evidence-coverage.test.js
```

Initial resumed result: exit 0; 48 tests passed, 0 failed, 0 skipped.

An additional read-only Node smoke constructed retrieval-only coverage and
verified that both proposition and condition summaries contain all nine stages
and that Markdown renders `supportedEvidence` and `verifiedAnswer` as explicit
`not_measured` rows.

### Independent-review RED/GREEN

The read-only task review found two fail-closed gaps not covered by the original
tests:

1. A requested answer result without measurable answer-stage fields could join
   and be reported as measured zero coverage.
2. An explicit empty `--answer-eval=` value silently disabled answer-stage
   measurement.

Focused regressions were added before the fixes. The exact two-file command then
produced exit 1 with 47 passed and 2 intended failures. The failures were the
missing empty-option rejection and missing answer-result schema rejection.

The minimal fix rejects empty or whitespace CLI answer-eval values and validates
each joined result: `claimEvidenceLinks` must be an array, `verifiedAnswer` must
be a string, and every `SUPPORTED` link must have a string
`evidenceSentence`.

The exact command was rerun after the fix:

```powershell
node --test scripts/rag-retrieval-eval.test.js scripts/rag-evidence-coverage.test.js
```

Final focused result: exit 0; 49 tests passed, 0 failed, 0 skipped.

## Binding-requirement review

- Option handling: the environment supplies the default answer-eval path and
  `--answer-eval` overwrites it, including the existing inline `--flag=value`
  form. An explicit empty or whitespace CLI value is rejected.
- Debug contract: `buildDebugRequest(...)` always emits
  `includeMatchedChildText: true`. The evaluator invokes
  `assertDebugResponse(..., { requireMatchedChildText: true })`, so missing,
  `null`, or other non-string values fail the case instead of falling back
  silently.
- Join identity: all answer-result IDs must be non-empty strings, all duplicate
  answer-eval IDs are rejected, every requested case ID must be present, and
  malformed answer-stage payloads cannot be counted as measured evidence loss.
- Provenance: missing values are mismatches. Base URLs are URL-normalized with
  trailing slashes removed; artifact hashes compare case-insensitively; runtime
  instance ID, index revision, dataset hash, and selection hash compare exactly.
- Supported evidence: only links whose relation is exactly `SUPPORTED`
  contribute their string `evidenceSentence`. Each sentence is matched
  independently; separate links are never concatenated.
- Verified answer: only the case's final `verifiedAnswer` string is measured.
- First loss: a group absent at entry is attributed to `candidateSources`;
  otherwise the first false stage through `verifiedAnswer` is used, with
  `survived` only after all nine measured stages remain present.
- No answer artifact: both answer-stage coverage values and summary rows are
  explicitly `not_measured`, rather than false or omitted.
- Output: JSON retains the legacy Task 3 `evidenceCoverage` and adds a separate
  per-case and aggregate `endToEndEvidenceCoverage`. Markdown reports
  proposition and condition coverage separately, first-loss counts, and
  per-case missing group IDs.

## Files

- `scripts/lib/rag-evidence-coverage.js`
- `scripts/rag-retrieval-eval.js`
- `scripts/rag-retrieval-eval.test.js`
- `scripts/rag-evidence-coverage.test.js`
- `.superpowers/sdd/task-4-report.md`

## Self-review

- Inspected every changed hunk against the Task 4 brief and the actual Java
  answer-evaluation response schema (`claimEvidenceLinks[].relation`,
  `claimEvidenceLinks[].evidenceSentence`, and `verifiedAnswer`).
- Independent read-only review found no Critical issues and the two Important
  fail-closed validation findings above; both were fixed test-first.
- Confirmed the answer artifact cannot join on partial or wildcard provenance.
- Confirmed no oracle data enters Java or any production request/ranking,
  judging, answer-generation, or verification path; matching remains confined
  to Node evaluation tooling.
- Confirmed no case-ID-specific behavior, runtime-control action, Java change,
  or 18080 interaction.
- `git diff --check` completed with exit 0 and no whitespace errors before the
  report was written; final diff and focused tests are rerun before commit.

## Commit

Commit message: `feat: report end-to-end RAG evidence coverage`

## Concerns

- Verification is intentionally limited to the two focused standalone Node
  suites because Task 4 changes only Node evaluation/reporting code. No Maven,
  live 8080 evaluation, full 85-case answer artifact join, or runtime process
  test was run in this task; those operational measurements belong to Task 5.
- The RED counts are inherited from the preserved first-GREEN checkpoint. The
  resumed review independently verified the changed tests, implementation,
  current GREEN result, and output contract without destructively rewinding the
  uncommitted work.
