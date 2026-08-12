package com.sunrich.oms.config

import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.user.User
import com.sunrich.oms.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * Bootstraps the very first Super Admin so the system is usable on a clean
 * database. Runs once — only when the users table is empty. Credentials are
 * configurable via environment variables (see application.yml).
 */
@Configuration
class DataInitializer(
    @Value("\${oms.bootstrap.super-admin.username}") private val username: String,
    @Value("\${oms.bootstrap.super-admin.email}") private val email: String,
    @Value("\${oms.bootstrap.super-admin.password}") private val password: String,
    @Value("\${oms.bootstrap.super-admin.full-name}") private val fullName: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun seedSuperAdmin(userRepository: UserRepository, passwordEncoder: PasswordEncoder): ApplicationRunner =
        ApplicationRunner {
            if (userRepository.existsByUsernameIgnoreCaseAndIsDeletedFalse(username)) return@ApplicationRunner

            val admin = User(
                username = username,
                email = email,
                passwordHash = passwordEncoder.encode(password),
                role = Role.SUPER_ADMIN,
                fullName = fullName,
                status = EntityStatus.ACTIVE,
                isActive = true
            )
            userRepository.save(admin)
            log.warn(
                "Bootstrapped default SUPER_ADMIN '{}'. CHANGE THE PASSWORD IMMEDIATELY after first login.",
                username
            )
        }
}
