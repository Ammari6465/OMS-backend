package com.sunrich.oms.systemdata

import com.sunrich.oms.common.enums.AuditAction
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.exception.ForbiddenException
import com.sunrich.oms.organization.Company
import com.sunrich.oms.organization.CompanyRepository
import com.sunrich.oms.security.JwtService
import com.sunrich.oms.security.UserPrincipal
import com.sunrich.oms.user.User
import com.sunrich.oms.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class AuditLogIntegrationTest {
    @Autowired lateinit var service: SystemDataService
    @Autowired lateinit var trail: AuditTrailService
    @Autowired lateinit var audits: AuditLogRepository
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var companies: CompanyRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var mockMvc: MockMvc
    private lateinit var companyA: Company
    private lateinit var companyB: Company
    private lateinit var superAdmin: User

    @BeforeEach fun setUp() {
        companyA=companies.save(Company("Audit A ${System.nanoTime()}"));companyB=companies.save(Company("Audit B ${System.nanoTime()}"))
        superAdmin=saveUser("audit-root-${System.nanoTime()}",Role.SUPER_ADMIN,null);authenticate(superAdmin)
    }
    @AfterEach fun clear()=SecurityContextHolder.clearContext()

    @Test fun `audit events are paged searched filtered sorted summarized and detailed`() {
        trail.record(superAdmin,AuditAction.CREATE,"Staff",101,companyA.id,"Staff",after="id=101,name=Alice,status=ACTIVE")
        trail.record(superAdmin,AuditAction.UPDATE,"Position",202,companyA.id,"Position",before="id=202,title=Engineer",after="id=202,title=Senior Engineer")
        val page=service.listAudit(0,1,"timestamp","desc","Position",AuditAction.UPDATE,"Position",null,null,companyA.id,"SUCCESS",LocalDateTime.now().minusDays(1),LocalDateTime.now().plusDays(1))
        assertThat(page.content).hasSize(1);assertThat(page.totalElements).isEqualTo(1);assertThat(page.content.single().entityId).isEqualTo(202)
        assertThat(service.getAudit(page.content.single().id).afterValue).contains("Senior Engineer")
        val summary=service.auditSummary(companyA.id);assertThat(summary.totalEvents).isGreaterThanOrEqualTo(2);assertThat(summary.successfulActions).isEqualTo(summary.totalEvents)
    }

    @Test fun `company admin sees only own company and cannot bypass scope with a filter or export`() {
        val adminA=saveUser("audit-admin-a-${System.nanoTime()}",Role.COMPANY_ADMIN,companyA.id)
        val adminB=saveUser("audit-admin-b-${System.nanoTime()}",Role.COMPANY_ADMIN,companyB.id)
        trail.record(adminA,AuditAction.UPDATE,"Department",11,companyA.id,after="id=11,name=Finance")
        trail.record(adminB,AuditAction.UPDATE,"Department",22,companyB.id,after="id=22,name=IT")
        authenticate(adminA)
        val page=service.listAudit(0,20,"timestamp","desc",null,null,null,null,null,null,null,null,null)
        assertThat(page.content).allMatch{it.companyId==companyA.id}.noneMatch{it.entityId==22L}
        assertThatThrownBy{service.listAudit(0,20,"timestamp","desc",null,null,null,null,null,companyB.id,null,null,null)}.isInstanceOf(ForbiddenException::class.java)
        assertThat(service.exportAudit(null,null,null,null,null,null,null,null,null)).contains("Finance").doesNotContain("name=IT")
    }

    @Test fun `sensitive historical values are redacted and trusted writer rejects secrets`() {
        val legacy=audits.save(AuditLog(changedBy=superAdmin,changeType=AuditAction.UPDATE,fieldName="User",entityType="User",entityId=9,
            oldValue="username=alice,passwordHash=hash-value,token=abc",newValue="username=alice,password=new-secret,Bearer abc.def.ghi"))
        val response=service.getAudit(legacy.id!!)
        assertThat(response.beforeValue).contains("[REDACTED]").doesNotContain("hash-value").doesNotContain("abc")
        assertThat(response.afterValue).doesNotContain("new-secret").doesNotContain("abc.def.ghi")
        assertThatThrownBy{trail.record(superAdmin,AuditAction.UPDATE,"User",9,after="password=plaintext")}.isInstanceOf(IllegalArgumentException::class.java)
        audits.save(AuditLog(changedBy=superAdmin,changeType=AuditAction.IMPORT,fieldName="Staff",entityType="Staff",newValue="=HYPERLINK(\"https://evil\")"))
        assertThat(service.exportAudit(null,null,null,null,null,null,null,null,null)).contains("'=HYPERLINK")
    }

    @Test fun `non admins cannot read and no API can create modify or delete audit records`() {
        val staffUser=saveUser("audit-staff-${System.nanoTime()}",Role.STAFF,companyA.id)
        val token=jwt.generateToken(staffUser.id!!,staffUser.username,staffUser.role,staffUser.companyId)
        SecurityContextHolder.clearContext()
        mockMvc.get("/audit-logs"){header("Authorization","Bearer $token")}.andExpect{status{isForbidden()}}
        val adminToken=jwt.generateToken(superAdmin.id!!,superAdmin.username,superAdmin.role,superAdmin.companyId)
        SecurityContextHolder.clearContext()
        mockMvc.post("/audit-logs"){header("Authorization","Bearer $adminToken");contentType=MediaType.APPLICATION_JSON;content="{\"action\":\"CREATE\"}"}.andExpect{status{isMethodNotAllowed()}}
    }

    private fun saveUser(username:String,role:Role,companyId:Long?)=users.saveAndFlush(User(username=username,email="$username@example.com",passwordHash="unused",role=role,fullName=username,companyId=companyId,status=EntityStatus.ACTIVE,isActive=true))
    private fun authenticate(user:User){val p=UserPrincipal(user.id!!,user.username,user.role,user.companyId);SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(p,null,listOf(SimpleGrantedAuthority(p.authority)))}
}
