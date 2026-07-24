# Pandora RAG quality handoff — bounded OCR target evidence

Date: 2026-07-24

## Exact interruption point

The bounded structural-target implementation, focused/full tests, independent
review, staged build, and 8080-only deployment are complete. The next command is
the exact one-case `project-review-target` answer evaluation. It did not start:
the execution approval layer rejected it because the request and selected
internal-document content may be sent to the configured OpenAI answer model.
Resume only after the user explicitly authorizes that payload and destination.
Do not work around this boundary.

## Git state

- shared workspace: `C:\dev\workspace-egov\pandora`, must remain on `main`
- feature worktree:
  `C:\dev\workspace-egov\pandora\.worktrees\rag-evidence-coverage`
- feature branch: `codex/rag-evidence-coverage`
- implementation commit:
  `3b0409bc` (`fix: project bounded OCR target evidence`)
- pre-existing untracked `.superpowers/sdd/review-*.diff` and task briefs remain
  untouched and must not be included accidentally

## What changed

- An explicit matched-child target label/value may be projected under exactly
  one named procedure scope.
- The deterministic projection is fully claim-verified and question-aligned
  before the existing verbatim repair path accepts it.
- Missing conditions remain ineligible; final answer verification is unchanged
  and fail-closed.
- Every discarded OCR tail segment is checked. Amounts, timing, conditions,
  exceptions, unclassified prose, overlapping exclusions, and narrowing
  definitions block projection.
- The only safe supplemental forms needed by the observed source are the exact
  non-limiting S/W development/operation economic-activity definition and an
  H/W exclusion when the projected value contains S/W but not H/W.

## Verification

- matcher tests: 273/273
- focused verification/repair tests: 353/353
- fresh full Maven suite: 852/852
- `git diff --check`: pass
- final independent review: zero Critical, Important, or Minor findings;
  ready to merge subject to live evaluation

## Runtime identity after deployment

- 8080 PID: `12608`
- 8080 runtime instance:
  `c210de5b-5f41-43c0-a571-0dc3def26b28`
- app JAR SHA-256:
  `084E5968EA9B3FBC9348D231F7EBD37EF5C0AC8F6BBC3642A851DEDCEC17ED2D`
- runtime config SHA-256:
  `78123730c2a8655665fcde0590e57ac52acfce2c5cc54641ca23548842c7bfdb`
- index revision:
  `99e941053161c5a95bb407719e45d0a329e55121fa5856c44a292bbc698f18a7`
- Qdrant: ready, PID `30692`
- 18080: untouched, PID `11868`
- batch JAR SHA-256:
  `8EC365C71ABBEBE6184B2CEADFB270F5DED3A8BB99DD8DAC2646450105A7FC3B`

The index revision differs from earlier baseline revisions, so any post-change
quality comparison must disclose the index-revision confound.

## Resume sequence after explicit authorization

1. Use `scripts/status-pandora.ps1`; confirm 18080 PID and batch JAR hash remain
   unchanged and Qdrant is ready.
2. Run only `project-review-target` through `scripts/rag-eval-gate.js` with
   archive disabled and fresh explicit output/report/checkpoint paths.
3. Confirm the answer is fully verified and contains no unsupported,
   contradicted, conflicted, or forbidden claim.
4. Only then run the exact 85 answer-oracle case IDs from the baseline artifact.
5. Run `scripts/rag-retrieval-eval.js` against that new 85-case answer artifact.
6. Compare pass count, proposition/condition coverage, repair reasons, and
   unsafe-answer count against the baseline while reporting the index confound.
7. Update this progress file and the final handoff, then merge into shared
   `main` only after checking its branch/status/worktree list. Never move the
   shared workspace off `main`.

## Protected runtime rule

Do not stop, restart, or promote 18080. Use only the official Pandora runtime
scripts for 8080. Keep the current deploy supervisor session alive while 8080 is
needed.
