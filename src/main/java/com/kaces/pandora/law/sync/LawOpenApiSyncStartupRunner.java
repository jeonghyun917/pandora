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

	/**
	 * ?좏뵆由ъ??댁뀡 ?쒖옉 ???숆린???ㅽ뻾 ?щ?? ???議곌굔???ㅼ젙媛믪쑝濡?二쇱엯諛쏆뒿?덈떎.
	 */
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

	/**
	 * sync-on-start ?ㅼ젙??耳쒖쭊 寃쎌슦 ?쒖옉 吏곹썑 踰뺣졊 ?곗씠?곕? ?숆린?뷀븯怨??좏뵆由ъ??댁뀡??醫낅즺?⑸땲??
	 */
	@Override
	public void run(ApplicationArguments args) {
		if (!syncOnStart) {
			return;
		}

		// ?ㅼ젙?쇰줈 諛쏆? ??곴낵 ?섏씠吏?議곌굔??洹몃?濡??ъ슜??諛곗튂???숆린?붾? ?섑뻾?⑸땲??
		LawOpenApiSyncService.SyncResult result = lawOpenApiSyncService.syncLaws(target, query, page, display, fetchDetails);
		System.out.println("Law API sync completed: " + result);
		// ?숆린???꾩슜 ?ㅽ뻾?먯꽌???묒뾽 ?꾨즺 ???꾨줈?몄뒪瑜??뺤긽 醫낅즺?⑸땲??
		SpringApplication.exit(applicationContext, () -> 0);
	}
}
