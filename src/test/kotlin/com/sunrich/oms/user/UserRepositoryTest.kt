package com.sunrich.oms.user

import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.config.JpaConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig::class)
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    private lateinit var userRepository: UserRepository

    @BeforeEach
    fun setUp() {
        userRepository.deleteAll()
    }

    @Test
    fun `save and find user by username case-insensitively`() {
        val user = User(
            username = "TestUser",
            email = "testuser@example.com",
            passwordHash = "hashedpassword",
            role = Role.COMPANY_ADMIN,
            fullName = "Test User",
            status = EntityStatus.ACTIVE,
            isActive = true
        )
        userRepository.save(user)

        val found = userRepository.findByUsernameIgnoreCaseAndIsDeletedFalse("testuser")
        assertTrue(found.isPresent)
        assertEquals("TestUser", found.get().username)
        assertEquals(Role.COMPANY_ADMIN, found.get().role)
    }

    @Test
    fun `soft deleted user is ignored by search queries`() {
        val user = User(
            username = "deleteduser",
            email = "deleted@example.com",
            passwordHash = "hashedpassword",
            role = Role.STAFF,
            status = EntityStatus.INACTIVE,
            isActive = false
        ).apply {
            isDeleted = true
        }
        userRepository.save(user)

        val found = userRepository.findByUsernameIgnoreCaseAndIsDeletedFalse("deleteduser")
        assertFalse(found.isPresent)
        assertFalse(userRepository.existsByUsernameIgnoreCaseAndIsDeletedFalse("deleteduser"))
    }

    @Test
    fun `existsByEmailIgnoreCaseAndIsDeletedFalse checks active email existence`() {
        val user = User(
            username = "uniqueuser",
            email = "unique@example.com",
            passwordHash = "hashedpassword",
            role = Role.MANAGER
        )
        userRepository.save(user)

        assertTrue(userRepository.existsByEmailIgnoreCaseAndIsDeletedFalse("UNIQUE@EXAMPLE.COM"))
        assertFalse(userRepository.existsByEmailIgnoreCaseAndIsDeletedFalse("other@example.com"))
    }
}
