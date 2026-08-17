package com.sunrich.oms.config

import com.sunrich.oms.security.SecurityUtils
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Bean
import org.springframework.data.domain.AuditorAware
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import java.util.Optional
import java.time.Clock

/**
 * Enables JPA auditing so BaseEntity's created/updated timestamps and actor
 * ids are populated automatically. The auditor is the current user's id.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
class JpaConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun auditorProvider(): AuditorAware<Long> =
        AuditorAware { Optional.ofNullable(SecurityUtils.currentUserIdOrNull()) }
}
