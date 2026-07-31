package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeArtifactIdentityTests {

	@TempDir
	Path tempDir;

	@Test
	void fingerprintsJarWithItsAbsolutePathAndModifiedTimestamp() throws IOException {
		Path jar = tempDir.resolve("pandora.jar");
		Files.writeString(jar, "pandora-artifact", StandardCharsets.UTF_8);

		RuntimeArtifactIdentity identity = RuntimeArtifactIdentity.fromPath(jar);

		assertThat(identity.kind()).isEqualTo("jar");
		assertThat(identity.sha256())
			.isEqualTo("e76590ef5e07c5eea15aeb4ac36e83729418454ad47104af528f2109290a4c9c");
		assertThat(identity.size()).isEqualTo(16L);
		assertThat(identity.path()).isEqualTo(jar.toAbsolutePath().normalize().toString());
		assertThat(identity.modifiedAt()).isNotBlank();
	}

	@Test
	void identifiesExplodedClassesWithoutPretendingTheyHaveAJarHash() {
		RuntimeArtifactIdentity identity = RuntimeArtifactIdentity.fromPath(tempDir);

		assertThat(identity.kind()).isEqualTo("classes");
		assertThat(identity.sha256()).isNull();
		assertThat(identity.size()).isNull();
		assertThat(identity.path()).isEqualTo(tempDir.toAbsolutePath().normalize().toString());
		assertThat(identity.modifiedAt()).isNotBlank();
	}

	@Test
	void failsClosedWhenArtifactCannotBeResolved() {
		RuntimeArtifactIdentity identity = RuntimeArtifactIdentity.fromPath(tempDir.resolve("missing.jar"));

		assertThat(identity.kind()).isEqualTo("unavailable");
		assertThat(identity.sha256()).isNull();
		assertThat(identity.size()).isNull();
		assertThat(identity.path()).isNull();
		assertThat(identity.modifiedAt()).isNull();
	}
}
