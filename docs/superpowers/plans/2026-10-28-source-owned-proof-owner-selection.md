# Source-owned proof owner selection implementation plan

> Native sequential execution under the user's explicit continuous implementation approval; use executing-plans, TDD and verification-before-completion.

**Goal:** A copied operative statement must not hide the original certificate owner.
**Architecture:** Preserve full-index ordinary contradiction/conflict checks, then verify the unchanged operative statement on the certificate owner's original indexed witnesses. Do not fabricate or import witnesses across grounds. Both control and semantic consumers use the same ownership requirement.
**Tech stack:** Existing Java17 records/immutable lists/JUnit, no dependency.
**Spec:** docs/superpowers/specs/2026-10-23-source-owned-conditional-rule-design.md; same-original-index-and-owner requirement.

## Constraints and review focus

- No change to certificate grammar, business labels, source text, question/dataset/oracle, thresholds, or final acceptance requirements.
- Never override ordinary CONTRADICTED/CONFLICTED or an operative full-index contradiction/conflict.
- The owner must independently support the unchanged operative literal; a copied statement alone cannot create a certificate.
- Preserve source-linked conditions, numeric/actor/strength guards and all negative certificate mutations.
- Keep main/18080/Q and active1765 unchanged; automation PAUSED; no terminal evaluation replay.

## Task1: Owner-aware consumers and delivery

Files: ClaimEvidenceMatcher.java, SemanticEvidenceMatcher.java, SourceOwnedConditionalScopeTests.java.
Interfaces remain match(claim,index); immutable owner-filtered evidence views are internal and carry original witness text/identity only.

- [x] New read-only current-source diagnostic: bound rule1, single-owner claim SUPPORTED, mixed copied-source claim INSUFFICIENT. This is a fresh local synthetic query, not a failed evaluation replay.
- [x] RED copiedOperativeInAnotherGroundCannotHideTheOriginalProofOwner: full operative first matches copied ground2, conditional claim must still use certified owner1 in both consumers.
- [ ] Pin conflict refusal using a complete source witness (an isolated counter-statement with an unresolved paragraph reference is not itself an owned conflict proof).

Ruling after RED: a complete opposite-permission witness still returned control INSUFFICIENT because unchanged `또는` triggers the generic disjunctive non-verbatim guard before polarity comparison. Semantic consumer already reports CONTRADICTED. Add a conservative exact-literal opposite-permission check: all normalized characters must be identical after replacing only `할수있`/`할수없` with a common marker, and parsed permission modes must be opposite. Return CONTRADICTED, never SUPPORTED. This strengthens conflict refusal rather than relaxing entailment or disjunction. The owner-only fallback still requires the full-index operative status SUPPORTED first.

Review correction: replacement is anchored ONLY to asserted terminal `할수(있|없)다$`, not all occurrences. Different antecedents remain different and cannot produce a false contradiction. Regression oppositePermissionsOnlyInsideDifferentAntecedentsAreNotAConflict RED precedes fix.
- [ ] Preserve full-index conflict checks; only after global operative support, select an independently supporting witness from its certified owner.
- [ ] Focused matcher/source-bound/repair regressions, one independent scoped review, stable full suite once.
- [ ] Commit/push, official closed-gate8080 deployment, health/1765 checks; same SHA transport does not rerun tests.
- [ ] New unique SNS→penalty only after source/runtime qualification; actual PASS gates12→57→1003. A local ownership fix is not whole quality completion.
