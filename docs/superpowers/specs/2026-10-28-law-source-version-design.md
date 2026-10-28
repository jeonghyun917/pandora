# Law source-version lifecycle design

User explicitly delegated implementation choices and opted out of design/approval questions. This is an authorized new source-version lifecycle, not a claim that the previous same-document1765 recovery already supports it.

## Intent and constraints

Preserve the current law/admrul source document and all its chunks, embeddings and assets while a different official source version is fetched, parsed, validated and indexed. A candidate must not affect CURRENT/PAST, document visibility, keyword/vector results, caches or the current lexical namespace. Promotion and rollback are compare-and-set operations over an exact source identity and preserved baseline. No verifier/oracle weakening, forced heading mapping, token extraction, Qdrant deletion, shared main/output changes or18080 interruption. AutomationPAUSED.

## Approach

Rejected: calling ordinary sync and repairing visibility later; it exposes a version before preparation. Rejected: overwriting an existing document then reconstructing it on rollback; it loses immutable version identity. Chosen: separate persistent source-version groups/candidates, inactive candidate documents, immutable source/content hashes and exact readiness receipts; keep historical documents intact.

The serving8080 runtime must protect search/answer/streaming from a source switch occurring mid-request. Prefer reuse of existing recovery snapshot/corpus identity; if adapting all existing law mappers would require a broad rewrite, use a bounded database shared read lease at the HTTP request boundary and exclusive source-switch lock. The latter must survive async dispatch until completion/error/timeout, reject timeout rather than silently serve a mixed request, and never depend on ThreadLocal state or a client-selected generation. Record the final choice before implementation. A lease consumes one connection for its lifetime; enforce admission/timeout bounds and verify lifecycle cleanup.

## State and transitions

Group identity is target+canonical title. The initial current document is explicit, singular and immutable as a baseline. Each candidate identifies baseline document/revision, distinct official external ID, effective date, exact raw-detail hash and parsed-content manifest. Date must be a valid nonfutureyyyyMMdd and later than the baseline for forward promotion; rollback restores a registered preserved version, not an arbitrary ID.

Candidate states: STAGED→VALIDATED→INDEX_READY→ACTIVE→RETIRED; a terminal source-fidelity rejection is REJECTED and cannot be blindly retried. Source candidates are excluded using document visibility and are never eligible for ordinary version auto-selection. Legacy metadata/sync paths must reject managed candidate/current mutation rather than re-enable it. Schema defaults preserve existing unmanaged behavior.

Preparation requires complete source/text coverage, source-linked chunks, nonempty bounded membership, quality gates, current embedding hashes and exact Qdrant readback, full lexical overlay verification, baseline preservation, product/config/source/epoch identity and durable receipts. No request-supplied boolean can certify readiness. Failed preparation remains inactive and resumable only with immutable matching identity, never blind replay of terminal quality failures.

Promotion rechecks those identities under serializable/exclusive locks, atomically switches document visibility/current pointer/lexical epoch, retains the old document and vector assets, and increments routing identity. CAS, missing assets, concurrent corpus changes or partial writes roll back entirely. Rollback uses the same preparation/readback and CAS requirements with a new revision. Never delete old points/documents as automatic cleanup.

## Delivery and actual operations

Use admin-session/CSRF/mutation-gate protections equivalent to existing recovery APIs. Default mutation gate closed. Tests must exercise real isolated MariaDB/Qdrant where necessary, not production data. Official8080 deployment only after focused/full verification and one independent review. Actual guideline refresh only after all readiness/rollback/request-boundary tests pass and actual owner/runtime/source snapshots are known. Penalty remains separately blocked until source applicability and actual quality prerequisites pass; source refresh alone does not open12→57→1003.
