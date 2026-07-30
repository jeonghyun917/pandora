package com.kaces.pandora.rag.storage.migration;

import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.storage.RagObjectStorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

@Configuration
@ConditionalOnProperty(prefix = "pandora.object-storage.migration", name = "enabled", havingValue = "true")
public class RagObjectStorageMigrationConfiguration {

	@Bean
	public RagObjectStorageMigrationRepository ragObjectStorageMigrationRepository(RagDocumentMapper mapper) {
		return new MyBatisRagObjectStorageMigrationRepository(mapper);
	}

	@Bean
	public RagObjectStorageObjectGateway ragObjectStorageObjectGateway(
		S3Client client,
		RagObjectStorageProperties properties
	) {
		return new S3RagObjectStorageObjectGateway(client, properties);
	}

	@Bean
	public RagObjectStorageMigrationService ragObjectStorageMigrationService(
		RagObjectStorageMigrationRepository repository,
		RagObjectStorageObjectGateway gateway,
		ObjectMapper objectMapper
	) {
		return new RagObjectStorageMigrationService(repository, gateway, objectMapper);
	}
}
