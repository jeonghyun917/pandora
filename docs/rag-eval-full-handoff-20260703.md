# RAG full eval handoff - 2026-07-03

## Current objective

Run the full 1,004-case RAG eval gate and fix failures until the gate passes.

## Current runtime contract

- Do not touch `18080` batch-runner unless explicitly asked.
- Use `8080` only for app-dev / RAG eval verification.
- Use `scripts/status-pandora.ps1`, `scripts/start-pandora.ps1`, and `scripts/stop-pandora.ps1`.
- Qdrant must be available on `6333`.

## Latest confirmed state before PC shutdown

- App jar: `target/pandora-0.0.1-SNAPSHOT.jar`
- App jar built at: `2026-07-03 16:51:37`
- `8080` app-dev was running from the new jar.
- Qdrant `6333` was running.
- `18080` batch-runner PID file was stale and was not touched.

## Active full eval run

- Output name: `rag-eval-gate-full-20260703-1653`
- Base URL: `http://127.0.0.1:8080`
- Checkpoint: `logs/rag-eval-gate-full-20260703-1653.checkpoint.json`
- Console log: `logs/rag-eval-gate-full-20260703-1653.console.log`
- PID file: `logs/rag-eval-gate-full-20260703-1653.pid`
- Last checked checkpoint:
  - total: `60`
  - passed: `60`
  - failed: `0`
  - passRate: `1`
  - gatePassed: `true`
  - blocking failures: none

If the PC is powered off, the background eval process will stop. The checkpoint file remains and can be used to resume or to inspect already completed cases.

## Changes made in this work session

### Evaluation answer-term matching

Added:

- `src/main/java/com/kaces/pandora/ai/answer/EvaluationTermMatcher.java`
- `src/test/java/com/kaces/pandora/ai/answer/EvaluationTermMatcherTests.java`

Purpose:

- Let eval answer terms match semantically equivalent Korean surface forms.
- Example: `보유 및 이용기간` can match `보유·이용 기간`.

### Forbidden answer-term handling

Changed:

- `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`

Purpose:

- Evidence forbidden terms are no longer automatically treated as answer forbidden terms.
- Answer forbidden terms are applied only when explicitly configured for answer verification.

### Claim verifier improvements

Changed:

- `src/main/java/com/kaces/pandora/ai/answer/ClaimVerifier.java`
- `src/test/java/com/kaces/pandora/ai/answer/ClaimVerifierTests.java`

Purpose:

- Avoid deleting cautious but useful follow-up sentences.
- Keep grounded non-strong procedural sentences when unsupported strong claims are removed.
- Support numeric/date claims by comparing numeric parts instead of exact surface strings.
- This fixed the date range case where evidence used `2025. 12. 17 ~ 2026. 10. 31` and the answer used `2025년 12월 17일부터 2026년 10월 31일까지`.

### Query intent dictionary adjustment

Changed:

- `src/main/resources/query-intent-dictionary.properties`

Purpose:

- Strengthened `project_review.target_scope` answer focus so answers about unrelated privacy-law grounds still explicitly distinguish 과업심의 / 소프트웨어사업 / 대상사업.

### Full eval helper

Added:

- `scripts/run-rag-eval-full.ps1`

Purpose:

- Run the full eval gate with stable output, checkpoint, report, and console log paths.
- Clears case ID / case limit env vars for a true full run.
- Supports `-Resume`.

## Targeted evals already passed

The following targeted evals passed after fixes:

- `privacy-consent-notice-items`
- `no-unrelated-privacy-for-sw`
- `gen-official-92181`
- `gen-official-89827`
- `public-data-preprocessing`
- `mcst-tourism-dure-support`
- `performance-measure-when`

Most recent targeted command/result:

```powershell
$env:RAG_EVAL_BASE_URL='http://127.0.0.1:8080'
$env:RAG_EVAL_CASE_IDS='performance-measure-when'
$env:RAG_EVAL_OUTPUT='logs/rag-eval-gate-performance-measure-when-latest.json'
$env:RAG_EVAL_REPORT='logs/rag-eval-gate-performance-measure-when-latest.md'
node scripts\rag-eval-gate.js
```

Result: `PASS 1/1 (100%)`

## Focused tests already passed

Most recent focused test command:

```powershell
.\mvnw.cmd test "-Dtest=ClaimVerifierTests,EvaluationTermMatcherTests,LawAiAnswerServiceEvidenceGateTests,LawAiEvaluationCaseCatalogTests"
```

Result:

- Tests run: `45`
- Failures: `0`
- Errors: `0`

## Next startup steps

1. Check runtime status:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\status-pandora.ps1
```

2. Start Qdrant if `6333` is not running. Do not restart it if it is already running.

3. Start app-dev on `8080` from the current jar:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-pandora.ps1 -Role app-dev -Port 8080 -UseJar
```

4. Resume the full eval from the last checkpoint:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-rag-eval-full.ps1 `
  -ProjectDir C:\dev\workspace-egov\pandora `
  -BaseUrl http://127.0.0.1:8080 `
  -OutputName rag-eval-gate-full-20260703-1653 `
  -CaseBatchSize 10 `
  -RequestTimeoutMs 180000 `
  -InterBatchSleepMs 300 `
  -Resume
```

5. Monitor checkpoint:

```powershell
node -e "const fs=require('fs'); const j=JSON.parse(fs.readFileSync('logs/rag-eval-gate-full-20260703-1653.checkpoint.json','utf8')); console.log(JSON.stringify({total:j.total,passed:j.passed,failed:j.failed,passRate:j.passRate,gatePassed:j.gatePassed,blocking:j.blockingFailureIds},null,2));"
```

6. If a failure appears:

- Stop only the eval process, not `8080` unless code changes are needed.
- Inspect the failed case from the checkpoint.
- Classify the failure:
  - retrieval failure
  - evidence selection/Judge failure
  - answer-term evaluation mismatch
  - ClaimVerifier over-blocking
  - transient `EVALUATION_ERROR`
- Fix the general class of problem, not the single question only.
- Run focused tests.
- Package and restart only `8080`.
- Run targeted eval for the failed case.
- Resume or rerun the full eval.

## If code changes are needed after restart

Use this sequence for `8080` only:

```powershell
.\mvnw.cmd test "-Dtest=ClaimVerifierTests,EvaluationTermMatcherTests,LawAiAnswerServiceEvidenceGateTests,LawAiEvaluationCaseCatalogTests"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop-pandora.ps1 -Role app-dev -Port 8080
.\mvnw.cmd package -DskipTests
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-pandora.ps1 -Role app-dev -Port 8080 -UseJar
```

Do not promote to `18080` during this eval work.

## Final completion criteria

- Full 1,004-case eval gate passes, or every failure is fixed and rerun until it passes.
- Run full backend tests when practical:

```powershell
.\mvnw.cmd test
```

- Final summary should include:
  - changed files
  - targeted eval results
  - full eval result
  - backend test result
  - remaining risks, if any
