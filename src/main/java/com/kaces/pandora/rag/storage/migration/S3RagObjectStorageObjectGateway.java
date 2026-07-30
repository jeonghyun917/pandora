package com.kaces.pandora.rag.storage.migration;

import com.kaces.pandora.rag.storage.RagObjectStorageProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class S3RagObjectStorageObjectGateway implements RagObjectStorageObjectGateway {

	private final S3Client client;
	private final String bucket;

	public S3RagObjectStorageObjectGateway(S3Client client, RagObjectStorageProperties properties) {
		this.client = client;
		this.bucket = properties.getBucket();
	}

	@Override
	public Optional<RagObjectStorageObjectMetadata> find(String objectKey) throws IOException {
		try {
			var response = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
			String sha256 = response.metadata().getOrDefault("sha256", "");
			return Optional.of(new RagObjectStorageObjectMetadata(response.contentLength(), sha256));
		} catch (NoSuchKeyException exception) {
			return Optional.empty();
		} catch (S3Exception exception) {
			if (exception.statusCode() == 404) {
				return Optional.empty();
			}
			throw new IOException("Could not inspect object storage key", exception);
		}
	}

	@Override
	public void upload(Path source, String objectKey, String contentType, String sha256) throws IOException {
		try {
			client.putObject(PutObjectRequest.builder()
				.bucket(bucket)
				.key(objectKey)
				.contentType(contentType)
				.metadata(Map.of("sha256", sha256))
				.build(), RequestBody.fromFile(source));
		} catch (S3Exception exception) {
			throw new IOException("Could not upload original document object", exception);
		}
	}
}
