package com.sunrich.oms.organogram

import com.sunrich.oms.common.enums.AuditAction
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.common.enums.PositionStatus
import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.exception.ConflictException
import com.sunrich.oms.exception.ForbiddenException
import com.sunrich.oms.organization.*
import com.sunrich.oms.realtime.OrganogramSseController
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
class OrganogramIntegrationTest {
    @Autowired lateinit var organogram: OrganogramService
    @Autowired lateinit var organizations: OrganizationService
    @Autowired lateinit var departmentService: DepartmentService
    @Autowired lateinit var staffService: StaffService
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var departments: DepartmentRepository
    @Autowired lateinit var positions: PositionRepository
    @Autowired lateinit var audits: AuditLogRepository
    @Autowired lateinit var sse: OrganogramSseController

    private lateinit var companyA: CompanyResponse
    private lateinit var companyB: CompanyResponse
    private lateinit var departmentA: DepartmentResponse
    private lateinit var departmentB: DepartmentResponse
    private lateinit var actor: User

    @BeforeEach
    fun setUp() {
        authenticate(999, Role.SUPER_ADMIN, null)
        val stamp = System.nanoTime()
        companyA = organizations.createCompany(CompanyRequest(name = "Org Alpha $stamp"))
        companyB = organizations.createCompany(CompanyRequest(name = "Org Beta $stamp"))
        departmentA = departmentService.create(DepartmentCreateRequest(companyA.id, "Engineering $stamp"))
        departmentB = departmentService.create(DepartmentCreateRequest(companyB.id, "Finance $stamp"))
        actor = users.save(User("org-admin-$stamp", "org-admin-$stamp@example.com", "hash", Role.COMPANY_ADMIN,
            "Organogram Admin", companyId = companyA.id))
    }

    @AfterEach fun tearDown() = SecurityContextHolder.clearContext()

    @Test
    fun `company scope cannot be bypassed and stream uses the same boundary`() {
        authenticate(actor.id!!, Role.COMPANY_ADMIN, companyA.id)
        assertThatThrownBy { organogram.get(companyB.id, OrganogramView.EMPLOYEE, true) }
            .isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { sse.stream(companyB.id) }.isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `minimal payload excludes contact data and safely reports inactive-manager orphans`() {
        authenticate(999, Role.SUPER_ADMIN, null)
        val manager = create(companyA.id, departmentA.id, "MGR", "Manager", email = "manager@example.com")
        val employee = create(companyA.id, departmentA.id, "EMP", "Employee", manager.id, "employee@example.com")
        staffService.update(manager.id, update(manager).copy(status = EntityStatus.INACTIVE))

        authenticate(actor.id!!, Role.COMPANY_ADMIN, companyA.id)
        val response = organogram.get(companyA.id, OrganogramView.EMPLOYEE, true)
        val node = response.nodes.single { it.id == employee.id }
        assertThat(node.name).isEqualTo("Employee")
        assertThat(node.javaClass.declaredFields.map { it.name }).doesNotContain("email", "landline", "cellNumber")
        assertThat(response.orphanIds).contains(employee.id)
        assertThat(response.warnings.map { it.code }).contains("MISSING_MANAGER")
    }

    @Test
    fun `manager patch validates hierarchy version and records a reparent audit`() {
        authenticate(999, Role.SUPER_ADMIN, null)
        val first = create(companyA.id, departmentA.id, "M1", "First Manager")
        val second = create(companyA.id, departmentA.id, "M2", "Second Manager")
        val employee = create(companyA.id, departmentA.id, "E1", "Employee", first.id)

        authenticate(actor.id!!, Role.COMPANY_ADMIN, companyA.id)
        val changed = organogram.changeManager(employee.id, ManagerChangeRequest(second.id, employee.version))
        assertThat(changed.parentId).isEqualTo(second.id)
        assertThat(changed.version).isGreaterThan(employee.version)
        assertThat(audits.findAll()).anyMatch { it.changeType == AuditAction.REPARENT && it.entityId == employee.id &&
            it.oldValue == "managerId=${first.id}" && it.newValue == "managerId=${second.id}" }

        assertThatThrownBy { organogram.changeManager(employee.id, ManagerChangeRequest(first.id, employee.version)) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `manager patch rejects self descendant inactive cross-company and unauthorized changes`() {
        authenticate(999, Role.SUPER_ADMIN, null)
        val manager = create(companyA.id, departmentA.id, "M3", "Manager")
        val child = create(companyA.id, departmentA.id, "E2", "Child", manager.id)
        val foreign = create(companyB.id, departmentB.id, "F1", "Foreign")

        authenticate(actor.id!!, Role.COMPANY_ADMIN, companyA.id)
        assertThatThrownBy { organogram.changeManager(manager.id, ManagerChangeRequest(manager.id, manager.version)) }
            .isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { organogram.changeManager(manager.id, ManagerChangeRequest(child.id, manager.version)) }
            .isInstanceOf(BadRequestException::class.java).hasMessageContaining("circular")
        assertThatThrownBy { organogram.changeManager(manager.id, ManagerChangeRequest(foreign.id, manager.version)) }
            .isInstanceOf(BadRequestException::class.java).hasMessageContaining("same company")

        authenticate(555, Role.MANAGER, companyA.id)
        assertThatThrownBy { organogram.changeManager(child.id, ManagerChangeRequest(null, child.version)) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `position view includes open vacancies only when requested`() {
        authenticate(999, Role.SUPER_ADMIN, null)
        val company = companies.findById(companyA.id).orElseThrow()
        val department = departments.findById(departmentA.id).orElseThrow()
        val root = positions.save(Position(company, "Chief Executive", department, isVacant = true, status = PositionStatus.OPEN))
        val child = positions.save(Position(company, "Engineer", department, root, isVacant = true, status = PositionStatus.OPEN))
        positions.save(Position(company, "Closed Role", department, root, isVacant = true, status = PositionStatus.CLOSED))

        val included = organogram.get(companyA.id, OrganogramView.POSITION, true)
        assertThat(included.nodes.map { it.id }).contains(root.id, child.id).doesNotContainNull()
        assertThat(included.vacancies.map { it.id }).containsExactlyInAnyOrder(root.id, child.id)
        val excluded = organogram.get(companyA.id, OrganogramView.POSITION, false)
        assertThat(excluded.nodes).isEmpty()
        assertThat(excluded.vacancies).isEmpty()
    }

    @Test
    fun `staff details expose contact fields only to permitted roles`() {
        authenticate(999, Role.SUPER_ADMIN, null)
        val person = create(companyA.id, departmentA.id, "CONTACT", "Contact Person", email = "contact@example.com")

        authenticate(700, Role.STAFF, companyA.id)
        assertThat(organogram.staffDetails(person.id).email).isNull()
        authenticate(701, Role.MANAGER, companyA.id)
        assertThat(organogram.staffDetails(person.id).email).isEqualTo("contact@example.com")
    }

    private fun create(companyId: Long, deptId: Long, code: String, name: String, managerId: Long? = null,
        email: String? = null) = staffService.create(StaffCreateRequest(companyId = companyId, deptId = deptId,
        managerId = managerId, employeeCode = "$code-${System.nanoTime()}", name = name, title = "Employee",
        email = email, status = EntityStatus.ACTIVE))

    private fun update(s: StaffResponse) = StaffUpdateRequest(s.companyId, s.deptId, s.managerId, s.positionId,
        s.employeeCode, s.name, s.title, s.empType, s.email, s.landline, s.cellNumber, s.dateJoined, s.dateLeft,
        s.status, s.photoUrl, s.version)

    private fun authenticate(userId: Long, role: Role, companyId: Long?) {
        val principal = UserPrincipal(userId, "organogram-test", role, companyId)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            principal, null, listOf(SimpleGrantedAuthority(principal.authority)))
    }
}
