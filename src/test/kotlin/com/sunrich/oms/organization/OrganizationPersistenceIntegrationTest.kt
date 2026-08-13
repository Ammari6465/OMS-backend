package com.sunrich.oms.organization

import com.sunrich.oms.common.enums.EmploymentType
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.PositionStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.security.UserPrincipal

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrganizationPersistenceIntegrationTest {
    @Autowired lateinit var service: OrganizationService
    @Autowired lateinit var departmentService: DepartmentService
    @Autowired lateinit var staffService: StaffService

    @BeforeEach
    fun authenticate() {
        val principal = UserPrincipal(1, "test-root", Role.SUPER_ADMIN, null)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            principal, null, listOf(SimpleGrantedAuthority(principal.authority))
        )
    }

    @AfterEach
    fun clearAuthentication() = SecurityContextHolder.clearContext()

    @Test
    fun `organization hierarchy is persisted in normalized related tables`() {
        val company = service.createCompany(CompanyRequest(name = "Sunrich Test", status = EntityStatus.ACTIVE))
        val department = departmentService.create(DepartmentCreateRequest(companyId = company.id, name = "Technology"))
        val manager = staffService.create(StaffCreateRequest(
            companyId = company.id,
            deptId = department.id,
            name = "Test Manager",
            empType = EmploymentType.PERMANENT
        ))
        val employee = staffService.create(StaffCreateRequest(
            companyId = company.id,
            deptId = department.id,
            managerId = manager.id,
            name = "Test Employee",
            empType = EmploymentType.CONTRACT
        ))
        val headed = departmentService.update(department.id, DepartmentUpdateRequest(
            companyId = company.id,
            name = department.name,
            headStaffId = manager.id,
            version = department.version
        ))
        val position = service.createPosition(PositionRequest(
            companyId = company.id,
            deptId = department.id,
            staffId = employee.id,
            title = "Developer",
            isVacant = false,
            status = PositionStatus.FILLED
        ))

        assertThat(headed.headStaffId).isEqualTo(manager.id)
        assertThat(staffService.listLegacy(false).single { it.id == employee.id }.managerId).isEqualTo(manager.id)
        assertThat(position.deptId).isEqualTo(department.id)
        assertThat(position.staffId).isEqualTo(employee.id)

        service.deleteCompany(company.id)
        assertThat(service.listCompanies(false)).noneMatch { it.id == company.id }
        assertThat(service.listCompanies(true)).anyMatch { it.id == company.id && it.isDeleted }
    }
}
