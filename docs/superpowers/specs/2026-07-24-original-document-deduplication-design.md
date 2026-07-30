# Original Document Deduplication Design

## Goal

Prevent repeated ministry-document collection from writing identical attachment
bytes under incremented names, and reclaim disk space by consolidating existing
exact duplicates without breaking database references, previews, or search.

The immediate cleanup target is:

`data/rag-upload/ministry_docs`

Law/admin-rule assets and documents outside this root are not modified by this
cleanup.

## Confirmed Root Cause

`RagMinistryCollectionService` upserts an article, but processes every returned
RSS item on every run. For every attachment it selects a new path through
`uniqueDestination`, downloads the file, writes it, and only then checks the
SHA-256 through the import flow. Database-level document hash deduplication
therefore does not prevent duplicate original files on disk.

The observed sample directory contains 186 byte-identical HWPX files. All 186
files have the same SHA-256, and the files alone occupy about 710 MB.

## Collection Prevention

### Existing attachment reuse

The collector first resolves an attachment by `(article_id, attachment_url)`.
When the row has a SHA-256 and a local path, the local file must exist under the
configured collection root and its SHA-256 must match the stored value.

When all conditions match, the collector:

- does not download the attachment;
- does not create another sidecar;
- reuses the existing attachment and document relationship;
- records the item as skipped/reused rather than downloaded.

If the file is missing or its hash does not match, the collector downloads a
replacement to a temporary file and follows the comparison flow below.

### New or refreshed attachment

Attachment bytes are downloaded to a temporary file in the article directory.
The collector calculates SHA-256 before selecting the final path.

- If the hash equals the existing attachment hash, the temporary file is
  deleted and the existing original is retained.
- If another file in the same article directory has the same hash, that file is
  reused and the temporary file is deleted.
- If the hash is new, the file is moved atomically to the intended name.
- If the intended name already belongs to different bytes, a stable hash suffix
  is added. This preserves changed-content versions without unbounded numeric
  suffixes.

A sidecar `.meta.json` is written only for a newly retained content version.

The default collection path treats an existing valid `(article_id, URL, hash,
path)` row as immutable and skips the network request. An explicit refresh mode
may revalidate the remote URL; only changed bytes create a new version.

## Cleanup Scope And Identity

The cleanup scans all content files below `ministry_docs` and computes SHA-256.
File size is only a pre-grouping optimization and is never a deletion criterion.

Automatic deletion is limited to exact-hash duplicates within the same article
directory. This scope reclaims the repeated-run duplicates while preserving
separate article provenance. Exact duplicates found across different article
directories are reported but not automatically deleted.

Canonical-file selection is deterministic:

1. a path referenced by an active `rag_documents` row;
2. a path referenced by an imported `rag_source_attachments` row;
3. the unsuffixed original filename;
4. the oldest creation/write time;
5. lexical absolute path order.

PDF/HWPX files with different bytes are not duplicates. A preferred PDF and an
HWPX source may both remain when their hashes differ.

## Database Reference Consolidation

Before deleting duplicate files, the cleanup updates every affected path to the
canonical path in one database transaction:

- `rag_source_attachments.local_path`;
- `rag_documents.file_path`;
- `rag_document_chunks.source_path`.

The cleanup does not merge `rag_documents` rows solely because source paths are
equal. Document identity remains governed by the existing unique SHA-256 rule.
Attachment/article provenance remains in the source tables.

The transaction is rolled back unless all planned old paths have been accounted
for and no active reference remains to a file scheduled for deletion.

## Plan, Manifest, And Apply

Cleanup is a two-phase maintenance command:

1. `plan` performs a read-only scan and writes JSON and CSV manifests;
2. `apply` accepts that exact manifest, verifies its digest and all current file
   hashes, updates database references, and permanently deletes approved files.

Manifests are written below:

`logs/storage-dedup/<timestamp>/`

Each record contains:

- canonical path;
- duplicate content path;
- content SHA-256;
- byte size;
- sidecar path, when present;
- database reference counts and planned updates;
- reason and scope;
- final status or error.

The manifest also stores root path, creation time, algorithm version, aggregate
counts, recoverable bytes, and a digest of the planned records.

## Sidecar Deletion

For each deleted exact duplicate content file, only the sidecar with the same
base filename is eligible for deletion. It is deleted when:

- the content duplicate was successfully consolidated;
- it is a regular file below the same article directory;
- no retained content file uses that sidecar base;
- database updates have committed.

Sidecars belonging to retained files or other articles are not removed.

## Safety Rules

- `plan` is mandatory before `apply`.
- All resolved paths must stay below the configured `ministry_docs` root.
- Symbolic links and reparse points are rejected.
- Hash/read failures exclude the entire duplicate group from deletion.
- A changed file, missing canonical file, changed manifest, or remaining DB
  reference aborts the affected group.
- The command refuses to apply while a ministry collection run is `RUNNING`.
- The command does not stop or restart the 18080 batch runner.
- Files are deleted individually from the verified manifest; no recursive delete
  is used.
- The JSON/CSV manifest remains after permanent deletion as an audit record, but
  it is not a byte-level backup. Permanent deletion is intentional.

## Verification

### Automated

- collector tests for existing valid attachment reuse;
- missing/corrupt local-file recovery;
- unchanged refresh result;
- changed-content version retention;
- deterministic canonical selection;
- dry-run manifest stability;
- path-boundary and reparse-point rejection;
- stale-manifest refusal;
- database-reference update and rollback behavior;
- exact duplicate and sidecar deletion tests on temporary directories.

### Repository

- focused tests for collection and cleanup;
- full `mvn test`;
- script/tool tests, when a script wrapper is added.

### Live cleanup

Before reporting completion:

- compare planned and actually reclaimed bytes;
- verify no active DB path points to a missing file;
- verify every retained document hash matches its canonical file;
- open representative PDF and HWPX-derived previews;
- run representative DB title search and AI retrieval checks;
- rescan the target tree and report remaining same-directory exact duplicates.

## Operational Rollout

1. Implement and test duplicate-prevention behavior.
2. Build and restart only the 8080 app-dev instance for verification.
3. Do not restart or promote code to 18080 in this task.
4. Confirm no ministry collection is running.
5. Generate and inspect the dry-run manifest.
6. Apply the manifest and permanently delete exact duplicates.
7. Run integrity, preview, search, and disk-usage verification.

