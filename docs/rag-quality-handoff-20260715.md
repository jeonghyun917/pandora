# Pandora RAG Quality Handoff - 2026-07-15

## Exact interruption point

- The full 1,004-case answer evaluation was intentionally stopped before PC shutdown.
- Completed checkpoint: **740/1,004 results** (**686 passed, 54 failed**).
- The evaluator had started batch 75/101, so only the 74 fully checkpointed batches are trusted.
- The evaluation Node process (PID 12140) was stopped. Pandora runtimes were not stopped.
- Primary checkpoint:
  - `logs/rag-eval-gate-full-final-20260715-checkpoint.json`
- Shutdown backup:
  - `logs/rag-eval-gate-full-final-20260715-checkpoint-shutdown-backup.json`
- Both files had SHA-256:
  - `5C997BC9F5DCF60F6A12A2141D2878D4E614E898E74FE7F352374D0EB5864AEB`

Do not reset, revert, clean, or overwrite the dirty worktree. The current branch is `main`, ahead of `origin/main` by 3 commits, with extensive tracked and untracked user changes.

## Important restart constraint

`scripts/lib/rag-eval-provenance.js` currently requires `runtimeInstanceId` to match when resuming a checkpoint. A real PC reboot creates a new 8080 instance ID, so directly setting `RAG_EVAL_RESUME=true` after reboot will intentionally reject this checkpoint even when the artifact, configuration, and Qdrant index are unchanged.

Do not edit the checkpoint identity to disguise a restart.

Tomorrow, choose one of these evidence-safe paths:

1. **Recommended for an authoritative final score:** restart only 8080 with the official script, verify artifact/config/index identity, and rerun the full 1,004 cases from zero.
2. **Time-saving segmented continuation:** first add a small, tested evaluator feature that permits a new runtime segment only when artifact SHA, config SHA, dataset/selection hashes, models, collections, and index revision all match. It must preserve both instance IDs in provenance and recompute exact ID completeness before the result can be called a full 1,004-case evaluation. Then run only the remaining 264 IDs and merge transparently.

The 740-result checkpoint is still valuable for failure analysis and for implementing option 2, but it must not be represented as a single-runtime resumed run after reboot.

## Runtime state at shutdown handoff

`scripts/status-pandora.ps1` was run read-only with execution-policy bypass.

- 8080 app-dev: PID 35116, listening
- 18080 batch-runner: PID 7504, listening and untouched
- Qdrant 6333: PID 30348, listening
- 8080 artifact:
  - size: 51,883,974 bytes
  - SHA-256: `1c8df722ef6e7ec43bea11d7aec30a438930353dd5e07bb0b48041b4cd378bbd`
- 8080 runtime instance used by the interrupted evaluation:
  - `711166e6-a567-44d5-981c-5b30a038e284`
- runtime config SHA-256:
  - `78123730c2a8655665fcde0590e57ac52acfce2c5cc54641ca23548842c7bfdb`
- Qdrant index revision:
  - `c9c457d5d5efad80dd83e919d437170c88d86f510774ad795e420c1ac78dfc4f`
- 18080 batch JAR SHA-256 (last verified before this handoff):
  - `AF28683C14234099F95A78BCC0E64333BC3196814225ADE3ECB4709A59291ED3`

After PC shutdown these processes will naturally stop. On the next session, use `scripts/status-pandora.ps1` first and never restart or promote 18080.

## Completed implementation and verification

The existing dirty changes were preserved. The completed generalized changes include:

- claim action/object matching and fail-closed handling for contradicted/conflicted strong claims;
- atomic answer prompt guidance;
- configuration-driven permission/document-purpose/privacy intent policies;
- question-anchored direct-evidence rescue/preservation;
- buffered streaming so unverified raw deltas are never exposed;
- strict eval request/result ID completeness and checkpoint validation;
- explicit UI-navigation exclusion without treating non-UI `경로` questions as navigation;
- runtime/index provenance and retrieval evaluation support from the previous handoff.

Latest verified test evidence before deployment:

- Maven: **380/380**, failures 0, build success
- Node evaluator tests: **41/41**
- `git diff --check`: exit 0; only existing LF-to-CRLF warnings
- independent reviews: no remaining Critical or Important findings in the final claim, navigation, streaming, and eval-ID changes

## Hard 12 final results

Retrieval:

- report: `logs/rag-retrieval-targeted-final-20260715.md`
- selected document hit: **12/12 (100%)**
- selected direct hit: **5/12 (41.7%)**
- vector document hit: **11/12 (91.7%)**
- no request errors; runtime/index stable

Answer gate:

- report: `logs/rag-eval-gate-targeted-final-20260715.md`
- **8/12 passed (66.7%)**
- failed IDs:
  - `performance-measure-when`
  - `whistleblower-protection-scope`
  - `privacy-consent-refusal`
  - `whistleblower-disadvantage`
- These failures were conservative answer-verification refusals caused by compound answers containing contradicted or over-broad claims, not unsafe answers being exposed.

## Full 1,004 retrieval result

The initial run completed 1,001 cases and had three transient debug-search HTTP 500 errors. The three IDs were retried successfully under the same runtime/index:

- `privacy-consent-items-law`
- `gen-law-11166539`
- `gen-law-11180491`

Artifacts:

- initial full: `logs/rag-retrieval-full-final-20260715.json`
- initial report: `logs/rag-retrieval-full-final-20260715.md`
- retry 3: `logs/rag-retrieval-full-final-20260715-retry3.json`
- retry report: `logs/rag-retrieval-full-final-20260715-retry3.md`

Combined ID validation:

- expected IDs: 1,004
- results: 1,004
- unique IDs: 1,004
- missing/unexpected/duplicate IDs: 0/0/0

Combined metrics:

- recall-eligible: 995; expected no-ground: 9
- selected document hit: **992/995 (99.70%)**
- selected direct hit: **924/995 (92.86%)**
- selected section/parent hit: **63/129 (48.84%)**
- selected document term coverage@10: **97.82%**
- no-ground false ground: **0/9 (0%)**
- reranked direct hit: **929/995 (93.37%)**

## Interrupted full answer evaluation observations

At 740 cases:

- passed: 686
- failed: 54
- provisional pass rate: 92.70%

This is not a final score. Earlier read-only analysis at 80 cases found:

- most early failures were contradicted-claim detections that produced the standard insufficient-evidence refusal;
- unsupported claims in passing cases were removed from the verified answer;
- there was one unexpected `NO_GROUNDS`, one forbidden-ground inclusion, and one answer that lost mandatory terms after sanitization;
- no evaluator/runtime/Qdrant error invalidated the run.

The pass rate rose sharply in later generated cases. Final scoring must therefore separate the full average, curated cases, hard 12, answer-verification-required cases, and fail-closed safety.

## Safe next-session sequence

1. Read `AGENTS.md`, `docs/rag-quality-handoff-20260713.md`, and this file.
2. Run `git status --short --branch` and `git diff --stat`; preserve everything.
3. Run the official status script:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\status-pandora.ps1
   ```

4. Start/restart **only 8080** with the official app-dev scripts if needed. Do not touch 18080.
5. Verify the 8080 artifact SHA/config SHA and Qdrant index revision against this handoff.
6. Decide between the authoritative full rerun and a tested multi-instance segmented continuation. Do not use plain resume across a reboot with the current code.
7. Finish the answer evaluation, validate exactly 1,004 unique result IDs, run final runtime/status checks, update the quality score, and report remaining risks.

For an authoritative rerun from zero, use:

```powershell
Remove-Item Env:RAG_EVAL_CASE_IDS -ErrorAction SilentlyContinue
Remove-Item Env:RAG_EVAL_CASE_LIMIT -ErrorAction SilentlyContinue
$env:RAG_EVAL_OUTPUT='.\logs\rag-eval-gate-full-final-20260716.json'
$env:RAG_EVAL_REPORT='.\logs\rag-eval-gate-full-final-20260716.md'
$env:RAG_EVAL_CHECKPOINT='.\logs\rag-eval-gate-full-final-20260716-checkpoint.json'
$env:RAG_EVAL_RESUME='false'
$env:RAG_EVAL_ARCHIVE='false'
$env:RAG_EVAL_CASE_BATCH_SIZE='10'
node .\scripts\rag-eval-gate.js
```

## Honest score status

- Hard-12-only independent provisional rubric: about **7.1/10**.
- Do not publish a final overall score yet because the full answer evaluation is incomplete.
- Retrieval is already strong at the document/direct-ground level, but section/parent precision and difficult compound-answer availability remain the main weaknesses.
- Hallucination cannot be eliminated; current changes emphasize preventing unsupported or contradicted claims from reaching the user, sometimes at the cost of over-refusal.
