package com.kaces.pandora.rag.storage.migration;

import com.kaces.pandora.rag.storage.RagObjectStorageProperties;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "pandora.object-storage.migration", name = "enabled", havingValue = "true")
public class RagObjectStorageMigrationRunner implements ApplicationRunner {

	private final RagObjectStorageProperties properties;
	private final RagObjectStorageMigrationService service;
	private final ApplicationContext applicationContext;

	public RagObjectStorageMigrationRunner(
		RagObjectStorageProperties properties,
		RagObjectStorageMigrationService service,
		ApplicationContext applicationContext
	) {
		this.properties = properties;
		this.service = service;
		this.applicationContext = applicationContext;
	}

	@Override
	public void run(ApplicationArguments arguments) throws Exception {
		if (!properties.isEnabled()) {
			throw new IllegalStateException("Object storage must be enabled for migration");
		}
		Path manifestPath = properties.getMigration().getManifestPath();
		if (manifestPath == null || manifestPath.toString().isBlank()) {
			throw new IllegalStateException("Object storage migration manifest-path is required");
		}
		String mode = properties.getMigration().getMode().toLowerCase(Locale.ROOT);
		switch (mode) {
			case "plan" -> service.plan(manifestPath);
			case "apply" -> service.apply(service.readManifest(manifestPath));
			default -> throw new IllegalStateException("Object storage migration mode must be plan or apply");
		}
		SpringApplication.exit(applicationContext, () -> 0);
	}
}
