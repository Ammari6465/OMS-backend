package com.sunrich.oms.systemdata

import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.security.JwtService
import com.sunrich.oms.security.UserPrincipal
import com.sunrich.oms.user.User
import com.sunrich.oms.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class SystemSettingsIntegrationTest {

    @Autowired lateinit var service: SystemDataService
    @Autowired lateinit var settingsRepository: SystemSettingRepository
    @Autowired lateinit var auditsRepository: AuditLogRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var mockMvc: MockMvc

    private lateinit var superAdmin: User
    private lateinit var staffUser: User

    @BeforeEach
    fun setUp() {
        superAdmin = userRepository.saveAndFlush(
            User(
                username = "setting-admin-${System.nanoTime()}",
                email = "setting-admin-${System.nanoTime()}@example.com",
                passwordHash = "unused",
                role = Role.SUPER_ADMIN,
                fullName = "System Admin",
                status = EntityStatus.ACTIVE,
                isActive = true
            )
        )
        staffUser = userRepository.saveAndFlush(
            User(
                username = "setting-staff-${System.nanoTime()}",
                email = "setting-staff-${System.nanoTime()}@example.com",
                passwordHash = "unused",
                role = Role.STAFF,
                fullName = "Staff Member",
                status = EntityStatus.ACTIVE,
                isActive = true
            )
        )
        authenticate(superAdmin)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `list create and update system settings with audit log tracking`() {
        val createReq = SettingRequest(
            kind = "notification-preferences",
            values = mapOf("onboarding" to true, "exits" to true, "transfers" to false, "vacancies" to true)
        )
        val created = service.createSetting(createReq)
        assertThat(created.id).isNotNull()
        assertThat(created.kind).isEqualTo("notification-preferences")
        assertThat(created.values["onboarding"]).isTrue()
        assertThat(created.values["vacancies"]).isTrue()

        // Verify audit log creation
        val auditList = service.listAudit(0, 10, "timestamp", "desc", null, null, "Settings", null, null, null, null, null, null)
        assertThat(auditList.content.any { it.entityId == created.id && it.action.name == "CREATE" }).isTrue()

        // Update setting
        val updateReq = SettingRequest(
            kind = "notification-preferences",
            values = mapOf("onboarding" to true, "exits" to false, "transfers" to true, "vacancies" to true)
        )
        val updated = service.updateSetting(created.id, updateReq)
        assertThat(updated.values["exits"]).isFalse()
        assertThat(updated.values["transfers"]).isTrue()

        // List settings
        val allSettings = service.listSettings(false)
        assertThat(allSettings.map { it.id }).contains(updated.id)
    }

    @Test
    fun `create setting with unsupported kind throws BadRequestException`() {
        val invalidReq = SettingRequest(
            kind = "invalid-custom-kind",
            values = mapOf("custom" to true)
        )
        assertThatThrownBy { service.createSetting(invalidReq) }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("Unsupported setting kind")
    }

    @Test
    fun `create duplicate setting kind throws BadRequestException`() {
        val req1 = SettingRequest(
            kind = "password-reset-roles",
            values = mapOf("SUPER_ADMIN" to true, "COMPANY_ADMIN" to true, "MANAGER" to false)
        )
        service.createSetting(req1)

        val req2 = SettingRequest(
            kind = "password-reset-roles",
            values = mapOf("SUPER_ADMIN" to true)
        )
        assertThatThrownBy { service.createSetting(req2) }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("already exists")
    }

    @Test
    fun `non-admin users are blocked from modifying settings via REST`() {
        val token = jwtService.generateToken(staffUser.id!!, staffUser.username, staffUser.role, staffUser.companyId)
        SecurityContextHolder.clearContext()

        mockMvc.post("/settings") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = "{\"kind\":\"notification-preferences\",\"values\":{\"onboarding\":true}}"
        }.andExpect {
            status { isForbidden() }
        }
    }

    private fun authenticate(user: User) {
        val p = UserPrincipal(user.id!!, user.username, user.role, user.companyId)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(p, null, listOf(SimpleGrantedAuthority(p.authority)))
    }
}
