# Source-owned HWPX hierarchy capture

Parent spec: docs/superpowers/specs/2026-10-28-law-source-version-design.md.
Native sequential continuation is authorized; preserve existing flat/nested proof
guards, active sources,1765,main,18080,Q and paused automation. No mapping or
readiness flag may be supplied by the caller.

## Evidence and bounded decision

Stored official annex bytes contain8tables/21rows/98cells. A7x4 outer table owns
6nested tables; the current flat extractor deliberately retains only a separate
1x3 row. Do not remove its nested-table rejection or `hasTableAncestor` guard.
Add a separate source-reference-only hierarchy representation. Capturing it is
NOT semantic/table fidelity, legal scope entailment or activation authority.

## Interface / architecture

`SourceHwpxTableGraph.capture(Path)` consumes only the already acquired bounded
native artifact. Returns immutable tables/cells with sourcePart, exact qualified
XML path, nearest owning parent-cell path, row/column coordinates, spans, raw
header attribute, direct-cell text and explicit child-table paths. Exclude nested
table text from the outer cell's direct text, preserving ownership rather than
copying inner values into outer headers. Never infer header roles or legal scope.

Use secure bounded StAX: reject DTD/external entities; max64depth/100000nodes,
256tables/10000cells/262144total grid positions/4million direct-text characters.
Archive/mimetype/duplicate identity checks remain explicit; section input bound
2MiB each/8MiB total. Preserve namespaces in qualified source paths. Validate
row ownership, one address/span per cell, coordinate/spans, no overlap and full
grid coverage; unsupported structures reject rather than produce partial proof.
No new dependency, global parser rewrite or change to existing SourceTableRow.

Ruling: retain raw namespaceURI separately and percent-encode namespace components
inside path IDs; reject over4096-character paths and over4million aggregate
stored-path characters. This makes owner IDs unambiguous and memory-bounded.
`directText` captures native character events/paragraph boundaries, not rendering
equivalence (inline tab/lineBreak controls remain a documented limitation). Exact
artifact bytes are authority; no semantic claim may rely on this limitation being
resolved implicitly.

SourceReferenceInspector HWPX structureHash may bind both existing page evidence
and this full hierarchy, while `INTEGRITY_ONLY/fidelityReady=false` stays fixed.
Immutable stored artifact bytes are the recomputation authority, not a URL or
mutable parser metadata. Product/candidate/manifest binding remains unchanged.

## Task1 TDD capture

- Nested table retains exact parent-cell ownership, coordinates, spans, source
  order and comparison/condition text without header inheritance.
- Existing flat extractor still refuses nested direct-proof rows.
- Malformed addresses/spans, overlaps/gaps, duplicate source identity, depth,
  cells/grid/text/XML bounds and DTD/external entities fail closed.

## Task2 integration and actual diagnosis

- Bind hierarchy to source-only inspection; no reference readiness relaxation.
- Inspect the observed official bytes through the isolated artifact path once for
  this changed capture path. Confirm all8table/98cell owners or record exact
  unsupported metadata. Do not force counts or re-run a terminal quality case.
- Complete source-body/reference semantic proof remains a separate gate even if
  hierarchy capture succeeds. General-law consequence applicability still needs
  direct official support; source structure cannot manufacture that support.

## Delivery

Focused RED→GREEN, stable changed-tree full suite, scoped review, verified
commit/push, official package/deploy and health. No repeated suite for transport.
Reuse the parent progress ledger and preserve exact evidence artifacts.

Delivered689a580: focused33PASS, actual official8tables/98cells/6nested capture
1PASS in isolated DB, stable full1969/f0/e0/skip28, scoped review noC/I, official
package/push/deploy and health. Capture availability is complete for this slice;
rendering/semantic/table/legal correspondence is not certified. Current remaining
release prerequisites and minimum external writer facts are recorded in
docs/evidence/rag-quality/source-reference-release-handoff-20261102.md.

## Continuation: native inline condition boundaries

Authorized native continuation, no additional design questions. Code inspection
found that inline native lineBreak/tab elements inside a cell's text are ignored,
joining separate conditions. Preserve these as newline/tab in the same owning
cell and existing text budget. Reject unknown/foreign/nonempty inline controls
rather than silently delete possible meaning. No renderer, inferred headers,
new dependencies, readiness permission or legacy extractor change.

- [x] Reproduce literal `90% 이상\n110% 미만\t4점` preservation failure and
  unknown/foreign/nonempty inline-control acceptance with real bounded HWPX ZIPs.
- [x] Minimal SourceHwpxTableGraph change, focused parser/inspector/legacy guards.
- [ ] One stable full suite; exact byte/structure hashes may differ but semantic
  readiness remains false. Self-review, commit/push, official package/deploy/health.
- [ ] Continue source/reference correspondence diagnosis; successful delimiter
  preservation is not full table equivalence or legal applicability.

RED8/f2/e0(bcf5ae/8f07c5); focused53PASS(8479a6);
stable full1971/f0/e0/skip28(6b5d48), owned isolatedDB23306 only.
Scoped independent inline_boundary_review: noC/I/Minor; no readiness authority.
