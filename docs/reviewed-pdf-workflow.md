# Reviewed PDF source workflow

This workflow is default-off. It preserves the existing original, active chunks, and index assets until an independently verified replacement is promoted. It does not certify legal applicability or answer correctness.

## Server authority

All operations are under the existing ADMIN session and recovery CSRF/origin boundary at `/api/law-data/rag-recovery/reviewed-pdf`.

- `pandora.rag.recovery.product-sha`: exact verified product commit.
- `pandora.rag.recovery.mutations-enabled` and `pandora.rag.reviewed-pdf.mutations-enabled`: both must be true for mutations. Both default to false.
- `pandora.rag.reviewed-pdf.manifest-path`: absolute server-owned JSON input file.
- `pandora.rag.reviewed-pdf.manifest-sha256`: independently reviewed SHA-256 of that file's exact bytes.

The JSON has `draft` (the immutable ReviewedPdfDraft including source/baseline/product identities and reviewed line units) and `originalPath` (absolute server source path). Requests cannot supply source bytes, paths, trusted digests or arbitrary draft bodies. Do not derive the trusted configuration digest from a request.

## Separate operations

1. GET `/review` inspects the configured identities, not paths.
2. POST `/register` registers a previously unmanaged preserved baseline only. Existing recovery state must be inspected, not overwritten.
3. POST `/stage` retains exact reviewed bytes and immutable review data outside search.
4. GET `/preparation?draftId=...` obtains the server-regenerated preparation manifest.
5. POST `/create` accepts only `draftId` and `expectedPreparedManifest`; creates PENDING inactive members.
6. POST `/validate` accepts only `generationId` and `expectedPreparedManifest`; proves complete regenerated membership.
7. POST `/prepare` accepts `generationId`; independently verifies original, PDF, vector and lexical receipts.
8. POST `/promote` accepts `generationId`, `expectedRevision`, `expectedManifest`; atomically changes source routing after readiness checks.
9. POST `/rollback` accepts `documentId`, `expectedActiveGeneration`, `expectedRevision`, `restoreGeneration`; only the designated preserved original can be restored.

No operation automatically runs the next one. A failed readiness or stale identity is a refusal, not permission to create another attempt or relax verification. Use generic recovery status to inspect exact persisted state after an interrupted response.

Before a live switch, verify real isolated Qdrant and database behavior, complete retained content, source/version/locator ownership, preserved original parity, and rollback. The mutation flags stay false until those prerequisites and approved runtime/data scope are satisfied. Atlas 18080 and shared Qdrant are not modified by this workflow.
