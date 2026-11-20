# Trusted evaluation verification envelope

User-approved continuation of the separate evaluation-version integration; execute inline without repeated approval. Preserve frozen datasets/oracles, paused automation, Atlas18080 and official runtime rules.

## Bounded design

The legacy evaluation report combines oracle outcome with verifier outcome and truncates the answer to1500 characters. A new scorer must not infer source support from that combined flag or shortened text. Add an additive nested, schema-versioned envelope captured directly from the existing trusted `AnswerVerificationService.Result` and captured grounds. Keep all legacy fields and scoring unchanged. No contract activation, new model call, corpus write or rescore is included.

Envelope fields: schemaVersion1, complete verifiedAnswer, aligned, insufficientEvidence, unsupported/numeric/contradicted claims, evidence links, unique ground attribution, unsafe disagreement count, and an unambiguous source-owned reference-phase receipt when present. Missing verification means no envelope, not an invented PASS. Metadata-only evaluation also has no claim-verifier envelope.

### Task 1: Capture and prove

Add a small immutable envelope DTO and model-free tests for complete text longer than1500, independent alignment/insufficiency, numeric failures, duplicate attribution and source-owned reference receipts. RED→GREEN. No verifier logic changes.

### Task 2: Transport and delivery

Carry the envelope through internal answer evaluation and append it to `CaseResult`. Capture before legacy truncation and preserve old constructors/default failure paths with no envelope. Test a synthetic long generated answer through the real evaluation route, plus existing legacy contract rejection. Focused regressions, one stable full backend suite, self-review, commit/push, official staged deploy and health. Existing unchanged Node tests are reused; test JSON shape directly. No live versioned contract can use this field until independently bound and registered.

## Acceptance

Legacy passed/answerVerified semantics and 1500-character report remain unchanged. The new envelope never reports oracle success and cannot be client-supplied through existing evaluation request schemas. Untested, unavailable, metadata-only and generation-error paths cannot fabricate proof. New scorer registration and immutable dataset/runtime/source binding remain the next independent work.
