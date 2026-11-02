# Source-owned reference relations implementation plan

> Native sequential execution under executing-plans; existing continuous approval
> opts out of design questions. Parent source-version spec remains binding.

**Goal:** Make explicit native condition/score column alignment and official
reference declaration provenance inspectable and immutable, without claiming
cross-format/semantic/legal fidelity.
**Architecture:** Separate pure bounded column evidence and declaration evidence;
bind them to native inspection/source receipts. Never flatten parent/child table
ownership or infer AND/OR, attachment legal effect or harmlessness.
**Spec:** docs/superpowers/specs/2026-10-28-law-source-version-design.md.
**Tech:** Existing Java17/StAX/Jackson/JDBC; no new dependency.

## Decisions and constraints

Chosen: source-coordinate evidence, not substring matching or model guesses.
Cost: unfamiliar layouts remain unsupported and cannot authorize promotion.
Recognize only an explicit first-column `구간` label (including vertical merge)
and final-row `점수` label; preserve every condition row and its exact cell path,
text and column alongside the same-column score. Value cells must be unmerged,
nonempty, child-table-free; scores must be literal bounded numbers. Extra labels,
unknown/ambiguous shapes refuse alignment, never guess a relation.
Outer ownership and all unsupported tables remain recorded. Alignment is not
condition evaluation, AND/OR semantics, source-body equivalence or legal scope.

Role provenance means exact JSON declaration path under the official root,
declared annex owner vs general attachment vs unknown; no filename/metadata
text becomes answer grounds. Raw source hash and exact asset order remain bound.
Existing readiness/mutation guards stay closed. Preserve main/Atlas18080/Q and
paused automation. Keep parent ledger/evidence directory for continuity.

## Tasks

- [x] TDD SourceTableColumnEvidence: two/multiple condition rows, vertical label
  merge, swapped scores stay source-owned, parent context retained; horizontal
  merges/nested values/duplicate labels/non-numeric/missing cells unsupported.
  Integrate evidence into SourceReferenceInspector and structure identity.
- [x] TDD SourceReferenceDeclarations: exact annex/attachment/unknown owners,
  duplicate occurrences retained, malformed/unbounded inputs refused; source
  receipt binds declaration list to verified raw payload and asset membership.
- [x] Focused real parser/receipt tests, one changed-tree full suite, one scoped
  independent review, commit/push, official8080 package/deploy/health.
- [x] Diagnose current official bytes once through changed relation path, retain
  exact byte hashes locally to avoid repeated downloads. Continue only genuine
  proof; no final readiness substitution. Actual update/penalty conditional on all
  remaining source/reference/applicability gates, not these local PASSes.

Actual551c2d:4hashes unchanged, annex8tables/6aligned/30columns; stored bytes for
offline continuation. Attachment body47dcd5:33articles match in order, no legal-role
certificate. Review twoImportant issues RED2→GREEN22: reject score-internal
whitespace; cap serialized evidence262144characters/artifact before retention.
Final changed-tree full1977/f0/e0/skip28(0561a0); pre-fix1975PASS is not release proof.
Ruling: exact declaration provenance and column alignment are separate partial
proofs, not a replacement for full semantic/legal/cross-format fidelity; cost is
continued refusal to activate valid-but-unproved references. Parent ledger and
artifacts retained; no cleanup/reapproval/automation resume inferred.

Delivered1655e5c/pushed8413c5; stage43850exit0, packaged cached-artifactb95ddaPASS,
officialdeploy83029 KEEPALIVE/PID33380/runtime3995f109-370d-4dc0-b49c-98a09c8e38b7;
health0f3ccb root200/DB=Q211658+84248/qreadytrue/qfail0,mutationgatesclosed.
No actual source update/penalty; remaining semantic/legal/cross-format conditions
are documented in docs/evidence/rag-quality/reference-relations-20261102.md.
