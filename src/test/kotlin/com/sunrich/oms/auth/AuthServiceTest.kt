package com.sunrich.oms.auth

import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.exception.UnauthorizedException
import com.sunrich.oms.security.JwtService
import com.sunrich.oms.user.User
import com.sunrich.oms.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime

@SpringBootTest
@ActiveProfiles("test")
class AuthServiceTest {

    @Autowired
    private lateinit var authService: AuthService

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    private lateinit var jwtService: JwtService

    @BeforeEach
    fun setUp() {
        userRepository.deleteAll()
    }

    @Test
    fun `successful login returns valid JWT token and user info`() {
        val rawPassword = "Password@123"
        val user = User(
            username = "auth_tester",
            email = "tester@example.com",
            passwordHash = passwordEncoder.encode(rawPassword),
            role = Role.COMPANY_ADMIN,
            fullName = "Auth Tester",
            status = EntityStatus.ACTIVE,
            isActive = true
        )
        userRepository.save(user)

        val response = authService.login(LoginRequest(username = "auth_tester", password = rawPassword))

        assertNotNull(response.token)
        assertEquals("auth_tester", response.user.username)
        assertEquals(Role.COMPANY_ADMIN, response.user.role)

        val parsed = jwtService.parse(response.token)
        assertNotNull(parsed)
        assertEquals("auth_tester", parsed?.username)
    }

    @Test
    fun `login fails with wrong password`() {
        val user = User(
            username = "wrong_pwd_user",
            email = "wrong_pwd@example.com",
            passwordHash = passwordEncoder.encode("CorrectPassword123"),
            role = Role.STAFF
        )
        userRepository.save(user)

        assertThrows(UnauthorizedException::class.java) {
            authService.login(LoginRequest(username = "wrong_pwd_user", password = "WrongPassword123"))
        }
    }

    @Test
    fun `login fails for inactive user`() {
        val user = User(
            username = "inactive_user",
            email = "inactive@example.com",
            passwordHash = passwordEncoder.encode("Password@123"),
            role = Role.STAFF,
            status = EntityStatus.INACTIVE,
            isActive = false
        )
        userRepository.save(user)

        assertThrows(UnauthorizedException::class.java) {
            authService.login(LoginRequest(username = "inactive_user", password = "Password@123"))
        }
    }

    @Test
    fun `requestPasswordReset generates reset token and resetPassword updates password`() {
        val user = User(
            username = "reset_user",
            email = "reset@example.com",
            passwordHash = passwordEncoder.encode("OldPassword123"),
            role = Role.STAFF
        )
        userRepository.save(user)

        authService.requestPasswordReset(ForgotPasswordRequest(email = "reset@example.com"))

        val updatedUser = userRepository.findByUsernameIgnoreCaseAndIsDeletedFalse("reset_user").get()
        assertNotNull(updatedUser.passwordResetToken)
        assertNotNull(updatedUser.passwordResetExpires)

        val token = updatedUser.passwordResetToken!!
        val newPassword = "NewPassword@123"

        authService.resetPassword(ResetPasswordRequest(token = token, newPassword = newPassword))

        val finalUser = userRepository.findByUsernameIgnoreCaseAndIsDeletedFalse("reset_user").get()
        assertNull(finalUser.passwordResetToken)
        assertTrue(passwordEncoder.matches(newPassword, finalUser.passwordHash))
    }
}
