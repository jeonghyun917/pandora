# Original Document Deduplication Implementation Plan

## Phase 1: Collector regression tests

Files:

- `src/test/java/com/kaces/pandora/rag/collecting/CollectedAttachmentResolverTests.java`
- `src/test/java/com/kaces/pandora/rag/collecting/CollectedFileStoreTests.java`

Steps:

1. Add failing tests for valid existing attachment reuse.
2. Add failing tests for missing and hash-mismatched local files.
3. Add failing tests for unchanged bytes and changed-content version naming.
4. Run the focused tests and confirm the intended failures.

## Phase 2: Collector prevention implementation

Files:

- `src/main/java/com/kaces/pandora/rag/collecting/RagCollectedAttachmentRow.java`
- `src/main/java/com/kaces/pandora/rag/collecting/RagCollectionMapper.java`
- `src/main/resources/mapper/rag/RagCollectionMapper.xml`
- `src/main/java/com/kaces/pandora/rag/collecting/CollectedAttachmentResolver.java`
- `src/main/java/com/kaces/pandora/rag/collecting/CollectedFileStore.java`
- `src/main/java/com/kaces/pandora/rag/collecting/RagMinistryCollectionService.java`

Steps:

1. Add attachment lookup by article and exact URL.
2. Extract path/hash validation and deterministic file placement.
3. Skip network download for a valid existing attachment.
4. Write new bytes to a temporary file, compare SHA-256, and retain only changed
   content.
5. Remove numeric `uniqueDestination` behavior from this collection path.
6. Run focused tests and mapper XML validation.

## Phase 3: Cleanup engine tests

Files:

- `scripts/ministry-original-dedup.test.js`
- `scripts/lib/ministry-original-dedup.js`

Steps:

1. Test SHA grouping and same-article automatic scope.
2. Test deterministic canonical selection.
3. Test sidecar matching.
4. Test root-boundary and reparse-point rejection.
5. Test manifest digest and stale-plan detection.
6. Test SQL reference-update generation and fail-closed validation.

## Phase 4: Cleanup command implementation

Files:

- `scripts/ministry-original-dedup.js`
- `scripts/lib/ministry-original-dedup.js`
- `docs/operations-original-document-deduplication.md`

Steps:

1. Implement `plan` with bounded concurrent SHA-256 scanning.
2. Query DB references through the MariaDB CLI using environment/configured
   connection values without printing secrets.
3. Write JSON and CSV audit manifests.
4. Implement `apply` requiring an unchanged manifest and file hashes.
5. Update attachment/document/chunk paths transactionally.
6. Re-query for remaining references before deleting individual content and
   sidecar files.
7. Refuse apply when a ministry collection run is active.

## Phase 5: Verification

Steps:

1. Run Node cleanup tests.
2. Run focused Maven collection tests.
3. Run full `mvn test`.
4. Perform code review for path traversal, symlink/reparse behavior, transaction
   boundaries, failure recovery, and secret handling.
5. Generate a real dry-run manifest against
   `data/rag-upload/ministry_docs`.
6. Compare manifest totals with the independent duplicate estimate.
7. Apply only the reviewed manifest.
8. Verify DB references, file hashes, representative previews, DB title search,
   AI retrieval, and post-cleanup disk use.
9. Merge the verified feature branch into shared `main`.

