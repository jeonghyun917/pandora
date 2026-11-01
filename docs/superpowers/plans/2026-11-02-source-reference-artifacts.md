# Immutable reference artifacts and fidelity prerequisites

Parent spec: docs/superpowers/specs/2026-10-28-law-source-version-design.md.
Continuation of the source structure/reference plan, after its bounded-PDF delivery.
User authorizes native sequential execution without routine reapproval. Preserve
the active source,1765,main,18080,Q and paused automation; no evaluator relaxation.

## Bounded goal

Persist exactly the acquired official bytes behind each candidate-bound inspection
so later structural/semantic verification reads immutable artifacts rather than
re-downloading mutable URLs. This is a fidelity prerequisite, not fidelity proof.
Do not open the transition reference gate in this slice.

## Task1: acquisition envelope

- Add a package-private immutable acquisition envelope (inspection plus cloned
  bytes). Public/API inspection returns hashes/status only; no caller can submit
  bytes, paths, readiness or commands.
- Preserve all existing official-address, declaration, download, format, expansion
  and worker bounds. One acquisition per uncached artifact, no automatic retry.
- Tests: bytes cannot be modified through returned arrays; public inspection and
  API response never contain artifact bytes; original acquisition hash binds the
  bytes actually passed to native inspection.

## Task2: atomic durable artifact parts

- Add candidate/asset/part rows with fixed1MiB parts, each part hash and a full
  artifact hash/part count/byte count bound to the inspection manifest. Total
  candidate bytes remain32MiB; no unbounded BLOB packet or new dependency.
- Insert inspection header and all parts in the same existing source-epoch
  transaction after candidate/input/raw/manifest recheck. Read back exact ordered
  parts and verify complete byte hash before commit. No external I/O while holding
  source DB locks. UPDATE/DELETE guards preserve registered evidence.
- Cache reuse must verify the complete immutable artifact set. Missing, reordered,
  altered, duplicated or surplus parts fail closed; never re-download or overwrite
  to conceal corruption. Older hash-only reports fail closed without a silent
  promotion or migration of their claimed authority.
- Actual isolated DB tests: success/reuse, input drift, injected part-write failure
  leaves no partial header/parts, modified/missing/surplus part rejection, immutable
  row protection, and reference gate still rejects INTEGRITY_ONLY.

## Task3: source-owned fidelity boundary

Observed actual artifact boundary (stored-byte diagnostic20261102):annex HWPX
contains8tables/21rows/98cells, no pictures or field codes. A7x4 outer table owns
6nested tables (3x6 plus five2x6); existing flat structure retains only a separate
1x3 row. Keep the existing nested/ancestor guards. Next implementation is a
separate source-only hierarchical table/cell graph with exact XML owner paths,
not a relaxation of the flat row proof or an inferred legal scope mapping.

- Preserve exact reference declaration paths and native table cell/header paths
  before designing a role-specific proof. URI equality, sibling adjacency, text
  substring coverage and raw/native hashes are not table/semantic entailment.
- Inspect stored artifacts and record which declared reference roles have direct
  source-owned correspondence. Unknown roles/cleaner-lost offsets/native PDF table
  structure remain unsupported; never fabricate mapping or delete prerequisites.
- Do not grant readiness in this slice. A later complete, mutation-tested proof
  must preserve conditions, subjects, amounts, temporal operators and whole-table
  relationships before an actual source update can be considered.

## Delivery

Focused RED→GREEN, actual isolated DB tests, one stable changed-tree full suite,
scoped review, verified commit/push, official package/deploy and health. Reuse the
parent evidence workspace/ledger and exact SHA-bound evidence; no repeated suite
for transport. Actual penalty remains blocked until source and direct consequence
scope gates hold; a terminal failed checkpoint is never replayed.
