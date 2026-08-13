package com.sunrich.oms.organization

import com.sunrich.oms.common.enums.EmploymentType
import com.sunrich.oms.common.enums.EntityStatus
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
class DepartmentServiceIntegrationTest {
    @Autowired lateinit var departments: DepartmentService
    @Autowired lateinit var organizations: OrganizationService
    @Autowired lateinit var staffService: StaffService

    private lateinit var companyA: CompanyResponse
    private lateinit var companyB: CompanyResponse

    @BeforeEach
    fun setUp() {
        authenticate(Role.SUPER_ADMIN, null)
        companyA = organizations.createCompany(CompanyRequest(name = "Alpha Group"))
        companyB = organizations.createCompany(CompanyRequest(name = "Beta Group"))
    }

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    @Test
    fun `list supports search status company filtering and pagination`() {
        departments.create(DepartmentCreateRequest(companyA.id, "Technology", status = EntityStatus.ACTIVE))
        departments.create(DepartmentCreateRequest(companyA.id, "Finance", status = EntityStatus.INACTIVE))
        departments.create(DepartmentCreateRequest(companyB.id, "Technology Operations", status = EntityStatus.ACTIVE))

        val page = departments.list(0, 10, "name", "asc", "tech", EntityStatus.ACTIVE, companyA.id, false)

        assertThat(page.totalElements).isEqualTo(1)
        assertThat(page.content.single().name).isEqualTo("Technology")
        assertThat(page.content.single().companyName).isEqualTo("Alpha Group")
    }

    @Test
    fun `duplicate names in a company are rejected case-insensitively`() {
        departments.create(DepartmentCreateRequest(companyA.id, "Technology"))

        assertThatThrownBy { departments.create(DepartmentCreateRequest(companyA.id, " technology ")) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage("Department already exists for this company.")
    }

    @Test
    fun `company admin cannot read or write another company's departments`() {
        val foreign = departments.create(DepartmentCreateRequest(companyB.id, "Finance"))
        authenticate(Role.COMPANY_ADMIN, companyA.id)

        assertThatThrownBy { departments.get(foreign.id) }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { departments.create(DepartmentCreateRequest(companyB.id, "Operations")) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `stale updates return a conflict`() {
        val created = departments.create(DepartmentCreateRequest(companyA.id, "Technology"))
        departments.update(created.id, DepartmentUpdateRequest(companyA.id, "Engineering", version = created.version))

        assertThatThrownBy {
            departments.update(created.id, DepartmentUpdateRequest(companyA.id, "Platform", version = created.version))
        }.isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("modified by another user")
    }

    @Test
    fun `department with assigned staff cannot be archived`() {
        val department = departments.create(DepartmentCreateRequest(companyA.id, "Technology"))
        staffService.create(StaffCreateRequest(
            companyId = companyA.id,
            deptId = department.id,
            name = "Assigned Employee",
            empType = EmploymentType.PERMANENT
        ))

        assertThatThrownBy { departments.archive(department.id) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("employees are assigned")
    }

    @Test
    fun `department cannot be its own parent`() {
        val department = departments.create(DepartmentCreateRequest(companyA.id, "Engineering"))

        assertThatThrownBy {
            departments.update(department.id, DepartmentUpdateRequest(
                companyId = companyA.id,
                name = "Engineering",
                parentDeptId = department.id,
                version = department.version
            ))
        }.isInstanceOf(BadRequestException::class.java)
            .hasMessage("A department cannot be its own parent")
    }

    @Test
    fun `parent department hierarchy cannot contain a cycle`() {
        val parent = departments.create(DepartmentCreateRequest(companyA.id, "Head Department"))
        val child = departments.create(DepartmentCreateRequest(companyA.id, "Sub Department", parentDeptId = parent.id))

        assertThatThrownBy {
            departments.update(parent.id, DepartmentUpdateRequest(
                companyId = companyA.id,
                name = "Head Department",
                parentDeptId = child.id,
                version = parent.version
            ))
        }.isInstanceOf(BadRequestException::class.java)
            .hasMessage("Department hierarchy cannot contain a cycle")
    }

    @Test
    fun `parent department must belong to the same company`() {
        val foreignParent = departments.create(DepartmentCreateRequest(companyB.id, "Foreign Head"))

        assertThatThrownBy {
            departments.create(DepartmentCreateRequest(companyA.id, "Local Child", parentDeptId = foreignParent.id))
        }.isInstanceOf(BadRequestException::class.java)
            .hasMessage("Parent department must belong to the same company")
    }

    @Test
    fun `department with active sub-departments cannot be archived`() {
        val parent = departments.create(DepartmentCreateRequest(companyA.id, "Parent Dept"))
        departments.create(DepartmentCreateRequest(companyA.id, "Child Dept", parentDeptId = parent.id))

        assertThatThrownBy { departments.archive(parent.id) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage("Department cannot be archived because it has active sub-departments.")
    }

    private fun authenticate(role: Role, companyId: Long?) {
        val principal = UserPrincipal(100, "department-test", role, companyId)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            principal, null, listOf(SimpleGrantedAuthority(principal.authority))
        )
    }
}
