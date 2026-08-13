package com.sunrich.oms.auth

import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.exception.AccountLockedException
import com.sunrich.oms.exception.UnauthorizedException
import com.sunrich.oms.user.User
import com.sunrich.oms.user.UserRepository
import com.sunrich.oms.systemdata.AuditLogRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.context.ActiveProfiles

/**
 * Full-context integration test of the authentication flow, run against
 * in-memory H2 (no MySQL required). Exercises the real AuthService +
 * LoginAttemptService (REQUIRES_NEW) + JPA persistence + JWT issuance.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {

    @Autowired lateinit var authService: AuthService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var auditLogs: AuditLogRepository

    private fun createUser(username: String, rawPassword: String, active: Boolean = true): User {
        val user = User(
            username = username,
            email = "$username@test.local",
            passwordHash = passwordEncoder.encode(rawPassword),
            role = Role.STAFF,
            fullName = "Test $username",
            status = if (active) EntityStatus.ACTIVE else EntityStatus.INACTIVE,
            isActive = active,
        )
        return userRepository.save(user)
    }

    @Test
    fun `login with correct credentials returns a signed token and the user`() {
        createUser("alice_ok", "Secret@123")

        val response = authService.login(LoginRequest("alice_ok", "Secret@123"))

        assertTrue(response.token.isNotBlank())
        assertEquals("alice_ok", response.user.username)
        assertEquals(Role.STAFF, response.user.role)
        assertEquals(86_400_000L, response.expiresInMs)
        assertTrue(auditLogs.findAll().any { it.changeType == com.sunrich.oms.common.enums.AuditAction.LOGIN && it.changedBy.username == "alice_ok" })
    }

    @Test
    fun `an unknown username is rejected`() {
        assertThrows<UnauthorizedException> { authService.login(LoginRequest("ghost", "whatever")) }
    }

    @Test
    fun `a wrong password is rejected and the failed-attempt counter is persisted`() {
        createUser("bob_fail", "Secret@123")

        assertThrows<UnauthorizedException> { authService.login(LoginRequest("bob_fail", "nope")) }

        // Regression guard: the REQUIRES_NEW attempt write must survive the thrown exception.
        val reloaded = userRepository.findByUsernameIgnoreCaseAndIsDeletedFalse("bob_fail").orElseThrow()
        assertEquals(1, reloaded.failedLoginAttempts)
        assertTrue(auditLogs.findAll().any { it.changeType == com.sunrich.oms.common.enums.AuditAction.LOGIN_FAILED && it.changedBy.username == "bob_fail" })
    }

    @Test
    fun `the account locks after five failed attempts and refuses even the correct password`() {
        createUser("carol_lock", "Secret@123")

        repeat(5) { i ->
            assertThrows<UnauthorizedException> { authService.login(LoginRequest("carol_lock", "bad-$i")) }
        }

        val locked = userRepository.findByUsernameIgnoreCaseAndIsDeletedFalse("carol_lock").orElseThrow()
        assertEquals(5, locked.failedLoginAttempts)
        assertNotNull(locked.lockedUntil)

        assertThrows<AccountLockedException> { authService.login(LoginRequest("carol_lock", "Secret@123")) }
    }

    @Test
    fun `a successful login resets the failure counter and records lastLogin`() {
        createUser("dan_reset", "Secret@123")
        assertThrows<UnauthorizedException> { authService.login(LoginRequest("dan_reset", "bad")) }

        authService.login(LoginRequest("dan_reset", "Secret@123"))

        val reloaded = userRepository.findByUsernameIgnoreCaseAndIsDeletedFalse("dan_reset").orElseThrow()
        assertEquals(0, reloaded.failedLoginAttempts)
        assertNull(reloaded.lockedUntil)
        assertNotNull(reloaded.lastLogin)
    }

    @Test
    fun `a deactivated account cannot sign in`() {
        createUser("erin_inactive", "Secret@123", active = false)

        assertThrows<UnauthorizedException> { authService.login(LoginRequest("erin_inactive", "Secret@123")) }
    }

    @Test
    fun `deactivating an account invalidates an already issued token`() {
        val user = createUser("frank_revoked", "Secret@123")
        val token = authService.login(LoginRequest("frank_revoked", "Secret@123")).token
        val current = userRepository.findById(user.id!!).orElseThrow()
        current.isActive = false
        current.status = EntityStatus.INACTIVE
        userRepository.saveAndFlush(current)

        mockMvc.get("/auth/me") { header("Authorization", "Bearer $token") }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `the bootstrap super admin is seeded and can sign in`() {
        // Other full-context auth tests intentionally clear the shared test database.
        // Re-establish the bootstrap fixture so this regression check is order-independent.
        val bootstrap = userRepository.findByUsernameIgnoreCase("superadmin").orElseGet {
            userRepository.save(User(
                username = "superadmin", email = "superadmin@oms.local",
                passwordHash = passwordEncoder.encode("Admin@12345"), role = Role.SUPER_ADMIN,
                fullName = "System Administrator", status = EntityStatus.ACTIVE, isActive = true
            ))
        }
        bootstrap.passwordHash = passwordEncoder.encode("Admin@12345")
        bootstrap.role = Role.SUPER_ADMIN
        bootstrap.status = EntityStatus.ACTIVE
        bootstrap.isActive = true
        bootstrap.failedLoginAttempts = 0
        bootstrap.lockedUntil = null
        userRepository.saveAndFlush(bootstrap)
        val response = authService.login(LoginRequest("superadmin", "Admin@12345"))

        assertEquals(Role.SUPER_ADMIN, response.user.role)
        assertTrue(response.token.isNotBlank())
    }
}
