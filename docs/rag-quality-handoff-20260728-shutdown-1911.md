# Pandora RAG quality handoff — 2026-07-28 19:11 KST

## Shutdown checkpoint

- Worktree: `C:\dev\workspace-egov\pandora\.worktrees\rag-direct-evidence-recovery`
- Branch: `codex/rag-direct-evidence-recovery`
- HEAD: `16443013 docs: plan failed proposition presence audit`
- Shared workspace remains on `main` at `46587a18`.
- No files were staged, committed, reset, reverted, or cleaned for this shutdown.
- All tracked and untracked changes remain in the feature worktree.
- `git diff --check` completed with no whitespace errors. It only printed the
  repository's existing LF-to-CRLF warnings.
- The cumulative tracked diff is 34 files, 3,343 insertions, and 109 deletions.
  Untracked implementation and audit files are listed by `git status`.

## Exact interruption point

The latest change is the deterministic document-identity answer path and its
question-title anchor safety guard:

- `DocumentIdentityAnswerComposer` composes
  `찾으시는 문서는 “{title}”입니다.` only for a document-identity query whose
  distinctive title anchors match the selected evidence title.
- `LawAiAnswerService` uses the composer in normal, streaming, and evaluation
  answer paths.
- `AnswerQuestionAlignmentVerifier` now permits the document-title bridge only
  when the selected title matches the query's distinctive anchors.
- The negative control rejects an unrelated selected title such as
  `개인정보 처리 가이드`.

The focused GREEN verification was started and then stopped at the user's
shutdown request before Maven produced a test result:

```powershell
.\mvnw.cmd -q "-Dtest=DocumentIdentityAnswerComposerTests,AnswerVerificationServiceTests#questionAwareVerificationAcceptsAnExactDocumentTitleIdentityAnswer+documentIdentityAlignmentRejectsASelectedTitleThatMissesTheQuestionAnchors" test
```

Therefore the newest source state is **not yet verified**. Do not describe it as
compiled, passing, or deployed until the command above and the subsequent gates
have completed.

## Last verified evidence before the interrupted change

- Full source evaluation:
  `logs/rag-eval-gate-full-direct-policy-final-20260728.json`
  - 918/1,004 passed
  - 86 failed
  - 91.43%
- Failure breakdown:
  - unsupported answer: 48
  - required conclusion missing: 19
  - no searchable grounds: 17
  - forbidden expression: 1
  - transient empty answer: 1
- Proposition-presence audit:
  - absent/not confirmed in top-K candidates: 50
  - partial in candidates: 15
  - present in selected grounds: 5
  - dropped before selected grounds: 1
  - no explicit oracle: 15
- The audit classification is conservative. `ABSENT` means the required
  proposition was not confirmed by the audit, not that semantic absence was
  proved.
- Prior to the newest composer/alignment edits:
  - Java full test suite: 923/923 passed
  - Node test suite: 65/65 passed
- Targeted runtime evaluation after the hardware-exclusion and autonomy-procedure
  fixes:
  `logs/rag-eval-gate-unsupported-selected3-after-fixes-20260728.json`
  - 2/3 passed
  - remaining failure: `official-doc-title`

## Important artifacts

- `logs/rag-retrieval-eval-failed86-proposition-20260728.json`
- `logs/rag-retrieval-eval-failed86-proposition-20260728.md`
- `logs/rag-failure-presence-audit-20260728.json`
- `logs/rag-failure-presence-audit-20260728.md`
- `logs/rag-eval-gate-unsupported-selected3-baseline-20260728.json`
- `logs/rag-eval-gate-unsupported-selected3-after-fixes-20260728.json`
- `logs/rag-eval-gate-unsupported-selected3-final-20260728.json`
- `logs/rag-eval-gate-official-doc-title-metadata-final-20260728.json`

## Runtime state at shutdown checkpoint

The official `scripts/status-pandora.ps1` result at 19:11 KST was:

- 8080: listening, PID 19516
- Qdrant 6333: listening, PID 22616
- 18080: not listening; PID file 11868 is stale
- `PandoraApp8080` Windows service: stopped/disabled
- `PandoraBatch18080` service: not installed

No runtime was stopped or restarted while making this checkpoint. In
particular, 18080 and Qdrant were not touched.

The running 8080 artifact was built before the newest deterministic
`DocumentIdentityAnswerComposer` integration and anchor safety fix. It must not
be used as evidence that the newest source works.

Process command-line inspection was denied by Windows. Java PID inspection
showed only the running 8080 process; no Maven/Surefire Java process remained.
Three Node processes were present, but their commands could not be identified,
so none was terminated. There was no confirmed active RAG evaluation process
and no evaluation checkpoint requiring a backup.

## Resume in this exact order

1. Re-read `AGENTS.md` and this handoff.
2. In the feature worktree, confirm branch, `git status`, `git diff`, worktree
   list, and runtime status. Preserve all existing changes.
3. Run the interrupted focused test exactly as shown above.
4. If it fails, use the failure evidence to make the smallest generalized
   TDD correction. Keep the unrelated-title negative control.
5. Run the full touched test classes, then `.\mvnw.cmd test`.
6. Run the Node test suite for the retrieval/presence audit.
7. Package once, verify the JAR hash, and deploy only to 8080 with the official
   scripts. Do not touch 18080.
8. Run the targeted runtime evaluation for:
   - `official-doc-title`
   - `project-review-exclusion-hardware`
   - `mois-autonomy-request-docs`
9. Self-review the diff and rerun the focused gates after any correction.
10. Continue the remaining 86 failures in the agreed order:
    unsupported 48, required-conclusion missing 19, then no-grounds 17.
    For each group, determine whether the required proposition exists in
    retrieval before changing the verifier. Do not relax fail-closed behavior.
11. Only after the focused cases are stable, proceed through difficult 12,
    85 cases twice, and finally the full 1,004-case gate.

## Current risk statement

The work is safely persisted on disk, but the newest deterministic title-answer
implementation is intentionally marked unverified because its first GREEN run
was interrupted. The last defensible overall score remains the prior full
evaluation result, 918/1,004 (91.43%); it is not a score for the newest source
state.
