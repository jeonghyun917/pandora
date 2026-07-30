package com.kaces.pandora.rag.storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration
public class RagOriginalDocumentStoreConfiguration {

	@Bean
	@ConditionalOnProperty(prefix = "pandora.object-storage", name = "enabled", havingValue = "true")
	public S3Client ragObjectStorageS3Client(RagObjectStorageProperties properties) {
		properties.validateEnabledConfiguration();
		return S3Client.builder()
			.endpointOverride(properties.validatedEndpoint())
			.region(Region.of(properties.getRegion()))
			.credentialsProvider(StaticCredentialsProvider.create(
				AwsBasicCredentials.create(properties.getAccessKeyId(), properties.getSecretAccessKey())
			))
			.serviceConfiguration(S3Configuration.builder()
				.pathStyleAccessEnabled(properties.isPathStyle())
				.build())
			.build();
	}

	@Bean
	public RagOriginalDocumentStore ragOriginalDocumentStore(
		RagObjectStorageProperties properties,
		org.springframework.beans.factory.ObjectProvider<S3Client> clientProvider
	) {
		if (!properties.isEnabled()) {
			return new LocalRagOriginalDocumentStore();
		}
		return new S3RagOriginalDocumentStore(clientProvider.getObject(), properties);
	}
}
