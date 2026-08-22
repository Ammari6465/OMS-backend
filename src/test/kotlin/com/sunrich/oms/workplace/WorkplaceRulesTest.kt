package com.sunrich.oms.workplace

import com.sunrich.oms.common.enums.*
import com.sunrich.oms.exception.*
import com.sunrich.oms.organization.*
import com.sunrich.oms.security.UserPrincipal
import com.sunrich.oms.systemdata.AuditLogRepository
import com.sunrich.oms.systemdata.NotificationRepository
import com.sunrich.oms.user.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

/**
 * Covers the workplace rules the map-payload test does not: archive restrictions, the floor search,
 * batch removal, role authorization on the plan file, audit and notification side effects, and the
 * lifecycle release hook.
 */
@SpringBootTest @ActiveProfiles("test") @Transactional
class WorkplaceRulesTest{
 @Autowired lateinit var service:WorkplaceService
 @Autowired lateinit var org:OrganizationService
 @Autowired lateinit var staffService:StaffService
 @Autowired lateinit var users:UserRepository
 @Autowired lateinit var audits:AuditLogRepository
 @Autowired lateinit var notifications:NotificationRepository
 @Autowired lateinit var desks:DeskRepository
 @Autowired lateinit var assignments:DeskAssignmentRepository
 @Autowired lateinit var clock:Clock

 lateinit var admin:User
 var company=0L; var otherCompany=0L; var staffId=0L
 var office=0L; var building=0L; var floor=0L; var zone=0L; var desk=0L; var desk2=0L

 @BeforeEach fun setup(){
  val n=System.nanoTime()
  admin=users.save(User("rules-$n","rules-$n@example.com","hash",Role.SUPER_ADMIN,"Rules Admin"))
  auth(admin)
  company=org.createCompany(CompanyRequest(name="Rules A $n")).id
  otherCompany=org.createCompany(CompanyRequest(name="Rules B $n")).id
  staffId=staffService.create(StaffCreateRequest(companyId=company,employeeCode="RA-$n",name="Priya Sharma",dateJoined=LocalDate.now(clock).minusYears(1))).id
  office=service.createOffice(OfficeRequest(company,"Head Office","HO-$n",city="Mumbai",timeZone="Asia/Calcutta")).id
  building=service.createBuilding(BuildingRequest(office,"Building A","A")).id
  floor=service.createFloor(FloorRequest(building,"Floor 3",3)).id
  zone=service.createZone(ZoneRequest(floor,"Finance Zone","FIN","#3366ff")).id
  desk=service.createDesk(deskRequest("F3-027",10,10,zone)).id
  desk2=service.createDesk(deskRequest("F3-028",20,10)).id
 }
 @AfterEach fun clear()=SecurityContextHolder.clearContext()

 // ---- single-record reads and scoping -------------------------------------------------------
 @Test fun `single record reads resolve the full hierarchy and stay company scoped`(){
  assertThat(service.getOffice(office).companyId).isEqualTo(company)
  assertThat(service.getFloor(floor).buildingName).isEqualTo("Building A")
  assertThat(service.getDesk(desk).zoneName).isEqualTo("Finance Zone")
  val outsider=users.save(User("out-${System.nanoTime()}","out-${System.nanoTime()}@example.com","hash",Role.COMPANY_ADMIN,"Outsider",companyId=otherCompany))
  auth(outsider)
  assertThatThrownBy{service.getDesk(desk)}.isInstanceOf(ForbiddenException::class.java)
  assertThatThrownBy{service.getFloor(floor)}.isInstanceOf(ForbiddenException::class.java)
 }

 @Test fun `desk and office listings only return the caller's company`(){
  val otherOffice=service.createOffice(OfficeRequest(otherCompany,"Other Office","OO-${System.nanoTime()}",timeZone="Asia/Calcutta")).id
  val scoped=users.save(User("sc-${System.nanoTime()}","sc-${System.nanoTime()}@example.com","hash",Role.COMPANY_ADMIN,"Scoped",companyId=company))
  auth(scoped)
  val visible=service.listOffices(null,false)
  // A sibling company's office stays hidden; premises inherited from the
  // holding company above may legitimately appear alongside the caller's own.
  assertThat(visible.map{it.id}).contains(office).doesNotContain(otherOffice)
  assertThat(service.listDesks(null,false).map{it.id}).contains(desk,desk2)
 }

 // ---- floor search ---------------------------------------------------------------------------
 @Test fun `floor search matches desk code zone and assigned staff`(){
  service.assign(AssignmentRequest(desk,staffId,LocalDate.now(clock),reason="Permanent desk"))
  assertThat(service.searchFloor(floor,"F3-027")).singleElement().satisfies({assertThat(it.deskCode).isEqualTo("F3-027");assertThat(it.matchedOn).isEqualTo("desk")})
  assertThat(service.searchFloor(floor,"priya")).singleElement().satisfies({assertThat(it.staffName).isEqualTo("Priya Sharma");assertThat(it.matchedOn).isEqualTo("staff")})
  assertThat(service.searchFloor(floor,"finance")).hasSize(1)
  assertThat(service.searchFloor(floor,"   ")).isEmpty()
  assertThat(service.searchFloor(floor,"nothing-matches-this")).isEmpty()
 }

 // ---- batch save and removal -------------------------------------------------------------------
 @Test fun `batch save moves desks and archives the ones the editor removed`(){
  val moved=DeskRequest(floor,code="F3-027",x=BigDecimal("40"),y=BigDecimal("40"),width=BigDecimal("4"),height=BigDecimal("3"))
  val saved=service.batch(floor,DeskBatchRequest(listOf(moved),removedDeskIds=listOf(desk2)))
  assertThat(saved.map{it.code}).containsExactly("F3-027")
  assertThat(saved.first().x).isEqualByComparingTo(BigDecimal("40"))
  assertThat(desks.findById(desk2).orElseThrow().isDeleted).isTrue()
 }

 @Test fun `batch rejects desks belonging to another floor`(){
  val otherFloor=service.createFloor(FloorRequest(building,"Floor 4",4)).id
  val stray=DeskRequest(otherFloor,code="F4-001",x=BigDecimal("5"),y=BigDecimal("5"),width=BigDecimal("4"),height=BigDecimal("3"))
  assertThatThrownBy{service.batch(floor,DeskBatchRequest(listOf(stray)))}.isInstanceOf(BadRequestException::class.java)
 }

 @Test fun `clearing floor contents removes desks zones and all their assignments`(){
  val assignment=service.assign(AssignmentRequest(desk,staffId,LocalDate.now(clock),reason="Permanent desk"))

  val result=service.clearFloorContents(floor)

  assertThat(result).isEqualTo(FloorContentsClearResult(desks=2,zones=1,assignments=1))
  assertThat(service.listDesks(floor,false)).isEmpty()
  assertThat(service.listZones(floor,false)).isEmpty()
  assertThat(service.currentForStaff(staffId)).isNull()
  assertThat(assignments.findById(assignment.id).orElseThrow().isDeleted).isTrue()
 }

 // ---- archive restrictions ------------------------------------------------------------------------
 @Test fun `archiving is blocked while dependants or active assignments exist`(){
  service.assign(AssignmentRequest(desk,staffId,LocalDate.now(clock),reason="Permanent desk"))
  assertThatThrownBy{service.archive("desks",desk)}.isInstanceOf(ConflictException::class.java)
  assertThatThrownBy{service.archive("floors",floor)}.isInstanceOf(ConflictException::class.java)
  assertThatThrownBy{service.archive("buildings",building)}.isInstanceOf(ConflictException::class.java)
  assertThatThrownBy{service.archive("offices",office)}.isInstanceOf(ConflictException::class.java)
  assertThatThrownBy{service.archive("nonsense",desk)}.isInstanceOf(BadRequestException::class.java)
 }

 @Test fun `a released desk can be archived and restored`(){
  val a=service.assign(AssignmentRequest(desk,staffId,LocalDate.now(clock),reason="Permanent desk"))
  service.release(a.id,ReleaseRequest(LocalDate.now(clock),"Released",a.version))
  service.archive("desks",desk)
  assertThat(desks.findById(desk).orElseThrow().isDeleted).isTrue()
  service.restore("desks",desk)
  assertThat(desks.findById(desk).orElseThrow().isDeleted).isFalse()
 }

 // ---- optimistic locking ----------------------------------------------------------------------------
 @Test fun `a stale version is rejected with a reload instruction`(){
  val current=service.getZone(zone)
  service.updateZone(zone,ZoneRequest(floor,"Finance Zone Renamed","FIN","#3366ff",version=current.version))
  assertThatThrownBy{service.updateZone(zone,ZoneRequest(floor,"Renamed","FIN","#3366ff",version=current.version))}
   .isInstanceOf(ConflictException::class.java).hasMessageContaining("Reload")
 }

 // ---- floor plans -------------------------------------------------------------------------------------
 @Test fun `floor plans require company scope and reject unsafe uploads`(){
  val png=MockMultipartFile("file","plan.png","image/png",pngBytes())
  service.uploadPlan(floor,png)
  assertThat(service.map(floor).planUrl).isEqualTo("/workplaces/floors/$floor/plan")
  assertThat(service.plan(floor).second).isEqualTo("image/png")
  assertThat(notifications.findAll().filter{it.recipient.id==admin.id}.map{it.type}).contains(NotificationType.FLOOR_PLAN_REPLACED)
  assertThatThrownBy{service.uploadPlan(floor,MockMultipartFile("file","evil.svg","image/svg+xml","<svg onload=\"x()\"></svg>".toByteArray()))}
   .isInstanceOf(BadRequestException::class.java)
  val truncated=MockMultipartFile("file","broken.png","image/png",byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)+ByteArray(16))
  assertThatThrownBy{service.uploadPlan(floor,truncated)}.isInstanceOf(BadRequestException::class.java)
  val outsider=users.save(User("plan-${System.nanoTime()}","plan-${System.nanoTime()}@example.com","hash",Role.COMPANY_ADMIN,"Outsider",companyId=otherCompany))
  auth(outsider)
  assertThatThrownBy{service.plan(floor)}.isInstanceOf(ForbiddenException::class.java)
  assertThatThrownBy{service.uploadPlan(floor,png)}.isInstanceOf(ForbiddenException::class.java)
 }

 @Test fun `requesting a plan that was never uploaded reports not found`(){
  assertThatThrownBy{service.plan(floor)}.isInstanceOf(ResourceNotFoundException::class.java)
 }

 // ---- audit and notifications ---------------------------------------------------------------------------
 @Test fun `assignment transfer and release are audited and notified`(){
  val before=audits.count()
  val a=service.assign(AssignmentRequest(desk,staffId,LocalDate.now(clock),reason="Permanent desk"))
  val moved=service.transfer(a.id,TransferRequest(desk2,LocalDate.now(clock),"Team move"))
  service.release(moved.id,ReleaseRequest(LocalDate.now(clock),"Released",moved.version))
  assertThat(audits.count()).isGreaterThan(before)
  val entries=audits.findAll().filter{it.entityType=="DeskAssignment"}
  assertThat(entries).isNotEmpty
  // Audit text carries identifiers and dates, never staff names or plan contents.
  assertThat(entries.mapNotNull{it.newValue}).allSatisfy({assertThat(it).doesNotContain("Priya Sharma")})
  val raised=notifications.findAll().filter{it.recipient.id==admin.id}
  assertThat(raised.map{it.type}).contains(NotificationType.DESK_ASSIGNED,NotificationType.DESK_RELEASED)
 }

 // ---- lifecycle hook -------------------------------------------------------------------------------------
 @Test fun `a lifecycle exit releases the active desk and notifies the responsible administrator`(){
  service.assign(AssignmentRequest(desk,staffId,LocalDate.now(clock),reason="Permanent desk"))
  service.releaseForStaff(staffId,LocalDate.now(clock),"Staff exit via L-2026-01")
  assertThat(service.currentForStaff(staffId)).isNull()
  assertThat(service.history(staffId)).singleElement().satisfies({assertThat(it.releaseReason).contains("Staff exit")})
  assertThat(service.getDesk(desk).availability).isEqualTo(DeskAvailability.AVAILABLE)
  assertThat(notifications.findAll().filter{it.recipient.id==admin.id}.map{it.type}).contains(NotificationType.DESK_RELEASED)
 }

 // ---- summary -----------------------------------------------------------------------------------------------
 @Test fun `summary counts desks assignments and staff without a desk`(){
  // Measured as deltas: the summary also counts shared premises inherited from
  // the holding company, which this fixture does not control.
  val base=service.summary(company)
  service.createDesk(deskRequest("F3-029",30,10).copy(mode=DeskMode.UNAVAILABLE))
  service.assign(AssignmentRequest(desk,staffId,LocalDate.now(clock),reason="Permanent desk"))
  val s=service.summary(company)
  assertThat(s.totalDesks).isEqualTo(base.totalDesks+1)
  assertThat(s.unavailableDesks).isEqualTo(base.unavailableDesks+1)
  assertThat(s.assignedDesks).isEqualTo(base.assignedDesks+1)
  assertThat(s.availableDesks).isEqualTo(base.availableDesks-1)
  assertThat(s.staffWithoutDesks).isEqualTo(base.staffWithoutDesks-1)
  assertThat(s.utilizationPercent).isEqualTo(s.assignedDesks*100.0/(s.totalDesks-s.unavailableDesks))
 }

 @Test fun `a super admin must name the company a summary is for`(){
  assertThatThrownBy{service.summary(null)}.isInstanceOf(BadRequestException::class.java).hasMessageContaining("companyId")
 }

 // ---- validation ------------------------------------------------------------------------------------------------
 @Test fun `desk geometry rotation and time zones are validated`(){
  assertThatThrownBy{service.createDesk(deskRequest("WIDE",98,10).copy(width=BigDecimal("5")))}.isInstanceOf(BadRequestException::class.java)
  assertThatThrownBy{service.createDesk(deskRequest("TALL",10,98).copy(height=BigDecimal("5")))}.isInstanceOf(BadRequestException::class.java)
  assertThat(service.createDesk(deskRequest("SPIN",50,50).copy(rotation=-90)).rotation).isEqualTo(270)
  assertThatThrownBy{service.createOffice(OfficeRequest(company,"Bad Zone","BZ-${System.nanoTime()}",timeZone="Not/AZone"))}.isInstanceOf(BadRequestException::class.java)
 }

 @Test fun `codes must be unique within their parent`(){
  assertThatThrownBy{service.createZone(ZoneRequest(floor,"Duplicate","FIN"))}.isInstanceOf(ConflictException::class.java)
  assertThatThrownBy{service.createBuilding(BuildingRequest(office,"Duplicate","A"))}.isInstanceOf(ConflictException::class.java)
  assertThatThrownBy{service.createOffice(OfficeRequest(company,"Duplicate","HO-DUP").copy(code=service.getOffice(office).code))}.isInstanceOf(ConflictException::class.java)
 }

 @Test fun `an inactive staff member cannot receive a desk`(){
  val inactive=staffService.create(StaffCreateRequest(companyId=company,employeeCode="IN-${System.nanoTime()}",name="Inactive Person",dateJoined=LocalDate.now(clock).minusYears(1))).id
  staffService.archive(inactive)
  assertThatThrownBy{service.assign(AssignmentRequest(desk,inactive,LocalDate.now(clock)))}.isInstanceOf(RuntimeException::class.java)
 }

 @Test fun `an unavailable desk cannot be assigned and end dates cannot precede start dates`(){
  val blocked=service.createDesk(deskRequest("F3-030",40,10).copy(mode=DeskMode.UNAVAILABLE)).id
  assertThatThrownBy{service.assign(AssignmentRequest(blocked,staffId,LocalDate.now(clock)))}.isInstanceOf(ConflictException::class.java)
  assertThatThrownBy{service.assign(AssignmentRequest(desk,staffId,LocalDate.now(clock),effectiveTo=LocalDate.now(clock).minusDays(1)))}.isInstanceOf(BadRequestException::class.java)
 }

 private fun deskRequest(code:String,x:Int,y:Int,zoneId:Long?=null)=
  DeskRequest(floor,zoneId=zoneId,code=code,x=BigDecimal(x),y=BigDecimal(y),width=BigDecimal("4"),height=BigDecimal("3"))

 /** A genuinely encoded 8x6 PNG, so the upload path decodes real image dimensions. */
 private fun pngBytes():ByteArray{val out=java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8,6,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray()}

 private fun auth(u:User){
  val p=UserPrincipal(u.id!!,u.username,u.role,u.companyId)
  SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(p,null,listOf(SimpleGrantedAuthority(p.authority)))
 }
}
