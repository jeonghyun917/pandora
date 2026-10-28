# Law source-version implementation plan

> Native sequential execution selected by the user; use superpowers:executing-plans, TDD, and verification-before-completion. No implementer subagents; one fresh whole-change review before delivery.

**Goal:** Inactive official source versions with preserved current search, atomic promotion and verified rollback.
**Architecture:** Dedicated managed-group/candidate records, inactive documents, immutable preparation receipts, existing corpus/lexical primitives; no destructive replacement or unverified auto-current selection.
**Tech Stack:** Java17/Spring/JDBC/MyBatis/MariaDB/Qdrant, existing dependencies.
**Spec:** docs/superpowers/specs/2026-10-28-law-source-version-design.md.

## Global constraints and review focus

- Existing active source and1765 recovery remain unchanged while staging.
- No new caller-provided authority, readiness boolean, arbitrary rollback ID or hash-only proof of external assets.
- Conflicting title/target/source identity, duplicate current rows, source drift, stale CAS or nonfuture-date violations fail closed.
- Concurrent legacy refresh must not change managed version visibility; transaction/async request failure must not leak a read lease.
- Preserve retired assets and lexical namespaces; no point deletion. Deployment and data mutations stay behind existing safety/ownership limits.

## Task1: Immutable source identity, candidate storage and legacy preservation

Files: new lawdata/version source lifecycle records/repository/service; schema.sql; LawVersionStatusService and LawDocumentWriter guards; tests under lawdata/version.
Interfaces: SourceVersionPolicy validates baseline/candidate identities and transitions; source group points to one active document/revision; candidate binds exact source payload and baseline. Repository is the only authority for persisted state, transaction boundaries belong to the service.

- [ ] RED: valid distinct newer same-group version can stage, while invalid/future/same-or-older date, same external identity, wrong group, bad hashes and stale revision reject.
- [ ] RED: ordinary refresh/update cannot auto-select a staged document or displace a managed active source; repeated identical stage is idempotent and changed payload conflicts.
- [ ] Implement additive schema and transactional stage that never calls ordinary current-selection or active replacement.
- [ ] Focused identity/storage/legacy regressions; isolated DB confirms baseline byte/state equality and rollback on mid-write failure. Commit verified slice; keep mutation gate closed until all tasks finish.

## Task2: Exact asset readiness and atomic promotion/rollback

Consumes Task1 immutable identities; produces prepare/status/promote/rollback service methods and admin-protected API. Reuse source parser/chunk planner and existing recovery lexical/corpus index verification; candidate vectors remain isolated.

- [ ] RED: missing/changed source coverage, baseline, embedding receipt, Qdrant point/hash/cardinality, lexical readback, product/config/corpus identity reject readiness.
- [ ] RED: source switch writes all visibility/pointer/epoch state atomically; stale CAS, concurrent preparation and injected write failures preserve baseline.
- [ ] RED: registered retired version rollback uses fresh verified assets and revision; arbitrary/unprepared version rejected; old assets remain.
- [ ] Implement exact receipts and transitions; isolated DB+Q tests (no bypass of denied scripts).

## Task3: Request boundary, delivery and qualified actual refresh

Consumes Task2 transition locks/epoch; produces mixed-generation prevention for search, title/keyword/vector/context/cache/answer and streaming, including async cleanup. Lock/snapshot mechanism must be recorded before implementation.

- [ ] RED: request spanning attempted switch cannot return mixed source versions; sync/async/error/timeout release; bounded admission fails closed.
- [ ] Admin/CSRF/mutation-disabled tests; focused suites and stable full suite once; one scoped independent review/fix pass.
- [ ] Commit/push approved branch; official stage/8080stop/deploy/health; no tests repeated solely for transport.
- [ ] Only if safety gates pass: exact current guideline stage→validate→prepare→promote and rollback-readiness check, baseline retained; provenance/DB-Q/consumer health verification. Do not touch18080 ownership or infer that a refreshed Article18 proves general applicability.
- [ ] Only cause-supported new penalty checkpoint after actual prerequisites; then unique12→57→1003 upon actual PASS. Otherwise preserve exact blocker without replay.
