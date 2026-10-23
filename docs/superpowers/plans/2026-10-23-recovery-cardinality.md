# Recovery cardinality implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Exact request-time posting loss detection without a 42-million-row scan.
**Architecture:** Transactional per-chunk posting counters maintained by five MariaDB triggers. Counters originate only at BUILDING chunk birth. A durable epoch and trigger creation/body attestation invalidate all prior accounting after trigger interruption; full preparation verification remains independent and exhaustive.
**Tech Stack:** Java 17, MyBatis, MariaDB 12.2, existing Spring schema maintenance.
**Spec:** `docs/superpowers/specs/2026-10-23-recovery-cardinality-design.md`.

## Global constraints

- No timeout increase, sampling, guard waiver, manual counter backfill or forced manifest mapping.
- Preserve baseline4, all old candidate assets, main/18080/shared Q and paused automation.
- No promotion before full actual preparation and request readiness pass.

## Review focus

- Cascaded parent deletion must remove counters even though FK cascades do not run posting triggers.
- TRUNCATE must not preserve false readiness.
- A transaction rollback or failed identity move must restore the original count.
- Old namespaces without counters must fail closed, not be inferred complete.
- Dropped triggers or cross-namespace moves must not silently preserve readiness.

## Task 1: Exact transactional cardinality and readiness

Files: `schema.sql`, `SemanticLexicalSchemaMaintenance.java`, `SemanticLexicalMapper.xml`,
new fenced `RecoveryCardinalityDatabaseTests.java`; reuse the existing DB test fence.

Interfaces: no new public HTTP API. Table columns are `index_version VARCHAR(64)`,
`target VARCHAR(30)`, `chunk_id BIGINT`, `term_count BIGINT CHECK(term_count>=0)`.
Trigger names: `recovery_terms_insert_count`, `recovery_terms_delete_count`,
`recovery_terms_update_old_count`, `recovery_terms_update_new_count`.

- [ ] Write actual fenced DB tests for every review-focus boundary, normal DML, missing/extra postings and namespace isolation. Observe failure before implementation.
- [ ] Create counters and provenance in idempotent maintenance, serialized by a DB advisory lock. Install five single-statement triggers. Do not scan/backfill existing postings; never synthesize a missing counter from postings.
- [ ] Replace only readiness posting COUNT with per-chunk SUM, trigger-presence and actual existence checks. Leave complete preparation readback/counts unchanged.
- [ ] Run focused isolated tests, self-review correctness, one independent high-risk review and fix important in-scope findings.
- [ ] Run stable backend suite once; commit/push exact verified files.

## Task 2: Official deployment and bounded live continuation

- [ ] Gracefully stop only the fenced DB after verification. Stage official JAR; check owners/writers and officially deploy exact product with recovery gate closed initially.
- [ ] Verify trigger/schema install, unchanged baseline Q/DB, runtime/JAR health and counterless old namespaces fail closed. Do not manufacture counters for them.
- [ ] Enable only the approved recovery flow through official deployment; fresh preview/create/validate/prepare for document1765 under the new product identity.
- [ ] Verify complete assets, exact readiness within10s and preserved baseline, then promote via normal ADMIN/CSRF UI. Verify request routing and source-consumer behavior.
- [ ] Continue original condition-proof/penalty→Difficult12→holdout57→full1003 order only when its prerequisites really pass. Keep automation PAUSED.
