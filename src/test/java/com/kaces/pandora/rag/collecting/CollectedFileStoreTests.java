package com.kaces.pandora.rag.collecting;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollectedFileStoreTests {
	@TempDir
	Path tempDir;

	private final CollectedFileStore store = new CollectedFileStore();

	@Test
	void reusesExistingAttachmentOnlyWhenPathAndHashStillMatch() throws Exception {
		Path file = tempDir.resolve("guide.hwpx");
		Files.writeString(file, "same-content");
		String hash = CollectedFileStore.sha256(file);
		RagCollectedAttachmentRow row = attachment(file, hash);

		assertThat(store.isReusable(row, tempDir)).isTrue();

		Files.writeString(file, "changed-content");
		assertThat(store.isReusable(row, tempDir)).isFalse();
	}

	@Test
	void refreshModeForcesDownloadEvenWhenStoredFileIsValid() throws Exception {
		Path file = tempDir.resolve("guide.hwpx");
		Files.writeString(file, "same-content");
		RagCollectedAttachmentRow row = attachment(file, CollectedFileStore.sha256(file));

		assertThat(store.shouldReuse(row, tempDir, false)).isTrue();
		assertThat(store.shouldReuse(row, tempDir, true)).isFalse();
	}

	@Test
	void rejectsExistingAttachmentOutsideArticleDirectory() throws Exception {
		Path outside = tempDir.getParent().resolve("outside-guide.hwpx");
		Files.writeString(outside, "same-content");
		try {
			assertThat(store.isReusable(attachment(outside, CollectedFileStore.sha256(outside)), tempDir)).isFalse();
		} finally {
			Files.deleteIfExists(outside);
		}
	}

	@Test
	void storesIdenticalBytesOnceAndReusesCanonicalFile() throws Exception {
		byte[] bytes = "same-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);

		CollectedFileStore.StoredFile first = store.store(tempDir, "guide.hwpx", bytes);
		CollectedFileStore.StoredFile second = store.store(tempDir, "guide.hwpx", bytes);

		assertThat(first.created()).isTrue();
		assertThat(second.created()).isFalse();
		assertThat(second.path()).isEqualTo(first.path());
		assertThat(Files.list(tempDir).filter(Files::isRegularFile).count()).isEqualTo(1);
	}

	@Test
	void preservesChangedContentUsingStableHashSuffix() throws Exception {
		CollectedFileStore.StoredFile first = store.store(tempDir, "guide.hwpx", "v1".getBytes());
		CollectedFileStore.StoredFile changed = store.store(tempDir, "guide.hwpx", "v2".getBytes());

		assertThat(first.path().getFileName().toString()).isEqualTo("guide.hwpx");
		assertThat(changed.created()).isTrue();
		assertThat(changed.path().getFileName().toString())
			.isEqualTo("guide-" + changed.sha256().substring(0, 12) + ".hwpx");
		assertThat(Files.readString(first.path())).isEqualTo("v1");
		assertThat(Files.readString(changed.path())).isEqualTo("v2");
	}

	private RagCollectedAttachmentRow attachment(Path path, String hash) {
		return new RagCollectedAttachmentRow(
			1L,
			7L,
			"https://example.test/guide.hwpx",
			"guide.hwpx",
			".hwpx",
			"application/octet-stream",
			hash,
			path.toAbsolutePath().toString(),
			11L,
			"IMPORTED"
		);
	}
}
