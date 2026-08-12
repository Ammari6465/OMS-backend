package com.sunrich.oms.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.security.JwtService
import com.sunrich.oms.user.User
import com.sunrich.oms.user.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    private lateinit var jwtService: JwtService

    @BeforeEach
    fun setUp() {
        userRepository.deleteAll()
    }

    @Test
    fun `POST auth login with valid credentials returns 200 OK and token`() {
        val user = User(
            username = "api_admin",
            email = "api_admin@example.com",
            passwordHash = passwordEncoder.encode("SecretPass123"),
            role = Role.SUPER_ADMIN,
            fullName = "API Admin",
            status = EntityStatus.ACTIVE,
            isActive = true
        )
        userRepository.save(user)

        val loginReq = LoginRequest(username = "api_admin", password = "SecretPass123")

        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginReq))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.token").exists())
            .andExpect(jsonPath("$.data.user.username").value("api_admin"))
            .andExpect(jsonPath("$.data.user.role").value("SUPER_ADMIN"))
    }

    @Test
    fun `POST auth login with invalid credentials returns 401 Unauthorized`() {
        val loginReq = LoginRequest(username = "non_existent", password = "WrongPassword")

        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginReq))
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.message").value("Invalid username or password"))
    }

    @Test
    fun `GET auth me with valid Bearer token returns current user details`() {
        val user = User(
            username = "me_user",
            email = "me@example.com",
            passwordHash = passwordEncoder.encode("Pass12345"),
            role = Role.MANAGER,
            fullName = "Me User",
            status = EntityStatus.ACTIVE,
            isActive = true
        )
        val saved = userRepository.save(user)

        val token = jwtService.generateToken(saved.id!!, saved.username, saved.role, saved.companyId)

        mockMvc.perform(
            get("/auth/me")
                .header("Authorization", "Bearer $token")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.username").value("me_user"))
            .andExpect(jsonPath("$.data.role").value("MANAGER"))
            .andExpect(jsonPath("$.data.email").value("me@example.com"))
    }

    @Test
    fun `GET auth me without Authorization header returns 401 Unauthorized`() {
        mockMvc.perform(get("/auth/me"))
            .andExpect(status().isUnauthorized)
    }
}
