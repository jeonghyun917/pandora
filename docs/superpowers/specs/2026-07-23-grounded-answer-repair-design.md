# Grounded Answer Verification And One-Shot Repair Design

**Date:** 2026-07-23

## Goal

Pandora must not return a plausible answer merely because its wording avoided the
old "strong claim" cues. Every substantive answer atom must be grounded, the
surviving answer must still answer the user's question, and one bounded repair
attempt may use only supported evidence atoms.

This slice does not change retrieval ranking, Qdrant data, or the 18080 batch
runtime.

## Confirmed failure modes

1. `ClaimVerifier` keeps non-strong sentences and caution-only sentences without
   matching them to evidence. When `strongClaimCount == 0`, it returns the original
   answer as supported.
2. Removing an unsupported conclusion can leave a supported but irrelevant side
   statement. The answer is then reported as `OK` because claim support and
   question responsiveness are not separate gates.
3. Answer evaluation requires only one fallback retrieval term unless a case has
   explicit answer terms. The current 85 answer-verification cases therefore do
   not encode the proposition, conditions, and forbidden conclusions that a human
   reviewer expects.
4. A first verification failure immediately refuses even when verified atomic
   evidence could support a shorter correct answer.

## Safety invariants

- Substantive text is verified by default. Wording style never exempts a claim.
- The only non-claim atoms allowed through are empty/format-only labels and the
  exact standard insufficient-evidence answer.
- Contradicted or conflicted claims remain fail-closed. Repair cannot override a
  contradiction.
- Post-verification alignment is checked against the question's subject,
  relation/intent, explicit condition anchors, and a direct conclusion signal.
- Repair runs at most once, receives no rejected draft, and receives only evidence
  atoms already selected as directly relevant and supportable.
- A repaired answer passes the same claim and alignment gates from the beginning.
  Failure returns the standard insufficient-evidence answer.
- Only the final verified answer can be streamed or cached.
- 18080 and its runtime JAR remain untouched.

## Design

### 1. Default substantive-atom verification

`ClaimVerifier` continues to use `ClaimEvidenceAtomizer`, but replaces the
strong-cue allowlist with a narrow structural exemption classifier:

- `STRUCTURAL`: punctuation/list markers or a heading such as `결론:` or `주의:`
  with no semantic payload.
- `STANDARD_REFUSAL`: the exact Pandora insufficient-evidence response.
- `SUBSTANTIVE`: every other nonblank atom, including noun phrases, bullets,
  fragments after a colon, caution wording, and non-assertive endings.

Every `SUBSTANTIVE` atom is matched by `ClaimEvidenceMatcher`. No supported atom
means refusal. Existing numeric and contradiction checks remain in force.

### 2. Post-sanitization question alignment

A focused `AnswerQuestionAlignmentVerifier` evaluates supported evidence links
after unsupported atoms are removed. It builds a `QuestionIntentProfile` and
requires:

- a configured entity/concept anchor when the question has one;
- a relation/intent group when the question asks scope, exception, requirement,
  procedure, period, amount, or another configured relation;
- explicit condition anchors from direct-evidence groups when present; and
- at least one supported claim that provides the requested direct conclusion,
  rather than only background or a side exception.

The component returns a reason code and missing groups for logs/tests. Existing
callers that do not have a question retain a claim-only overload; production and
evaluation paths must use the question-aware overload.

### 3. Explicit answer-evaluation oracle

The 85 answer-verification cases receive explicit data fields:

- proposition groups: AND across groups, OR among aliases in a group;
- required condition groups: every named condition must be present;
- forbidden answer expressions: any match fails the answer.

The fields live in a maintainable TSV oracle keyed by evaluation case ID and are
merged by both the Java and Node loaders. Loaders reject duplicate IDs, orphan
oracles, missing oracles for explicitly verified cases, and a count other than 85.
Evaluation no longer falls back to one retrieval term for these cases.

### 4. One-shot grounded repair

`GroundedAnswerRepairService` orchestrates verification:

1. Guard and verify the generated answer.
2. Run question alignment on the surviving supported atoms.
3. If either gate fails without a contradiction, collect only question-aligned
   evidence atoms associated with supported/direct selected grounds.
4. If no such atom exists, refuse immediately.
5. Call a small rewriter interface once with the question and those atoms only.
6. Guard, claim-verify, and alignment-verify the rewritten answer once.
7. Return it only when all gates pass; otherwise refuse.

The OpenAI adapter uses a dedicated repair prompt that forbids adding facts,
conditions, numbers, exceptions, or citations not present in the supplied atoms.
Unit tests use a fake rewriter and make no network call.

## Error and observability behavior

- Claim, contradiction, alignment, and repair-exhausted failures are distinguishable
  internally while preserving the public fail-closed result contract.
- Repair exceptions do not expose partial text; they become the standard refusal.
- Logs record whether repair was attempted and the alignment reason, without
  logging secrets or the OpenAI key.

## Verification strategy

For each component: write a failing test, confirm the intended failure, implement
the smallest general rule, run focused tests, inspect the diff, and run the broader
suite before moving on. Final verification includes Maven tests, Node evaluation
tests, PowerShell runtime tests, two stable 85-case answer runs, and then the full
1,004-case gate when runtime prerequisites are healthy. Runtime validation may
restart only Qdrant and 8080 through repository scripts.
