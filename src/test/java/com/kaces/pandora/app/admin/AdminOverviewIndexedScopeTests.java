package com.kaces.pandora.app.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.semantic.config.LawAiProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class AdminOverviewIndexedScopeTests {

	@Test
	void lawParityCountQueriesExcludeRetiredVersionsForBothTargets() throws Exception {
		var jdbc = new CapturingJdbcTemplate();
		var service = new AdminOverviewService(jdbc, new LawAiProperties(null, null, null, null), new ObjectMapper());
		var count = AdminOverviewService.class.getDeclaredMethod("indexedLawChunks", String.class);
		count.setAccessible(true);
		for (String target : List.of("law", "admrul")) {
			assertThat(count.invoke(service, target)).isEqualTo(3L);
			assertThat(jdbc.sql.replaceAll("\\s+", " "))
				.contains("c.activation_status = 'ACTIVE'")
				.contains("d.use_yn = 'Y'")
				.contains("c.use_yn = 'Y'")
				.contains("e.status = 'INDEXED'")
				.contains("COALESCE(e.content_hash, '') = COALESCE(c.content_hash, '')");
			assertThat(jdbc.arguments).containsExactly(target);
		}
	}

	private static final class CapturingJdbcTemplate extends JdbcTemplate {
		private String sql;
		private Object[] arguments;

		@Override
		public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
			this.sql = sql;
			this.arguments = args;
			return requiredType.cast(3L);
		}
	}
}
