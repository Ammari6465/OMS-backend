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
class PositionControllerIntegrationTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var departments: DepartmentRepository
    @Autowired lateinit var positions: PositionRepository
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtService

    @Test
    fun `manager position list is restricted to their company`() {
        val own = companies.save(Company("Scoped Positions"))
        val foreign = companies.save(Company("Foreign Positions"))
        positions.save(Position(own, "Own Position"))
        positions.save(Position(foreign, "Foreign Position"))

        mockMvc.perform(get("/positions").bearer(token(Role.MANAGER, own.id)))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].title").value("Own Position"))
    }

    @Test
    fun `read only users cannot create positions`() {
        val company = companies.save(Company("Read Only Positions"))
        val request = PositionCreateRequest(company.id, "Blocked Position")
        mockMvc.perform(post("/positions").bearer(token(Role.READ_ONLY, company.id))
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `company admin cannot create a position in another company and invalid requests return field errors`() {
        val own = companies.save(Company("Position Admin Company"))
        val foreign = companies.save(Company("Position Blocked Company"))
        mockMvc.perform(post("/positions").bearer(token(Role.COMPANY_ADMIN, own.id))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(PositionCreateRequest(foreign.id, "Blocked"))))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.message").value("You cannot access positions belonging to another company"))

        mockMvc.perform(post("/positions").bearer(token(Role.SUPER_ADMIN, null))
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.message").value("Validation failed"))
            .andExpect(jsonPath("$.fieldErrors").isArray)
    }

    private fun token(role: Role, companyId: Long?): String {
        val suffix = System.nanoTime()
        val user = users.save(User(username = "position_${role.name.lowercase()}_$suffix",
            email = "position_controller_$suffix@example.com", passwordHash = "unused", role = role,
            companyId = companyId, status = EntityStatus.ACTIVE, isActive = true))
        return jwt.generateToken(user.id!!, user.username, role, companyId)
    }

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.bearer(token: String) =
        header("Authorization", "Bearer $token")
}
