package com.sunrich.oms.user

import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.exception.ConflictException
import com.sunrich.oms.exception.ForbiddenException
import com.sunrich.oms.organization.Company
import com.sunrich.oms.organization.CompanyRepository
import com.sunrich.oms.organization.Staff
import com.sunrich.oms.organization.StaffRepository
import com.sunrich.oms.security.UserPrincipal
import com.sunrich.oms.systemdata.AuditLogRepository
import com.sunrich.oms.systemdata.NotificationRepository
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
class UserAdminIntegrationTest {
    @Autowired lateinit var service: UserAdminService
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var staff: StaffRepository
    @Autowired lateinit var encoder: PasswordEncoder
    @Autowired lateinit var audits: AuditLogRepository
    @Autowired lateinit var notifications: NotificationRepository
    private lateinit var companyA: Company
    private lateinit var companyB: Company
    private lateinit var superAdmin: User

    @BeforeEach
    fun setUp() {
        companyA = companies.save(Company("Users A ${System.nanoTime()}"))
        companyB = companies.save(Company("Users B ${System.nanoTime()}"))
        superAdmin = saveUser("security-super-${System.nanoTime()}", Role.SUPER_ADMIN, null)
        authenticate(superAdmin)
    }

    @AfterEach fun clearSecurity() = SecurityContextHolder.clearContext()

    @Test
    fun `list is server paged searchable filterable and summary uses database state`() {
        saveUser("alpha-manager-${System.nanoTime()}", Role.MANAGER, companyA.id)
        saveUser("beta-staff-${System.nanoTime()}", Role.STAFF, companyB.id, active = false)

        val page = service.list(0, 10, "username", "asc", "alpha-manager", Role.MANAGER,
            companyA.id, null, true, false, false)
        assertThat(page.content).hasSize(1)
        assertThat(page.content.single().companyName).isEqualTo(companyA.name)
        assertThat(service.summary(companyA.id).total).isGreaterThanOrEqualTo(1)
        assertThat(service.roles(companyA.id).first { it.role == Role.MANAGER }.assignedUsers).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `company admin cannot cross company scope or assign administrative roles`() {
        val admin = saveUser("company-admin-${System.nanoTime()}", Role.COMPANY_ADMIN, companyA.id)
        authenticate(admin)

        assertThatThrownBy { service.list(0, 20, "fullName", "asc", null, null, companyB.id, null, null, null, false) }
            .isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { service.create(create("forbidden-${System.nanoTime()}", Role.COMPANY_ADMIN, companyA.id)) }
            .isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { service.create(create("cross-${System.nanoTime()}", Role.STAFF, companyB.id)) }
            .isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { service.changeStatus(admin.id!!, UserStatusRequest(false, admin.version)) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `administrator cannot change own role or deactivate own account`() {
        assertThatThrownBy { service.changeRole(superAdmin.id!!, UserRoleRequest(Role.STAFF, superAdmin.version)) }
            .isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { service.changeStatus(superAdmin.id!!, UserStatusRequest(false, superAdmin.version)) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `last active super admin cannot be deactivated downgraded or archived`() {
        users.findAll().filter { it.role == Role.SUPER_ADMIN && it.id != superAdmin.id }.forEach {
            it.isActive = false; it.status = EntityStatus.INACTIVE; users.save(it)
        }
        val actor = saveUser("inactive-actor-${System.nanoTime()}", Role.SUPER_ADMIN, null, active = false)
        authenticate(actor)

        assertThatThrownBy { service.changeStatus(superAdmin.id!!, UserStatusRequest(false, superAdmin.version)) }
            .isInstanceOf(ConflictException::class.java).hasMessage("The system must have at least one active SUPER_ADMIN.")
        assertThatThrownBy { service.changeRole(superAdmin.id!!, UserRoleRequest(Role.STAFF, superAdmin.version)) }
            .isInstanceOf(ConflictException::class.java)
        assertThatThrownBy { service.delete(superAdmin.id!!) }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `staff association is company checked and unique for active accounts`() {
        val member = staff.save(Staff(companyA, name = "Linked Staff"))
        service.create(create("linked-${System.nanoTime()}", Role.STAFF, companyA.id, member.id))

        assertThatThrownBy { service.create(create("duplicate-${System.nanoTime()}", Role.STAFF, companyA.id, member.id)) }
            .isInstanceOf(ConflictException::class.java)
        assertThatThrownBy { service.create(create("wrong-company-${System.nanoTime()}", Role.STAFF, companyB.id, member.id)) }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `creation hashes password and status role unlock and reset operations are audited and notified`() {
        val created = service.create(create("audited-${System.nanoTime()}", Role.STAFF, companyA.id))
        val persisted = users.findById(created.id).orElseThrow()
        assertThat(persisted.passwordHash).isNotEqualTo("Password@123")
        assertThat(encoder.matches("Password@123", persisted.passwordHash)).isTrue()

        service.changeRole(created.id, UserRoleRequest(Role.MANAGER, created.version))
        val changed = users.findById(created.id).orElseThrow()
        changed.failedLoginAttempts = 5
        changed.lockedUntil = java.time.LocalDateTime.now().plusMinutes(10)
        users.saveAndFlush(changed)
        service.unlock(changed.id!!, UserVersionRequest(changed.version))
        service.requestPasswordReset(changed.id!!)

        assertThat(audits.findAll().map { it.fieldName }).anyMatch { it.contains("User") }
        assertThat(notifications.findAll().map { it.recipient.id }).contains(changed.id)
        assertThat(users.findById(changed.id!!).orElseThrow().passwordResetToken).isNotBlank()
    }

    private fun create(username: String, role: Role, companyId: Long?, staffId: Long? = null) = CreateUserRequest(
        username, username, "$username@example.com", role, companyId, staffId, true, "Password@123")

    private fun saveUser(username: String, role: Role, companyId: Long?, active: Boolean = true) = users.saveAndFlush(User(
        username = username, email = "$username@example.com", passwordHash = encoder.encode("Password@123"),
        role = role, fullName = username, companyId = companyId,
        status = if (active) EntityStatus.ACTIVE else EntityStatus.INACTIVE, isActive = active))

    private fun authenticate(user: User) {
        val principal = UserPrincipal(user.id!!, user.username, user.role, user.companyId)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            principal, null, listOf(SimpleGrantedAuthority(principal.authority)))
    }
}
