package com.sunrich.oms.config

import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.user.User
import com.sunrich.oms.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * Seeds test users for development and testing environments across all key application roles.
 */
@Configuration
@Profile("dev", "test")
class TestDataSeeder {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun seedTestAccounts(
        userRepository: UserRepository,
        passwordEncoder: PasswordEncoder
    ): ApplicationRunner = ApplicationRunner {
        val testUsers = listOf(
            User(
                username = "admin_sunrich",
                email = "admin@sunrichgroup.com",
                passwordHash = passwordEncoder.encode("Admin@12345"),
                role = Role.COMPANY_ADMIN,
                fullName = "Company Admin",
                status = EntityStatus.ACTIVE,
                isActive = true
            ),
            User(
                username = "hr_manager",
                email = "hr@sunrichgroup.com",
                passwordHash = passwordEncoder.encode("Hr@12345"),
                role = Role.MANAGER,
                fullName = "HR Manager",
                status = EntityStatus.ACTIVE,
                isActive = true
            ),
            User(
                username = "dept_manager",
                email = "deptmanager@sunrichgroup.com",
                passwordHash = passwordEncoder.encode("Dept@12345"),
                role = Role.MANAGER,
                fullName = "Department Manager",
                status = EntityStatus.ACTIVE,
                isActive = true
            ),
            User(
                username = "staff_john",
                email = "john@sunrichgroup.com",
                passwordHash = passwordEncoder.encode("Staff@12345"),
                role = Role.STAFF,
                fullName = "John Doe (Staff)",
                status = EntityStatus.ACTIVE,
                isActive = true
            ),
            User(
                username = "viewer_guest",
                email = "viewer@sunrichgroup.com",
                passwordHash = passwordEncoder.encode("Viewer@12345"),
                role = Role.READ_ONLY,
                fullName = "Read-Only Viewer",
                status = EntityStatus.ACTIVE,
                isActive = true
            )
        )

        var seededCount = 0
        for (u in testUsers) {
            if (!userRepository.existsByUsernameIgnoreCaseAndIsDeletedFalse(u.username)) {
                userRepository.save(u)
                seededCount++
            }
        }

        if (seededCount > 0) {
            log.info("Seeded {} test accounts for testing and development.", seededCount)
        }
    }
}
