# Request-scoped verification index implementation plan

> Implement inline with TDD; one focused independent review before delivery.

**Goal:** Remove repeated immutable evidence parsing within a single synchronous repair request without changing any verdict.

**Design:** A try-with-resources evidence scope binds a deep-frozen grounds key (including nullable context membership) and captured routing snapshot on the current thread. ClaimVerifier lazily creates its control and semantic indices once for that exact pair. Without a captured routing snapshot, semantic source-table proofs are rebuilt on every call. Every claim, alignment check, shadow check, rewrite and final verification still runs. Scope close restores a nested outer scope or removes the thread-local state. Other grounds, generations, threads and subsequent requests cannot reuse it.

**Alternatives:** Global cache rejected for stale-generation/source risk. Passing a new context through every repair selector rejected as unnecessarily broad API churn. This bounded synchronous scope is the selected implementation choice under the user's autonomous implementation approval.

## Constraints and review focus

- No thresholds, oracle, dataset, authority flags, timeouts, output budget or retrieval changes.
- Preserve original verification results, unsupported conditions and final checks.
- Changed body/metadata or routing identity must miss the scoped indices.
- Close on normal return and exceptions; nested scopes restore the outer binding.
- Two concurrent threads must not share prepared indices.
- No cross-request cache or external model calls in the reproduction.

## Task 1: Scope and consumer wiring

Files: ClaimVerifier.java, AnswerVerificationService.java, GroundedAnswerRepairService.java; new VerificationEvidenceScopeTests.java.

- [x] RED: real verifier behavior unchanged but repeated same-ground verification builds each index once during repair; changed source/route, closed/nested scopes and threads remain isolated.
- [x] Implement package-local AutoCloseable scope with lazy indices keyed to exact full grounds and routing identity; wrap complete repair lifecycle.
- [x] Focused scope, repair, matcher, alignment tests; compare original-source synthetic repair timing against 52967ms baseline and equal result.
- [x] Independent focused review; stable full backend suite once; fix demonstrated blocking findings only.
- [x] Commit/push, official staged package/deploy with recovery mutation gate closed, health and active1765 retention.
- [ ] Fresh SNS/penalty only when runtime and source prerequisites pass; no terminal failed evaluation replay. Continue Difficult12→holdout57→full1003 only after both actual PASS.

Pre-flight: single task, no shared task-interface conflict. Existing active worktree reused; main/18080/Q untouched. User opted out of routine design/reapproval questions.

Verification: initial missing-scope RED7d054e/2ae3b3; focused6b9c94/a996f3; independent review found mutable context key and null-route source freshness issues. Both reproduced3fa341/5e4e4b (2 failures) then fixedbddd27/4affcc. Final suitecc3d43/c1e141 fresh137XML1819/f0/e0/skip26. Shadow-on captured-generation local contrast936b4d/c41c39: uncached54668ms/scoped3852ms, full Result equal0dc1f8. No external evaluation replay.

Delivery completed for3016eb1 and follow-up cause-proven products through68032cd; see progress ledger for exact stage/runtime identities. Latest actual SNS PASS, penalty FAIL SOURCE_CONDITION_NOT_PRESERVED after conditional rewrite. No CPU timeout. Literal source consumer now reaches the API, but rejected model output was not retained and its precise mismatch is not recoverable. Diagnostic-only cause categories do not qualify a new penalty retry; the dependent ladder remains unopened. Automation stays PAUSED. Resume substantive quality work only from new direct mismatch evidence, not speculative prompt changes or identical terminal-case replay.
