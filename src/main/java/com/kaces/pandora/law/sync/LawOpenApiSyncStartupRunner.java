package com.kaces.pandora.law.sync;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class LawOpenApiSyncStartupRunner implements ApplicationRunner {

	private final LawOpenApiSyncService lawOpenApiSyncService;
	private final ConfigurableApplicationContext applicationContext;
	private final boolean syncOnStart;
	private final String target;
	private final String query;
	private final int page;
	private final int display;
	private final boolean fetchDetails;
	public LawOpenApiSyncStartupRunner(
		LawOpenApiSyncService lawOpenApiSyncService,
		ConfigurableApplicationContext applicationContext,
		@Value("${law-open-api.sync-on-start:false}") boolean syncOnStart,
		@Value("${law-open-api.sync-target:law}") String target,
		@Value("${law-open-api.sync-query:*}") String query,
		@Value("${law-open-api.sync-page:1}") int page,
		@Value("${law-open-api.sync-display:10}") int display,
		@Value("${law-open-api.sync-fetch-details:true}") boolean fetchDetails
	) {
		this.lawOpenApiSyncService = lawOpenApiSyncService;
		this.applicationContext = applicationContext;
		this.syncOnStart = syncOnStart;
		this.target = target;
		this.query = query;
		this.page = page;
		this.display = display;
		this.fetchDetails = fetchDetails;
	}
	@Override
	public void run(ApplicationArguments args) {
		if (!syncOnStart) {
			return;
		}
		LawOpenApiSyncService.SyncResult result = lawOpenApiSyncService.syncLaws(target, query, page, display, fetchDetails);
		System.out.println("Law API sync completed: " + result);
		SpringApplication.exit(applicationContext, () -> 0);
	}
}
