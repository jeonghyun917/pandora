package com.kaces.pandora.rag.storage;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RagObjectStoragePropertiesTests {

	@Test
	void requiresEveryBucketCredentialWhenStorageIsEnabled() {
		RagObjectStorageProperties properties = new RagObjectStorageProperties();
		properties.setEnabled(true);

		assertThatThrownBy(properties::validateEnabledConfiguration)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("endpoint");
	}

	@Test
	void acceptsACompletePrivateBucketConfiguration() {
		RagObjectStorageProperties properties = new RagObjectStorageProperties();
		properties.setEnabled(true);
		properties.setEndpoint("https://bucket.example.internal");
		properties.setRegion("us-west-2");
		properties.setBucket("pandora-originals");
		properties.setAccessKeyId("key");
		properties.setSecretAccessKey("secret");

		assertThatCode(properties::validateEnabledConfiguration).doesNotThrowAnyException();
	}
}
