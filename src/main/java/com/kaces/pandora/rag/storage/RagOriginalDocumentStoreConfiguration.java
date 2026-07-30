package com.kaces.pandora.rag.storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration
public class RagOriginalDocumentStoreConfiguration {

	@Bean
	public RagOriginalDocumentStore ragOriginalDocumentStore(RagObjectStorageProperties properties) {
		if (!properties.isEnabled()) {
			return new LocalRagOriginalDocumentStore();
		}
		properties.validateEnabledConfiguration();
		S3Client client = S3Client.builder()
			.endpointOverride(properties.validatedEndpoint())
			.region(Region.of(properties.getRegion()))
			.credentialsProvider(StaticCredentialsProvider.create(
				AwsBasicCredentials.create(properties.getAccessKeyId(), properties.getSecretAccessKey())
			))
			.serviceConfiguration(S3Configuration.builder()
				.pathStyleAccessEnabled(properties.isPathStyle())
				.build())
			.build();
		return new S3RagOriginalDocumentStore(client, properties);
	}
}
