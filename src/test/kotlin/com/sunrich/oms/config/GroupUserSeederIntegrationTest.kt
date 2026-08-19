package com.sunrich.oms.config

import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.organization.CompanyRepository
import com.sunrich.oms.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

@SpringBootTest(properties = ["spring.datasource.url=jdbc:h2:mem:group-users;DB_CLOSE_DELAY=-1"])
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GroupUserSeederIntegrationTest {
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var encoder: PasswordEncoder

    @Test
    fun `one chairman and all company roles are seeded across every sister concern`() {
        val active = users.findAll().filter { !it.isDeleted && it.isActive }
        val chairmen = active.filter { it.role == Role.SUPER_ADMIN }

        assertThat(chairmen).hasSize(1)
        val chairman = chairmen.single()
        assertThat(chairman.username).isEqualTo(GroupUserSeeder.CHAIRMAN_USERNAME)
        assertThat(chairman.companyId).isNull()
        assertThat(encoder.matches("Chairman@2026!", chairman.passwordHash)).isTrue()

        val sisters = companies.findAll().filter { !it.isDeleted && it.parentCompany != null }
        assertThat(sisters).hasSize(GroupUserSeeder.COMPANY_ACCOUNTS.size)
        sisters.forEach { company ->
            val companyUsers = active.filter { it.companyId == company.id }
            assertThat(companyUsers.map { it.role }).containsExactlyInAnyOrderElementsOf(GroupUserSeeder.COMPANY_ROLES)
            assertThat(companyUsers).allMatch { !it.fullName.isNullOrBlank() }
        }
    }

    @Test
    fun `known dummy accounts cannot sign in or appear in the active user list`() {
        GroupUserSeeder.DUMMY_USERNAMES.forEach { username ->
            users.findByUsernameIgnoreCase(username).ifPresent { dummy ->
                assertThat(dummy.isDeleted).isTrue()
                assertThat(dummy.isActive).isFalse()
            }
        }
    }
}
