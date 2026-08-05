package com.kaces.pandora.semantic.integrity;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.lawdata.persistence.LawApiSchemaMaintenance;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicitly gated; creates and drops its own UUID database, never the Pandora schema. */
@EnabledIfSystemProperty(named = "pandora.mariadb.it", matches = "true")
class LawMissingEmbeddingRepairOperationMariaDbIntegrationTests {
	private String database;

	@Test
	void executesSchemaIdempotencyRecordMappingAndLeaseCasAgainstDisposableMariaDb() throws Exception {
		DataSource admin = dataSource("jdbc:mariadb://" + System.getProperty("pandora.mariadb.it.host", "127.0.0.1") + ":3306/mysql");
		database = "pandora_repair_it_" + UUID.randomUUID().toString().replace("-", "");
		JdbcTemplate adminJdbc = new JdbcTemplate(admin);
		adminJdbc.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
		DataSource dataSource = dataSource("jdbc:mariadb://" + System.getProperty("pandora.mariadb.it.host", "127.0.0.1") + ":3306/" + database + "?serverTimezone=Asia/Seoul");
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("CREATE TABLE law_api_assets (proxy_url VARCHAR(32) NULL)");
		jdbc.execute("CREATE TABLE law_api_documents (document_id BIGINT PRIMARY KEY)");
		jdbc.execute("CREATE TABLE law_api_document_chunks (chunk_id BIGINT PRIMARY KEY, document_id BIGINT NOT NULL, use_yn CHAR(1) NOT NULL DEFAULT 'Y', sort_order INT NOT NULL DEFAULT 0)");
		LawApiSchemaMaintenance maintenance = new LawApiSchemaMaintenance(jdbc);
		maintenance.run(new DefaultApplicationArguments());
		maintenance.run(new DefaultApplicationArguments());
		jdbc.execute("ALTER TABLE law_missing_embedding_repair_operations DROP COLUMN trusted_index_revision");
		maintenance.run(new DefaultApplicationArguments());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='law_missing_embedding_repair_operations' AND COLUMN_NAME='trusted_index_revision'", Integer.class)).isEqualTo(1);

		SqlSessionFactory factory = mapperFactory(dataSource);
		String operationId = UUID.randomUUID().toString();
		Instant now = Instant.now();
		try (SqlSession session = factory.openSession()) {
			LawMissingEmbeddingRepairOperationMapper mapper = session.getMapper(LawMissingEmbeddingRepairOperationMapper.class);
			LawMissingEmbeddingRepairOperation.OperationRow operation = new LawMissingEmbeddingRepairOperation.OperationRow(operationId, "a".repeat(64), "{}", "b".repeat(64), "law", UUID.randomUUID().toString(), "c".repeat(64), LawMissingEmbeddingRepairOperation.Status.READY, 2, 1, 0, 0, null, null, null, now, now);
			assertThat(mapper.insertOperation(operation)).isEqualTo(1);
			assertThat(mapper.insertItems(operationId, List.of(item(operationId, 0, 11), item(operationId, 1, 12)))).isEqualTo(2);
			session.commit();
		}
		try (SqlSession session = factory.openSession(true)) {
			LawMissingEmbeddingRepairOperationMapper mapper = session.getMapper(LawMissingEmbeddingRepairOperationMapper.class);
			assertThat(mapper.findOperationByIdempotencyKey("a".repeat(64)).normalizedRequest()).isEqualTo("{}");
			String owner = UUID.randomUUID().toString();
			assertThat(mapper.claimReadyItem(operationId, 0, owner, jdbc.queryForObject("SELECT runtime_instance_id FROM law_missing_embedding_repair_operations WHERE operation_id=?", String.class, operationId), "c".repeat(64), Instant.now().plusSeconds(30))).isPositive();
			assertThat(mapper.claimReadyItem(operationId, 1, UUID.randomUUID().toString(), jdbc.queryForObject("SELECT runtime_instance_id FROM law_missing_embedding_repair_operations WHERE operation_id=?", String.class, operationId), "c".repeat(64), Instant.now().plusSeconds(30))).isZero();
			jdbc.update("UPDATE law_missing_embedding_repair_operations SET lease_expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE operation_id=?", operationId);
			jdbc.update("UPDATE law_missing_embedding_repair_items SET lease_expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE operation_id=? AND ordinal=0", operationId);
			assertThat(mapper.claimExpiredItem(operationId, 0, UUID.randomUUID().toString(), jdbc.queryForObject("SELECT runtime_instance_id FROM law_missing_embedding_repair_operations WHERE operation_id=?", String.class, operationId), "c".repeat(64), Instant.now().plusSeconds(30))).isPositive();
		}
	}

	@AfterEach void dropDatabase() { if (database != null) new JdbcTemplate(dataSource("jdbc:mariadb://" + System.getProperty("pandora.mariadb.it.host", "127.0.0.1") + ":3306/mysql")).execute("DROP DATABASE IF EXISTS " + database); }
	private LawMissingEmbeddingRepairOperation.Item item(String operationId, int ordinal, long chunkId) { Instant now = Instant.now(); return new LawMissingEmbeddingRepairOperation.Item(operationId, ordinal, chunkId, 1, "d".repeat(64), LawMissingEmbeddingRepairOperation.ItemState.READY, null, null, null, null, null, now, now); }
	private DataSource dataSource(String url) { DriverManagerDataSource dataSource = new DriverManagerDataSource(); dataSource.setDriverClassName("org.mariadb.jdbc.Driver"); dataSource.setUrl(url); dataSource.setUsername(System.getProperty("pandora.mariadb.it.user", "root")); dataSource.setPassword(System.getProperty("pandora.mariadb.it.password", "")); return dataSource; }
	private SqlSessionFactory mapperFactory(DataSource dataSource) throws Exception { Configuration configuration = new Configuration(new Environment("it", new JdbcTransactionFactory(), dataSource)); try (var input = Resources.getResourceAsStream("mapper/law/LawMissingEmbeddingRepairOperationMapper.xml")) { new XMLMapperBuilder(input, configuration, "mapper/law/LawMissingEmbeddingRepairOperationMapper.xml", configuration.getSqlFragments()).parse(); } return new SqlSessionFactoryBuilder().build(configuration); }
}
