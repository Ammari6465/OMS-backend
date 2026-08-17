package com.sunrich.oms.lifecycle

import com.sunrich.oms.common.enums.*
import com.sunrich.oms.organization.*
import com.sunrich.oms.security.UserPrincipal
import com.sunrich.oms.user.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate

@SpringBootTest @ActiveProfiles("test")
class LifecycleWorkflowIntegrationTest {
 @Autowired lateinit var lifecycle:LifecycleWorkflowService
 @Autowired lateinit var organizations:OrganizationService
 @Autowired lateinit var staffService:StaffService
 @Autowired lateinit var staffRepository:StaffRepository
 @Autowired lateinit var positionRepository:PositionRepository
 @Autowired lateinit var userRepository:UserRepository
 private var companyId=0L;private var employeeId=0L;private var positionId=0L

 @BeforeEach fun setup(){
  val stamp=System.nanoTime();val admin=userRepository.save(User("lifecycle-admin-$stamp","lifecycle-admin-$stamp@example.com","hash",Role.SUPER_ADMIN,"Lifecycle Admin"));authenticate(admin)
  val company=organizations.createCompany(CompanyRequest(name="Lifecycle ${System.nanoTime()}"));companyId=company.id
  val employee=staffService.create(StaffCreateRequest(companyId=companyId,employeeCode="LC-${System.nanoTime()}",name="Lifecycle Employee",dateJoined=LocalDate.now().minusYears(1)));employeeId=employee.id
  positionId=organizations.createPosition(PositionRequest(companyId=companyId,title="Lifecycle Analyst ${System.nanoTime()}",staffId=employeeId,status=PositionStatus.OPEN)).id
 }
 @AfterEach fun clear()=SecurityContextHolder.clearContext()

 @Test fun `approved leaver deactivates staff access and opens the actual position exactly once`(){
  val submitter=currentUser();val draft=lifecycle.create(LifecycleRequest(LifecycleType.LEAVER,employeeId,companyId,LocalDate.now(),reason="Resignation",positionDisposition=PositionDisposition.OPEN,responsibilitiesAcknowledged=true))
  val pending=lifecycle.submit(draft.id,VersionRequest(draft.version))
  val checker=userRepository.save(User("checker-${System.nanoTime()}","checker-${System.nanoTime()}@example.com","hash",Role.COMPANY_ADMIN,"Lifecycle Checker",companyId=companyId));authenticate(checker)
  val approved=lifecycle.approve(pending.id,VersionRequest(pending.version));val completed=lifecycle.execute(approved.id)
  assertThat(completed.status).isEqualTo(WorkflowStatus.COMPLETED)
  assertThat(staffRepository.findById(employeeId).orElseThrow().status).isEqualTo(EntityStatus.INACTIVE)
  val position=positionRepository.findById(positionId).orElseThrow();assertThat(position.staff).isNull();assertThat(position.status).isEqualTo(PositionStatus.OPEN);assertThat(position.isVacant).isTrue()
  val repeated=lifecycle.execute(completed.id);assertThat(repeated.status).isEqualTo(WorkflowStatus.COMPLETED)
  authenticate(submitter)
 }
 private fun currentUser()=userRepository.findById((SecurityContextHolder.getContext().authentication.principal as UserPrincipal).userId).orElseThrow()
 private fun authenticate(user:User){val p=UserPrincipal(user.id!!,user.username,user.role,user.companyId);SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(p,null,listOf(SimpleGrantedAuthority(p.authority)))}
}
