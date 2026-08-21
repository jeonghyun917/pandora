package com.kaces.pandora.semantic.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaces.pandora.lawdata.persistence.LawApiSchemaMaintenance;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.semantic.indexing.LawSemanticIndexStatusPersistenceService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Explicitly gated; creates and drops its own UUID database, never the Pandora schema. */
@EnabledIfSystemProperty(named = "pandora.mariadb.it", matches = "true")
class LawMissingEmbeddingRepairOperationMariaDbIntegrationTests {
	private String database;
	private DataSource dataSource;
	private JdbcTemplate jdbc;
	private LawApiSchemaMaintenance maintenance;
	private SqlSessionFactory factory;

	@BeforeEach
	void createDisposableDatabase() throws Exception {
		DataSource admin = dataSource(adminUrl());
		database = "pandora_repair_it_" + UUID.randomUUID().toString().replace("-", "");
		new JdbcTemplate(admin).execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
		dataSource = dataSource(databaseUrl());
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("CREATE TABLE law_api_assets (proxy_url VARCHAR(32) NULL)");
		jdbc.execute("CREATE TABLE law_api_documents (document_id BIGINT PRIMARY KEY)");
		jdbc.execute("CREATE TABLE law_api_document_chunks (chunk_id BIGINT PRIMARY KEY, document_id BIGINT NOT NULL, use_yn CHAR(1) NOT NULL DEFAULT 'Y', sort_order INT NOT NULL DEFAULT 0)");
		maintenance = new LawApiSchemaMaintenance(jdbc);
		maintenance.run(new DefaultApplicationArguments());
		maintenance.run(new DefaultApplicationArguments());
		factory = mapperFactory(dataSource);
	}

	@Test
	void operationAndItemsRollBackAtomicallyWhenAnItemInsertFails() {
		String operationId = UUID.randomUUID().toString();
		try (SqlSession session = factory.openSession(false)) {
			LawMissingEmbeddingRepairOperationMapper mapper = session.getMapper(LawMissingEmbeddingRepairOperationMapper.class);
			assertThat(mapper.insertOperation(operation(operationId, "a".repeat(64), "{}", "b".repeat(64), 2))).isEqualTo(1);
			assertThatThrownBy(() -> mapper.insertItems(operationId, List.of(
				item(operationId, 0, 11), item(operationId, 0, 12))))
				.isInstanceOf(RuntimeException.class);
			session.rollback();
		}
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM law_missing_embedding_repair_operations WHERE operation_id=?",
			Integer.class, operationId)).isZero();
	}

	@Test
	void productionSpringGraphReusesOneCommittedWinnerAndTwoOrderedItems() throws Exception {
		SqlSessionTemplate sessions = new SqlSessionTemplate(springMapperFactory(dataSource));
		LawMissingEmbeddingRepairOperationMapper delegate = sessions.getMapper(LawMissingEmbeddingRepairOperationMapper.class);
		CyclicBarrier initialMisses = new CyclicBarrier(2);
		LawMissingEmbeddingRepairOperationMapper barrierMapper = (LawMissingEmbeddingRepairOperationMapper) Proxy.newProxyInstance(
			getClass().getClassLoader(), new Class<?>[] { LawMissingEmbeddingRepairOperationMapper.class }, (proxy, method, args) -> {
				Object result = invoke(delegate, method, args);
				if ("findOperationByIdempotencyKey".equals(method.getName()) && result == null) {
					initialMisses.await(10, TimeUnit.SECONDS);
				}
				return result;
			}
		);
		try (AnnotationConfigApplicationContext context = productionContext(barrierMapper)) {
			assertThat(AopUtils.isAopProxy(context.getBean(LawMissingEmbeddingRepairOperationPersistenceService.class))).isTrue();
			LawMissingEmbeddingRepairOperationService service = context.getBean(LawMissingEmbeddingRepairOperationService.class);
			LawMissingEmbeddingRepairOperationService.RepairRequest request = twoItemRequest();
			var executor = Executors.newFixedThreadPool(2);
			try {
				var first = executor.submit(() -> service.register(request));
				var second = executor.submit(() -> service.register(request));
				String firstId = first.get(30, TimeUnit.SECONDS).operation().request().operationId();
				String secondId = second.get(30, TimeUnit.SECONDS).operation().request().operationId();
				assertThat(firstId).isEqualTo(secondId);
			} finally {
				executor.shutdownNow();
			}
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM law_missing_embedding_repair_operations", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM law_missing_embedding_repair_items", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForList("SELECT ordinal FROM law_missing_embedding_repair_items ORDER BY ordinal", Integer.class)).containsExactly(0, 1);
	}

	@Test
	void productionSpringGraphRollsBackOperationWhenItemPersistenceFails() throws Exception {
		SqlSessionTemplate sessions = new SqlSessionTemplate(springMapperFactory(dataSource));
		LawMissingEmbeddingRepairOperationMapper delegate = sessions.getMapper(LawMissingEmbeddingRepairOperationMapper.class);
		LawMissingEmbeddingRepairOperationMapper failingItems = (LawMissingEmbeddingRepairOperationMapper) Proxy.newProxyInstance(
			getClass().getClassLoader(), new Class<?>[] { LawMissingEmbeddingRepairOperationMapper.class }, (proxy, method, args) -> {
				if ("insertItems".equals(method.getName())) {
					throw new org.springframework.dao.DataIntegrityViolationException("forced item failure");
				}
				return invoke(delegate, method, args);
			}
		);
		try (AnnotationConfigApplicationContext context = productionContext(failingItems)) {
			assertThat(AopUtils.isAopProxy(context.getBean(LawMissingEmbeddingRepairOperationPersistenceService.class))).isTrue();
			assertThatThrownBy(() -> context.getBean(LawMissingEmbeddingRepairOperationService.class).register(request()))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM law_missing_embedding_repair_operations", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM law_missing_embedding_repair_items", Integer.class)).isZero();
	}

	@Test
	void duplicateIdempotencyAndNormalizedRequestCollisionPreserveTheOriginalRow() {
		String operationId = UUID.randomUUID().toString();
		String idempotencyKey = "a".repeat(64);
		insertOperation(operation(operationId, idempotencyKey, "{\"target\":\"law\"}", "b".repeat(64), 1));

		assertThatThrownBy(() -> insertOperation(operation(
			UUID.randomUUID().toString(), idempotencyKey, "{\"target\":\"law\"}", "b".repeat(64), 1)))
			.isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> insertOperation(operation(
			UUID.randomUUID().toString(), idempotencyKey, "{\"target\":\"admrul\"}", "e".repeat(64), 1)))
			.isInstanceOf(RuntimeException.class);

		try (SqlSession session = factory.openSession(true)) {
			LawMissingEmbeddingRepairOperation.OperationRow persisted = session
				.getMapper(LawMissingEmbeddingRepairOperationMapper.class)
				.findOperationByIdempotencyKey(idempotencyKey);
			assertThat(persisted.operationId()).isEqualTo(operationId);
			assertThat(persisted.normalizedRequest()).isEqualTo("{\"target\":\"law\"}");
			assertThat(persisted.requestHash()).isEqualTo("b".repeat(64));
			assertThat(persisted.status()).isEqualTo(LawMissingEmbeddingRepairOperation.Status.READY);
			assertThat(persisted.createdAt()).isNotNull();
		}
	}

	@Test
	void sameItemSecondClaimIsZeroLeaseUsesDatabaseClockAndNonFailedFailRemainingIsZero() {
		String operationId = UUID.randomUUID().toString();
		String runtimeId = UUID.randomUUID().toString();
		insertOperationAndItems(operation(operationId, "a".repeat(64), "{}", "b".repeat(64), 2, runtimeId),
			List.of(item(operationId, 0, 11), item(operationId, 1, 12)));

		try (SqlSession session = factory.openSession(true)) {
			LawMissingEmbeddingRepairOperationMapper mapper = session.getMapper(LawMissingEmbeddingRepairOperationMapper.class);
			assertThat(mapper.findItemsByOperationId(operationId))
				.extracting(LawMissingEmbeddingRepairOperation.Item::ordinal)
				.containsExactly(0, 1);
			assertThat(mapper.markReadyItemsNotAttempted(operationId)).isZero();
			String owner = UUID.randomUUID().toString();
			assertThat(mapper.claimReadyItem(operationId, 0, owner, runtimeId, "c".repeat(64), 30)).isPositive();
			assertThat(mapper.claimReadyItem(operationId, 0, UUID.randomUUID().toString(), runtimeId, "c".repeat(64), 30)).isZero();
			Integer offsetSeconds = jdbc.queryForObject("""
				SELECT TIMESTAMPDIFF(SECOND, CURRENT_TIMESTAMP(6), lease_expires_at)
				FROM law_missing_embedding_repair_items WHERE operation_id=? AND ordinal=0
				""", Integer.class, operationId);
			assertThat(offsetSeconds).isBetween(20, 40);
			Long expiryDeltaMicros = jdbc.queryForObject("""
				SELECT TIMESTAMPDIFF(MICROSECOND, o.lease_expires_at, i.lease_expires_at)
				FROM law_missing_embedding_repair_operations o
				JOIN law_missing_embedding_repair_items i ON i.operation_id=o.operation_id
				WHERE o.operation_id=? AND i.ordinal=0
				""", Long.class, operationId);
			assertThat(expiryDeltaMicros).isZero();
		}
	}

	@Test
	void expiredLeaseCanBeReclaimedUsingTheSameDatabaseClock() {
		String operationId = UUID.randomUUID().toString();
		String runtimeId = UUID.randomUUID().toString();
		insertOperationAndItems(operation(operationId, "a".repeat(64), "{}", "b".repeat(64), 1, runtimeId),
			List.of(item(operationId, 0, 11)));
		try (SqlSession session = factory.openSession(true)) {
			LawMissingEmbeddingRepairOperationMapper mapper = session.getMapper(LawMissingEmbeddingRepairOperationMapper.class);
			assertThat(mapper.claimReadyItem(operationId, 0, UUID.randomUUID().toString(), runtimeId, "c".repeat(64), 30)).isPositive();
			jdbc.update("UPDATE law_missing_embedding_repair_operations SET lease_expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE operation_id=?", operationId);
			jdbc.update("UPDATE law_missing_embedding_repair_items SET lease_expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE operation_id=? AND ordinal=0", operationId);
			assertThat(mapper.claimExpiredItem(operationId, 0, UUID.randomUUID().toString(), runtimeId, "c".repeat(64), 30)).isPositive();
		}
	}

	@Test
	void expiredOwnerCannotRenewItsItemOrOperationLease() {
		String operationId = UUID.randomUUID().toString();
		String runtimeId = UUID.randomUUID().toString();
		String owner = UUID.randomUUID().toString();
		insertOperationAndItems(operation(operationId, "a".repeat(64), "{}", "b".repeat(64), 1, runtimeId),
			List.of(item(operationId, 0, 11)));

		try (SqlSession session = factory.openSession(true)) {
			LawMissingEmbeddingRepairOperationMapper mapper = session.getMapper(LawMissingEmbeddingRepairOperationMapper.class);
			assertThat(mapper.claimReadyItem(operationId, 0, owner, runtimeId, "c".repeat(64), 30)).isPositive();
			jdbc.update("UPDATE law_missing_embedding_repair_operations SET lease_expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE operation_id=?", operationId);
			jdbc.update("UPDATE law_missing_embedding_repair_items SET lease_expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE operation_id=? AND ordinal=0", operationId);

			assertThat(mapper.renewItemLease(operationId, 0, owner, 600)).isZero();
		}
	}

	@Test
	void productionSemanticStatusTransactionRollsBackEmbeddingWhenChunkStatusWriteFails() throws Exception {
		jdbc.execute("""
			ALTER TABLE law_api_document_chunks
			  ADD COLUMN index_status VARCHAR(30) NULL,
			  ADD COLUMN indexed_at DATETIME NULL,
			  ADD COLUMN last_error_message TEXT NULL,
			  ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
			""");
		jdbc.update("INSERT INTO law_api_documents (document_id) VALUES (1)");
		jdbc.update("INSERT INTO law_api_document_chunks (chunk_id, document_id) VALUES (101, 1)");
		jdbc.execute("""
			CREATE TABLE law_api_chunk_embeddings (
			  chunk_id BIGINT NOT NULL,
			  embedding_model VARCHAR(100) NOT NULL,
			  vector_store VARCHAR(100) NOT NULL,
			  vector_point_id VARCHAR(100) NOT NULL,
			  content_hash CHAR(64) NULL,
			  status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
			  embedded_at DATETIME NULL,
			  last_error_message TEXT NULL,
			  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
			  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
			  PRIMARY KEY (chunk_id, embedding_model, vector_store),
			  CONSTRAINT fk_test_embedding_chunk FOREIGN KEY (chunk_id)
			    REFERENCES law_api_document_chunks (chunk_id) ON DELETE CASCADE
			) ENGINE=InnoDB
			""");
		jdbc.execute("""
			CREATE TRIGGER fail_chunk_status BEFORE UPDATE ON law_api_document_chunks
			FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced chunk status failure'
			""");
		SqlSessionTemplate sessions = new SqlSessionTemplate(springLawChunkMapperFactory(dataSource));
		LawChunkMapper mapper = sessions.getMapper(LawChunkMapper.class);
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.register(ProductionTransactionConfiguration.class);
			context.registerBean(DataSource.class, () -> dataSource);
			context.registerBean("transactionManager", PlatformTransactionManager.class,
				() -> new DataSourceTransactionManager(dataSource));
			context.registerBean(LawChunkMapper.class, () -> mapper);
			context.registerBean(LawSemanticIndexStatusPersistenceService.class);
			context.refresh();

			LawSemanticIndexStatusPersistenceService persistence =
				context.getBean(LawSemanticIndexStatusPersistenceService.class);
			assertThat(AopUtils.isAopProxy(persistence)).isTrue();
			assertThatThrownBy(() -> persistence.markIndexed(
				101L, "text-embedding-3-small", "law_chunks", "a".repeat(64)))
				.isInstanceOf(RuntimeException.class);
		}

		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM law_api_chunk_embeddings WHERE chunk_id=101", Integer.class)).isZero();
		assertThat(jdbc.queryForObject(
			"SELECT index_status FROM law_api_document_chunks WHERE chunk_id=101", String.class)).isNull();
	}

	@Test
	void activeHeartbeatKeepsABlockedWorkerExclusivePastTheOriginalLease() throws Exception {
		String operationId = UUID.randomUUID().toString();
		String runtimeId = UUID.randomUUID().toString();
		insertOperationAndItems(operation(operationId, "a".repeat(64), "{}", "b".repeat(64), 1, runtimeId),
			List.of(item(operationId, 0, 11)));
		SqlSessionTemplate sessions = new SqlSessionTemplate(springMapperFactory(dataSource));
		LawMissingEmbeddingRepairOperationMapper delegate = sessions.getMapper(LawMissingEmbeddingRepairOperationMapper.class);
		CountDownLatch renewed = new CountDownLatch(1);
		LawMissingEmbeddingRepairOperationMapper observingMapper = (LawMissingEmbeddingRepairOperationMapper) Proxy.newProxyInstance(
			getClass().getClassLoader(), new Class<?>[] { LawMissingEmbeddingRepairOperationMapper.class }, (proxy, method, args) -> {
				Object result = invoke(delegate, method, args);
				if ("renewItemLease".equals(method.getName()) && result instanceof Integer count && count > 0) {
					renewed.countDown();
				}
				return result;
			}
		);
		LawMissingEmbeddingRepairService legacy = mock(LawMissingEmbeddingRepairService.class);
		when(legacy.currentRuntimeSnapshot()).thenReturn(new LawIndexIntegrityRuntimeInfo(runtimeId, "c".repeat(64)));
		CountDownLatch repairEntered = new CountDownLatch(1);
		CountDownLatch releaseRepair = new CountDownLatch(1);
		AtomicInteger repairCalls = new AtomicInteger();
		when(legacy.repairExact(any(), any())).thenAnswer(invocation -> {
			repairCalls.incrementAndGet();
			repairEntered.countDown();
			assertThat(releaseRepair.await(10, TimeUnit.SECONDS)).isTrue();
			((LawMissingEmbeddingRepairService.RepairCheckpoint) invocation.getArgument(1)).verifyOwnership();
			return new LawMissingEmbeddingRepairService.RepairResult(true, true,
				new LawIndexIntegrityRuntimeInfo(runtimeId, "e".repeat(64)), List.of(
				new LawMissingEmbeddingRepairService.RepairOutcome(11L, 1L,
					LawMissingEmbeddingRepairService.RepairState.INDEXED, "indexed")));
		});

		try (AnnotationConfigApplicationContext context = productionContext(
			observingMapper, legacy, new LawMissingEmbeddingRepairOperationService.LeasePolicy(2, Duration.ofMillis(50)))) {
			LawMissingEmbeddingRepairOperationService service = context.getBean(LawMissingEmbeddingRepairOperationService.class);
			var executor = Executors.newFixedThreadPool(2);
			try {
				var first = executor.submit(() -> service.step(UUID.fromString(operationId)));
				assertThat(repairEntered.await(5, TimeUnit.SECONDS)).isTrue();
				assertThat(renewed.await(5, TimeUnit.SECONDS)).isTrue();
				Thread.sleep(2_100L);
				assertThat(service.step(UUID.fromString(operationId))).isPresent();
				assertThat(repairCalls).hasValue(1);
				releaseRepair.countDown();
				assertThat(first.get(10, TimeUnit.SECONDS)).isPresent();
			} finally {
				releaseRepair.countDown();
				executor.shutdownNow();
			}
		}

		assertThat(repairCalls).hasValue(1);
		assertThat(jdbc.queryForObject(
			"SELECT status FROM law_missing_embedding_repair_operations WHERE operation_id=?", String.class, operationId))
			.isEqualTo("INDEXING_COMPLETE");
	}

	@Test
	void expiredRecoveryDistinguishesCleanMissingAndAmbiguousWithoutDuplicateRepair() throws Exception {
		String runtimeId = UUID.randomUUID().toString();
		String cleanId = UUID.randomUUID().toString();
		String missingId = UUID.randomUUID().toString();
		String ambiguousId = UUID.randomUUID().toString();
		insertOperationAndItems(operation(cleanId, "1".repeat(64), "clean", "a".repeat(64), 1, runtimeId),
			List.of(item(cleanId, 0, 21)));
		insertOperationAndItems(operation(missingId, "2".repeat(64), "missing", "b".repeat(64), 1, runtimeId),
			List.of(item(missingId, 0, 22)));
		insertOperationAndItems(operation(ambiguousId, "3".repeat(64), "ambiguous", "e".repeat(64), 2, runtimeId),
			List.of(item(ambiguousId, 0, 23), item(ambiguousId, 1, 24)));
		SqlSessionTemplate sessions = new SqlSessionTemplate(springMapperFactory(dataSource));
		LawMissingEmbeddingRepairOperationMapper mapper = sessions.getMapper(LawMissingEmbeddingRepairOperationMapper.class);
		for (String operationId : List.of(cleanId, missingId, ambiguousId)) {
			assertThat(mapper.claimReadyItem(operationId, 0, UUID.randomUUID().toString(), runtimeId, "c".repeat(64), 10)).isPositive();
			jdbc.update("UPDATE law_missing_embedding_repair_operations SET lease_expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE operation_id=?", operationId);
			jdbc.update("UPDATE law_missing_embedding_repair_items SET lease_expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE operation_id=? AND ordinal=0", operationId);
		}
		LawMissingEmbeddingRepairService legacy = mock(LawMissingEmbeddingRepairService.class);
		when(legacy.inspectExactCandidate(any(), any())).thenAnswer(invocation -> {
			LawMissingEmbeddingRepairService.RepairCandidate candidate = invocation.getArgument(0);
			return switch ((int) candidate.chunkId()) {
				case 21 -> new LawMissingEmbeddingRepairService.ExactInspection(
					new LawIndexIntegrityRuntimeInfo(runtimeId, "f".repeat(64)),
					LawMissingEmbeddingRepairService.RepairState.INDEXED, 1L);
				case 22 -> new LawMissingEmbeddingRepairService.ExactInspection(
					new LawIndexIntegrityRuntimeInfo(runtimeId, "c".repeat(64)),
					LawMissingEmbeddingRepairService.RepairState.READY, 1L);
				default -> new LawMissingEmbeddingRepairService.ExactInspection(
					new LawIndexIntegrityRuntimeInfo(runtimeId, "c".repeat(64)),
					LawMissingEmbeddingRepairService.RepairState.REJECTED_CLASSIFICATION_DRIFT, 1L);
			};
		});
		when(legacy.repairExact(any(), any())).thenAnswer(invocation -> {
			LawMissingEmbeddingRepairService.RepairRequest request = invocation.getArgument(0);
			((LawMissingEmbeddingRepairService.RepairCheckpoint) invocation.getArgument(1)).verifyOwnership();
			assertThat(request.candidates()).extracting(LawMissingEmbeddingRepairService.RepairCandidate::chunkId)
				.containsExactly(22L);
			return new LawMissingEmbeddingRepairService.RepairResult(true, true,
				new LawIndexIntegrityRuntimeInfo(runtimeId, "9".repeat(64)), List.of(
				new LawMissingEmbeddingRepairService.RepairOutcome(22L, 1L,
					LawMissingEmbeddingRepairService.RepairState.INDEXED, "indexed")));
		});

		try (AnnotationConfigApplicationContext context = productionContext(mapper, legacy)) {
			LawMissingEmbeddingRepairOperationService service = context.getBean(LawMissingEmbeddingRepairOperationService.class);
			assertThat(service.step(UUID.fromString(cleanId))).isPresent();
			assertThat(service.step(UUID.fromString(missingId))).isPresent();
			assertThat(service.step(UUID.fromString(ambiguousId))).isPresent();
		}

		verify(legacy, times(1)).repairExact(any(), any());
		assertThat(jdbc.queryForObject("SELECT status FROM law_missing_embedding_repair_operations WHERE operation_id=?", String.class, cleanId))
			.isEqualTo("INDEXING_COMPLETE");
		assertThat(jdbc.queryForObject("SELECT status FROM law_missing_embedding_repair_operations WHERE operation_id=?", String.class, missingId))
			.isEqualTo("INDEXING_COMPLETE");
		assertThat(jdbc.queryForObject("SELECT status FROM law_missing_embedding_repair_operations WHERE operation_id=?", String.class, ambiguousId))
			.isEqualTo("FAILED");
		assertThat(jdbc.queryForList(
			"SELECT state FROM law_missing_embedding_repair_items WHERE operation_id=? ORDER BY ordinal", String.class, ambiguousId))
			.containsExactly("FAILED", "NOT_ATTEMPTED");
	}

	@Test
	void productionStepKeepsRemoteIoOutsideTransactionsAndCompletesStateAtomically() throws Exception {
		String operationId = UUID.randomUUID().toString();
		String runtimeId = UUID.randomUUID().toString();
		insertOperationAndItems(operation(operationId, "a".repeat(64), "{}", "b".repeat(64), 1, runtimeId),
			List.of(item(operationId, 0, 11)));
		SqlSessionTemplate sessions = new SqlSessionTemplate(springMapperFactory(dataSource));
		LawMissingEmbeddingRepairOperationMapper mapper = sessions.getMapper(LawMissingEmbeddingRepairOperationMapper.class);
		LawMissingEmbeddingRepairService legacy = mock(LawMissingEmbeddingRepairService.class);
		when(legacy.currentRuntimeSnapshot()).thenReturn(new LawIndexIntegrityRuntimeInfo(runtimeId, "c".repeat(64)));
		AtomicBoolean remoteSawTransaction = new AtomicBoolean(true);
		when(legacy.repairExact(any(), any())).thenAnswer(invocation -> {
			remoteSawTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
			return new LawMissingEmbeddingRepairService.RepairResult(true, true,
				new LawIndexIntegrityRuntimeInfo(runtimeId, "e".repeat(64)), List.of(
				new LawMissingEmbeddingRepairService.RepairOutcome(11L, 1L,
					LawMissingEmbeddingRepairService.RepairState.INDEXED, "indexed")));
		});

		try (AnnotationConfigApplicationContext context = productionContext(mapper, legacy)) {
			assertThat(context.getBean(LawMissingEmbeddingRepairOperationService.class).step(UUID.fromString(operationId))).isPresent();
		}

		assertThat(remoteSawTransaction).isFalse();
		assertThat(jdbc.queryForMap("""
			SELECT status, trusted_index_revision, indexed_count, failed_count, lease_owner
			FROM law_missing_embedding_repair_operations WHERE operation_id=?
			""", operationId))
			.containsEntry("status", "INDEXING_COMPLETE")
			.containsEntry("trusted_index_revision", "e".repeat(64))
			.containsEntry("indexed_count", 1)
			.containsEntry("failed_count", 0)
			.containsEntry("lease_owner", null);
		assertThat(jdbc.queryForMap("""
			SELECT state, before_index_revision, after_index_revision, detail, lease_owner
			FROM law_missing_embedding_repair_items WHERE operation_id=? AND ordinal=0
			""", operationId))
			.containsEntry("state", "INDEXED")
			.containsEntry("before_index_revision", "c".repeat(64))
			.containsEntry("after_index_revision", "e".repeat(64))
			.containsEntry("detail", "INDEXED")
			.containsEntry("lease_owner", null);
	}

	@Test
	void productionStepFailureAtomicallyStopsRemainingItemsAndStoresOnlySanitizedReason() throws Exception {
		String operationId = UUID.randomUUID().toString();
		String runtimeId = UUID.randomUUID().toString();
		insertOperationAndItems(operation(operationId, "a".repeat(64), "{}", "b".repeat(64), 2, runtimeId),
			List.of(item(operationId, 0, 11), item(operationId, 1, 12)));
		SqlSessionTemplate sessions = new SqlSessionTemplate(springMapperFactory(dataSource));
		LawMissingEmbeddingRepairOperationMapper mapper = sessions.getMapper(LawMissingEmbeddingRepairOperationMapper.class);
		LawMissingEmbeddingRepairService legacy = mock(LawMissingEmbeddingRepairService.class);
		when(legacy.currentRuntimeSnapshot()).thenReturn(new LawIndexIntegrityRuntimeInfo(runtimeId, "c".repeat(64)));
		when(legacy.repairExact(any(), any())).thenThrow(new IllegalStateException("secret credential at private host"));

		try (AnnotationConfigApplicationContext context = productionContext(mapper, legacy)) {
			assertThat(context.getBean(LawMissingEmbeddingRepairOperationService.class).step(UUID.fromString(operationId))).isPresent();
		}

		assertThat(jdbc.queryForMap("""
			SELECT status, indexed_count, failed_count, last_error FROM law_missing_embedding_repair_operations WHERE operation_id=?
			""", operationId))
			.containsEntry("status", "FAILED")
			.containsEntry("indexed_count", 0)
			.containsEntry("failed_count", 1)
			.containsEntry("last_error", "EXACT_INDEX_FAILED");
		assertThat(jdbc.queryForList("""
			SELECT state FROM law_missing_embedding_repair_items WHERE operation_id=? ORDER BY ordinal
			""", String.class, operationId)).containsExactly("FAILED", "NOT_ATTEMPTED");
	}

	@Test
	void maintenanceRebuildsMissingAndWrongCompositeItemPrimaryKey() {
		jdbc.execute("ALTER TABLE law_missing_embedding_repair_items DROP PRIMARY KEY");
		maintenance.run(new DefaultApplicationArguments());
		assertThat(primaryKeyColumns()).containsExactly("operation_id", "ordinal");

		jdbc.execute("ALTER TABLE law_missing_embedding_repair_items DROP PRIMARY KEY, ADD PRIMARY KEY (operation_id, chunk_id)");
		maintenance.run(new DefaultApplicationArguments());
		assertThat(primaryKeyColumns()).containsExactly("operation_id", "ordinal");
	}

	@Test
	void maintenanceReplacesWrongSameNamedForeignKeyAndPreservesFullDefinition() {
		jdbc.execute("CREATE TABLE wrong_repair_parent (operation_id CHAR(36) NOT NULL PRIMARY KEY)");
		jdbc.execute("ALTER TABLE law_missing_embedding_repair_items DROP FOREIGN KEY fk_law_missing_embedding_repair_item_operation");
		jdbc.execute("""
			ALTER TABLE law_missing_embedding_repair_items
			ADD CONSTRAINT fk_law_missing_embedding_repair_item_operation
			FOREIGN KEY (operation_id) REFERENCES wrong_repair_parent(operation_id) ON DELETE RESTRICT
			""");
		maintenance.run(new DefaultApplicationArguments());

		Map<String, Object> foreignKey = jdbc.queryForMap("""
			SELECT k.COLUMN_NAME, k.REFERENCED_TABLE_NAME, k.REFERENCED_COLUMN_NAME, r.DELETE_RULE
			FROM information_schema.KEY_COLUMN_USAGE k
			JOIN information_schema.REFERENTIAL_CONSTRAINTS r
			  ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME
			WHERE k.CONSTRAINT_SCHEMA=DATABASE() AND k.TABLE_NAME='law_missing_embedding_repair_items'
			  AND k.CONSTRAINT_NAME='fk_law_missing_embedding_repair_item_operation'
			""");
		assertThat(foreignKey)
			.containsEntry("COLUMN_NAME", "operation_id")
			.containsEntry("REFERENCED_TABLE_NAME", "law_missing_embedding_repair_operations")
			.containsEntry("REFERENCED_COLUMN_NAME", "operation_id")
			.containsEntry("DELETE_RULE", "CASCADE");
	}

	@Test
	void maintenanceRejectsUnsignedIntegerType() {
		jdbc.execute("ALTER TABLE law_missing_embedding_repair_operations MODIFY candidate_count INT UNSIGNED NOT NULL");
		assertThatThrownBy(() -> maintenance.run(new DefaultApplicationArguments()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("candidate_count");
	}

	@Test
	void maintenanceRejectsWrongDefaultAndMissingOnUpdate() {
		jdbc.execute("ALTER TABLE law_missing_embedding_repair_operations MODIFY target VARCHAR(20) NOT NULL DEFAULT 'law'");
		assertThatThrownBy(() -> maintenance.run(new DefaultApplicationArguments()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("target");

		jdbc.execute("ALTER TABLE law_missing_embedding_repair_operations MODIFY target VARCHAR(20) NOT NULL");
		jdbc.execute("ALTER TABLE law_missing_embedding_repair_operations MODIFY indexed_count INT NOT NULL DEFAULT 99");
		assertThatThrownBy(() -> maintenance.run(new DefaultApplicationArguments()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("indexed_count");

		jdbc.execute("ALTER TABLE law_missing_embedding_repair_operations MODIFY indexed_count INT NOT NULL DEFAULT 0");
		jdbc.execute("ALTER TABLE law_missing_embedding_repair_operations MODIFY updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)");
		assertThatThrownBy(() -> maintenance.run(new DefaultApplicationArguments()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("updated_at");
	}

	@Test
	void maintenanceFailsClosedWhenNonEmptyTableLacksImmutableRequiredColumn() {
		String operationId = UUID.randomUUID().toString();
		insertOperation(operation(operationId, "a".repeat(64), "{}", "b".repeat(64), 1));
		jdbc.execute("ALTER TABLE law_missing_embedding_repair_operations DROP COLUMN trusted_index_revision");
		assertThatThrownBy(() -> maintenance.run(new DefaultApplicationArguments()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("trusted_index_revision");
	}

	@Test
	void maintenanceRepairsMissingRequiredColumnOnlyWhenTableIsEmpty() {
		jdbc.execute("ALTER TABLE law_missing_embedding_repair_operations DROP COLUMN trusted_index_revision");
		maintenance.run(new DefaultApplicationArguments());
		assertThat(jdbc.queryForObject("""
			SELECT COUNT(*) FROM information_schema.COLUMNS
			WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='law_missing_embedding_repair_operations'
			  AND COLUMN_NAME='trusted_index_revision'
			""", Integer.class)).isEqualTo(1);
	}

	@AfterEach
	void dropDatabase() {
		if (database != null) {
			new JdbcTemplate(dataSource(adminUrl())).execute("DROP DATABASE IF EXISTS " + database);
		}
	}

	private void insertOperation(LawMissingEmbeddingRepairOperation.OperationRow operation) {
		try (SqlSession session = factory.openSession(true)) {
			assertThat(session.getMapper(LawMissingEmbeddingRepairOperationMapper.class).insertOperation(operation)).isEqualTo(1);
		}
	}

	private void insertOperationAndItems(
		LawMissingEmbeddingRepairOperation.OperationRow operation,
		List<LawMissingEmbeddingRepairOperation.Item> items
	) {
		try (SqlSession session = factory.openSession(false)) {
			LawMissingEmbeddingRepairOperationMapper mapper = session.getMapper(LawMissingEmbeddingRepairOperationMapper.class);
			assertThat(mapper.insertOperation(operation)).isEqualTo(1);
			assertThat(mapper.insertItems(operation.operationId(), items)).isEqualTo(items.size());
			session.commit();
		}
	}

	private LawMissingEmbeddingRepairOperation.OperationRow operation(
		String operationId,
		String idempotencyKey,
		String normalizedRequest,
		String requestHash,
		int candidateCount
	) {
		return operation(operationId, idempotencyKey, normalizedRequest, requestHash, candidateCount, UUID.randomUUID().toString());
	}

	private LawMissingEmbeddingRepairOperation.OperationRow operation(
		String operationId,
		String idempotencyKey,
		String normalizedRequest,
		String requestHash,
		int candidateCount,
		String runtimeInstanceId
	) {
		Instant now = Instant.now();
		return new LawMissingEmbeddingRepairOperation.OperationRow(
			operationId, idempotencyKey, normalizedRequest, requestHash, "law", runtimeInstanceId,
			"c".repeat(64), LawMissingEmbeddingRepairOperation.Status.READY, candidateCount, 1,
			0, 0, null, null, null, now, now);
	}

	private LawMissingEmbeddingRepairOperation.Item item(String operationId, int ordinal, long chunkId) {
		Instant now = Instant.now();
		return new LawMissingEmbeddingRepairOperation.Item(
			operationId, ordinal, chunkId, 1, "d".repeat(64), LawMissingEmbeddingRepairOperation.ItemState.READY,
			null, null, null, null, null, now, now);
	}

	private List<String> primaryKeyColumns() {
		return jdbc.queryForList("""
			SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE
			WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='law_missing_embedding_repair_items'
			  AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION
			""", String.class);
	}

	private String adminUrl() {
		return "jdbc:mariadb://" + System.getProperty("pandora.mariadb.it.host", "127.0.0.1") + ":3306/mysql";
	}

	private String databaseUrl() {
		return "jdbc:mariadb://" + System.getProperty("pandora.mariadb.it.host", "127.0.0.1") + ":3306/" + database + "?serverTimezone=Asia/Seoul";
	}

	private DataSource dataSource(String url) {
		DriverManagerDataSource source = new DriverManagerDataSource();
		source.setDriverClassName("org.mariadb.jdbc.Driver");
		source.setUrl(url);
		source.setUsername(System.getProperty("pandora.mariadb.it.user", "root"));
		source.setPassword(System.getProperty("pandora.mariadb.it.password", ""));
		return source;
	}

	private SqlSessionFactory mapperFactory(DataSource source) throws Exception {
		Configuration configuration = new Configuration(new Environment("it", new JdbcTransactionFactory(), source));
		return mapperFactory(configuration);
	}

	private SqlSessionFactory springMapperFactory(DataSource source) throws Exception {
		Configuration configuration = new Configuration(new Environment("spring-it", new SpringManagedTransactionFactory(), source));
		return mapperFactory(configuration);
	}

	private SqlSessionFactory springLawChunkMapperFactory(DataSource source) throws Exception {
		Configuration configuration = new Configuration(new Environment("spring-law-index-it", new SpringManagedTransactionFactory(), source));
		try (var input = Resources.getResourceAsStream("mapper/law/LawChunkMapper.xml")) {
			new XMLMapperBuilder(input, configuration, "mapper/law/LawChunkMapper.xml", configuration.getSqlFragments()).parse();
		}
		return new SqlSessionFactoryBuilder().build(configuration);
	}

	private SqlSessionFactory mapperFactory(Configuration configuration) throws Exception {
		try (var input = Resources.getResourceAsStream("mapper/law/LawMissingEmbeddingRepairOperationMapper.xml")) {
			new XMLMapperBuilder(input, configuration, "mapper/law/LawMissingEmbeddingRepairOperationMapper.xml", configuration.getSqlFragments()).parse();
		}
		return new SqlSessionFactoryBuilder().build(configuration);
	}

	private static Object invoke(Object delegate, java.lang.reflect.Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(delegate, args);
		} catch (java.lang.reflect.InvocationTargetException exception) {
			throw exception.getCause();
		}
	}

	private LawMissingEmbeddingRepairOperationService.RepairRequest request() {
		return new LawMissingEmbeddingRepairOperationService.RepairRequest(
			"law", "00000000-0000-0000-0000-000000000001", "a".repeat(64), true,
			List.of(11L), List.of(new LawMissingEmbeddingRepairOperationService.RepairCandidate(101L, "b".repeat(64)))
		);
	}

	private LawMissingEmbeddingRepairOperationService.RepairRequest twoItemRequest() {
		return new LawMissingEmbeddingRepairOperationService.RepairRequest(
			"law", "00000000-0000-0000-0000-000000000001", "a".repeat(64), true,
			List.of(11L, 12L), List.of(
				new LawMissingEmbeddingRepairOperationService.RepairCandidate(101L, "b".repeat(64)),
				new LawMissingEmbeddingRepairOperationService.RepairCandidate(102L, "c".repeat(64))
			)
		);
	}

	private LawMissingEmbeddingRepairService readyLegacy() {
		LawMissingEmbeddingRepairService legacy = mock(LawMissingEmbeddingRepairService.class);
		when(legacy.preflight(any())).thenAnswer(invocation -> {
			LawMissingEmbeddingRepairService.RepairRequest request = invocation.getArgument(0);
			List<LawMissingEmbeddingRepairService.RepairOutcome> outcomes = new java.util.ArrayList<>();
			for (int index = 0; index < request.candidates().size(); index++) {
				outcomes.add(new LawMissingEmbeddingRepairService.RepairOutcome(
					request.candidates().get(index).chunkId(), request.expectedDocumentIds().get(index),
					LawMissingEmbeddingRepairService.RepairState.READY, "ready"
				));
			}
			return new LawMissingEmbeddingRepairService.RepairResult(false, false,
				new LawIndexIntegrityRuntimeInfo("00000000-0000-0000-0000-000000000001", "a".repeat(64)), outcomes);
		});
		return legacy;
	}

	private AnnotationConfigApplicationContext productionContext(LawMissingEmbeddingRepairOperationMapper mapper) {
		return productionContext(mapper, readyLegacy());
	}

	private AnnotationConfigApplicationContext productionContext(
		LawMissingEmbeddingRepairOperationMapper mapper, LawMissingEmbeddingRepairService legacy
	) {
		return productionContext(mapper, legacy, null);
	}

	private AnnotationConfigApplicationContext productionContext(
		LawMissingEmbeddingRepairOperationMapper mapper,
		LawMissingEmbeddingRepairService legacy,
		LawMissingEmbeddingRepairOperationService.LeasePolicy leasePolicy
	) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.register(ProductionTransactionConfiguration.class);
		context.registerBean(DataSource.class, () -> dataSource);
		context.registerBean("transactionManager", PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
		context.registerBean(LawMissingEmbeddingRepairOperationMapper.class, () -> mapper);
		context.registerBean(LawMissingEmbeddingRepairService.class, () -> legacy);
		context.registerBean(LawMissingEmbeddingRepairOperationPersistenceService.class);
		if (leasePolicy == null) {
			context.registerBean(LawMissingEmbeddingRepairOperationService.class);
		} else {
			context.registerBean(LawMissingEmbeddingRepairOperationService.class, () ->
				new LawMissingEmbeddingRepairOperationService(
					mapper, legacy, context.getBean(LawMissingEmbeddingRepairOperationPersistenceService.class),
					Clock.systemUTC(), leasePolicy
				));
		}
		context.refresh();
		return context;
	}

	@org.springframework.context.annotation.Configuration
	@EnableTransactionManagement
	static class ProductionTransactionConfiguration {
	}
}
