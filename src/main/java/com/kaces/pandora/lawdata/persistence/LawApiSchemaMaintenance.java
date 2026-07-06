package com.kaces.pandora.lawdata.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LawApiSchemaMaintenance implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LawApiSchemaMaintenance.class);

	private final JdbcTemplate jdbcTemplate;

	public LawApiSchemaMaintenance(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void run(ApplicationArguments args) {
		ensureAssetProxyUrlCanStoreLongUrls();
	}

	private void ensureAssetProxyUrlCanStoreLongUrls() {
		Integer count = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM information_schema.COLUMNS
			WHERE TABLE_SCHEMA = DATABASE()
			  AND TABLE_NAME = 'law_api_assets'
			  AND COLUMN_NAME = 'proxy_url'
			  AND DATA_TYPE IN ('text', 'mediumtext', 'longtext')
			""", Integer.class);
		if (count != null && count > 0) {
			return;
		}
		jdbcTemplate.execute("ALTER TABLE law_api_assets MODIFY proxy_url TEXT NULL");
		log.info("Updated law_api_assets.proxy_url to TEXT for long proxied asset URLs.");
	}
}
