# RAG Quality Handoff - 2026-07-13

> Latest shutdown/resume handoff: `docs/rag-quality-handoff-20260715.md`. Read it before continuing; it records the preserved 740/1,004 answer-eval checkpoint and the post-reboot resume constraint.

## Current objective

Improve answer and evidence accuracy in this order:

1. Evaluation provenance reliability
2. Explicit failure classification without `UNKNOWN`
3. Full 1,004-case evaluation on the current code
4. Extractive refactoring of search and judge responsibilities
5. Freeze and clean v4 chunks instead of creating v5
6. Claim verification with sentence-level evidence links and semantic conflict checks

## Completed and verified

- Evaluation reports now record git commit/dirty state, dataset hash, selection hash,
  execution port, model names, and index collections.
- Full and targeted reports are stored separately. Targeted runs cannot overwrite
  `rag-eval-gate-full-latest.json`.
- Curated/generated/answer-verification metrics are separated.
- Search failure types and stages are explicit; new failures do not use `UNKNOWN`.
- RAG v4 quality gate and quality statuses are implemented.
- Existing v4 cleanup was applied; do not create a v5 collection for this work.
- `SemanticVectorSearchService`, `EvidenceCandidateDiversifier`, and the existing
  parent/failure/verification services reduce `LawAiAnswerService` responsibilities.
- `EvidenceJudge` uses the immutable `EvidenceQuestionProfile` boundary.
- `ClaimVerifier` checks sentence-local evidence, semantic relation conflicts,
  obligation/permission upgrades, and overgeneralization.
- Provenance Node tests passed 3/3.
- Before the last policy cleanup, the full Maven suite passed 291/291.

## Baseline full evaluation

- Report: `logs/rag-eval-gate-full-latest.json`
- Archived report: `logs/rag-eval-gate-full-20260713-105646-251Z.json`
- Scope: 1,004/1,004 cases
- Result: 992 PASS, 12 FAIL
- Curated: 133/145 PASS
- Generated: 859/859 PASS
- Answer verification: 78/85 PASS
- Runtime: `http://127.0.0.1:8080`
- Important: this baseline used the older jar that was running on 8080. It is a
  comparison baseline, not the final current-source result.

Failed case IDs:

- `egov-preliminary-review-target`
- `performance-measure-when`
- `irm-user-auth-guide`
- `whistleblower-protection-scope`
- `noise-irm-menu-user-auth`
- `privacy-integrated-guide-purpose`
- `pre-consultation-plan-stage`
- `privacy-consent-refusal`
- `whistleblower-disadvantage`
- `mois-national-safety-plan`
- `mois-disaster-field-support`
- `official-find-pipc-ai-privacy`

## Exact interruption point

The following uncommitted policy cleanup was applied but has not passed its
focused test yet:

- `QuestionIntentProfile` now keeps `policySearchKeywords` separately.
- Policy search terms were added to `query-intent-dictionary.properties`.
- `LawAiAnswerService` uses configured policy terms for lexical search and
  snippet cues.
- Hard-coded `2025.12` / `2026.10` search boosts were removed.
- Duplicated performance-plan/performance-measure lexical branches were removed.

Focused test command:

```powershell
.\mvnw.cmd "-Dtest=KoreanQueryNormalizerTests,EvidenceJudgeTests" test
```

Result: 79 PASS, 1 FAIL.

Failing test:

- `EvidenceJudgeTests.relationQuestionStripsNaturalKoreanQuestionEndings`

Confirmed cause:

- `policy.performance_plan_scope.match` currently includes the broad cue `수립`.
- Therefore the relation question
  `irm 업무성과계획을 수립 한걸 확인하는게 성과측정인가?` is incorrectly
  classified as a target-scope policy question.
- The first correction should be to remove `수립` from the policy match group and
  require an explicit scope cue such as `대상` or `해당`.
- Do not weaken Evidence Judge globally to make this test pass.

Expected configuration correction:

```properties
policy.performance_plan_scope.match=업무성과계획;대상|해당
```

After that correction, rerun the focused tests and inspect whether the policy's
direct groups remain appropriate for actual target-scope questions.

## Resume sequence

1. Read this file and inspect `git status --short --branch`.
2. Apply the narrow configuration correction described above.
3. Run the focused 80 tests until all pass.
4. Run the broader focused RAG suite, then `.\mvnw.cmd test`.
5. Run `node .\scripts\rag-eval-provenance.test.js` and `git diff --check`.
6. Build the current fat jar.
7. Use `scripts/status-pandora.ps1`, then restart only 8080 with the documented
   stop/start scripts. Do not touch 18080.
8. Run a targeted evaluation for the 12 baseline failures. Confirm that the
   targeted report does not overwrite the full latest report.
9. Classify and fix general failure classes only; avoid question-specific Java
   branches.
10. Run the final current-code 1,004-case full gate and review curated,
    generated, and answer-verification metrics separately.

## Runtime safety

- `8080`: app-dev; currently an older jar may still be running.
- `18080`: batch-runner; it was not touched during this work.
- `6333`: Qdrant.
- Always use the repository runtime scripts. Never restart 18080 while resuming
  this handoff.

## Working tree

The work is intentionally uncommitted and includes the provenance, failure
classification, v4 quality, refactoring, claim-verification, and policy cleanup
changes. Preserve all existing changes; do not reset or revert the working tree.
