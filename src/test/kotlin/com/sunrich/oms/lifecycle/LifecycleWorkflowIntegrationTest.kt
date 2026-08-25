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
 @Autowired lateinit var workplace:com.sunrich.oms.workplace.WorkplaceService
 @Autowired lateinit var deskBookings:com.sunrich.oms.workplace.BookingService
 private var companyId=0L;private var employeeId=0L;private var positionId=0L

 @BeforeEach fun setup(){
  val stamp=System.nanoTime();val admin=userRepository.save(User("lifecycle-admin-$stamp","lifecycle-admin-$stamp@example.com","hash",Role.SUPER_ADMIN,"Lifecycle Admin"));authenticate(admin)
  val company=organizations.createCompany(CompanyRequest(name="Lifecycle ${System.nanoTime()}"));companyId=company.id
  val employee=staffService.create(StaffCreateRequest(companyId=companyId,employeeCode="LC-${System.nanoTime()}",name="Lifecycle Employee",dateJoined=LocalDate.now().minusYears(1)));employeeId=employee.id
  positionId=organizations.createPosition(PositionRequest(companyId=companyId,title="Lifecycle Analyst ${System.nanoTime()}",staffId=employeeId,status=PositionStatus.OPEN)).id
 }
 @AfterEach fun clear()=SecurityContextHolder.clearContext()

 @Test fun `approved leaver deactivates staff access and opens the actual position exactly once`(){
  val submitter=currentUser()
  // Give the leaver a future desk booking; the leaver flow must cancel it.
  val office=workplace.createOffice(com.sunrich.oms.workplace.OfficeRequest(companyId,"HQ","HQ-${System.nanoTime()}",timeZone="Asia/Kolkata"))
  val building=workplace.createBuilding(com.sunrich.oms.workplace.BuildingRequest(office.id,"T","T-${System.nanoTime()}"))
  val floorId=workplace.createFloor(com.sunrich.oms.workplace.FloorRequest(building.id,"L1",1)).id
  val deskId=workplace.createDesk(com.sunrich.oms.workplace.DeskRequest(floorId,code="LR-${System.nanoTime()}",x=java.math.BigDecimal(10),y=java.math.BigDecimal(10),width=java.math.BigDecimal(4),height=java.math.BigDecimal(3),mode=com.sunrich.oms.workplace.DeskMode.RESERVABLE)).id
  val booking=deskBookings.book(com.sunrich.oms.workplace.BookingRequest(deskId=deskId,staffId=employeeId,bookingDate=LocalDate.now().plusDays(3),startTime=java.time.LocalTime.of(9,0),endTime=java.time.LocalTime.of(17,0))).single()
  val draft=lifecycle.create(LifecycleRequest(LifecycleType.LEAVER,employeeId,companyId,LocalDate.now(),reason="Resignation",positionDisposition=PositionDisposition.OPEN,responsibilitiesAcknowledged=true))
  val pending=lifecycle.submit(draft.id,VersionRequest(draft.version))
  val checker=userRepository.save(User("checker-${System.nanoTime()}","checker-${System.nanoTime()}@example.com","hash",Role.COMPANY_ADMIN,"Lifecycle Checker",companyId=companyId));authenticate(checker)
  val approved=lifecycle.approve(pending.id,VersionRequest(pending.version));val completed=lifecycle.execute(approved.id)
  assertThat(completed.status).isEqualTo(WorkflowStatus.COMPLETED)
  assertThat(staffRepository.findById(employeeId).orElseThrow().status).isEqualTo(EntityStatus.INACTIVE)
  val position=positionRepository.findById(positionId).orElseThrow();assertThat(position.staff).isNull();assertThat(position.status).isEqualTo(PositionStatus.OPEN);assertThat(position.isVacant).isTrue()
  val repeated=lifecycle.execute(completed.id);assertThat(repeated.status).isEqualTo(WorkflowStatus.COMPLETED)
  authenticate(submitter)
  // The leaver's future desk booking must have been cancelled by the workflow.
  assertThat(deskBookings.history(employeeId).first{it.id==booking.id}.status).isEqualTo(com.sunrich.oms.workplace.BookingStatus.CANCELLED)
 }
 private fun currentUser()=userRepository.findById((SecurityContextHolder.getContext().authentication.principal as UserPrincipal).userId).orElseThrow()
 private fun authenticate(user:User){val p=UserPrincipal(user.id!!,user.username,user.role,user.companyId);SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(p,null,listOf(SimpleGrantedAuthority(p.authority)))}
}
