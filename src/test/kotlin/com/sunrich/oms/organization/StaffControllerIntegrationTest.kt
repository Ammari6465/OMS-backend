package com.sunrich.oms.organization

import com.fasterxml.jackson.databind.ObjectMapper
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.security.JwtService
import com.sunrich.oms.user.User
import com.sunrich.oms.user.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StaffControllerIntegrationTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var departments: DepartmentRepository
    @Autowired lateinit var staff: StaffRepository
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService

    @Test
    fun `manager can only list staff in their own company`() {
        val ownCompany = companies.save(Company("Staff Scope Company"))
        val foreignCompany = companies.save(Company("Foreign Staff Company"))
        val ownDepartment = departments.save(Department(ownCompany, "Technology"))
        val foreignDepartment = departments.save(Department(foreignCompany, "Finance"))
        staff.save(Staff(ownCompany, ownDepartment, name = "Own Employee", employeeCode = "OWN1"))
        staff.save(Staff(foreignCompany, foreignDepartment, name = "Foreign Employee", employeeCode = "FOR1"))

        mockMvc.perform(get("/staff").bearer(token(Role.MANAGER, ownCompany.id)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].name").value("Own Employee"))
            .andExpect(jsonPath("$.data.content[0].companyId").value(ownCompany.id))
    }

    @Test
    fun `read only user cannot create staff`() {
        val company = companies.save(Company("Read Only Company"))
        val request = StaffCreateRequest(companyId = company.id, name = "Blocked Employee")

        mockMvc.perform(
            post("/staff").bearer(token(Role.READ_ONLY, company.id))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `company admin cannot create staff in another company`() {
        val ownCompany = companies.save(Company("Admin Staff Company"))
        val foreignCompany = companies.save(Company("Blocked Staff Company"))
        val request = StaffCreateRequest(companyId = foreignCompany.id, name = "Blocked Employee")

        mockMvc.perform(
            post("/staff").bearer(token(Role.COMPANY_ADMIN, ownCompany.id))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.message").value("You cannot access staff belonging to another company"))
    }

    @Test
    fun `invalid create request returns field validation errors`() {
        mockMvc.perform(
            post("/staff").bearer(token(Role.SUPER_ADMIN, null))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("Validation failed"))
            .andExpect(jsonPath("$.fieldErrors").isArray)
    }

    private fun token(role: Role, companyId: Long?): String {
        val suffix = System.nanoTime()
        val user = users.save(User(
            username = "staff_${role.name.lowercase()}_$suffix",
            email = "staff_$suffix@example.com",
            passwordHash = "not-used",
            role = role,
            companyId = companyId,
            status = EntityStatus.ACTIVE,
            isActive = true
        ))
        return jwt.generateToken(user.id!!, user.username, role, companyId)
    }

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.bearer(token: String) =
        header("Authorization", "Bearer $token")
}
