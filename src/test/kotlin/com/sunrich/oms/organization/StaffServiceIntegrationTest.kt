package com.sunrich.oms.organization

import com.sunrich.oms.common.enums.EmploymentType
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.PositionStatus
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.exception.ConflictException
import com.sunrich.oms.exception.ForbiddenException
import com.sunrich.oms.security.UserPrincipal
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
class StaffServiceIntegrationTest {
    @Autowired lateinit var service: StaffService
    @Autowired lateinit var organizations: OrganizationService
    @Autowired lateinit var departmentService: DepartmentService
    @Autowired lateinit var positions: PositionRepository

    private lateinit var companyA: CompanyResponse
    private lateinit var companyB: CompanyResponse
    private lateinit var departmentA: DepartmentResponse
    private lateinit var departmentB: DepartmentResponse

    @BeforeEach
    fun setUp() {
        authenticate(Role.SUPER_ADMIN, null)
        companyA = organizations.createCompany(CompanyRequest(name = "Staff Alpha"))
        companyB = organizations.createCompany(CompanyRequest(name = "Staff Beta"))
        departmentA = departmentService.create(DepartmentCreateRequest(companyA.id, "Engineering"))
        departmentB = departmentService.create(DepartmentCreateRequest(companyB.id, "Finance"))
    }

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    @Test
    fun `list supports scoped server-side search filters sorting and pagination`() {
        service.create(createRequest(companyA.id, departmentA.id, "EMP002", "Zara Engineer"))
        service.create(createRequest(companyA.id, departmentA.id, "EMP001", "Alice Engineer"))
        service.create(createRequest(companyB.id, departmentB.id, "EMP003", "Alice Finance"))

        val result = service.list(
            page = 0, size = 1, sort = "employeeCode", direction = "asc", search = "alice",
            companyId = companyA.id, departmentId = departmentA.id, positionId = null,
            managerId = null, status = EntityStatus.ACTIVE, includeDeleted = false
        )

        assertThat(result.totalElements).isEqualTo(1)
        assertThat(result.content.single().employeeCode).isEqualTo("EMP001")
        assertThat(result.content.single().companyName).isEqualTo("Staff Alpha")
        assertThat(result.content.single().departmentName).isEqualTo("Engineering")
    }

    @Test
    fun `one employee can be assigned to multiple sister concerns without duplicate staff records`() {
        val employee = service.create(createRequest(companyA.id, departmentA.id, "GROUP01", "Group Employee").copy(
            additionalCompanyIds = setOf(companyB.id)
        ))

        assertThat(employee.companyId).isEqualTo(companyA.id)
        assertThat(employee.companyIds).containsExactlyInAnyOrder(companyA.id, companyB.id)
        assertThat(employee.assignments.count { it.isPrimary }).isEqualTo(1)

        val fromSecondaryCompany = service.list(
            page = 0, size = 20, sort = "name", direction = "asc", search = "Group Employee",
            companyId = companyB.id, departmentId = null, positionId = null,
            managerId = null, status = EntityStatus.ACTIVE, includeDeleted = false
        )
        assertThat(fromSecondaryCompany.content.map { it.id }).containsExactly(employee.id)

        authenticate(Role.COMPANY_ADMIN, companyB.id)
        assertThat(service.get(employee.id).companyIds).contains(companyB.id)
    }

    @Test
    fun `list searches organizational and contact fields and filters joining dates and employment type`() {
        val manager = service.create(createRequest(companyA.id, departmentA.id, "MGR70", "Nadia Supervisor"))
        val position = organizations.createPosition(PositionRequest(
            companyId = companyA.id,
            deptId = departmentA.id,
            title = "Platform Specialist",
            status = PositionStatus.OPEN
        ))
        val employee = service.create(StaffCreateRequest(
            companyId = companyA.id,
            deptId = departmentA.id,
            managerId = manager.id,
            positionId = position.id,
            employeeCode = "EMP70",
            name = "Searchable Employee",
            empType = EmploymentType.CONTRACT,
            cellNumber = "+91 98765 43210",
            dateJoined = java.time.LocalDate.of(2024, 6, 15),
            status = EntityStatus.ACTIVE
        ))

        listOf("98765", "engineering", "nadia", "platform specialist").forEach { term ->
            val result = service.list(
                page = 0, size = 20, sort = "name", direction = "asc", search = term,
                companyId = companyA.id, departmentId = null, positionId = null,
                managerId = null, status = null, includeDeleted = false
            )
            assertThat(result.content.map { it.id }).contains(employee.id)
        }

        val filtered = service.list(
            page = 0, size = 20, sort = "name", direction = "asc", search = null,
            companyId = companyA.id, departmentId = null, positionId = null,
            managerId = manager.id, status = EntityStatus.ACTIVE, includeDeleted = false,
            employmentType = EmploymentType.CONTRACT,
            joinedFrom = java.time.LocalDate.of(2024, 1, 1),
            joinedTo = java.time.LocalDate.of(2024, 12, 31)
        )
        assertThat(filtered.content.map { it.id }).containsExactly(employee.id)

        assertThatThrownBy {
            service.list(
                page = 0, size = 20, sort = "name", direction = "asc", search = null,
                companyId = companyA.id, departmentId = null, positionId = null,
                managerId = null, status = null, includeDeleted = false,
                joinedFrom = java.time.LocalDate.of(2024, 12, 31),
                joinedTo = java.time.LocalDate.of(2024, 1, 1)
            )
        }.isInstanceOf(BadRequestException::class.java)
            .hasMessage("Joining date end cannot be before joining date start")
    }

    @Test
    fun `employee code is unique within a company and normalized`() {
        service.create(createRequest(companyA.id, departmentA.id, " emp-1 ", "First Employee"))

        assertThatThrownBy {
            service.create(createRequest(companyA.id, departmentA.id, "EMP-1", "Second Employee"))
        }.isInstanceOf(ConflictException::class.java)
            .hasMessage("Employee code already exists for this company.")

        val otherCompany = service.create(createRequest(companyB.id, departmentB.id, "emp-1", "Other Employee"))
        assertThat(otherCompany.employeeCode).isEqualTo("EMP-1")
    }

    @Test
    fun `cross-company department manager and position assignments are rejected`() {
        val foreignManager = service.create(createRequest(companyB.id, departmentB.id, "BOSS1", "Foreign Manager"))
        val foreignPosition = organizations.createPosition(PositionRequest(
            companyId = companyB.id,
            deptId = departmentB.id,
            title = "Accountant",
            status = PositionStatus.OPEN
        ))

        assertThatThrownBy {
            service.create(createRequest(companyA.id, departmentB.id, "EMP10", "Invalid Department"))
        }.isInstanceOf(BadRequestException::class.java)
            .hasMessage("Department must belong to the selected company")

        assertThatThrownBy {
            service.create(createRequest(companyA.id, departmentA.id, "EMP11", "Invalid Manager", foreignManager.id))
        }.isInstanceOf(BadRequestException::class.java)
            .hasMessage("Manager must belong to the selected company")

        assertThatThrownBy {
            service.create(createRequest(companyA.id, departmentA.id, "EMP12", "Invalid Position", positionId = foreignPosition.id))
        }.isInstanceOf(BadRequestException::class.java)
            .hasMessage("Position must belong to the selected company")
    }

    @Test
    fun `reporting cycles are rejected`() {
        val manager = service.create(createRequest(companyA.id, departmentA.id, "MGR1", "Manager"))
        val employee = service.create(createRequest(companyA.id, departmentA.id, "EMP20", "Employee", manager.id))

        assertThatThrownBy {
            service.update(manager.id, updateRequest(manager, managerId = employee.id))
        }.isInstanceOf(BadRequestException::class.java)
            .hasMessage("Invalid reporting relationship. This change would create a circular hierarchy.")
    }

    @Test
    fun `position occupancy follows staff assignment and archive`() {
        val position = organizations.createPosition(PositionRequest(
            companyId = companyA.id,
            deptId = departmentA.id,
            title = "Senior Developer",
            status = PositionStatus.OPEN
        ))
        val employee = service.create(createRequest(
            companyA.id, departmentA.id, "EMP30", "Positioned Employee", positionId = position.id
        ))

        assertThat(employee.positionId).isEqualTo(position.id)
        assertThat(employee.positionTitle).isEqualTo("Senior Developer")
        assertThat(positions.findById(position.id).orElseThrow().staff?.id).isEqualTo(employee.id)
        assertThat(positions.findById(position.id).orElseThrow().isVacant).isFalse()

        service.archive(employee.id)

        val vacated = positions.findById(position.id).orElseThrow()
        assertThat(vacated.staff).isNull()
        assertThat(vacated.isVacant).isTrue()
        assertThat(vacated.status).isEqualTo(PositionStatus.OPEN)
    }

    @Test
    fun `completed leaving date deactivates staff and opens their position`() {
        val position = organizations.createPosition(PositionRequest(
            companyId = companyA.id,
            deptId = departmentA.id,
            title = "Finance Lead",
            status = PositionStatus.OPEN
        ))
        val employee = service.create(createRequest(
            companyA.id, departmentA.id, "EMP31", "Departing Employee", positionId = position.id
        ))

        val departed = service.update(employee.id, updateRequest(employee).copy(
            dateLeft = java.time.LocalDate.now().minusDays(1),
            status = EntityStatus.ACTIVE
        ))

        assertThat(departed.status).isEqualTo(EntityStatus.INACTIVE)
        assertThat(departed.positionId).isNull()
        val vacancy = positions.findById(position.id).orElseThrow()
        assertThat(vacancy.staff).isNull()
        assertThat(vacancy.isVacant).isTrue()
        assertThat(vacancy.status).isEqualTo(PositionStatus.OPEN)
    }

    @Test
    fun `position API cannot bypass staff department and occupancy rules`() {
        val employee = service.create(createRequest(companyA.id, departmentA.id, "EMP35", "Position Guard"))
        val first = organizations.createPosition(PositionRequest(
            companyId = companyA.id,
            deptId = departmentA.id,
            staffId = employee.id,
            title = "Developer"
        ))
        val otherDepartment = departmentService.create(DepartmentCreateRequest(companyA.id, "Operations"))

        assertThat(first.status).isEqualTo(PositionStatus.FILLED)
        assertThatThrownBy {
            organizations.createPosition(PositionRequest(
                companyId = companyA.id,
                deptId = otherDepartment.id,
                staffId = employee.id,
                title = "Operations Lead"
            ))
        }.isInstanceOf(BadRequestException::class.java)
            .hasMessage("Assigned staff member must belong to the position's department")

        assertThatThrownBy {
            organizations.createPosition(PositionRequest(
                companyId = companyA.id,
                deptId = departmentA.id,
                staffId = employee.id,
                title = "Second Developer Role"
            ))
        }.isInstanceOf(ConflictException::class.java)
            .hasMessage("Staff member is already assigned to another position.")
    }

    @Test
    fun `stale updates return a conflict`() {
        val employee = service.create(createRequest(companyA.id, departmentA.id, "EMP40", "Versioned Employee"))
        service.update(employee.id, updateRequest(employee, name = "Updated Employee"))

        assertThatThrownBy {
            service.update(employee.id, updateRequest(employee, name = "Stale Employee"))
        }.isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("modified by another user")
    }

    @Test
    fun `company admin cannot access another company's staff`() {
        val foreign = service.create(createRequest(companyB.id, departmentB.id, "EMP50", "Foreign Employee"))
        authenticate(Role.COMPANY_ADMIN, companyA.id)

        assertThatThrownBy { service.get(foreign.id) }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { service.create(createRequest(companyB.id, departmentB.id, "EMP51", "Blocked")) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `date left cannot be before date joined`() {
        val request = StaffCreateRequest(
            companyId = companyA.id,
            deptId = departmentA.id,
            employeeCode = "EMP60",
            name = "Date Test Staff",
            dateJoined = java.time.LocalDate.of(2023, 5, 1),
            dateLeft = java.time.LocalDate.of(2023, 4, 30),
            status = EntityStatus.ACTIVE
        )

        assertThatThrownBy { service.create(request) }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessage("Date left cannot be before date joined")
    }

    private fun createRequest(
        companyId: Long,
        departmentId: Long,
        code: String,
        name: String,
        managerId: Long? = null,
        positionId: Long? = null
    ) = StaffCreateRequest(
        companyId = companyId,
        deptId = departmentId,
        managerId = managerId,
        positionId = positionId,
        employeeCode = code,
        name = name,
        title = "Employee",
        empType = EmploymentType.PERMANENT,
        email = "${code.trim().lowercase()}@example.com",
        status = EntityStatus.ACTIVE
    )

    private fun updateRequest(
        staff: StaffResponse,
        name: String = staff.name,
        managerId: Long? = staff.managerId
    ) = StaffUpdateRequest(
        companyId = staff.companyId,
        deptId = staff.deptId,
        managerId = managerId,
        positionId = staff.positionId,
        employeeCode = staff.employeeCode,
        name = name,
        title = staff.title,
        empType = staff.empType,
        email = staff.email,
        landline = staff.landline,
        cellNumber = staff.cellNumber,
        dateJoined = staff.dateJoined,
        dateLeft = staff.dateLeft,
        status = staff.status,
        photoUrl = staff.photoUrl,
        version = staff.version
    )

    private fun authenticate(role: Role, companyId: Long?) {
        val principal = UserPrincipal(500, "staff-test", role, companyId)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            principal, null, listOf(SimpleGrantedAuthority(principal.authority))
        )
    }
}
