package com.kaces.pandora.semantic.integrity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class LawMissingEmbeddingRepairOperationMapperTests {

	private static final String RESOURCE = "mapper/law/LawMissingEmbeddingRepairOperationMapper.xml";
	private static final String NAMESPACE = LawMissingEmbeddingRepairOperationMapper.class.getName();

	@Test
	void mapperExposesDurableOperationAndItemContracts() {
		assertThat(List.of(LawMissingEmbeddingRepairOperationMapper.class.getMethods()).stream()
			.map(Method::getName))
			.contains(
				"insertOperation", "insertItems", "findOperationById", "findOperationByIdempotencyKey",
				"findItemsByOperationId", "findItemByOrdinal", "claimReadyItem", "claimExpiredItem",
				"renewItemLease", "completeClaimedItemAndAdvanceRevision", "failClaimedItemAndOperation",
				"markReadyItemsNotAttempted", "markOperationIndexingComplete"
			);
	}

	@Test
	void orderedItemReadsAndIdempotencyLookupExposeFullStoredRequest() throws Exception {
		Configuration configuration = parseMapper();
		String operationSql = sql(configuration, "findOperationByIdempotencyKey", Map.of("idempotencyKey", "a".repeat(64)));
		String itemSql = sql(configuration, "findItemsByOperationId", Map.of("operationId", "00000000-0000-0000-0000-000000000001"));

		assertThat(operationSql).contains("idempotency_key = ?", "normalized_request AS normalizedRequest", "request_hash AS requestHash");
		assertThat(itemSql).contains("WHERE operation_id = ?", "ORDER BY ordinal ASC");
	}

	@Test
	void readyClaimUsesOperationFenceAndCannotClaimTheSameItemTwice() throws Exception {
		String sql = sql(parseMapper(), "claimReadyItem", claimParameters());

		assertThat(sql)
			.contains("i.state = 'READY'", "o.status IN ('READY','RUNNING')", "o.lease_owner IS NULL OR o.lease_expires_at <= CURRENT_TIMESTAMP(6)", "o.runtime_instance_id = ?", "o.trusted_index_revision = ?")
			.contains("i.lease_owner = ?", "i.lease_expires_at = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6))")
			.doesNotContain("lease_expires_at = ?")
			.doesNotContain("OR i.state");
	}

	@Test
	void expiredLeaseReclaimAndRenewalUseOwnerAndUtcExpiryPredicates() throws Exception {
		Configuration configuration = parseMapper();
		String reclaim = sql(configuration, "claimExpiredItem", claimParameters());
		String renew = sql(configuration, "renewItemLease", Map.of(
			"operationId", "00000000-0000-0000-0000-000000000001", "ordinal", 0,
			"owner", "00000000-0000-0000-0000-000000000002", "leaseSeconds", 600
		));

		assertThat(reclaim).contains("i.state = 'PROCESSING'", "i.lease_expires_at <= CURRENT_TIMESTAMP(6)", "o.lease_owner IS NULL OR o.lease_expires_at <= CURRENT_TIMESTAMP(6)", "o.runtime_instance_id = ?", "o.trusted_index_revision = ?");
		assertThat(renew)
			.contains("i.state = 'PROCESSING'", "i.lease_owner = ?", "i.lease_expires_at = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6))")
			.doesNotContain("lease_expires_at = ?", "OR i.lease_owner");
	}

	@Test
	void completionAdvancesTrustedRevisionAtomicallyAndFailureIsFailClosed() throws Exception {
		Configuration configuration = parseMapper();
		Map<String, Object> completion = new java.util.HashMap<>(claimParameters());
		completion.put("afterIndexRevision", "b".repeat(64));
		completion.put("detail", "indexed");
		String complete = sql(configuration, "completeClaimedItemAndAdvanceRevision", completion);
		String fail = sql(configuration, "failClaimedItemAndOperation", Map.of(
			"operationId", "00000000-0000-0000-0000-000000000001", "ordinal", 0,
			"owner", "00000000-0000-0000-0000-000000000002", "lastError", "boom", "detail", "boom"
		));
		String remaining = sql(configuration, "markReadyItemsNotAttempted", Map.of("operationId", "00000000-0000-0000-0000-000000000001"));

		assertThat(complete).contains("i.state = 'INDEXED'", "o.trusted_index_revision = ?", "o.indexed_count = o.indexed_count + 1", "i.before_index_revision = o.trusted_index_revision", "i.after_index_revision = ?", "i.state = 'PROCESSING'", "i.lease_owner = ?", "o.lease_owner = ?", "i.lease_expires_at > CURRENT_TIMESTAMP(6)", "o.lease_expires_at > CURRENT_TIMESTAMP(6)");
		assertThat(fail).contains("i.state = 'FAILED'", "o.status = 'FAILED'", "o.failed_count = o.failed_count + 1", "i.state = 'PROCESSING'", "i.lease_owner = ?", "o.lease_owner = ?", "i.lease_expires_at > CURRENT_TIMESTAMP(6)", "o.lease_expires_at > CURRENT_TIMESTAMP(6)");
		assertThat(remaining).contains("state = 'NOT_ATTEMPTED'", "state = 'READY'", "JOIN law_missing_embedding_repair_operations", "o.status = 'FAILED'");
	}

	@Test
	void indexingCompleteTransitionRequiresNoUnfinishedItems() throws Exception {
		String sql = sql(parseMapper(), "markOperationIndexingComplete", Map.of("operationId", "00000000-0000-0000-0000-000000000001"));

		assertThat(sql).contains("status = 'RUNNING'", "status = 'INDEXING_COMPLETE'", "NOT EXISTS", "i.state <> 'INDEXED'");
	}

	private Map<String, Object> claimParameters() {
		return Map.of(
			"operationId", "00000000-0000-0000-0000-000000000001", "ordinal", 0,
			"owner", "00000000-0000-0000-0000-000000000002", "runtimeInstanceId", "00000000-0000-0000-0000-000000000003",
			"trustedIndexRevision", "a".repeat(64), "leaseSeconds", 600
		);
	}

	private String sql(Configuration configuration, String id, Map<String, Object> parameters) {
		MappedStatement statement = configuration.getMappedStatement(NAMESPACE + "." + id);
		return statement.getBoundSql(parameters).getSql().replaceAll("\\s+", " ").trim();
	}

	private Configuration parseMapper() throws Exception {
		Configuration configuration = new Configuration();
		try (InputStream input = Resources.getResourceAsStream(RESOURCE)) {
			new XMLMapperBuilder(input, configuration, RESOURCE, configuration.getSqlFragments()).parse();
		}
		return configuration;
	}
}
