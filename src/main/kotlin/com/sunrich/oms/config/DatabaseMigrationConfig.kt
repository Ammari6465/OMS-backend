package com.sunrich.oms.config

import org.flywaydb.core.api.MigrationState
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DatabaseMigrationConfig {
    @Bean
    fun flywayMigrationStrategy() = FlywayMigrationStrategy { flyway ->
        val failed = flyway.info().all().filter { it.state == MigrationState.FAILED }
        if (failed.isNotEmpty()) {
            val unexpected = failed.filter { it.version?.version != WORKPLACE_MIGRATION }
            check(unexpected.isEmpty()) {
                "Refusing to repair unexpected failed migrations: ${unexpected.map { it.version }}"
            }
            // MySQL DDL is not transactional. A previous release created the
            // parent tables before failing on the reserved column name
            // `accessible`; the migration itself is idempotent and safe to retry.
            flyway.repair()
        }
        flyway.migrate()
    }

    private companion object {
        const val WORKPLACE_MIGRATION = "20260817"
    }
}
