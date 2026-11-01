# Source structure and reference validation implementation plan

> Native sequential execution is explicitly authorized. Use executing-plans, TDD and verification-before-completion. Reuse the isolated group-balanced worktree; no implementation agents. One final scoped review.

**Goal:** Preserve source-owned headings and annex grids, and validate immutable official reference assets without substituting integrity for semantic fidelity.
**Architecture:** A named source-version parser path preserves exact authoritative structure while legacy parsing stays unchanged. Server-only reference validation is bounded, official-host-only and bound to candidate/input/manifest; unknown content or structural fidelity never grants readiness.
**Tech:** Existing Java17/Jackson/PDFBox/JDBC/MariaDB, no new dependency.
**Spec:** docs/superpowers/specs/2026-10-28-law-source-version-design.md and the rulings below.

## Constraints / rulings

- Preserve active sources,1765,shared main/output,18080 and shared Q. Automation stays paused.
- No source/question-specific mapping, policy/auth bypass, credential extraction, caller readiness flag or verifier/oracle weakening.
- Ruling: use a named source-version parse method, not a global parser rewrite, to preserve legacy rebuild manifests; tradeoff is explicitly testing the two paths.
- Ruling: reference file integrity and readable-content evidence are necessary but not sufficient for table/attachment fidelity. Keep the existing activation gate closed unless a complete server-owned fidelity proof is actually supported. Unknown/HWP/image structures fail closed, not silently omitted.
- Preserve the evidence workspace rather than generic cleanup; it is the user's required exact resume record.

## Task1: Source structure preservation

Files: LawOpenApiPayloadParser.java, SourceVersionTextPlan.java, SourceVersionPreparationService.java and corresponding tests.
Interface: parseSourceVersionDocument(String raw,String fallbackTitle) returns SyncDetailDocument; legacy parseDetailDocument is unchanged. Source plan and stored detail use the same source parse contract.

- [x] RED: chapter headings retain original order/path and context-only role; annex short numeric/condition rows and box-drawing grid remain in source order; legacy parse output unchanged.
- [x] Implement source-only structure preservation and wire all source-version consumers.
- [x] Assert existing loss gates still reject a deliberately lossy parser; blank/metadata-only/main-body-missing sources still reject.
- [x] Focused tests and actual offline source diagnostic; no live source registration just to discover parser failure.

## Task2: Official reference validation

Files: source reference validator/receipt code, schema/config integration, tests. Consumes Task1 immutable source snapshot and manifest.

- [ ] RED: arbitrary host/port/userinfo/path/redirect, missing/oversize/truncated/unsupported assets, duplicate/conflicting identities and changed candidate/manifest reject.
- [ ] Implement bounded server-owned asset acquisition, format/native-content inspection and immutable candidate-bound receipt evidence. Never expose bytes/credentials in status/errors.
- [ ] Reference integrity must not authorize semantic/table fidelity; missing proof rejects before embedding/activation, and rollback rechecks registered proof rather than trusting caller state.
- [ ] Actual isolated DB and controlled HTTP tests; real reference diagnostics only on observed public official links.

2026-11-02 delivery ruling: the implemented foundation persists integrity-only inspection evidence and is covered by actual isolated DB tests. PDF native extraction is explicitly rejected pending resource isolation; full semantic/table fidelity remains unimplemented and blocks actual source updates. Pre-fix native PDF observations do not qualify current readiness. Final reviewed suite1952/f0/e0/skip27. Do not mark Task2 or the overall source-version release complete merely because the foundation ships.

## Task3: Delivery and qualified continuation

### Qualified continuation: bounded PDF inspection

After foundation6a17b9f delivery, implement a server-owned Java worker (no PowerShell policy/engine change): fixed heap128MiB/metaspace96MiB/direct32MiB, process deadline10s, bounded native output4million characters/1000pages, fixed hash-only response. Parent kills only its own worker on deadline and bounds concurrent workers. Use the existing Java runtime and Spring Boot PropertiesLauncher for packaged classpath; never caller-supplied commands. Plain and packaged launch paths need explicit tests/smoke. Native extraction remains INTEGRITY_ONLY, not semantic/table fidelity. No live candidate or new penalty checkpoint until all remaining gates actually hold.

- [x] Worker TDD: nominal native PDF, malformed PDF, forced timeout and fixed launch resource bounds.
- [x] Parent integration and receipt tests preserve all reference readiness guards.
- [x] Focused plus stable changed-tree full suite, reviewed scoped delivery and packaged-worker smoke (2419183;1956/f0/e0/skip27;4official references integrity-only).

- [ ] Stable full backend suite once on changed tree; final scoped independent review and one Critical/Important fix pass.
- [ ] Verified commit/push, official staged package, owned8080 normal stop/deploy and health. No repeated tests for transport.
- [ ] Actual source update only after all source/body/reference/DB-Q/writer/rollback gates hold; otherwise record the exact unsupported structure, not a stale Q/permission blocker.
- [ ] Only a cause-supported new penalty checkpoint; actual PASS precedes12→57→1003. Never replay terminal86396e8.

## Review focus

- Reordered/duplicated headings or nested table rows cannot pass merely by substring presence.
- Source preparation, persisted detail, manifest and readback must use one parser contract.
- Reference URL redirects and decompression cannot escape official-host/storage/size bounds.
- Native text/hash evidence must not be labelled semantic fidelity or bypass reference gating.
- Receipt reuse across candidate/product/manifest changes must fail closed.
