package com.sunrich.oms.organization

import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.exception.ResourceNotFoundException
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
import java.time.LocalDate

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CompanyIntegrationTest {
    @Autowired lateinit var service: OrganizationService

    @BeforeEach
    fun setUp() {
        val principal = UserPrincipal(1, "company-test-user", Role.SUPER_ADMIN, null)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            principal, null, listOf(SimpleGrantedAuthority(principal.authority))
        )
    }

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    @Test
    fun `create company succeeds with valid request`() {
        val request = CompanyRequest(
            name = " Sunrich Logistics ",
            regNumber = " LOG-9988 ",
            headOffice = " Colombo, Sri Lanka ",
            dateEstablished = LocalDate.of(2020, 5, 15),
            status = EntityStatus.ACTIVE
        )

        val created = service.createCompany(request)

        assertThat(created.id).isGreaterThan(0)
        assertThat(created.name).isEqualTo("Sunrich Logistics")
        assertThat(created.regNumber).isEqualTo("LOG-9988")
        assertThat(created.headOffice).isEqualTo("Colombo, Sri Lanka")
        assertThat(created.dateEstablished).isEqualTo(LocalDate.of(2020, 5, 15))
        assertThat(created.status).isEqualTo(EntityStatus.ACTIVE)
        assertThat(created.isDeleted).isFalse()
    }

    @Test
    fun `create company fails when name is blank or whitespace`() {
        val request = CompanyRequest(name = "   ")

        assertThatThrownBy { service.createCompany(request) }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessage("Company name is required")
    }

    @Test
    fun `update company updates details cleanly`() {
        val company = service.createCompany(CompanyRequest(name = "Original Company"))

        val updated = service.updateCompany(company.id, CompanyRequest(
            name = "Updated Company Name",
            regNumber = "REG-5544",
            headOffice = "Kandy Office",
            status = EntityStatus.INACTIVE
        ))

        assertThat(updated.name).isEqualTo("Updated Company Name")
        assertThat(updated.regNumber).isEqualTo("REG-5544")
        assertThat(updated.headOffice).isEqualTo("Kandy Office")
        assertThat(updated.status).isEqualTo(EntityStatus.INACTIVE)
    }

    @Test
    fun `update company fails when name is set to blank`() {
        val company = service.createCompany(CompanyRequest(name = "Valid Company"))

        assertThatThrownBy { service.updateCompany(company.id, CompanyRequest(name = "   ")) }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessage("Company name is required")
    }

    @Test
    fun `list companies respects includeDeleted flag`() {
        val activeComp = service.createCompany(CompanyRequest(name = "Active Enterprise"))
        val archivedComp = service.createCompany(CompanyRequest(name = "Archived Enterprise"))
        service.deleteCompany(archivedComp.id)

        val activeList = service.listCompanies(includeDeleted = false)
        val allList = service.listCompanies(includeDeleted = true)

        val activeIds = activeList.map { it.id }
        val allIds = allList.map { it.id }

        assertThat(activeIds).contains(activeComp.id).doesNotContain(archivedComp.id)
        assertThat(allIds).contains(activeComp.id, archivedComp.id)
    }

    @Test
    fun `soft delete and restore company toggles isDeleted`() {
        val company = service.createCompany(CompanyRequest(name = "Temporary Corp"))

        val archivedEntity = service.deleteCompany(company.id)
        assertThat(archivedEntity.isDeleted).isTrue()

        val restoredResponse = service.restoreCompany(company.id)
        assertThat(restoredResponse.isDeleted).isFalse()
    }

    @Test
    fun `updating non-existent company throws ResourceNotFoundException`() {
        assertThatThrownBy { service.updateCompany(99999L, CompanyRequest(name = "Ghost Corp")) }
            .isInstanceOf(ResourceNotFoundException::class.java)
    }
}
