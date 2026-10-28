# Source-version whole-change review

Base: 3456aa7 (before this source-version feature). Head: 137730f.
Worktree: C:/dev/workspace-egov/pandora/.worktrees/group-balanced-bm25.
Read-only review: do not edit files, Git state, runtime, DB, or run Maven. The executor is running the full suite on this exact head.

Read docs/superpowers/specs/2026-10-28-law-source-version-design.md, docs/superpowers/plans/2026-10-28-law-source-version.md, and this plan's progress.md rulings. Review all product changes in 3456aa7..137730f, not just the last commit. The feature includes immutable inactive source storage, native source preservation, pre-Q writes, exact vectors, source/corpus/lexical transition reservations, atomic promotion/registered rollback, request leases and serving routing/cache/runtime/index-integrity integration, official-fetch admin API and closed gates.

Plan global constraints / review focus (verbatim):
- Existing active source and1765 recovery remain unchanged while staging.
- No new caller-provided authority, readiness boolean, arbitrary rollback ID or hash-only proof of external assets.
- Conflicting title/target/source identity, duplicate current rows, source drift, stale CAS or nonfuture-date violations fail closed.
- Concurrent legacy refresh must not change managed version visibility; transaction/async request failure must not leak a read lease.
- Preserve retired assets and lexical namespaces; no point deletion. Deployment and data mutations stay behind existing safety/ownership limits.

Important verification bounds: focused tests have executed real owned MariaDB/real lexical index operations and HTTP Q protocol servers; network embedding/Q in lifecycle DB tests is mocked. Actual isolated Q test script was policy-denied and has not been replayed/bypassed. Actual source mutation is still gated by legitimate Q validation, complete reference fidelity, baseline DB/Q health, and compatibility/ownership of all writers including unchanged18080. Default workflow and mutation flags are false. Required new source reference assets without fidelity proof reject readiness. Assess readiness for a guarded/closed-gate8080 deployment separately from actual source activation and whole RAG quality completion. No penalty/eval replay is authorized by local PASS.

Trace important boundaries including inactive preparation's cache/corpus invariance; active/retired/ready origin receipts; configured model/product and baseline provenance; visibility->source->RAG locks; async completion; every retrieval shortcut; source-only runtime/index identity; source-specific integrity verification without placeholder rows or silent membership exclusion. The spec is a vision document, so judge unenumerated input effects by reasonable-user expectations, but do not expand into unrelated refactors/features. Also identify removable complexity under ponytail-review; do not omit correctness/data-loss guards to shrink code.

Return concrete Critical / Important / Minor findings with file:line, impact, and minimal fix. Include every behavior considered but declined to judge, with reason; an empty list is explicit. No subagents, no code changes, no second review. The executor will re-grade against impact, perform one scoped fix pass for Critical/Important with RED->GREEN and changed-tree suite, and defer minors.
