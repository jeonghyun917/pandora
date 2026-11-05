# Retrieval accuracy contrast checks — 2026-11-06

Scope: preserve correct local ordering while investigating the frozen 24-case development split. Product base is `d26158a`. No dataset/oracle, threshold, final verifier, retrieval authority, runtime or schedule changes.

## New executable controls

`RetrievalAccuracyContrastTests` exercises the production EvidenceReranker and QuestionIntentProfile directly, without database, network or model mocks:

1. A reporting action and destination must outrank a same-title incident overview.
2. Explicit required RFP contents must outrank a same-title design-audit check.
3. A conditional review-omission rule must outrank a confidentiality-impact form example.

All three passed before any production change. These are characterization/regression controls, not RED→GREEN fixes. Do not invent a ranking adjustment to obtain a product delta. The fixtures preserve important source distinctions found in the archived development-candidate review, but use general questions and synthetic identities rather than case/document-ID product overrides.

## What the checks establish

The isolated score function can prefer the direct alternative when both alternatives are supplied. The checks do not establish that current candidate retrieval supplies both, that total fused score keeps this order, or that the limited Judge input preserves either. They do not supply current Recall@20, MRR@10, legal applicability proof, or final answer accuracy.

The earlier diagnostic-only `d26158a` corrects first-loss tracing; it does not improve ranking. Existing passed evidence for it is not rerun merely for transport.

## Remaining development split work

- 24 cases have historical first-hit/body-source leads recorded in the plan workspace; the 41 available official-document channel slots were probed against the current literal audit matcher. Remaining law/admin-rule slots require applicable source/version body identity.
- TVING body exists in two matching archived SQL rows; current Qdrant metadata alone is not current-body proof. A literal audit miss is not absence of its police reporting instruction.
- The dated AI-law case has an upcoming-status expectation conflicting with the general effective date after 2026-08-28. Preserve the frozen evaluation and report the conflict, not silent relabeling, deletion or forced PASS.
- Current corpus query ranks still require an authenticated body/data path and an identified runtime without changing the protected 8080 owner. Empty test schemas or July snapshots must not be passed off as current corpus clones.

Focused verification: `mvnw.cmd -q -Dtest=RetrievalAccuracyContrastTests test` exit 0; 3 tests, 0 failures/errors/skips. Approved-host stable-tree full `mvnw.cmd -q test` exit 0; 158 fresh XML reports, 1987 tests, 0 failures/errors, 80 skipped. Conditional skipped DB/external tests are not claimed as executed. Full log: plan workspace `full-contrast-20261106.log`.
