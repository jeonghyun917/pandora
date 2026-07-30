# Railway Original Document Object Storage Design

## Goal

Make only the original files referenced by active `rag_documents` rows available
to the Railway production app through a Railway Storage Bucket, without changing
the existing local-development file workflow or re-indexing RAG content.

## Confirmed Scope

- Include an original only when its active `rag_documents` row has a readable
  local file and a SHA-256 `file_hash` matching that file.
- Store the original binary as the durable source of truth. Do not upload the
  current `storage/rag-preview` cache; it is derived from source files and can
  be regenerated in Railway's ephemeral `/tmp` cache.
- Exclude unreferenced collection downloads, duplicate candidates, sidecar
  `.json` files, batch artifacts, Qdrant snapshots, and local database dumps.
- Preserve `rag_documents.file_path` and all local files. The migration never
  deletes or rewrites local source paths.

## Context

`data/rag-upload` currently contains 1,511 files totaling 5,753,515,129 bytes.
Most bytes are original PDFs, HWPX, and HWP files. The application currently
opens `rag_documents.file_path` with `java.nio.file.Path`, so uploading files
alone would make Railway detail/download/preview endpoints return not found.

Railway Storage Buckets are private and S3-compatible. The app therefore owns
the document access boundary; raw bucket credentials and object URLs are never
returned to the browser.

## Chosen Design

### Persistent Identity

Add a nullable `object_key` column to `rag_documents`.

- `file_path` remains the local source/import path.
- `object_key` is set only after the source file has uploaded and the bucket
  confirms the expected object metadata.
- `file_hash` remains the canonical content identity and is already unique for
  `rag_documents`.

Object keys are deterministic:

```text
rag-originals/sha256/<first-two-hex>/<sha256><original-extension>
```

For example:

```text
rag-originals/sha256/ab/ab12...ef.pdf
```

This makes retries idempotent, prevents same-name collisions, and lets two
documents with the same bytes share one object naturally.

### Storage Access Boundary

Introduce a small `RagOriginalDocumentStore` abstraction.

- In local development, it resolves and reads `file_path` exactly as today.
- In Railway, when `object_key` is populated, it streams the S3 object for the
  original-file endpoint and materializes a verified temporary source file only
  when preview code requires a `Path`.
- A failed bucket read is surfaced as a document-unavailable response; the app
  must not silently claim that a missing original is present.

The app proxies original file downloads instead of returning raw bucket URLs.
This preserves the existing `/api/rag-documents/{id}/file` contract, keeps
bucket credentials and object naming internal, and leaves one app-level place
for future authorization and audit rules.

### Preview Behavior

PDF originals stream directly from the bucket. HWPX HTML and generated PDF
previews first materialize the verified original under a bounded `/tmp` cache,
then use the existing preview generators. Generated HTML/image/PDF previews are
ephemeral derivatives, not migration inputs.

HWP, DOCX, and other formats that currently rely on external conversion remain
downloadable as originals. Their Railway preview support is verified separately;
the migration does not claim a conversion capability that the Docker image does
not provide.

### Bucket Configuration

The Railway app receives bucket credentials through Railway Variable References
using application-specific environment variables:

```text
PANDORA_OBJECT_STORE_ENABLED=true
PANDORA_OBJECT_STORE_ENDPOINT=<bucket ENDPOINT>
PANDORA_OBJECT_STORE_REGION=<bucket REGION>
PANDORA_OBJECT_STORE_BUCKET=<bucket BUCKET>
PANDORA_OBJECT_STORE_ACCESS_KEY_ID=<bucket ACCESS_KEY_ID>
PANDORA_OBJECT_STORE_SECRET_ACCESS_KEY=<bucket SECRET_ACCESS_KEY>
```

Credentials are stored only in Railway variables and never in Git, manifests,
logs, or API responses.

## Migration Workflow

1. Create a read-only manifest from the Railway MariaDB `rag_documents` rows.
   Each row records document ID, local path, file hash, MIME type, byte size,
   deterministic object key, and eligibility/rejection reason.
2. Review the manifest counts and bytes. Any missing file, hash mismatch, or
   unsafe path is rejected and remains untouched.
3. Upload each eligible object with SHA-256 and MIME metadata. Reuse an existing
   object only when a `HeadObject` check confirms the same SHA-256 and size.
4. After each object verifies successfully, update only that document's
   `object_key` in Railway MariaDB. This is deliberately resumable: a partial
   run leaves only verified, usable mappings.
5. Deploy the app change, then verify originals and previews through the public
   Railway app. Keep local originals as the rollback source.

S3 and MariaDB cannot share one transaction. Safety comes from deterministic
keys, immutable hash verification, idempotent retries, and refusing a DB update
before the object exists and matches.

## Failure and Rollback Rules

- A manifest run never uploads or updates the database.
- An upload/hash/size mismatch skips that document and records a failure row.
- Existing objects with a different hash are never overwritten.
- The migration never changes `file_path`, chunks, embeddings, Qdrant data, or
  `rag_source_attachments.local_path`.
- Rollback consists of setting affected `object_key` values to `NULL`; local
  files remain available for re-upload. Bucket objects are retained until a
  separately approved cleanup operation.

## Verification

- Compare eligible manifest count, uploaded count, verified bucket count, and
  updated Railway DB count.
- Select representative PDF, HWPX, HWP, and a document from each source root.
- Verify the detail API exposes the original link only for a verified object.
- Download each selected original and compare SHA-256 to its database hash.
- Verify HWPX HTML preview and its image assets; document any unsupported
  converter-based preview rather than masking it as success.
- Run focused storage/controller tests, full Maven tests, frontend build when
  endpoint behavior changes, and an end-to-end Railway detail/download check.

## Non-Goals

- Re-importing, re-chunking, or re-embedding RAG documents.
- Moving local development data into Git or a Railway volume.
- Uploading all collection artifacts or generated preview cache files.
- Making the Railway Bucket public.
