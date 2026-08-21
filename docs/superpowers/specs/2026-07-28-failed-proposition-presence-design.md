# Failed Proposition Presence Audit Design

## Goal

Classify the 86 failures from the authoritative 1,004-case run by the first
retrieval stage at which each required proposition or condition is absent,
without weakening answer verification and without exposing complete internal
document text through the debug API.

## Confirmed Baseline

- Evaluation artifact:
  `logs/rag-eval-gate-full-direct-policy-final-20260728.json`
- Runtime artifact SHA-256:
  `07c38433525905df49ffe6115bd2b6ed1e9070d0b08df84625c3381bf7576118`
- Runtime instance:
  `63c1ff6b-9cda-467e-85a9-866817cb167d`
- Index revision:
  `892ff2509e5a107e46772092b32078123e2217c45482797953f9620aa913b9a7`
- Failures: 86, including 48 unsupported-answer cases, 19 missing-answer
  cases, 17 no-ground cases, one forbidden-evidence case, and one transient
  empty-answer case.
- A K=30 debug replay completed 86/86 with no request errors. Existing
  document/section gold survived to selection in 21 cases, first failed at
  candidate sources in 39, and was lost at an intermediate stage in 26.

These document/section metrics are routing evidence only. They do not prove
that the required proposition text exists in a candidate body.

## Considered Approaches

1. Reuse only the existing title/section retrieval metrics. This is cheap but
   cannot answer the proposition-presence question.
2. Return complete chunk bodies from the debug API and audit them in Node.
   This is accurate but unnecessarily exposes internal document content and
   creates large diagnostic responses.
3. Send bounded oracle alias groups with a protected debug request and return
   only per-chunk group-match metadata. This inspects the complete chunk body
   in the application, preserves stage identity, and avoids returning the body.

Approach 3 is selected.

## Diagnostic Contract

`LawAiDebugRequest` accepts optional `auditTermGroups`. Each group is an OR
list of literal aliases. Groups are AND requirements at the case level.

For every item at vector, lexical, merged, reranked, intent-filtered,
Judge-candidate, judged, and selected stages, the server returns
`matchedAuditGroupIndexes` and `matchedAuditAliases`. Matching uses normalized
Unicode text over the complete stored chunk body. It does not infer semantic
equivalence, entailment, or legal sufficiency.

The contract is bounded:

- at most 32 groups;
- at most 16 aliases per group;
- at most 160 characters per alias;
- blank aliases are ignored;
- malformed or oversized input fails closed with a clear validation error.

Only match metadata is returned. Complete chunk bodies are never added to the
response.

## Classification

For explicit answer-oracle cases:

- `PRESENT_IN_SELECTED`: every proposition and condition group occurs in at
  least one selected chunk body.
- `DROPPED_BEFORE_SELECTED`: all groups occur in candidate sources but not in
  selected grounds; record the first downstream stage missing a group.
- `PARTIAL_IN_CANDIDATES`: at least one, but not all, required groups occurs in
  candidate sources.
- `ABSENT_FROM_TOP_K_CANDIDATES`: no required group is confirmed in the K=30
  vector or lexical candidates.

Cases without explicit proposition oracles are reported as
`NO_EXPLICIT_ORACLE`; their existing retrieval gold and no-ground failure stage
remain visible, but expected retrieval terms are not relabeled as propositions.

Literal absence is reported as "not confirmed" rather than semantic absence.
The output is a diagnostic classification, not a quality-gate relaxation.

## Safety and Runtime

- Answer generation, Evidence Judge policy, answer verification, and fail-closed
  behavior do not change.
- Only 8080 may be rebuilt and restarted for live verification.
- 18080 is not stopped, started, promoted, or modified.
- Runtime artifact, configuration, instance, Qdrant readiness, and index
  revision are captured at both ends of the audit.

## Testing

- Java unit tests cover group normalization, body-only matching, bounds, and
  response-field exposure.
- Node tests cover group-index aggregation, first-loss classification, explicit
  oracle versus no-oracle behavior, and failure-category joins.
- Focused tests run before implementation is considered green.
- Full Maven and Node suites run before a live 86-case audit.

