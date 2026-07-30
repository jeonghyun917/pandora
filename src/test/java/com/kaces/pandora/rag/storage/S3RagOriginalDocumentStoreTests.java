package com.kaces.pandora.rag.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kaces.pandora.rag.document.RagDocumentRow;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

class S3RagOriginalDocumentStoreTests {

	@Test
	void objectKeyUsesHashShardAndLowercaseExtension() {
		String hash = "ab" + "1".repeat(62);

		assertThat(S3RagOriginalDocumentStore.objectKey(hash, "Guide.PDF"))
			.isEqualTo("rag-originals/sha256/ab/" + hash + ".pdf");
	}

	@Test
	void materializeUsesTheConfiguredBucketAndVerifiesTheDownloadedHash(@TempDir Path cacheRoot) throws Exception {
		byte[] bytes = "hello world".getBytes(StandardCharsets.UTF_8);
		String hash = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9";
		S3Client s3Client = mock(S3Client.class);
		when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(response(bytes));
		RagObjectStorageProperties properties = properties(cacheRoot);
		RagDocumentRow document = document(hash, "rag-originals/sha256/b9/" + hash + ".pdf");

		Path materialized = new S3RagOriginalDocumentStore(s3Client, properties).materialize(document);

		ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
		org.mockito.Mockito.verify(s3Client).getObject(request.capture());
		assertThat(request.getValue().bucket()).isEqualTo("pandora-originals");
		assertThat(request.getValue().key()).isEqualTo(document.objectKey());
		assertThat(Files.readAllBytes(materialized)).isEqualTo(bytes);
	}

	@Test
	void materializeRejectsAByteStreamWhoseHashDiffersFromTheDocument(@TempDir Path cacheRoot) {
		S3Client s3Client = mock(S3Client.class);
		when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(response("tampered".getBytes(StandardCharsets.UTF_8)));
		String expectedHash = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9";

		assertThatThrownBy(() -> new S3RagOriginalDocumentStore(s3Client, properties(cacheRoot))
			.materialize(document(expectedHash, "rag-originals/sha256/b9/" + expectedHash + ".pdf")))
			.isInstanceOf(java.io.IOException.class)
			.hasMessageContaining("hash does not match");
	}

	private RagObjectStorageProperties properties(Path cacheRoot) {
		RagObjectStorageProperties properties = new RagObjectStorageProperties();
		properties.setBucket("pandora-originals");
		properties.setCacheRoot(cacheRoot);
		return properties;
	}

	private RagDocumentRow document(String hash, String objectKey) {
		return new RagDocumentRow(
			7L,
			"official_doc",
			"Guide",
			null,
			null,
			null,
			null,
			null,
			1,
			"Guide.PDF",
			"C:/local/guide.pdf",
			objectKey,
			hash,
			"application/pdf",
			null,
			"INDEXED"
		);
	}

	private ResponseInputStream<GetObjectResponse> response(byte[] bytes) {
		GetObjectResponse metadata = GetObjectResponse.builder()
			.contentLength((long) bytes.length)
			.contentType("application/pdf")
			.build();
		return new ResponseInputStream<>(metadata, new ByteArrayInputStream(bytes));
	}
}
