package com.sunrich.oms.workplace

import com.sunrich.oms.common.enums.*
import com.sunrich.oms.exception.*
import com.sunrich.oms.organization.*
import com.sunrich.oms.security.UserPrincipal
import com.sunrich.oms.user.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.mock.web.MockMultipartFile
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Propagation
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.LocalDate

@SpringBootTest @ActiveProfiles("test") @Transactional
class WorkplaceIntegrationTest{
 @Autowired lateinit var service:WorkplaceService;@Autowired lateinit var org:OrganizationService;@Autowired lateinit var staffService:StaffService;@Autowired lateinit var users:UserRepository;@Autowired lateinit var clock:Clock
 lateinit var admin:User;var company=0L;var otherCompany=0L;var staffId=0L;var otherStaff=0L;var floor=0L;var desk=0L;var desk2=0L
 @BeforeEach fun setup(){val n=System.nanoTime();admin=users.save(User("wp-$n","wp-$n@example.com","hash",Role.SUPER_ADMIN,"Workplace Admin"));auth(admin);company=org.createCompany(CompanyRequest(name="Workplace A $n")).id;otherCompany=org.createCompany(CompanyRequest(name="Workplace B $n")).id;staffId=staffService.create(StaffCreateRequest(companyId=company,employeeCode="WA-$n",name="Finance Person",dateJoined=LocalDate.now(clock).minusYears(1))).id;otherStaff=staffService.create(StaffCreateRequest(companyId=otherCompany,employeeCode="WB-$n",name="Other Person",dateJoined=LocalDate.now(clock).minusYears(1))).id;val office=service.createOffice(OfficeRequest(company,"Head Office","HO",city="Mumbai",country="India",timeZone="Asia/Calcutta"));val building=service.createBuilding(BuildingRequest(office.id,"Building A","A"));floor=service.createFloor(FloorRequest(building.id,"Floor 3",3)).id;service.createZone(ZoneRequest(floor,"Finance Zone","FIN","#3366ff"));desk=service.createDesk(deskRequest("F3-027",10,10)).id;desk2=service.createDesk(deskRequest("F3-028",20,10)).id}
 @AfterEach fun clear()=SecurityContextHolder.clearContext()

 @Autowired lateinit var floors:FloorRepository
 @Value("\${oms.workplace.storage-directory:./data/workplace-plans}") lateinit var planDir:String
 private val planDirectory:Path get()=Paths.get(planDir).toAbsolutePath().normalize()
 private fun planReference(floorId:Long)=floors.findById(floorId).orElseThrow().planStorageRef!!
 @Test fun `map returns hierarchy desks zones and current assignment in one payload`(){service.assign(AssignmentRequest(desk,staffId,LocalDate.now(clock),reason="Permanent desk"));val map=service.map(floor);assertThat(map.floor.name).isEqualTo("Floor 3");assertThat(map.zones).extracting<String>{it.code}.contains("FIN");assertThat(map.desks.first{it.id==desk}.assignment?.staffName).isEqualTo("Finance Person")}
 @Test fun `coordinates duplicate codes and cross-company assignments are rejected`(){assertThatThrownBy{service.createDesk(deskRequest("OUTSIDE",99,10).copy(width=BigDecimal("5")))}.isInstanceOf(BadRequestException::class.java);assertThatThrownBy{service.createDesk(deskRequest("F3-027",30,10))}.isInstanceOf(ConflictException::class.java);assertThatThrownBy{service.assign(AssignmentRequest(desk,otherStaff,LocalDate.now(clock)))}.isInstanceOf(BadRequestException::class.java)}
 @Test fun `prevents double assignment and retains transfer and release history`(){val first=service.assign(AssignmentRequest(desk,staffId,LocalDate.now(clock),reason="Initial"));assertThatThrownBy{service.assign(AssignmentRequest(desk2,staffId,LocalDate.now(clock)))}.isInstanceOf(ConflictException::class.java);val moved=service.transfer(first.id,TransferRequest(desk2,LocalDate.now(clock),"Team move"));assertThat(moved.deskCode).isEqualTo("F3-028");assertThat(service.history(staffId)).hasSize(2);service.release(moved.id,ReleaseRequest(LocalDate.now(clock),"Released",moved.version));assertThat(service.currentForStaff(staffId)).isNull()}
 @Test fun `uploaded plan is advertised and a plan whose file vanished is not`(){
  val svg="<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"400\" height=\"300\"><rect width=\"10\" height=\"10\"/></svg>"
  val uploaded=service.uploadPlan(floor,MockMultipartFile("file","floor3.svg","image/svg+xml",svg.toByteArray()))
  assertThat(uploaded.hasPlan).isTrue()
  assertThat(service.map(floor).floor.hasPlan).isTrue()

  // Simulate the image being lost while its metadata survives — a container
  // rebuilt without its plan volume, or a database-only restore. The floor must
  // stop advertising a plan rather than handing clients a link that 404s.
  Files.delete(planDirectory.resolve(planReference(floor)))

  assertThat(service.map(floor).floor.hasPlan).isFalse()
  assertThatThrownBy{service.plan(floor)}.isInstanceOf(ResourceNotFoundException::class.java)
 }
 @Test fun `read-only user of a sister concern sees the holding company shared premises`(){
  // Premises registered against the holding company are shared group assets, so
  // a viewer at a sister concern must be able to find a desk in them.
  val n=System.nanoTime()
  val holding=org.listCompanies(false).first{it.isGroupParent}.id
  val concern=org.listCompanies(false).first{it.parentCompanyId==holding}.id
  val shared=service.createOffice(OfficeRequest(holding,"Group Head Office $n","GHO-$n".take(20),city="Colombo",country="Sri Lanka",timeZone="Asia/Colombo"))
  val sharedBuilding=service.createBuilding(BuildingRequest(shared.id,"Group Tower","GT")).id
  val sharedFloor=service.createFloor(FloorRequest(sharedBuilding,"Ground Floor",1)).id

  auth(users.save(User("viewer-$n","viewer-$n@example.com","hash",Role.READ_ONLY,"Viewer",companyId=concern)))

  assertThat(service.listOffices(null)).extracting<Long>{it.id}.contains(shared.id)
  assertThat(service.listFloors(null)).extracting<Long>{it.id}.contains(sharedFloor)
  assertThat(service.map(sharedFloor).floor.name).isEqualTo("Ground Floor")
 }
 @Test fun `a sister concern private office is not exposed to its siblings`(){
  // Two concerns under the same holding company: neither is an ancestor of the
  // other, so visibility must not leak sideways.
  val n=System.nanoTime()
  val left=org.createCompany(CompanyRequest(name="Left Concern $n")).id
  val right=org.createCompany(CompanyRequest(name="Right Concern $n")).id
  val private=service.createOffice(OfficeRequest(left,"Left Office $n","LO-$n".take(20),city="Colombo",country="Sri Lanka",timeZone="Asia/Colombo"))
  val privateBuilding=service.createBuilding(BuildingRequest(private.id,"Left Tower","LT")).id
  val privateFloor=service.createFloor(FloorRequest(privateBuilding,"Left Floor",1)).id

  auth(users.save(User("sibling-$n","sibling-$n@example.com","hash",Role.READ_ONLY,"Sibling Viewer",companyId=right)))

  assertThat(service.listOffices(null)).extracting<Long>{it.id}.doesNotContain(private.id)
  assertThatThrownBy{service.map(privateFloor)}.isInstanceOf(ForbiddenException::class.java)
 }
 @Test fun `company admin cannot view another company floor`(){val scoped=users.save(User("scoped-${System.nanoTime()}","scoped-${System.nanoTime()}@example.com","hash",Role.COMPANY_ADMIN,"Scoped",companyId=otherCompany));auth(scoped);assertThatThrownBy{service.map(floor)}.isInstanceOf(ForbiddenException::class.java)}
 @Test @Transactional(propagation=Propagation.NOT_SUPPORTED) fun `summary works without an open persistence session`(){service.assign(AssignmentRequest(desk,staffId,LocalDate.now(clock),reason="Permanent desk"));assertThat(service.summary(company)).isEqualTo(WorkplaceSummary(2,1,1,0,0,50.0))}
 private fun deskRequest(code:String,x:Int,y:Int)=DeskRequest(floor,code=code,x=BigDecimal(x),y=BigDecimal(y),width=BigDecimal("4"),height=BigDecimal("3"))
 private fun auth(u:User){val p=UserPrincipal(u.id!!,u.username,u.role,u.companyId);SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(p,null,listOf(SimpleGrantedAuthority(p.authority)))}
}
