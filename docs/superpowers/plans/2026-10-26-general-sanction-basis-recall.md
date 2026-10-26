# General sanction basis recall implementation plan

> Native sequential implementation under explicit autonomous approval; executing-plans and TDD.

**Goal:** Recall current general administrative-sanction basis alongside the domain consequence source.
**Architecture/spec:** Existing policy metadata supplies a bounded title/body lookup. Its hit supplements rather than replaces SW domain lookup. Candidate filtering retains the explicit legal-basis norm; direct/supporting judgment and all final checks stay authoritative.
**Tech stack:** Existing Java17/properties/MyBatis/JUnit, no dependency.

Implementation and official closed deployment completed through0ebc55e with fresh137XML1833/f0/e0/skip26. Original candidate-only/local trace missed the final display cap; the subsequent display-order regression and correction are recorded in the ledger. New0ebc55e SNS PASS; penalty actual FAIL with supporting norm present as ninth selected row. Initial SW57 premise omission and repair structural rejection remain fail-closed; no failure replay. Difficult12/holdout57/full1003 remain gated. A new single synthetic public-payload diagnostic approval is pending because previous one-call approval is consumed. This is not overall quality completion.

## Constraints and review focus

- Administrative-sanction rules do not automatically cover criminal or contractual penalties.
- No question/source IDs, ranking boost, applicability inference, verifier/oracle changes, source mutations or failed evaluation replay.
- Recognize short law titles, not phrases ending in 방법.
- Supplemental hits must retain the domain source; partial/nonlegal mentions must not enter this candidate class.
- Keep automation PAUSED, active1765, main/18080/Q unchanged.

## Task 1: Recall and candidate retention

Files: query-intent-dictionary.properties, LawAiAnswerService.java, LawAiAnswerServiceEvidenceGateTests.java.
- [ ] RED: bounded lookup returns both general basis and domain rule; filter retains complete legal norm, rejects partial/nonlegal mentions.
- [ ] Add generic legal-noncompliance consequence policy with search-only metadata. Reuse mapper; supplement this policy's hits, retain fallback.
- [ ] Recognize short law titles and complete legal-basis body cues only at candidate boundary.
- [ ] Focused tests and new read-only source consumer diagnostic, no model calls.

## Task 2: Delivery and qualification

New current-source trace after candidate/direct split proves basis survives filter but is absent from final context. Necessary bounded continuation (no direct promotion):
- [ ] RED: at most one general-basis supporting row may replace a duplicate-document row only after an existing nonbasis direct source exists; no direct source/all unique/full context remains unchanged.
- [ ] Connect this after weak-evidence rejection, preserve unique source coverage and score maps/counts; mark only that row `related_definition` in assembled grounds, not `direct`.
- [ ] Test role preservation and no standalone answer enablement; review this new context boundary separately from prior candidate-only fix.

Actual source trace `support-current-selection.log` shows all eight retained rows are intent-direct, so duplicate-document replacement cannot safely supply the supporting norm. Revised bounded design: preserve all selected direct rows, permit exactly one additional current PASS general-basis row under a separate supporting budget (maximum total9), and carry it through answer-context compression. Never replace a direct or unique-source row. Keep its assembled role related_definition using original target/chunk identity, not truncated display text; no new standalone answer eligibility, direct counts, score boost, oracle or verifier change. This is a retrieval-context budget adjustment, not relaxation of evidentiary acceptance.

- [ ] Stable full suite once, scoped review, commit/push, official stage/deploy with mutation gate closed, health/1765 retention.
- [ ] Only after full local qualification: fresh SNS→penalty→12→57→1003. Otherwise trace next actual boundary, never replay terminal failures.
