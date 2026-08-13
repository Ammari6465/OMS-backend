package com.sunrich.oms.auth

import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.security.UserPrincipal
import com.sunrich.oms.user.User
import com.sunrich.oms.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProfileIntegrationTest {
    @Autowired lateinit var authService: AuthService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    private lateinit var testUser: User
    private lateinit var otherUser: User

    @BeforeEach
    fun setUp() {
        testUser = userRepository.save(User(
            username = "profile_user",
            email = "profile@example.com",
            passwordHash = passwordEncoder.encode("OldPassword123"),
            role = Role.STAFF,
            fullName = "Original Name",
            status = EntityStatus.ACTIVE,
            isActive = true
        ))

        otherUser = userRepository.save(User(
            username = "other_user",
            email = "other@example.com",
            passwordHash = passwordEncoder.encode("Password123"),
            role = Role.STAFF,
            fullName = "Other User",
            status = EntityStatus.ACTIVE,
            isActive = true
        ))

        authenticate(testUser)
    }

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    @Test
    fun `currentUser returns profile details of authenticated user`() {
        val current = authService.currentUser()

        assertThat(current.userId).isEqualTo(testUser.id)
        assertThat(current.username).isEqualTo("profile_user")
        assertThat(current.email).isEqualTo("profile@example.com")
        assertThat(current.fullName).isEqualTo("Original Name")
    }

    @Test
    fun `updateProfile updates full name and email cleanly`() {
        val updated = authService.updateProfile(UpdateProfileRequest(
            fullName = " New Full Name ",
            email = " newemail@example.com "
        ))

        assertThat(updated.fullName).isEqualTo("New Full Name")
        assertThat(updated.email).isEqualTo("newemail@example.com")

        val reloaded = userRepository.findById(testUser.id!!).get()
        assertThat(reloaded.fullName).isEqualTo("New Full Name")
        assertThat(reloaded.email).isEqualTo("newemail@example.com")
    }

    @Test
    fun `updateProfile fails when email is already owned by another active user`() {
        assertThatThrownBy {
            authService.updateProfile(UpdateProfileRequest(
                fullName = "Duplicate Email Tester",
                email = "other@example.com"
            ))
        }.isInstanceOf(BadRequestException::class.java)
            .hasMessage("That email address is already in use")
    }

    @Test
    fun `changePassword succeeds with correct current password`() {
        authService.changePassword(ChangePasswordRequest(
            currentPassword = "OldPassword123",
            newPassword = "NewStrongPassword123"
        ))

        val reloaded = userRepository.findById(testUser.id!!).get()
        assertThat(passwordEncoder.matches("NewStrongPassword123", reloaded.passwordHash)).isTrue()
    }

    @Test
    fun `changePassword fails when current password is incorrect`() {
        assertThatThrownBy {
            authService.changePassword(ChangePasswordRequest(
                currentPassword = "WrongCurrentPassword",
                newPassword = "NewStrongPassword123"
            ))
        }.isInstanceOf(BadRequestException::class.java)
            .hasMessage("Current password is incorrect")
    }

    private fun authenticate(user: User) {
        val principal = UserPrincipal(user.id!!, user.username, user.role, user.companyId)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            principal, null, listOf(SimpleGrantedAuthority(principal.authority))
        )
    }
}
