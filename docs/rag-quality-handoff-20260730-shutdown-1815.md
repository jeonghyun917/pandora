# Pandora RAG quality handoff — 2026-07-30 18:15 KST

## Shutdown checkpoint

- Worktree: `C:\dev\workspace-egov\pandora\.worktrees\rag-direct-evidence-recovery`
- Branch: `codex/rag-direct-evidence-recovery`
- HEAD: `e48c6168ee026e94aa56bc16b6f9fcd56d82dc4`
  (`feat: bind RAG evaluation to a baseline manifest`)
- Branch state before this handoff commit: 5 commits ahead of
  `origin/codex/rag-direct-evidence-recovery`.
- Shared workspace remains clean on `main` at
  `b27c4e986b78567975cdbcfbc113bf0377fbcba4`.
- The feature worktree had no tracked or staged changes. The pre-existing
  untracked `output/` directory remains present and was not staged, cleaned, or
  modified by the shutdown procedure.
- No reset, revert, branch switch, or worktree removal was performed.

## Exact interruption point

The approved 15-task implementation plan is being executed with
Superpowers subagent-driven development:

- Plan:
  `docs/superpowers/plans/2026-07-30-rag-retrieval-evidence-shadow-migration.md`
- Design:
  `docs/superpowers/specs/2026-07-30-rag-retrieval-evidence-shadow-migration-design.md`
- SDD ledger:
  `.superpowers/sdd/2026-07-30-rag-retrieval-evidence-shadow-migration/progress.md`

Task 1 is complete:

- `8e9c6d4c feat: add independent RAG blocking gates`
- `d65f1ae1 fix: fail closed on malformed RAG gate results`
- Focused Node suite recorded 55/55 passing.
- Independent review found one Important strict-boolean issue; the fix was
  independently re-reviewed and accepted cleanly.

Task 2 implementation is committed but Task 2 is **not complete**:

- Commit: `e48c6168 feat: bind RAG evaluation to a baseline manifest`
- Task report:
  `.superpowers/sdd/2026-07-30-rag-retrieval-evidence-shadow-migration/task-2-report.md`
- Implementer-recorded focused evidence:
  - Node provenance tests: 32/32 passed.
  - `LawAiRuntimeInfoTests`: 2/2 passed.
  - staged package completed.
- `git diff e48c6168^ e48c6168 --check` was rerun at shutdown and was clean.
- Task 2 still requires:
  1. a separate independent task review;
  2. a real baseline manifest with a ready Qdrant index revision;
  3. focused, difficult-12, 85-case, and full 1,004-case baseline archives
     under the same manifest.

Do not mark Task 2 complete or start Task 3 until those three items are done.

## Why the real baseline was not created

At the start of Task 2, ports 8080, 18080, and 6333 were all stopped.
The implementer built and deployed only the worktree's app-dev 8080 using the
official script. The server then correctly reported:

- `qdrantReady=false`
- `indexRevision=null`

The new fail-closed commands behaved as intended:

```text
node .\scripts\rag-baseline-manifest.js --write
[rag-baseline-manifest] baseline manifest requires indexRevision
```

```text
node .\scripts\rag-eval-gate.js
[rag-eval-gate] ERROR Qdrant search is not ready
```

No baseline manifest or evaluation result was fabricated. The four baseline
evaluations never started.

The user previously authorized starting Qdrant 6333 with the repository's
official administrator script. The shutdown request interrupted execution
before Qdrant was started.

## Verified runtime state at shutdown

The official runtime status was run after cleanly stopping the verification
8080 process with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-pandora.ps1 `
  -Role app-dev -Port 8080 `
  -ProjectDir 'C:\dev\workspace-egov\pandora\.worktrees\rag-direct-evidence-recovery'
```

Final status:

- 8080: no listener; PID file removed.
- 18080: no listener; no PID file; never started, stopped, promoted, or
  modified during this task.
- 6333: no listener; not started before shutdown.
- `PandoraApp8080`: stopped and disabled.
- `PandoraBatch18080`: not installed.

The staged JAR remains at:

`target-stage\pandora-0.0.1-SNAPSHOT.jar`

Verified SHA-256:

`E1146AA1B380B1E2F98063E2DC9072D632D607AE84B5AC187559DA793BC44DD8`

## Resume in this exact order

1. Read `AGENTS.md`, this handoff, the design, the plan, the Task 2 brief, and
   the Task 2 report.
2. Confirm the shared workspace is still on `main`; in the feature worktree run
   `git status`, `git diff`, `git log -1`, and `git worktree list`. Preserve
   `output/` and all unrelated worktree changes.
3. Run the official runtime status script. Do not touch 18080.
4. Start Qdrant 6333 through the repository's official administrator/start
   workflow and verify `/collections`, `law_chunks`, and `rag_chunks_v4` are
   healthy before continuing.
5. Rebuild or reuse the staged JAR only after verifying its SHA. Deploy only
   app-dev 8080 with the official script. Confirm the running artifact SHA,
   `qdrantReady=true`, a non-null `indexRevision`, and zero search failures.
6. Run `node .\scripts\rag-baseline-manifest.js --write`, set
   `RAG_EVAL_BASELINE_MANIFEST` to the returned path, and verify that its commit,
   JAR, runtime instance, config, index, lexical, dataset, and selection
   identities are complete.
7. Generate and archive the four pre-change baseline evaluations against that
   one unchanged manifest: focused, difficult 12, 85 cases, and all 1,004
   cases. A quality failure is acceptable for the baseline; provenance or
   runtime-identity failure is not.
8. Generate a bounded review package for
   `d65f1ae1..e48c6168` and run the separate Task 2 spec/code-quality review.
   Fix Important/Critical findings with TDD and re-review.
9. Only when the manifest, four archives, and review are complete, append Task
   2 completion to the SDD ledger and begin Task 3.

## Quality statement

There is no new defensible quality score for the current JAR because the real
Task 2 baseline did not run. The last archived full score documented before
this migration remains 918/1,004 (91.43%) from 2026-07-28; it must not be
presented as the score of commit `e48c6168`.
