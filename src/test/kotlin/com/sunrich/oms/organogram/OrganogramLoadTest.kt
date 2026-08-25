package com.sunrich.oms.organogram

import com.fasterxml.jackson.databind.ObjectMapper
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.organization.Company
import com.sunrich.oms.organization.CompanyRepository
import com.sunrich.oms.organization.Department
import com.sunrich.oms.organization.DepartmentRepository
import com.sunrich.oms.organization.Staff
import com.sunrich.oms.organization.StaffRepository
import com.sunrich.oms.security.UserPrincipal
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import kotlin.math.ceil
import kotlin.system.measureNanoTime

/** Reproducible service/payload load gate for the upper supported organisation size. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrganogramLoadTest {
    @Autowired lateinit var service: OrganogramService
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var departments: DepartmentRepository
    @Autowired lateinit var staff: StaffRepository
    @Autowired lateinit var entityManager: EntityManager
    @Autowired lateinit var objectMapper: ObjectMapper

    @AfterEach fun clearSecurity() = SecurityContextHolder.clearContext()

    @Test
    fun `1300 employee hierarchy meets service and payload load gates`() {
        val company = companies.save(Company("Organogram Load ${System.nanoTime()}"))
        val department = departments.save(Department(company, "Load Engineering"))
        val people = ArrayList<Staff>(1300)
        repeat(1300) { index ->
            val manager = if (index == 0) null else people[(index - 1) / 4]
            people += staff.save(Staff(company, department, manager, "LOAD-${index + 1}", "Employee ${index + 1}", "Position ${index + 1}"))
        }
        staff.flush();entityManager.clear()
        val principal = UserPrincipal(1, "organogram-load", Role.SUPER_ADMIN, null)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null,
            listOf(SimpleGrantedAuthority(principal.authority)))

        service.get(company.id!!, OrganogramView.EMPLOYEE, true) // JVM/query warm-up
        val timings = ArrayList<Double>()
        var response: OrganogramResponse? = null
        repeat(10) {
            entityManager.clear()
            val nanos = measureNanoTime { response = service.get(company.id!!, OrganogramView.EMPLOYEE, true) }
            timings += nanos / 1_000_000.0
        }
        val ordered = timings.sorted();val p95 = ordered[ceil(ordered.size * .95).toInt() - 1]
        val payloadBytes = objectMapper.writeValueAsBytes(response).size
        println("ORGANOGRAM_LOAD_METRIC nodes=1300 p50Ms=${ordered[ordered.size / 2]} p95Ms=$p95 maxMs=${ordered.last()} payloadBytes=$payloadBytes")

        assertThat(response!!.nodes).hasSize(1300)
        assertThat(response!!.rootIds).containsExactly(people.first().id)
        assertThat(response!!.warnings).isEmpty()
        assertThat(p95).isLessThan(1500.0)
        assertThat(payloadBytes).isLessThan(2_500_000)
    }
}
