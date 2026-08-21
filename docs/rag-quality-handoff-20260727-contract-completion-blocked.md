# Pandora Contract-Completion RAG Handoff — 2026-07-27

## Exact interruption point

Implementation and local verification are complete through the final contract-
completion answer-repair correction. Live OpenAI-backed evaluation is blocked
because the current Codex sandbox cannot give a newly started 8080 process
outbound network access, and the escalation/usage allowance is exhausted until
2026-08-02 07:17 KST.

Do not interpret the latest 0/4 reports as product regressions:

- `logs/rag-eval-gate-contract-completion-final6-20260727.json`
- `logs/rag-eval-gate-contract-completion-runtime-probe-20260727.json`

Both were produced by a sandbox-owned runtime and failed as
`EVALUATION_ERROR / retrieval_empty` before candidate selection because the
OpenAI embedding call could not leave the sandbox.

The last valid network-capable runtime evaluation before the final repair was:

- `logs/rag-eval-gate-contract-completion-final5-20260727.json`
- runtime JAR SHA-256:
  `03F1C5E28B26849E01DD3A4CC4082983B1A643C037C14012B0D9D8ED7EC4173B`
- result: 0/4, with all four searches correctly selecting only
  `(계약예규) 용역계약일반조건` Articles 20 and 27 and
  `국가를 당사자로 하는 계약에 관한 법률 시행령` Article 55.
- failure boundary: answer repair. The model omitted a required procedural
  stage, final answer coverage rejected it, and repair still returned a
  fail-closed refusal.

The final code fixes that boundary by re-verifying the already-supported atomic
grounds as a deterministic fallback when the model rewrite drops a required
stage. If the atomic combination still fails whole-answer verification, Pandora
continues to refuse.

## Final source and test state

Worktree:

`C:\dev\workspace-egov\pandora\.worktrees\rag-direct-evidence-recovery`

Branch:

`codex/rag-direct-evidence-recovery`

Shared workspace remained on `main`. Existing changes were not reset or
reverted.

Final packaged JAR:

`C:\dev\workspace-egov\pandora\.worktrees\rag-direct-evidence-recovery\target\pandora-0.0.1-SNAPSHOT.jar`

- size: `52,045,363` bytes
- SHA-256:
  `4DE3AE7D21893D6921FC943A8C644FA28E9485E6D7A06BD1F4ABEC38F0D5E5B8`

The same JAR is deployed to the shared `target` path.

Verified:

- focused answer-alignment/repair tests: 68/68
- full Maven suite: 877/877
- Node evaluator/audit tests before the final Java-only repair: 58/58
- `git diff --check`: no whitespace errors
- unauthenticated runtime-info after restoration: HTTP 401
- temporary `pandora.auth.enabled=false` and `server.address=127.0.0.1`
  overrides removed

The full Maven suite must use a worktree-local temporary directory in a
sandboxed session:

```powershell
$tmp = Join-Path (Resolve-Path '.\target') 'sandbox-test-tmp'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$env:TEMP = $tmp
$env:TMP = $tmp
.\mvnw.cmd -o '-Dmaven.repo.local=C:\Users\kaces\.m2\repository' `
  "-Djava.io.tmpdir=$tmp" test
```

## Runtime safety

- 8080 is stopped. The restricted sandbox-owned instance was shut down with
  the official script rather than leaving a runtime that could not reach
  OpenAI. Restart it outside the sandbox before evaluation.
- 8080 authentication is restored.
- Qdrant 6333 was not restarted or mutated after the document-scoped rebuild.
- 18080 was never stopped, started, restarted, or promoted in this task. The
  status script showed no 18080 listener and a pre-existing stale PID file;
  leave it untouched.
- The disabled `PandoraApp8080` Windows service was not modified.

## Exact resume sequence

1. Read `AGENTS.md`, this handoff, and
   `docs/superpowers/plans/2026-07-27-contract-completion-retrieval.md`.
2. Check both worktrees with `git status --short --branch`, `git diff --stat`,
   and `git worktree list`. Preserve every change.
3. Run `scripts/status-pandora.ps1`. Do not touch 18080.
4. Restart only 8080 from a network-capable/approved execution context using
   the official runtime script and the final JAR SHA above.
5. Temporarily disable auth only for the bounded local evaluation, record the
   runtime config SHA, and restore auth immediately after evaluation.
6. Re-run the four cases:

```powershell
$env:RAG_EVAL_CASE_IDS = 'contract-completion-before-period,contract-completion-before-period-paraphrase,contract-completion-actual-finished,contract-completion-work-remaining-control'
$env:RAG_EVAL_OUTPUT = 'logs/rag-eval-gate-contract-completion-final7-20260727.json'
$env:RAG_EVAL_REPORT = 'logs/rag-eval-gate-contract-completion-final7-20260727.md'
$env:RAG_EVAL_CHECKPOINT = 'logs/rag-eval-gate-contract-completion-final7-20260727-checkpoint.json'
node .\scripts\rag-eval-gate.js
```

7. Inspect every selected ground and final answer. Require Articles 20, 27,
   and 55; reject unrelated Articles 26/58/59, survey/design-only clauses,
   warranty language, unconditional early-submission permission/prohibition,
   and invented deadlines.
8. Only after a clean 4/4, run the difficult 12, the original 85
   answer-verification cases twice, and then the complete 1,004-case gate with
   unique output/report/checkpoint paths and stable runtime provenance.
9. Run final self-review and verification, restore auth, then commit the feature
   worktree, merge it into shared `main` without changing the shared workspace
   branch, and push only after all gates are complete.

## Remaining evaluation risk

No honest final score can be assigned yet. The structural retrieval defect is
fixed and locally verified, but the final OpenAI-backed 4/4, difficult-12,
85x2, and 1,004 gates have not run against the final JAR. The previous
authoritative score remains 7.2/10 until those gates provide replacement
evidence.
