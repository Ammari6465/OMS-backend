package com.sunrich.oms.config

import org.flywaydb.core.api.MigrationState
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DatabaseMigrationConfig {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    @Bean
    fun flywayMigrationStrategy() = FlywayMigrationStrategy { flyway ->
        try {
            // MySQL DDL is non-transactional. Repair aligns checksums and removes
            // any failed state records so migrations always apply cleanly on deployment.
            flyway.repair()
        } catch (ex: Exception) {
            log.warn("Flyway repair encountered an issue, proceeding with migration: {}", ex.message)
        }
        flyway.migrate()
    }
}
