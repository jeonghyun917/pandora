# Source premise ownership boundary

Status: diagnostic design only; no answer authority or verifier changes.

## Goal and evidence

The release goal remains grounded answers, not acceptance by relaxed checks. Actual gate 52523 failed both cases. Local contrast shows the literal statutory duty supported by both matchers, while adding a question-business membership premise preserves the duty roles but adds one unsupported condition. The current conditional-generation instructions request that extra condition. This is a representation contract mismatch, not another newline or role-extraction defect.

## Approaches

1. Preserve strict verification and inspect source ownership before generation (selected). Keep the question premise separate from source propositions in diagnostics; never promote it to a source fact.
2. Retry or adjust the prompt alone (rejected). The same unsupported-condition contract remains, so another API attempt would not establish a causal fix.
3. Relax condition matching or inject a synthesized source witness (prohibited). It would bypass the release safety contract.

## Smallest next slice

Extend the existing ignored, readonly probe, not product interfaces. For each selected source, report its document ID, chunk ID, actual version, content hash, literal clause boundaries, actor, modality, conditions and exceptions. Report the question premise separately as HYPOTHETICAL_UNPROVEN. Compare exact source conditions to generated conditions and report the missing owner without constructing a new evidence atom.

Same-document adjacency, document titles and parent metadata are not proof. Do not join a scope chunk to a duty chunk unless an explicit body-source relationship and version ownership are established; even then the output remains diagnostic, not an acceptance witness. A generic software definition cannot prove that the user's business is software procurement.

## Acceptance and exclusions

- Literal duty stays SUPPORTED in both existing matchers.
- A separate literal scope plus that duty does not support an added individual-business premise.
- An explicitly identical conditional source can support its literal conditional claim; do not reject all conditionals indiscriminately.
- Changed issuer, business class, modality, purpose, timing or exception remains unsupported.
- No API call, DB mutation, cookie/token handling, failed-artifact execution, dataset/oracle change, final-verifier change or authority activation.
- Diagnostic success is not SNS/penalty PASS. Difficult12, holdout57 and full1003 remain gated.

## Verification

The source-premise ownership safety test in SemanticEvidenceMatcherTests fixes the first three acceptance checks. Focused SemanticEvidenceMatcherTests and GroundedAnswerRepairServiceTests passed 114 tests, zero failures/errors (93689). Product code is unchanged from e8f7c8a3; the already-verified deployed artifact is unchanged. Further diagnostic output must be compared to original literal source, never used as fabricated grounds.
