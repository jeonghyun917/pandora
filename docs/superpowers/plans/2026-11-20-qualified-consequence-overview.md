# Qualified Consequence Overview Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline. The user has explicitly delegated ordinary implementation decisions and opted out of repeat approval questions. Keep progress in the plan-scoped ledger and continue across task boundaries.

**Goal:** Answer an explicitly requested conditional-example overview using complete, independently owned applicability and consequence proof, without asserting that an unspecified real project is covered.

**Architecture:** Reuse the existing approval-stage source proof, scoped retrieval and strict full-answer verifier. Distinguish an explicit example-explanation intent from the unchanged general-violation question. Only the already proved public-SW approval example is initially supported; this is not a generic permission to cite any sanction provision. Register no evaluation contract until the full source-qualified path and separate contract identity are ready.

**Tech Stack:** Existing Java/Spring/MyBatis and Node tooling; no new dependencies.

**Spec:** User-approved separate penalty/date evaluation version; `docs/rag-qualified-example-scoring.md`, `docs/rag-quality-gate.md`, and the unchanged safety constraints in `docs/evidence/rag-quality/source-list-scope-20260929.md`. Fresh diagnosis `b5a887`: complete applicability + remedy has two supported claims/unsupported0 but broad explicit overview intent still fails SUBJECT/CONDITION. This is a new model-free request, not replay of a frozen failed case.

## Global Constraints

- Keep Atlas18080 and paused automation unchanged; preserve shared main and unrelated dirty files.
- Preserve generic-violation exclusion, Article51 breach, approval-review stage, actor, discretion, source ownership and final unsupported-claim checks.
- No question IDs, source IDs, expected answer text or fixed rankings in product behavior. Existing recognized normative source patterns may be reused; source IDs belong only to offline fixtures/contracts.
- A conditional example does not establish applicability to an actual unspecified project. Missing qualification, unrelated private scope or a broader automatic sanction remains a refusal.
- No failed evaluation replay, frozen oracle replacement, external payload expansion or live registration as an incidental implementation step.
- Reuse exact-tree verification; one focused RED/GREEN cycle per defect, one stable relevant full suite, one scoped review/fix pass, official deployment health checks.

## Review Focus

- An ordinary general violation question must still reject the subcontract remedy.
- Appending an example-format request must not erase a named incompatible project, duty, actor or stage.
- Scope and remedy with identical text in different documents/editions must not join.
- Missing or ambiguous source attribution, added claims, weakened modality and missing conditions must fail.
- One complete example is not evidence that every legal regime or every information-system project has the same consequence.

### Task 1: Explicit overview intent and source-owned final proof

**Files:** Modify `src/main/java/com/kaces/pandora/ai/answer/AnswerQuestionAlignmentVerifier.java`; create `src/main/java/com/kaces/pandora/ai/answer/ConditionalExampleOverview.java`; test `src/test/java/com/kaces/pandora/ai/answer/ConditionalExampleOverviewTests.java` and existing alignment/claim tests.

**Interfaces:** `ConditionalExampleOverview.requested(String question)` recognizes an explicitly conditional, non-assumptive overview request only. `AnswerQuestionAlignmentVerifier.qualifiedApprovalExample(VerificationResult claims, List<LawAiAnswerGround> grounds)` returns an accepted operative claim only after complete existing approval/source checks. It consumes full verification, never a truncated report or a model-authored verification.

- [ ] Write a positive test with complete applicability, literal remedy and the independently referenced approval procedure; ordinary broad-question, missing scope, wrong source/version, actor, duty, stage, modality and extra-claim controls must refuse.
- [ ] Run focused tests; expected RED only for the new explicit overview intent, with ordinary source claim support already intact.
- [ ] Extract/reuse the existing approved source-owner checks from `approvalReviewProfile`; do not implement a synthetic scoped question or skip alignment groups generally.
- [ ] Require exactly the two supported applicability/remedy claims, unchanged unsupported/numeric/contradiction checks and unique same-edition owners for the supported example. Named restrictions in the request remain binding, not discarded as format words.
- [ ] Run focused tests; expected GREEN and existing broad-violation controls unchanged. Commit the coherent proof slice.

### Task 2: Recall, selection and complete context transport

**Files:** Modify `EvidenceJudge.java`, `LawAiAnswerService.java`; test `LawAiAnswerServiceEvidenceGateTests.java` and relevant evidence-judge/context tests, under the same Java package paths as Task1.

**Interfaces:** Consumes `ConditionalExampleOverview.requested`. Reuses `EvidenceJudge.isSubcontractApprovalRemedySource`, current bounded approval lookup, `withSubcontractApprovalProcedure`, `linkedSubcontractApprovalScope`, and the existing direct/related source-role transport. Produces a complete source-owned context, not a final verdict.

- [ ] Write a deterministic lookup→intent→judge→context test for the new request class; prove the complete scope and procedure survive without evicting the operative remedy. Negative generic/private/wrong-stage controls stay rejected.
- [ ] Run focused tests; expected RED at the first diagnosed loss stage, not a recreated external failure.
- [ ] Add the explicit overview intent to the existing bounded source lookup and support-slot path only. Keep direct-source approval conditional on the source pattern, and keep final acceptance conditional on Task1 proof.
- [ ] Add a source-literal response instruction for this explicit request class; no assertion of real-project coverage and no general automatic consequence. Answer and streaming use the same captured context.
- [ ] Run focused tests and a fresh model-free active-source route probe; expected complete context and unchanged negative controls. Commit the connected behavior.

### Task 3: Stable verification, delivery and contract handoff

**Files:** The two tasks' source/tests; `docs/rag-qualified-example-scoring.md`; evidence top entry. No frozen dataset files.

**Interfaces:** Consumes complete source-qualified verification envelopes and exact product/source identities. Produces a verified pushed/deployed product and a bounded next contract-registration handoff, not a fabricated penalty PASS.

- [ ] Run `.\mvnw.cmd -q test` once on the stable tree on the approved host; expected exit0 with failures/errors0, separately count skipped tests and exclude orphan old reports.
- [ ] Run relevant Node scorer/provenance tests; expected zero failures. Request one scoped independent review, correct only Critical/Important findings with RED→GREEN evidence.
- [ ] Commit/push tested files, stage with `-Papp-dev-staged-package -DskipTests package`, confirm official host status/ownership, official stop of owned8080 if needed, verify no listener, official deploy and health. Atlas/Qdrant stay unchanged.
- [ ] Record actual behavior, exact SHA/JAR/source identities and untouched legacy gate. Before any versioned live evaluation: bind immutable reviewed dataset/contract and supported server registration, exact external-payload approval, runtime/corpus preflight and exclusive slot. Otherwise keep only that evaluation branch withheld and continue independent work.

## Pre-flight decisions

Task1→Task2 shares the explicit request predicate and existing source proof; Task2 may retrieve candidates but cannot replace Task1's final acceptance. Task2→Task3 shares the exact stable tree and complete source-qualified envelope; transport alone cannot be reported as answer PASS. User opted out of repeated design/implementation approval questions; implement inline within these constraints.
