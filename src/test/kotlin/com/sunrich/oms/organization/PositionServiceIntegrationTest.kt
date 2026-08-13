package com.sunrich.oms.organization

import com.sunrich.oms.common.enums.AuditAction
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.PositionStatus
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.exception.ConflictException
import com.sunrich.oms.exception.ForbiddenException
import com.sunrich.oms.security.UserPrincipal
import com.sunrich.oms.systemdata.AuditLogRepository
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
class PositionServiceIntegrationTest {
    @Autowired lateinit var service: PositionService
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var departments: DepartmentRepository
    @Autowired lateinit var staff: StaffRepository
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var audits: AuditLogRepository

    private lateinit var companyA: Company
    private lateinit var companyB: Company
    private lateinit var departmentA: Department
    private lateinit var departmentB: Department
    private lateinit var actor: User

    @BeforeEach
    fun setUp() {
        companyA = companies.save(Company("Position Alpha"))
        companyB = companies.save(Company("Position Beta"))
        departmentA = departments.save(Department(companyA, "Engineering"))
        departmentB = departments.save(Department(companyB, "Finance"))
        actor = users.save(User(username = "position-actor-${System.nanoTime()}", email = "position-${System.nanoTime()}@example.com",
            passwordHash = "unused", role = Role.SUPER_ADMIN, status = EntityStatus.ACTIVE, isActive = true))
        authenticate(Role.SUPER_ADMIN, null, actor.id!!)
    }

    @AfterEach fun tearDown() = SecurityContextHolder.clearContext()

    @Test
    fun `list provides server-side search combined filtering sorting and pagination`() {
        val manager = service.create(request(companyA.id!!, departmentA.id!!, "Engineering Manager"))
        val occupant = staff.save(Staff(companyA, departmentA, name = "Priya Engineer", employeeCode = "EMP-P1"))
        service.create(request(companyA.id!!, departmentA.id!!, "Platform Engineer", manager.id, occupant.id))
        service.create(request(companyB.id!!, departmentB.id!!, "Finance Manager"))

        val result = service.list(0, 1, "title", "desc", "priya", companyA.id, departmentA.id,
            PositionStatus.FILLED, manager.id, true, false, false)

        assertThat(result.totalElements).isEqualTo(1)
        assertThat(result.content.single().title).isEqualTo("Platform Engineer")
        assertThat(result.content.single().reportsToPositionTitle).isEqualTo("Engineering Manager")
        assertThat(result.content.single().staffName).isEqualTo("Priya Engineer")
    }

    @Test
    fun `duplicate title and invalid department company relationships are rejected`() {
        service.create(request(companyA.id!!, departmentA.id!!, "Developer"))
        assertThatThrownBy { service.create(request(companyA.id!!, departmentA.id!!, " developer ")) }
            .isInstanceOf(ConflictException::class.java).hasMessageContaining("already exists")
        assertThatThrownBy { service.create(request(companyA.id!!, departmentB.id!!, "Accountant")) }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessage("The selected department does not belong to the selected company.")
    }

    @Test
    fun `self references circular hierarchies and cross-company parents are rejected`() {
        val parent = service.create(request(companyA.id!!, departmentA.id!!, "Director"))
        val child = service.create(request(companyA.id!!, departmentA.id!!, "Manager", parent.id))
        val foreign = service.create(request(companyB.id!!, departmentB.id!!, "Foreign Director"))

        assertThatThrownBy { service.update(parent.id, update(parent, reportsTo = child.id)) }
            .isInstanceOf(BadRequestException::class.java).hasMessageContaining("circular position hierarchy")
        assertThatThrownBy { service.update(child.id, update(child, reportsTo = child.id)) }
            .isInstanceOf(BadRequestException::class.java).hasMessageContaining("circular position hierarchy")
        assertThatThrownBy { service.update(child.id, update(child, reportsTo = foreign.id)) }
            .isInstanceOf(BadRequestException::class.java).hasMessage("Reporting position must belong to the selected company")
    }

    @Test
    fun `archive and close protect staffing and hierarchy dependencies`() {
        val parent = service.create(request(companyA.id!!, departmentA.id!!, "Chief Architect"))
        service.create(request(companyA.id!!, departmentA.id!!, "Architect", parent.id))
        assertThatThrownBy { service.archive(parent.id) }.isInstanceOf(ConflictException::class.java)
            .hasMessage("This position cannot be archived because other positions report to it.")
        assertThatThrownBy { service.update(parent.id, update(parent, status = PositionStatus.CLOSED)) }
            .isInstanceOf(ConflictException::class.java).hasMessageContaining("cannot be closed")

        val occupant = staff.save(Staff(companyA, departmentA, name = "Assigned Architect"))
        val assigned = service.create(request(companyA.id!!, departmentA.id!!, "Security Architect", staffId = occupant.id))
        assertThatThrownBy { service.archive(assigned.id) }.isInstanceOf(ConflictException::class.java)
            .hasMessage("This position cannot be archived because it is currently assigned to staff.")
    }

    @Test
    fun `company administrators are scoped and changes are audited`() {
        val created = service.create(request(companyA.id!!, departmentA.id!!, "Audited Position"))
        assertThat(audits.findAll()).anySatisfy { audit ->
            assertThat(audit.fieldName).isEqualTo("Vacancy")
            assertThat(audit.changeType).isEqualTo(AuditAction.CREATE)
            assertThat(audit.newValue).contains("title=Audited Position")
        }
        authenticate(Role.COMPANY_ADMIN, companyA.id, actor.id!!)
        assertThatThrownBy { service.create(request(companyB.id!!, departmentB.id!!, "Blocked Position")) }
            .isInstanceOf(ForbiddenException::class.java)
        assertThat(service.get(created.id).title).isEqualTo("Audited Position")
    }

    private fun request(companyId: Long, deptId: Long, title: String, reportsTo: Long? = null, staffId: Long? = null) =
        PositionCreateRequest(companyId, title, deptId, reportsTo, staffId, PositionStatus.OPEN)

    private fun update(position: PositionResponse, reportsTo: Long? = position.reportsToPositionId,
                       status: PositionStatus = position.status) = PositionUpdateRequest(
        position.companyId, position.title, position.deptId, reportsTo, position.staffId, status, position.version)

    private fun authenticate(role: Role, companyId: Long?, userId: Long) {
        val principal = UserPrincipal(userId, "position-test", role, companyId)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            principal, null, listOf(SimpleGrantedAuthority(principal.authority)))
    }
}
