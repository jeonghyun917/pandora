# Railway Original Document Object Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Store active RAG originals in a private Railway Storage Bucket and serve them from the Railway app without changing local file-path workflows or re-indexing RAG content.

**Architecture:** The nullable rag_documents.object_key stores a deterministic S3 key while file_path remains the local import path. A storage abstraction uses local files in development and S3 in Railway. A disabled migration runner writes a SHA-256 manifest, uploads idempotently, verifies bucket metadata, and then maps verified keys in MariaDB.

**Tech Stack:** Spring Boot 4, Java 17, MyBatis, MariaDB, AWS SDK for Java v2 BOM 2.46.8, Railway Storage Bucket S3 API, JUnit 5, AssertJ, PowerShell.

## Global Constraints

- Only active rag_documents rows with a readable local original whose SHA-256 equals file_hash are eligible.
- Keys use rag-originals/sha256/<first-two-hex>/<sha256><lowercase-extension>.
- Keep file_path, chunks, embeddings, Qdrant, and local originals unchanged.
- Exclude preview cache, sidecars, unreferenced downloads, snapshots, dumps, and batch artifacts.
- Keep credentials only in Railway Variable References or process environment variables.
- Apply changes a DB row only after the matching object is verified.
- All work stays in this isolated worktree; shared main remains untouched.

---

## File Structure

- Modify pom.xml, application.properties, application-railway.properties, schema.sql, RagDocumentRow.java, RagDocumentMapper.java, RagDocumentMapper.xml.
- Create rag/persistence/RagObjectStorageSchemaMaintenance.java.
- Create rag/storage/RagObjectStorageProperties.java, RagOriginalDocumentStore.java, LocalRagOriginalDocumentStore.java, S3RagOriginalDocumentStore.java, RagOriginalDocumentStoreConfiguration.java.
- Modify RagDocumentController.java, RagDocumentPreviewService.java, HwpxHtmlPreviewService.java.
- Create rag/storage/migration/RagObjectStorageManifestEntry.java, RagObjectStorageManifest.java, RagObjectStorageMigrationService.java, RagObjectStorageMigrationRunner.java.
- Create scripts/run-railway-object-storage-migration.ps1.
- Create focused tests under src/test/java/com/kaces/pandora/rag/storage/ and update document/persistence tests.
- Modify docs/railway-phase1-deployment.md.

### Task 1: Persist Object Storage Mappings

**Files:**
- Modify: src/main/resources/schema.sql:204-232
- Modify: src/main/java/com/kaces/pandora/rag/document/RagDocumentRow.java
- Modify: src/main/java/com/kaces/pandora/rag/persistence/RagDocumentMapper.java
- Modify: src/main/resources/mapper/law/RagDocumentMapper.xml:30-100,220-270
- Create: src/main/java/com/kaces/pandora/rag/persistence/RagObjectStorageSchemaMaintenance.java
- Test: src/test/java/com/kaces/pandora/rag/persistence/RagDocumentMapperXmlTests.java
- Test: src/test/java/com/kaces/pandora/rag/persistence/RagObjectStorageSchemaMaintenanceTests.java

**Interfaces:**

~~~
List<RagDocumentRow> findActiveDocumentsForObjectStorage();
int assignObjectKeyIfHashMatches(long documentId, String fileHash, String objectKey);
public record RagDocumentRow(..., String filePath, String fileHash, String objectKey, String mimeType, ...) {}
~~~

- [ ] **Step 1: Write the failing tests**

~~~
@Test
void objectStorageStatementsReadActiveRowsAndAssignOnlyMatchingHashes() throws Exception {
    Configuration configuration = parseMapper();
    String listSql = configuration.getMappedStatement(
        "com.kaces.pandora.rag.persistence.RagDocumentMapper.findActiveDocumentsForObjectStorage"
    ).getBoundSql(Map.of()).getSql().replaceAll("\\s+", " ");
    String updateSql = configuration.getMappedStatement(
        "com.kaces.pandora.rag.persistence.RagDocumentMapper.assignObjectKeyIfHashMatches"
    ).getBoundSql(Map.of("documentId", 7L, "fileHash", "a".repeat(64), "objectKey", "rag-originals/sha256/aa/a.pdf"))
        .getSql().replaceAll("\\s+", " ");

    assertThat(listSql).contains("object_key AS objectKey", "use_yn = 'Y'");
    assertThat(updateSql).contains("SET object_key = ?", "document_id = ?", "file_hash = ?", "use_yn = 'Y'");
}
~~~

Add a JdbcTemplate test with a table lacking object_key; assert that maintenance adds object_key VARCHAR(1024) NULL once and a second invocation is a no-op.

- [ ] **Step 2: Run the focused tests to verify they fail**

Run: .\mvnw.cmd -Dtest=RagDocumentMapperXmlTests,RagObjectStorageSchemaMaintenanceTests test

Expected: FAIL because the mapper statements and maintenance class do not exist.

- [ ] **Step 3: Write the minimal implementation**

Add object_key VARCHAR(1024) NULL after file_path. Add object_key AS objectKey to every RagDocumentRow query. The active-row query orders by document_id; the update requires document_id, file_hash, and use_yn = Y. The maintenance runner checks information_schema.COLUMNS and never backfills, removes, or overwrites a value.

- [ ] **Step 4: Run focused tests to verify they pass**

Run: .\mvnw.cmd -Dtest=RagDocumentMapperXmlTests,RagObjectStorageSchemaMaintenanceTests test

Expected: PASS.

- [ ] **Step 5: Commit**

~~~
git add src/main/resources/schema.sql src/main/java/com/kaces/pandora/rag/document/RagDocumentRow.java src/main/java/com/kaces/pandora/rag/persistence/RagDocumentMapper.java src/main/resources/mapper/law/RagDocumentMapper.xml src/main/java/com/kaces/pandora/rag/persistence/RagObjectStorageSchemaMaintenance.java src/test/java/com/kaces/pandora/rag/persistence
git commit -m "feat: persist RAG object storage keys"
~~~

### Task 2: Add a Configured S3 Read Boundary

**Files:**
- Modify: pom.xml
- Modify: src/main/resources/application.properties
- Modify: src/main/resources/application-railway.properties
- Create: src/main/java/com/kaces/pandora/rag/storage/RagObjectStorageProperties.java
- Create: src/main/java/com/kaces/pandora/rag/storage/RagOriginalDocumentStore.java
- Create: src/main/java/com/kaces/pandora/rag/storage/LocalRagOriginalDocumentStore.java
- Create: src/main/java/com/kaces/pandora/rag/storage/S3RagOriginalDocumentStore.java
- Create: src/main/java/com/kaces/pandora/rag/storage/RagOriginalDocumentStoreConfiguration.java
- Test: src/test/java/com/kaces/pandora/rag/storage/RagObjectStoragePropertiesTests.java
- Test: src/test/java/com/kaces/pandora/rag/storage/S3RagOriginalDocumentStoreTests.java

**Interfaces:**

~~~
public interface RagOriginalDocumentStore {
    boolean exists(RagDocumentRow document);
    StoredOriginal open(RagDocumentRow document) throws IOException;
    Path materialize(RagDocumentRow document) throws IOException;
}

public record StoredOriginal(InputStream inputStream, long contentLength, String contentType)
    implements AutoCloseable {
    @Override public void close() throws IOException { inputStream.close(); }
}
~~~

- [ ] **Step 1: Write the failing tests**

~~~
@Test
void requiresEveryBucketCredentialWhenStorageIsEnabled() {
    RagObjectStorageProperties properties = new RagObjectStorageProperties();
    properties.setEnabled(true);
    assertThatThrownBy(properties::validatedEndpoint)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("endpoint");
}

@Test
void objectKeyUsesHashShardAndLowercaseExtension() {
    assertThat(S3RagOriginalDocumentStore.objectKey("ab" + "1".repeat(62), "Guide.PDF"))
        .isEqualTo("rag-originals/sha256/ab/ab" + "1".repeat(62) + ".pdf");
}
~~~

Use a package-private fake S3 gateway constructor to assert that open uses the configured bucket and materialize rejects bytes whose SHA-256 differs from fileHash.

- [ ] **Step 2: Run the focused tests to verify they fail**

Run: .\mvnw.cmd -Dtest=RagObjectStoragePropertiesTests,S3RagOriginalDocumentStoreTests test

Expected: FAIL because the properties and S3 store do not exist.

- [ ] **Step 3: Write the minimal implementation**

Import AWS SDK v2 BOM version 2.46.8 and add only software.amazon.awssdk:s3. Bind pandora.object-storage with defaults enabled=false, cache-root=/tmp/pandora-object-cache, and path-style=false. Build S3Client from endpoint, Region.of(region), static credentials, and URL style.

LocalRagOriginalDocumentStore retains the normalized local-path behavior. S3RagOriginalDocumentStore rejects blank object keys, uses HeadObject for exists, and materializes a temporary file only after verifying SHA-256. Its key function returns exactly the global key format.

- [ ] **Step 4: Run focused tests to verify they pass**

Run: .\mvnw.cmd -Dtest=RagObjectStoragePropertiesTests,S3RagOriginalDocumentStoreTests test

Expected: PASS.

- [ ] **Step 5: Commit**

~~~
git add pom.xml src/main/resources/application.properties src/main/resources/application-railway.properties src/main/java/com/kaces/pandora/rag/storage src/test/java/com/kaces/pandora/rag/storage
git commit -m "feat: add private RAG object storage reader"
~~~

### Task 3: Route Detail, Download, and Preview Through the Store

**Files:**
- Modify: src/main/java/com/kaces/pandora/rag/document/RagDocumentController.java
- Modify: src/main/java/com/kaces/pandora/rag/preview/RagDocumentPreviewService.java
- Modify: src/main/java/com/kaces/pandora/rag/preview/HwpxHtmlPreviewService.java
- Test: src/test/java/com/kaces/pandora/rag/document/RagDocumentControllerTests.java
- Test: src/test/java/com/kaces/pandora/rag/preview/RagDocumentPreviewServiceTests.java
- Test: src/test/java/com/kaces/pandora/rag/preview/HwpxHtmlPreviewServiceTests.java

**Interfaces:**
- Consumes: RagOriginalDocumentStore open, exists, and materialize.
- Produces: unchanged routes /api/rag-documents/{id}/file, /preview.pdf, /preview.html, and /preview-assets/{fileName}.

- [ ] **Step 1: Write failing controller and preview tests**

~~~
@Test
void detailExposesOriginalUrlWhenTheConfiguredStoreHasTheObject() {
    RagOriginalDocumentStore store = new FakeStore(true, pdfPath);
    LawDetailResponse response = controllerWith(store).detail(7L).getBody();
    assertThat(response.originalFileUrl()).isEqualTo("/api/rag-documents/7/file");
}

@Test
void fileStreamsStoreContentWithoutUsingDatabaseLocalPath() throws Exception {
    ResponseEntity<Resource> response = controllerWith(new FakeStore(true, pdfPath)).file(7L);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    assertThat(response.getBody().getInputStream().readAllBytes()).isEqualTo(pdfBytes);
}
~~~

Add HWPX tests proving a materialized source renders HTML and asset lookup rejects ../secret.png.

- [ ] **Step 2: Run focused tests to verify they fail**

Run: .\mvnw.cmd -Dtest=RagDocumentControllerTests,RagDocumentPreviewServiceTests,HwpxHtmlPreviewServiceTests test

Expected: FAIL because controller and preview classes still open filePath directly.

- [ ] **Step 3: Write the minimal implementation**

Inject RagOriginalDocumentStore into the controller. Replace local readability checks with store.exists. Build the original-file response from StoredOriginal.inputStream using InputStreamResource, known content length, stored/document MIME type, and existing UTF-8 inline disposition.

Preview routes call store.materialize and pass the verified path into preview services. Keep generated HWPX HTML, images, and PDFs under /tmp; preserve traversal rejection. PDF/HWPX behavior is verified; HWP and DOCX remain direct-download capable when converter support is unavailable.

- [ ] **Step 4: Run focused tests to verify they pass**

Run: .\mvnw.cmd -Dtest=RagDocumentControllerTests,RagDocumentPreviewServiceTests,HwpxHtmlPreviewServiceTests test

Expected: PASS.

- [ ] **Step 5: Commit**

~~~
git add src/main/java/com/kaces/pandora/rag/document/RagDocumentController.java src/main/java/com/kaces/pandora/rag/preview src/test/java/com/kaces/pandora/rag/document src/test/java/com/kaces/pandora/rag/preview
git commit -m "feat: serve RAG originals from object storage"
~~~

### Task 4: Build a Resumable Manifest and Upload Runner

**Files:**
- Create: src/main/java/com/kaces/pandora/rag/storage/migration/RagObjectStorageManifestEntry.java
- Create: src/main/java/com/kaces/pandora/rag/storage/migration/RagObjectStorageManifest.java
- Create: src/main/java/com/kaces/pandora/rag/storage/migration/RagObjectStorageMigrationService.java
- Create: src/main/java/com/kaces/pandora/rag/storage/migration/RagObjectStorageMigrationRunner.java
- Create: scripts/run-railway-object-storage-migration.ps1
- Test: src/test/java/com/kaces/pandora/rag/storage/migration/RagObjectStorageMigrationServiceTests.java

**Interfaces:**

~~~
public record RagObjectStorageManifestEntry(
    long documentId, String filePath, String fileHash, String fileName,
    String mimeType, long byteSize, String objectKey, String status, String reason
) {}

public record RagObjectStorageManifest(
    String createdAt, String source, List<RagObjectStorageManifestEntry> entries
) {}
~~~

- [ ] **Step 1: Write the failing migration tests**

~~~
@Test
void planRejectsMissingAndHashMismatchedFilesWithoutCallingS3OrUpdatingMariaDb() throws Exception {
    RagObjectStorageManifest manifest = service.plan(output);
    assertThat(manifest.entries()).extracting(RagObjectStorageManifestEntry::status)
        .containsExactlyInAnyOrder("ELIGIBLE", "MISSING", "HASH_MISMATCH");
    assertThat(fakeStore.uploadCalls()).isZero();
    assertThat(fakeRepository.assignCalls()).isZero();
}

@Test
void applyAssignsObjectKeyOnlyAfterHeadVerification() throws Exception {
    service.apply(manifestWithOneEligibleEntry);
    assertThat(fakeStore.uploadCalls()).isEqualTo(1);
    assertThat(fakeRepository.assignments()).containsEntry(7L, expectedKey);
}
~~~

- [ ] **Step 2: Run focused tests to verify they fail**

Run: .\mvnw.cmd -Dtest=RagObjectStorageMigrationServiceTests test

Expected: FAIL because the manifest and migration classes do not exist.

- [ ] **Step 3: Write the minimal implementation**

Plan loads active rows, requires a regular local file, recomputes SHA-256, derives the deterministic key, and writes JSON containing every eligible or rejected row. It performs no S3 or SQL mutation.

Apply reads that exact JSON, recomputes each eligible hash and size, uploads with content type and sha256 metadata, runs HeadObject, compares size and metadata, and then calls assignObjectKeyIfHashMatches. A reused object is valid only when metadata and size match. A mismatch leaves the DB row unchanged.

The ApplicationRunner exists only when pandora.object-storage.migration.enabled=true and accepts only mode=plan or mode=apply. The PowerShell wrapper requires -Mode plan|apply and -ManifestPath; it never prints credentials.

- [ ] **Step 4: Run focused tests to verify they pass**

Run: .\mvnw.cmd -Dtest=RagObjectStorageMigrationServiceTests test

Expected: PASS.

- [ ] **Step 5: Commit**

~~~
git add src/main/java/com/kaces/pandora/rag/storage/migration scripts/run-railway-object-storage-migration.ps1 src/test/java/com/kaces/pandora/rag/storage/migration
git commit -m "feat: add verified RAG object storage migration"
~~~

### Task 5: Configure Railway and Perform the Controlled Migration

**Files:**
- Modify: docs/railway-phase1-deployment.md
- Modify: docs/superpowers/plans/2026-07-30-railway-original-document-storage.md

**Interfaces:**
- Consumes: the Task 4 plan and apply runner.
- Produces: verified object_key values in Railway MariaDB and a deployed pandora-app serving uploaded originals.

- [ ] **Step 1: Document exact operational checks before the first write**

Document: create pandora-originals Bucket in the app region; inject Bucket Variable References into PANDORA_OBJECT_STORAGE values; redeploy MariaDB and verify rag_documents count; generate a manifest; compare eligible count and bytes; enable MariaDB public TCP only for migration and remove it after apply.

- [ ] **Step 2: Run local tests and build before operational work**

Run:

~~~
.\mvnw.cmd -Dtest=RagObjectStoragePropertiesTests,RagObjectStorageMigrationServiceTests,RagDocumentControllerTests test
Set-Location frontend
npm run build
~~~

Expected: all tests and the frontend build pass.

- [ ] **Step 3: Create the Bucket and inject credentials without exposing secrets**

In Railway, create Bucket pandora-originals in the same region as pandora-app. Use Bucket Variable References for PANDORA_OBJECT_STORAGE_ENDPOINT, REGION, BUCKET, ACCESS_KEY_ID, and SECRET_ACCESS_KEY; set PANDORA_OBJECT_STORAGE_ENABLED=true.

- [ ] **Step 4: Generate and review the Railway-target manifest**

Temporarily enable Railway MariaDB public TCP, set process-scoped datasource variables, then run:

~~~
.\scripts\run-railway-object-storage-migration.ps1 -Mode plan -ManifestPath runtime\object-storage\railway-plan.json
~~~

Expected: no bucket write and no DB update. The JSON reports eligible count, rejected reasons, and aggregate bytes.

- [ ] **Step 5: Apply and verify the upload**

After reviewing the manifest, run:

~~~
.\scripts\run-railway-object-storage-migration.ps1 -Mode apply -ManifestPath runtime\object-storage\railway-plan.json
~~~

Expected: every success has a matching S3 head response and rag_documents.object_key; failures remain without a key. Remove the temporary MariaDB public TCP proxy after the DB success count matches the manifest.

- [ ] **Step 6: Deploy and perform live checks**

Merge verified implementation into main, then into codex/railway-phase1-prep. Deploy pandora-app, sign in, and test representative PDF, HWPX, DOCX, and documents from every source root. Compare downloaded SHA-256 with file_hash, verify HWPX HTML/image assets, verify RAG search, and confirm a missing object returns a document-unavailable response.

- [ ] **Step 7: Commit**

~~~
git add docs/railway-phase1-deployment.md docs/superpowers/plans/2026-07-30-railway-original-document-storage.md
git commit -m "docs: document Railway object storage rollout"
~~~

## Plan Self-Review

- Spec coverage: persistent mapping is Task 1; S3 access boundary is Task 2; unchanged routes and preview materialization are Task 3; idempotent migration is Task 4; Railway setup, verification, and rollback-preserving rollout are Task 5.
- Placeholder scan: every task identifies exact files, interfaces, test commands, and expected outcomes.
- Type consistency: RagOriginalDocumentStore, StoredOriginal, RagObjectStorageManifestEntry, and mapper method names are defined before later tasks consume them.
