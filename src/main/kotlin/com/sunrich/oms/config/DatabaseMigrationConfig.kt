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
            // MySQL DDL is non-transactional. If a migration failed due to transient locks
            // or schema issues, repair the failed state so idempotent scripts can retry safely.
            flyway.repair()
        }
        flyway.migrate()
    }
}
