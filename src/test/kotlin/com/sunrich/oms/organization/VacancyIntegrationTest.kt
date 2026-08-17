package com.sunrich.oms.organization

import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.NotificationType
import com.sunrich.oms.common.enums.PositionStatus
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.security.UserPrincipal
import com.sunrich.oms.systemdata.AuditLogRepository
import com.sunrich.oms.systemdata.NotificationRepository
import com.sunrich.oms.systemdata.SystemSetting
import com.sunrich.oms.systemdata.SystemSettingRepository
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VacancyIntegrationTest {
    @Autowired lateinit var service: PositionService
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var departments: DepartmentRepository
    @Autowired lateinit var staff: StaffRepository
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var audits: AuditLogRepository
    @Autowired lateinit var notifications: NotificationRepository
    @Autowired lateinit var settings: SystemSettingRepository
    private lateinit var company: Company
    private lateinit var department: Department

    @BeforeEach
    fun setUp() {
        company = companies.save(Company("Vacancy Company"))
        department = departments.save(Department(company, "Technology"))
        val actor = users.save(User(username = "vacancy-${System.nanoTime()}", email = "vacancy-${System.nanoTime()}@example.com",
            passwordHash = "unused", role = Role.SUPER_ADMIN, status = EntityStatus.ACTIVE, isActive = true))
        val principal = UserPrincipal(actor.id!!, actor.username, Role.SUPER_ADMIN, null)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            principal, null, listOf(SimpleGrantedAuthority(principal.authority)))
    }

    @AfterEach fun tearDown() = SecurityContextHolder.clearContext()

    @Test
    fun `summary uses persisted vacancy lifecycle states and honors company scope`() {
        val open = service.create(PositionCreateRequest(company.id, "Open Role", department.id, status = PositionStatus.OPEN))
        val closedBase = service.create(PositionCreateRequest(company.id, "Closed Role", department.id, status = PositionStatus.OPEN))
        service.update(closedBase.id, update(closedBase, PositionStatus.CLOSED))
        val employee = staff.save(Staff(company, department, name = "Filled Employee"))
        service.create(PositionCreateRequest(company.id, "Filled Role", department.id, staffId = employee.id, status = PositionStatus.OPEN))

        val summary = service.vacancySummary(company.id)
        assertThat(open.status).isEqualTo(PositionStatus.OPEN)
        assertThat(summary.total).isEqualTo(3)
        assertThat(summary.open).isEqualTo(1)
        assertThat(summary.filled).isEqualTo(1)
        assertThat(summary.closed).isEqualTo(1)
    }

    @Test
    fun `opening closing filling and reopening are audited and notify the actor`() {
        val rules = settings.findByKind("notification-preferences") ?: SystemSetting("notification-preferences")
        rules.vacancies = true
        settings.saveAndFlush(rules)
        val vacancy = service.create(PositionCreateRequest(company.id, "Lifecycle Role", department.id, status = PositionStatus.OPEN))
        val closed = service.update(vacancy.id, update(vacancy, PositionStatus.CLOSED))
        val reopened = service.update(closed.id, update(closed, PositionStatus.OPEN))
        val employee = staff.save(Staff(company, department, name = "Lifecycle Employee"))
        service.update(reopened.id, PositionUpdateRequest(company.id, reopened.title, department.id,
            reopened.reportsToPositionId, employee.id, PositionStatus.OPEN, reopened.version))

        assertThat(audits.findAll().filter { it.fieldName == "Vacancy" }).hasSizeGreaterThanOrEqualTo(4)
        assertThat(notifications.findAll().map { it.type }).contains(NotificationType.VACANCY_OPENED, NotificationType.VACANCY_CLOSED)
    }

    @Test
    fun `closed vacancy cannot be filled without reopening`() {
        val vacancy = service.create(PositionCreateRequest(company.id, "Closed Fill Guard", department.id, status = PositionStatus.OPEN))
        val closed = service.update(vacancy.id, update(vacancy, PositionStatus.CLOSED))
        val employee = staff.save(Staff(company, department, name = "Blocked Employee"))
        assertThatThrownBy { service.update(closed.id, PositionUpdateRequest(company.id, closed.title, department.id,
            null, employee.id, PositionStatus.CLOSED, closed.version)) }
            .isInstanceOf(BadRequestException::class.java).hasMessage("A closed position cannot have assigned staff")
    }

    private fun update(position: PositionResponse, status: PositionStatus) = PositionUpdateRequest(
        position.companyId, position.title, position.deptId, position.reportsToPositionId,
        position.staffId, status, position.version)
}
