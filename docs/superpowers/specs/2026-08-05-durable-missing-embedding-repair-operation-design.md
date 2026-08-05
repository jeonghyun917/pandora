# Durable Missing-Embedding Repair Operation Design

## Problem and evidence

The current missing-embedding repair endpoint executes every selected chunk in one synchronous HTTP request. Wave 2 proved that this is unsafe as an operational contract: the client reported `fetch failed` after 311.1 seconds while the servlet continued indexing. The server eventually completed the exact planned 100 chunks, but the client could not distinguish continued success from an abandoned or partially applied request.

The failure was not the CLI's explicit 600-second abort. The client socket entered `FIN_WAIT_2`, the server socket entered `CLOSE_WAIT`, no server error was logged, and Qdrant continued increasing until the exact target count. A timeout increase or a smaller synchronous wave reduces probability but cannot remove the ambiguity.

## Chosen architecture

Add a repair-specific, database-persisted operation driven by short explicit HTTP steps on app-dev 8080. Do not use 18080, OpenAI Batch, a background scheduler, or fire-and-forget work.

1. `POST /api/admin/law-index-integrity/missing-embedding-repair-operations` validates the entire bounded candidate set and atomically stores an operation plus ordered per-chunk rows. It returns `202 Accepted` with an operation ID immediately.
2. `GET /api/admin/law-index-integrity/missing-embedding-repair-operations/{operationId}` returns the durable operation, current trusted runtime/index fence, and every per-ID outcome.
3. `POST /api/admin/law-index-integrity/missing-embedding-repair-operations/{operationId}/step` claims at most one item by lease/CAS, performs the existing exact one-chunk repair, verifies it, records the item outcome, and advances the trusted index revision. The response is short.
4. The Node runner registers once, repeatedly calls `step`, and uses `GET` after any transport error. It continues only when durable state proves the expected item reached `INDEXED`; otherwise it stops fail-closed.
5. When all items are `INDEXED`, the operation becomes `INDEXING_COMPLETE`. The runner then executes the existing full integrity, parent/child, short-chunk dry, runtime identity, and DB-Qdrant gates. Only the runner's verified report may call the wave successful. The database state deliberately does not call this `COMPLETE`, because the server cannot independently verify the Node audit artifacts.

The legacy synchronous endpoint remains available for compatibility but the repair-wave runner no longer uses it for live mutation.

## Boundaries and invariants

- Target is exactly `law`.
- Registration accepts 1-1,000 positive explicit chunk IDs, exact 64-hex content hashes, and at most 50 explicit document IDs.
- Registration requires exact runtime-instance and index-revision fences and performs the existing preview validation before any row is stored.
- An idempotency key is the SHA-256 of the normalized target, runtime/index fence, ordered document IDs, and ordered chunk ID/hash pairs. Re-registering the same request returns the existing operation; a key collision with different normalized content fails closed.
- Operation states are `READY`, `RUNNING`, `INDEXING_COMPLETE`, and `FAILED`.
- Item states are `READY`, `PROCESSING`, `INDEXED`, `FAILED`, and `NOT_ATTEMPTED`.
- A step processes one item. It claims the operation/item with an owner token and expiring lease using compare-and-set updates.
- Immediately before indexing, the runtime instance and operation's trusted index revision must match. After exact verification, the newly observed revision replaces the trusted revision atomically with the item transition to `INDEXED`.
- A lost response is recovered by `GET`; terminal `INDEXED` items are never indexed again.
- An expired `PROCESSING` item may be reclaimed only after current integrity is reclassified. Already indexed becomes `INDEXED`; still-missing may retry the deterministic exact-index seam; any other class makes the operation `FAILED`.
- Any failure records the current item as `FAILED`, marks remaining `READY` items `NOT_ATTEMPTED`, and makes the operation terminal `FAILED`.
- A runtime restart does not silently rebind an operation. Remaining work must be registered as a new operation from a fresh audit; terminal item state prevents already indexed chunks from being selected again.

## Persistence

Create dedicated tables rather than overloading semantic batch or activation-saga tables:

- `law_missing_embedding_repair_operations`: ID, idempotency key, target, bound runtime instance, trusted index revision, normalized request hash, status, requested/document/indexed/failed counts, lease owner/expiry, last error, timestamps.
- `law_missing_embedding_repair_items`: operation ID, ordinal, chunk ID, document ID, expected content hash, state, detail, before/after index revision, lease owner/expiry, timestamps.

Schema maintenance is idempotent and validates check constraints and indexes. The operation row owns the evolving fence; item rows are the durable evidence of exactly-once orchestration.

## Components

- `LawMissingEmbeddingRepairOperationMapper` and XML: atomic insert, lookup, claim, lease, item transition, aggregate-count, and fail-remaining statements.
- `LawMissingEmbeddingRepairOperationService`: registration, status, single-step execution, recovery classification, and durable state transitions. It delegates actual indexing and exact verification to `LawMissingEmbeddingRepairService` rather than duplicating retrieval or Qdrant logic.
- `LawMissingEmbeddingRepairOperationController`: protected admin POST/GET/step endpoints.
- `law-missing-embedding-repair-wave.js`: operation-aware register/step/poll loop followed by existing post-wave audits. Preview remains read-only and unchanged.

## Error handling

- HTTP transport failure during registration: repeat registration with the same idempotency key and accept only the identical stored request.
- HTTP transport failure during step: GET the operation. Continue only if the claimed ordinal is durably `INDEXED`; stop for `PROCESSING` with a live lease, `FAILED`, identity drift, or malformed state.
- App restart during a step: no automatic rebind. After restart, audit current state, close the old operation as failed/recovery-required through an explicit recovery method, and register only the remaining fresh candidates.
- OpenAI/Qdrant/DB failure: existing exact repair returns a per-ID failure, persisted before the operation becomes terminal.
- Post-wave audit failure: no additional mutation. Preserve the operation ID, before/after artifacts, and exact invariant failure.

## Testing

- Mapper/XML tests prove bounds, unique idempotency, lease/CAS predicates, ordered items, and terminal transitions.
- Service RED/GREEN tests cover registration idempotency, whole-request preview rejection, one-item stepping, evolving revision, lost-response GET recovery, expired-lease reconciliation, failure propagation, and restart drift.
- Controller tests cover 202 registration, GET status, step response, access protection, malformed IDs, and terminal behavior.
- Node tests simulate response loss after a committed step, confirm GET-based recovery without a duplicate step, require every item `INDEXED`, and preserve all existing post-wave gates.
- Focused Java/Node suites and independent review precede 8080 deployment. Live verification starts with a fresh manifest and a 10-item canary operation before larger bounded waves.

## Explicit non-goals

- No batch-runner 18080 integration.
- No OpenAI Batch API integration.
- No generic job framework or scheduler.
- No automatic retry of semantic/model errors.
- No relaxation of integrity, runtime, index, AnswerGuard, or evidence verification policies.
