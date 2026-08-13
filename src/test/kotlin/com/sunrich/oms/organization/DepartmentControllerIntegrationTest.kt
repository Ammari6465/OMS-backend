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
class DepartmentControllerIntegrationTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var departments: DepartmentRepository
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService

    @Test
    fun `manager can only list departments in their own company`() {
        val ownCompany = companies.save(Company("Scoped Company"))
        val foreignCompany = companies.save(Company("Foreign Company"))
        departments.save(Department(ownCompany, "Technology"))
        departments.save(Department(foreignCompany, "Finance"))
        val token = token(Role.MANAGER, ownCompany.id)

        mockMvc.perform(get("/departments").bearer(token))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].name").value("Technology"))
            .andExpect(jsonPath("$.data.content[0].companyId").value(ownCompany.id))
    }

    @Test
    fun `staff cannot create a department`() {
        val company = companies.save(Company("Staff Company"))
        val request = DepartmentCreateRequest(company.id, "Operations")

        mockMvc.perform(
            post("/departments").bearer(token(Role.STAFF, company.id))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `company admin cannot create a department for another company`() {
        val ownCompany = companies.save(Company("Admin Company"))
        val foreignCompany = companies.save(Company("Blocked Company"))
        val request = DepartmentCreateRequest(foreignCompany.id, "Operations")

        mockMvc.perform(
            post("/departments").bearer(token(Role.COMPANY_ADMIN, ownCompany.id))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.message").value("You cannot access departments belonging to another company"))
    }

    @Test
    fun `invalid create request returns field validation errors`() {
        mockMvc.perform(
            post("/departments").bearer(token(Role.SUPER_ADMIN, null))
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
            username = "department_${role.name.lowercase()}_$suffix",
            email = "department_$suffix@example.com",
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
