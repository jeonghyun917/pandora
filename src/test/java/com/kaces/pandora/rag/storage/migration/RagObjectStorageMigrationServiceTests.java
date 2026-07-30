package com.kaces.pandora.rag.storage.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.rag.document.RagDocumentRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class RagObjectStorageMigrationServiceTests {

	@TempDir
	Path temporaryDirectory;

	@Test
	void planRejectsMissingAndHashMismatchedFilesWithoutMutatingStorageOrDatabase() throws Exception {
		Path eligible = Files.writeString(temporaryDirectory.resolve("eligible.pdf"), "eligible original");
		Path mismatched = Files.writeString(temporaryDirectory.resolve("mismatched.pdf"), "changed original");
		FakeRepository repository = new FakeRepository(List.of(
			document(7L, eligible, sha256(eligible), "eligible.pdf"),
			document(8L, temporaryDirectory.resolve("missing.pdf"), "b".repeat(64), "missing.pdf"),
			document(9L, mismatched, "c".repeat(64), "mismatched.pdf")
		));
		FakeObjectGateway gateway = new FakeObjectGateway();
		RagObjectStorageMigrationService service = new RagObjectStorageMigrationService(
			repository, gateway, new ObjectMapper()
		);

		RagObjectStorageManifest manifest = service.plan(temporaryDirectory.resolve("plan.json"));

		assertThat(manifest.entries()).extracting(RagObjectStorageManifestEntry::status)
			.containsExactly("ELIGIBLE", "MISSING", "HASH_MISMATCH");
		assertThat(gateway.uploadCalls).isZero();
		assertThat(repository.assignCalls).isZero();
		assertThat(Files.readString(temporaryDirectory.resolve("plan.json"))).contains("ELIGIBLE", "MISSING", "HASH_MISMATCH");
	}

	@Test
	void applyAssignsTheObjectKeyOnlyAfterTheUploadedObjectIsVerified() throws Exception {
		Path original = Files.writeString(temporaryDirectory.resolve("original.pdf"), "verified original");
		String hash = sha256(original);
		RagObjectStorageManifestEntry entry = new RagObjectStorageManifestEntry(
			7L, original.toString(), hash, "Original.PDF", "application/pdf", Files.size(original),
			"rag-originals/sha256/" + hash.substring(0, 2) + "/" + hash + ".pdf", "ELIGIBLE", ""
		);
		FakeRepository repository = new FakeRepository(List.of());
		FakeObjectGateway gateway = new FakeObjectGateway();
		RagObjectStorageMigrationService service = new RagObjectStorageMigrationService(
			repository, gateway, new ObjectMapper()
		);

		RagObjectStorageMigrationResult result = service.apply(new RagObjectStorageManifest("2026-07-30T00:00:00Z", "test", List.of(entry)));

		assertThat(gateway.uploadCalls).isEqualTo(1);
		assertThat(repository.assignments).containsEntry(7L, entry.objectKey());
		assertThat(result.updatedCount()).isEqualTo(1);
	}

	@Test
	void applyDoesNotAssignWhenTheUploadedObjectVerificationFails() throws Exception {
		Path original = Files.writeString(temporaryDirectory.resolve("original.pdf"), "verification failure");
		String hash = sha256(original);
		RagObjectStorageManifestEntry entry = new RagObjectStorageManifestEntry(
			7L, original.toString(), hash, "original.pdf", "application/pdf", Files.size(original),
			"rag-originals/sha256/" + hash.substring(0, 2) + "/" + hash + ".pdf", "ELIGIBLE", ""
		);
		FakeRepository repository = new FakeRepository(List.of());
		FakeObjectGateway gateway = new FakeObjectGateway();
		gateway.returnWrongMetadata = true;
		RagObjectStorageMigrationService service = new RagObjectStorageMigrationService(
			repository, gateway, new ObjectMapper()
		);

		RagObjectStorageMigrationResult result = service.apply(new RagObjectStorageManifest("2026-07-30T00:00:00Z", "test", List.of(entry)));

		assertThat(repository.assignCalls).isZero();
		assertThat(result.failedCount()).isEqualTo(1);
	}

	private RagDocumentRow document(long id, Path file, String hash, String name) {
		return new RagDocumentRow(id, "official_doc", name, "", "", "", "", "", 3, name,
			file.toString(), "", hash, "application/pdf", "", "READY");
	}

	private String sha256(Path path) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
	}

	private static class FakeRepository implements RagObjectStorageMigrationRepository {
		private final List<RagDocumentRow> documents;
		private final java.util.Map<Long, String> assignments = new java.util.LinkedHashMap<>();
		private int assignCalls;

		private FakeRepository(List<RagDocumentRow> documents) {
			this.documents = documents;
		}

		@Override
		public List<RagDocumentRow> findActiveDocuments() {
			return documents;
		}

		@Override
		public int assignObjectKeyIfHashMatches(long documentId, String fileHash, String objectKey) {
			assignCalls++;
			assignments.put(documentId, objectKey);
			return 1;
		}
	}

	private static class FakeObjectGateway implements RagObjectStorageObjectGateway {
		private final java.util.Map<String, RagObjectStorageObjectMetadata> objects = new java.util.HashMap<>();
		private int uploadCalls;
		private boolean returnWrongMetadata;

		@Override
		public java.util.Optional<RagObjectStorageObjectMetadata> find(String objectKey) {
			return java.util.Optional.ofNullable(objects.get(objectKey));
		}

		@Override
		public void upload(Path source, String objectKey, String contentType, String sha256) throws java.io.IOException {
			uploadCalls++;
			objects.put(objectKey, new RagObjectStorageObjectMetadata(
				returnWrongMetadata ? Files.size(source) + 1 : Files.size(source),
				returnWrongMetadata ? "0".repeat(64) : sha256
			));
		}
	}
}
