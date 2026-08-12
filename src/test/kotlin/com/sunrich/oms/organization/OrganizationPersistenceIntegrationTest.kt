package com.sunrich.oms.organization

import com.sunrich.oms.common.enums.EmploymentType
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.PositionStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrganizationPersistenceIntegrationTest {
    @Autowired lateinit var service: OrganizationService

    @Test
    fun `organization hierarchy is persisted in normalized related tables`() {
        val company = service.createCompany(CompanyRequest(name = "Sunrich Test", status = EntityStatus.ACTIVE))
        val department = service.createDepartment(DepartmentRequest(companyId = company.id, name = "Technology"))
        val manager = service.createStaff(StaffRequest(
            companyId = company.id,
            deptId = department.id,
            name = "Test Manager",
            empType = EmploymentType.PERMANENT
        ))
        val employee = service.createStaff(StaffRequest(
            companyId = company.id,
            deptId = department.id,
            managerId = manager.id,
            name = "Test Employee",
            empType = EmploymentType.CONTRACT
        ))
        val headed = service.updateDepartment(department.id, DepartmentRequest(
            companyId = company.id,
            name = department.name,
            headStaffId = manager.id
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
        assertThat(service.listStaff(false).single { it.id == employee.id }.managerId).isEqualTo(manager.id)
        assertThat(position.deptId).isEqualTo(department.id)
        assertThat(position.staffId).isEqualTo(employee.id)

        service.deleteCompany(company.id)
        assertThat(service.listCompanies(false)).noneMatch { it.id == company.id }
        assertThat(service.listCompanies(true)).anyMatch { it.id == company.id && it.isDeleted }
    }
}
