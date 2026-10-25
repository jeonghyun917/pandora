# Source-owned conditional rule implementation plan

> **For agentic workers:** Use superpowers:executing-plans for native sequential implementation.

**Goal:** Prove only an explicitly hypothetical application of a literal source-owned legal rule.
**Architecture:** One bounded certificate parser shared by existing control/semantic matchers; ordinary guards and final verifier remain authoritative. Existing rewriteConditional alone consumes verified source context.
**Tech Stack:** Existing Java17 patterns/records, existing JUnit and OpenAI rewrite client; no dependency.
**Spec:** docs/superpowers/specs/2026-10-23-source-owned-conditional-rule-design.md

## Global constraints

No source/question-ID special case, source or oracle changes, inferred procurement,
guard waiver, synthesized evidence, authority changes, or terminal checkpoint replay.
Keep active1765/baseline assets, sharedmain/18080/Q and automationPAUSED unchanged.

## Review focus

- A missing recognition condition or stronger action remains rejected.
- Conditions cannot be borrowed across paragraphs/articles or a shortened rendering.
- Multiple/ambiguous references and marked exceptions cannot generate a certificate.
- Legacy review/SNS conditional behavior and conflicts remain unchanged.
- A prompt output is never accepted without original full verification.

## Task1: Literal source certificate and both consumer boundaries

Files: new ai/answer/SourceOwnedConditionalScope.java and tests; existing
ClaimEvidenceMatcher.java, SemanticEvidenceMatcher.java (internal index only).
Interfaces: Rule(owner,scopeLiteral,operativeLiteral,originalContext,premiseLiteral);
index(List<LawAiAnswerGround>) returns immutable rules;
bind(String claim,List<Rule>) returns owner-bound unmodified operative literals.

- [x] RED tests: exact hypothetical template supported only under unique complete source ownership; negative review-focus mutations all rejected.
- [x] Parse only bounded source paragraphs; retain original literals. Existing matchers recheck operative literal on the original index, same owner, only after INSUFFICIENT.
- [x] Focused matcher/caveat/SNS regressions and actual new-source local diagnostics, no quality checkpoint yet.

## Task2: Existing repair API and delivery

Files: GroundedAnswerRepairService.java, infra/openai/OpenAiAnswerClient.java,
GroundedAnswerRepairServiceTests.java and existing OpenAI client contract tests;
AnswerQuestionAlignmentVerifier.java and query-intent-dictionary.properties.

Actual Task1 source diagnostic d85d0b proves the literal conditional claim in both
matchers but final alignment still rejects RELATION/CONDITION. Preserve all final
requirements: consume the same-owner source certificate only for per-anchor legal
norm/noncompliance equivalence; every unrelated condition still needs literal proof.
Add source-backed improvement-recommendation terms to the existing consequence
dictionary, not an oracle, question-ID exception, threshold change or blanket bridge.

- [x] RED verified source context seeds rewriteConditional; unsupported/mutated output remains rejected; valid output passes claim+alignment.
- [x] Reuse existing API and bound atom budgets, preserve legacy source-scope guards and final re-verification.
- [ ] One independent in-scope review/fix round, stable full suite once, commit/push official closed-gate deployment and health/active1765 retention.
- [ ] New source diagnostic/approved actual checkpoint only if prerequisites really pass; then subsequent unique checkpoints in order. Otherwise report the precise remaining blocker without changing verifier/oracle.

2026-10-26: independent review/fix and stable full136XML1804/f0/e0/skip26 complete.
Commit/push/deploy remain blocked at require_escalated Git object write, explicit
sandbox SID Deny ACL on shared .git/objects. HEAD is still197336c. Do not bypass
ACL or deploy uncommitted work. Resume at approved Git write once managed execution
permissions permit it; preserve exact verified source tree and reuse test evidence.
