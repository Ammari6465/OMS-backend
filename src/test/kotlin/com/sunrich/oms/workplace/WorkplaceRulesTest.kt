package com.sunrich.oms.workplace

import com.sunrich.oms.common.enums.*
import com.sunrich.oms.exception.*
import com.sunrich.oms.organization.*
import com.sunrich.oms.security.UserPrincipal
import com.sunrich.oms.systemdata.AuditLogRepository
import com.sunrich.oms.systemdata.NotificationRepository
import com.sunrich.oms.user.*
import com.sunrich.oms.workplace.detection.DetectedObject
import com.sunrich.oms.workplace.detection.DetectedObjectRepository
import com.sunrich.oms.workplace.detection.DetectedObjectType
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
 @Autowired lateinit var floors:FloorRepository
 @Autowired lateinit var detectedObjects:DetectedObjectRepository
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

 // ---- batch save and removal (ID-based matching) -------------------------------------------------
 @Test fun `batch save moves desks by id and archives the ones the editor removed`(){
  val saved=service.batch(floor,DeskBatchRequest(listOf(item(desk,"F3-027",40,40,zone)),removedDeskIds=listOf(desk2),mapRevision=mapRevision()))
  assertThat(saved.map{it.code}).containsExactly("F3-027")
  assertThat(saved.first().id).isEqualTo(desk)
  assertThat(saved.first().x).isEqualByComparingTo(BigDecimal("40"))
  assertThat(desks.findById(desk2).orElseThrow().isDeleted).isTrue()
 }

 @Test fun `renaming a desk updates the same row instead of creating a second desk`(){
  val saved=service.batch(floor,DeskBatchRequest(listOf(item(desk,"F3-999",10,10,zone),item(desk2,"F3-028",20,10)),mapRevision=mapRevision()))
  // The renamed desk keeps its id; no third desk is created.
  assertThat(desks.findById(desk).orElseThrow().code).isEqualTo("F3-999")
  assertThat(saved.first{it.code=="F3-999"}.id).isEqualTo(desk)
  assertThat(desks.findAllByFloor_IdAndIsDeletedFalseOrderByCode(floor).map{it.id}).containsExactlyInAnyOrder(desk,desk2)
 }

 @Test fun `batch rejects a new desk belonging to another floor`(){
  val otherFloor=service.createFloor(FloorRequest(building,"Floor 4",4)).id
  val stray=DeskBatchItem(floorId=otherFloor,code="F4-001",x=BigDecimal("5"),y=BigDecimal("5"),width=BigDecimal("4"),height=BigDecimal("3"))
  assertThatThrownBy{service.batch(floor,DeskBatchRequest(listOf(stray),mapRevision=mapRevision()))}.isInstanceOf(BadRequestException::class.java)
 }

 @Test fun `batch cannot modify a desk that lives on another floor`(){
  val otherFloor=service.createFloor(FloorRequest(building,"Floor 5",5)).id
  val stray=service.createDesk(DeskRequest(otherFloor,code="F5-001",x=BigDecimal("5"),y=BigDecimal("5"),width=BigDecimal("4"),height=BigDecimal("3")))
  // Referenced by id but claiming this floor: rejected because the row lives elsewhere.
  val forged=DeskBatchItem(id=stray.id,version=stray.version,floorId=floor,code="F5-001",x=BigDecimal("6"),y=BigDecimal("6"),width=BigDecimal("4"),height=BigDecimal("3"))
  assertThatThrownBy{service.batch(floor,DeskBatchRequest(listOf(forged),mapRevision=mapRevision()))}.isInstanceOf(BadRequestException::class.java)
  assertThat(desks.findById(stray.id).orElseThrow().code).isEqualTo("F5-001")
 }

 @Test fun `batch rejects duplicate desk codes in the same request`(){
  val a=DeskBatchItem(floorId=floor,code="DUP",x=BigDecimal("30"),y=BigDecimal("30"),width=BigDecimal("4"),height=BigDecimal("3"))
  val b=DeskBatchItem(floorId=floor,code="dup",x=BigDecimal("35"),y=BigDecimal("35"),width=BigDecimal("4"),height=BigDecimal("3"))
  assertThatThrownBy{service.batch(floor,DeskBatchRequest(listOf(a,b),mapRevision=mapRevision()))}.isInstanceOf(ConflictException::class.java)
 }

 @Test fun `a desk cannot be updated and removed in the same request`(){
  assertThatThrownBy{service.batch(floor,DeskBatchRequest(listOf(item(desk,"F3-027",40,40,zone)),removedDeskIds=listOf(desk),mapRevision=mapRevision()))}
   .isInstanceOf(BadRequestException::class.java)
 }

 @Test fun `a stale map revision is rejected`(){
  val stale=mapRevision()
  service.createDesk(deskRequest("F3-100",60,10))            // another admin changes the map
  assertThatThrownBy{service.batch(floor,DeskBatchRequest(listOf(item(desk,"F3-027",40,40,zone)),mapRevision=stale))}
   .isInstanceOf(ConflictException::class.java).hasMessageContaining("changed")
 }

 @Test fun `a stale desk version inside a batch is rejected with 409`(){
  val staleVersion=service.getDesk(desk).version
  service.updateDesk(desk,DeskRequest(floor,zoneId=zone,code="F3-027",x=BigDecimal("11"),y=BigDecimal("11"),width=BigDecimal("4"),height=BigDecimal("3"),version=staleVersion))
  // Fresh map revision isolates the desk-version check from the map-revision check.
  assertThatThrownBy{service.batch(floor,DeskBatchRequest(listOf(item(desk,"F3-027",40,40,zone,staleVersion)),mapRevision=mapRevision()))}
   .isInstanceOf(ConflictException::class.java)
 }

 @Test fun `batch requires the map revision`(){
  assertThatThrownBy{service.batch(floor,DeskBatchRequest(listOf(item(desk,"F3-027",40,40,zone)),mapRevision=null))}
   .isInstanceOf(BadRequestException::class.java)
 }

 @Test fun `batch recreates an archived desk code instead of violating the database constraint`(){
  service.archive("desks",desk2)
  val saved=service.batch(floor,DeskBatchRequest(listOf(item(desk,"F3-027",10,10,zone),DeskBatchItem(floorId=floor,code="F3-028",x=BigDecimal("55"),y=BigDecimal("30"),width=BigDecimal("4"),height=BigDecimal("3"))),mapRevision=mapRevision()))
  assertThat(saved.map{it.code}).containsExactly("F3-027","F3-028")
  assertThat(saved.first{it.code=="F3-028"}.id).isEqualTo(desk2)     // restored the same row
  assertThat(saved.first{it.code=="F3-028"}.x).isEqualByComparingTo(BigDecimal("55"))
  assertThat(desks.findById(desk2).orElseThrow().isDeleted).isFalse()
 }

 // ---- date-aware availability (assignment expiry) ------------------------------------------------
 @Test fun `a naturally expired assignment frees the desk`(){
  val today=LocalDate.now(clock)
  service.assign(AssignmentRequest(desk,staffId,today.minusDays(10),effectiveTo=today.minusDays(1),reason="Temporary"))
  assertThat(service.getDesk(desk).availability).isEqualTo(DeskAvailability.AVAILABLE)
  assertThat(service.currentForStaff(staffId)).isNull()
  assertThat(service.map(floor).desks.first{it.id==desk}.availability).isEqualTo(DeskAvailability.AVAILABLE)
 }

 @Test fun `a future assignment does not occupy the desk today but is visible in history`(){
  val today=LocalDate.now(clock)
  val a=service.assign(AssignmentRequest(desk,staffId,today.plusDays(7),reason="Joiner"))
  assertThat(service.getDesk(desk).availability).isEqualTo(DeskAvailability.AVAILABLE)
  assertThat(service.currentForStaff(staffId)).isNull()
  assertThat(service.history(staffId).map{it.id}).contains(a.id)
 }

 @Test fun `same-day start and release leaves the desk available and keeps history`(){
  val today=LocalDate.now(clock)
  val a=service.assign(AssignmentRequest(desk,staffId,today,reason="Day desk"))
  service.release(a.id,ReleaseRequest(today,"Released same day",a.version))
  assertThat(service.getDesk(desk).availability).isEqualTo(DeskAvailability.AVAILABLE)
  assertThat(service.history(staffId)).isNotEmpty
 }

 @Test fun `the legacy Asia Calcutta time zone is stored as Asia Kolkata`(){
  val tz=service.createOffice(OfficeRequest(company,"TZ Office","TZ-${System.nanoTime()}",timeZone="Asia/Calcutta")).timeZone
  assertThat(tz).isEqualTo("Asia/Kolkata")
 }

 // ---- detected-object promotion links (soft-delete unlinking) ------------------------------------
 @Test fun `archiving a promoted desk unlinks its detected object so it can be promoted again`(){
  val promoted=service.createDesk(deskRequest("PROMO",30,30)).id
  val obj=detectedObjects.save(DetectedObject(floor=floors.findById(floor).orElseThrow(),type=DetectedObjectType.DESK,polygon="0,0 0.1,0 0.1,0.1 0,0.1",deskId=promoted))
  service.archive("desks",promoted)
  assertThat(detectedObjects.findById(obj.id!!).orElseThrow().deskId).isNull()
 }

 @Test fun `archiving a promoted zone unlinks its detected object and unzones its desks`(){
  val promoted=service.createZone(ZoneRequest(floor,"Promoted Cabin","CAB1","#8b5cf6")).id
  service.updateDesk(desk,DeskRequest(floor,zoneId=promoted,code="F3-027",x=BigDecimal("10"),y=BigDecimal("10"),width=BigDecimal("4"),height=BigDecimal("3"),version=service.getDesk(desk).version))
  val obj=detectedObjects.save(DetectedObject(floor=floors.findById(floor).orElseThrow(),type=DetectedObjectType.CABIN,polygon="0,0 0.2,0 0.2,0.2 0,0.2",zoneId=promoted))
  service.archive("zones",promoted)
  assertThat(detectedObjects.findById(obj.id!!).orElseThrow().zoneId).isNull()
  assertThat(desks.findById(desk).orElseThrow().zone).isNull()
 }

 // ---- archive / restore hierarchy ----------------------------------------------------------------
 @Test fun `a floor cannot be archived while zones or desks remain`(){
  assertThatThrownBy{service.archive("floors",floor)}.isInstanceOf(ConflictException::class.java)
 }

 @Test fun `a child cannot be restored while its parent is archived`(){
  val emptyFloor=service.createFloor(FloorRequest(building,"Spare",9)).id
  val d=service.createDesk(DeskRequest(emptyFloor,code="E-1",x=BigDecimal("5"),y=BigDecimal("5"),width=BigDecimal("4"),height=BigDecimal("3"))).id
  service.archive("desks",d)
  service.archive("floors",emptyFloor)
  assertThatThrownBy{service.restore("desks",d)}.isInstanceOf(ConflictException::class.java)
  service.restore("floors",emptyFloor)
  service.restore("desks",d)
  assertThat(desks.findById(d).orElseThrow().isDeleted).isFalse()
 }

 // ---- workplace spaces (Phase 2: typed rooms with permanent geometry) ----------------------------
 @Test fun `a space stores its type and geometry and can be edited without losing either`(){
  val s=service.createSpace(spaceRequest("Director Cabin","CAB-1",SpaceType.CABIN))
  assertThat(s.type).isEqualTo(SpaceType.CABIN)
  assertThat(s.polygon).isNotEmpty()
  assertThat(s.bbox.width).isGreaterThan(0.0)
  val updated=service.updateSpace(s.id,SpaceRequest(floorId=floor,type=SpaceType.CONFERENCE_ROOM,name="Board Room",code="CAB-1",colour="#0ea5e9",capacity=8,amenities="TV, whiteboard",bookable=true,version=s.version))
  assertThat(updated.type).isEqualTo(SpaceType.CONFERENCE_ROOM)
  assertThat(updated.name).isEqualTo("Board Room")
  assertThat(updated.capacity).isEqualTo(8)
  assertThat(updated.amenities).isEqualTo("TV, whiteboard")
  assertThat(updated.colour).isEqualTo("#0ea5e9")
  assertThat(updated.bookable).isTrue()
  // Geometry survives an edit that does not resend the polygon.
  assertThat(updated.polygon).isNotEmpty()
 }

 @Test fun `desks can be assigned to a space and the space reports its desk count`(){
  val s=service.createSpace(spaceRequest("Cabin","CAB-1"))
  service.updateDesk(desk,DeskRequest(floor,zoneId=zone,spaceId=s.id,code="F3-027",x=BigDecimal("10"),y=BigDecimal("10"),width=BigDecimal("4"),height=BigDecimal("3"),version=service.getDesk(desk).version))
  assertThat(service.getDesk(desk).spaceId).isEqualTo(s.id)
  assertThat(service.getSpace(s.id).deskCount).isEqualTo(1)
  assertThat(service.map(floor).spaces.first{it.id==s.id}.deskCount).isEqualTo(1)
 }

 @Test fun `archiving a space unassigns its desks and unlinks its detected objects`(){
  val s=service.createSpace(spaceRequest("Cabin","CAB-1"))
  service.updateDesk(desk,DeskRequest(floor,spaceId=s.id,code="F3-027",x=BigDecimal("10"),y=BigDecimal("10"),width=BigDecimal("4"),height=BigDecimal("3"),version=service.getDesk(desk).version))
  val obj=detectedObjects.save(DetectedObject(floor=floors.findById(floor).orElseThrow(),type=DetectedObjectType.CABIN,polygon="0,0 0.2,0 0.2,0.2 0,0.2",spaceId=s.id))
  service.archive("spaces",s.id)
  assertThat(desks.findById(desk).orElseThrow().space).isNull()
  assertThat(detectedObjects.findById(obj.id!!).orElseThrow().spaceId).isNull()
 }

 @Test fun `space codes are unique per floor`(){
  service.createSpace(spaceRequest("Cabin","CAB-1"))
  assertThatThrownBy{service.createSpace(spaceRequest("Another","CAB-1"))}.isInstanceOf(ConflictException::class.java)
 }

 @Test fun `a space rejects a non-existent department and an out-of-range polygon`(){
  assertThatThrownBy{service.createSpace(spaceRequest("Cabin","CAB-1").copy(departmentId=999999))}.isInstanceOf(ResourceNotFoundException::class.java)
  assertThatThrownBy{service.createSpace(spaceRequest("Cabin","CAB-2",polygon="0.1,0.1 2.0,0.1 0.3,0.3"))}.isInstanceOf(BadRequestException::class.java)
 }

 @Test fun `a floor cannot be archived while spaces remain`(){
  val f2=service.createFloor(FloorRequest(building,"Spaces Floor",8)).id
  service.createSpace(SpaceRequest(floorId=f2,type=SpaceType.WASHROOM,name="Washroom",code="WC-1"))
  assertThatThrownBy{service.archive("floors",f2)}.isInstanceOf(ConflictException::class.java).hasMessageContaining("spaces")
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

 private fun spaceRequest(name:String,code:String,type:SpaceType=SpaceType.CABIN,polygon:String?="0.1,0.1 0.3,0.1 0.3,0.3 0.1,0.3")=
  SpaceRequest(floorId=floor,type=type,name=name,code=code,polygon=polygon)

 /** The current floor-map revision, which a batch save must present. */
 private fun mapRevision()=service.map(floor).floor.mapRevision
 /** A batch item for an existing desk (id given, version read from the DB) or a new one (id null). */
 private fun item(id:Long?,code:String,x:Int,y:Int,zoneId:Long?=null,version:Long?=id?.let{service.getDesk(it).version})=
  DeskBatchItem(id=id,version=version,floorId=floor,zoneId=zoneId,code=code,x=BigDecimal(x),y=BigDecimal(y),width=BigDecimal("4"),height=BigDecimal("3"))

 /** A genuinely encoded 8x6 PNG, so the upload path decodes real image dimensions. */
 private fun pngBytes():ByteArray{val out=java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8,6,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray()}

 private fun auth(u:User){
  val p=UserPrincipal(u.id!!,u.username,u.role,u.companyId)
  SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(p,null,listOf(SimpleGrantedAuthority(p.authority)))
 }
}
