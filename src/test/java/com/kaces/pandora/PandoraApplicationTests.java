package com.kaces.pandora;

import static org.mockito.Mockito.verifyNoInteractions;

import com.kaces.pandora.ai.answer.LawAiSearchFailureSchemaMaintenance;
import com.kaces.pandora.app.admin.AdminOverviewService;
import com.kaces.pandora.app.PandoraApplication;
import com.kaces.pandora.app.auth.AdminAuthService;
import com.kaces.pandora.lawdata.persistence.LawApiSchemaMaintenance;
import com.kaces.pandora.lawdata.sync.LawOpenApiSyncStartupRunner;
import com.kaces.pandora.lawdata.version.LawVersionStatusService;
import com.kaces.pandora.rag.persistence.RagChunkQualitySchemaMaintenance;
import com.kaces.pandora.rag.search.RagChunkSearchIndexSchemaMaintenance;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
	classes = PandoraApplication.class,
	properties = {
		"law.version-status.startup-refresh-enabled=false",
		"spring.sql.init.mode=never",
		"spring.datasource.url=jdbc:mariadb://127.0.0.1:1/pandora_smoke_test",
		"spring.datasource.hikari.connection-timeout=250",
		"spring.datasource.hikari.validation-timeout=250"
	}
)
class PandoraApplicationTests {

	@MockitoBean
	private DataSource dataSource;

	@MockitoBean
	private AdminOverviewService adminOverviewService;

	@MockitoBean
	private AdminAuthService adminAuthService;

	@MockitoBean
	private LawAiSearchFailureSchemaMaintenance lawAiSearchFailureSchemaMaintenance;

	@MockitoBean
	private LawApiSchemaMaintenance lawApiSchemaMaintenance;

	@MockitoBean
	private LawOpenApiSyncStartupRunner lawOpenApiSyncStartupRunner;

	@MockitoBean
	private LawVersionStatusService lawVersionStatusService;

	@MockitoBean
	private RagChunkQualitySchemaMaintenance ragChunkQualitySchemaMaintenance;

	@MockitoBean
	private RagChunkSearchIndexSchemaMaintenance ragChunkSearchIndexSchemaMaintenance;

	@Test
	// 메소드 설명: contextLoads 처리 흐름을 수행합니다.
	void contextLoadsWithoutRequestingADatasourceConnection() {
		verifyNoInteractions(dataSource);
	}

}
