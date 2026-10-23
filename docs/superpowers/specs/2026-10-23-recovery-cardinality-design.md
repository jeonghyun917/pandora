# Exact recovery cardinality without per-request posting scans

## Intent and evidence

Preserve fail-closed loss detection before cache/search, without counting 41,924,991
posting rows on every request. Actual `recoveryAvailable` exceeded its unchanged
10-second SQL limit (eeac8d). Full source/row/statistic verification remains mandatory;
the bounded verifier in 8727bf2 passed the existing immutable corpus read-only in
488020ms (a2bfef), without publishing or rebinding that generation.

The user explicitly delegates recommended in-scope design, implementation and
verification without another approval question. This supplements the approved
document-recovery design; it does not authorize broader DB tuning or a guard waiver.

## Selected design

Add `semantic_lexical_term_cardinality`, keyed by `(index_version,target,chunk_id)`,
with nonnegative `term_count` and an ON DELETE CASCADE FK to lexical chunks.
An AFTER INSERT chunk trigger creates zero counters only while the parent is BUILDING.
Posting triggers never insert or repair counters. MariaDB triggers maintain each chunk count in the posting mutation transaction:
AFTER INSERT increments, AFTER DELETE decrements; BEFORE UPDATE decrements the old
chunk and AFTER UPDATE increments the new chunk only when its identity changes.
Field/value-only updates do not change cardinality, as with the existing count guard.

Request readiness keeps the exact READY chunk count and statistic count. Replace
only the posting COUNT with SUM of at most one count per chunk, requiring exact counter coverage.
A durable epoch binds counters and integrity receipts. Under a DB advisory lock,
missing/changed trigger creation timestamps or body hashes rotate the epoch while
disabled before DDL; enable only after all five attestations are installed. Normal
restart preserves an unchanged epoch; a write gap permanently invalidates old receipts.
Every readiness checks current epoch and all five trigger attestations. Also check actual posting existence for
positive/zero expected counts: TRUNCATE does not fire DML triggers, so an empty
posting table must not look ready. A missing counter table/trigger fails closed.

Parent chunk deletion cascades its count row alongside its postings; it cannot
leave the posting count artificially high. Namespace deletion removes all child
metadata through existing FKs. Transaction rollback restores both data and counts.

No backfill, manual counter repair, mapping or READY override is allowed. Existing
namespaces without complete counters are unavailable for recovery. Ordinary READY
retrieval is unchanged. New recovery namespaces are built after trigger installation.
Full preparation verification still reads all actual chunks/postings/statistics and
compares their values and cardinality independently of this request-time counter.

## Alternatives rejected

- Raise 10-second timeout or cache an old readiness result: fails latency/loss safety.
- Sample postings or trust declared counts alone: weakens loss detection.
- One counter row per namespace: unnecessary hot-row contention and cascade handling.

## Scope and verification

Only lexical schema maintenance, readiness SQL, isolated integration tests and
operational evidence change. No source/verifier/model/authority gates change.
Test insert/delete/identity move, rollback, parent cascade, missing counter/trigger,
empty table, and cross-namespace isolation in fenced MariaDB. Keep existing missing
posting tests. Review the DB mutation boundary independently before live use.
Deploy only after stable backend verification; create a fresh product-bound candidate.
Measure request readiness on its full corpus before promotion. Preserve automation PAUSED.
