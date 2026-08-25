package com.sunrich.oms.workplace

import com.sunrich.oms.common.enums.*
import com.sunrich.oms.exception.*
import com.sunrich.oms.organization.*
import com.sunrich.oms.security.UserPrincipal
import com.sunrich.oms.user.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

@SpringBootTest @ActiveProfiles("test") @Transactional
class RoomBookingServiceTest {
 @Autowired lateinit var service: RoomBookingService
 @Autowired lateinit var workplace: WorkplaceService
 @Autowired lateinit var org: OrganizationService
 @Autowired lateinit var staffService: StaffService
 @Autowired lateinit var users: UserRepository
 @Autowired lateinit var clock: Clock

 lateinit var admin: User
 var company = 0L; var staffId = 0L; var floor = 0L; var room = 0L

 @BeforeEach fun setup() {
  val n = System.nanoTime()
  admin = users.save(User("rb-$n", "rb-$n@example.com", "hash", Role.SUPER_ADMIN, "Room Admin")); auth(admin)
  company = org.createCompany(CompanyRequest(name = "Room Co $n")).id
  staffId = staffService.create(StaffCreateRequest(companyId = company, employeeCode = "RB-$n", name = "Organiser One", dateJoined = LocalDate.now(clock).minusYears(1))).id
  val office = workplace.createOffice(OfficeRequest(company, "HQ", "HQ", timeZone = "Asia/Kolkata"))
  val building = workplace.createBuilding(BuildingRequest(office.id, "Tower", "T"))
  floor = workplace.createFloor(FloorRequest(building.id, "L1", 1)).id
  room = workplace.createSpace(SpaceRequest(floorId = floor, type = SpaceType.CONFERENCE_ROOM, name = "Board Room", code = "CR1", bookable = true, capacity = 8)).id
 }
 @AfterEach fun clear() = SecurityContextHolder.clearContext()

 private fun req(start: String, end: String, participants: Int = 4, date: LocalDate = LocalDate.now(clock), dates: List<LocalDate> = emptyList()) =
  RoomBookingRequest(spaceId = room, organizerStaffId = staffId, bookingDate = date, startTime = LocalTime.parse(start), endTime = LocalTime.parse(end), participants = participants, dates = dates)

 @Test fun `books a bookable room and lists it in the agenda`() {
  val b = service.book(req("09:00", "10:00")).single()
  assertThat(b.status).isEqualTo(BookingStatus.SCHEDULED)
  val today = LocalDate.now(clock)
  assertThat(service.agenda(floor, today, today).map { it.id }).contains(b.id)
 }

 @Test fun `a non-bookable space cannot be booked`() {
  val store = workplace.createSpace(SpaceRequest(floorId = floor, type = SpaceType.STORAGE, name = "Store", code = "ST1", bookable = false)).id
  assertThatThrownBy { service.book(req("09:00", "10:00").copy(spaceId = store)) }.isInstanceOf(ConflictException::class.java)
 }

 @Test fun `participants beyond capacity are rejected`() {
  assertThatThrownBy { service.book(req("09:00", "10:00", participants = 20)) }.isInstanceOf(BadRequestException::class.java)
 }

 @Test fun `rejects an overlapping booking but allows an adjacent one`() {
  service.book(req("09:00", "11:00"))
  assertThatThrownBy { service.book(req("10:00", "12:00")) }.isInstanceOf(ConflictException::class.java)
  assertThat(service.book(req("11:00", "12:00")).single().status).isEqualTo(BookingStatus.SCHEDULED)
 }

 @Test fun `check-in then check-out completes the booking`() {
  val b = service.book(req("09:00", "10:00")).single()
  assertThat(service.checkIn(b.id).status).isEqualTo(BookingStatus.CHECKED_IN)
  assertThat(service.checkOut(b.id).status).isEqualTo(BookingStatus.COMPLETED)
 }

 @Test fun `cancelling keeps organizer history`() {
  val b = service.book(req("09:00", "10:00")).single()
  service.cancel(b.id, CancelRoomBookingRequest("Meeting moved", b.version))
  assertThat(service.history(staffId).single().status).isEqualTo(BookingStatus.CANCELLED)
 }

 @Test fun `a recurring series books each date and the leaver hook cancels the future ones`() {
  val nextWeek = LocalDate.now(clock).plusDays(7)
  val series = service.book(req("09:00", "10:00", dates = listOf(nextWeek)))
  assertThat(series).hasSize(2)
  assertThat(service.cancelUpcomingForStaff(staffId, LocalDate.now(clock), "Organiser left")).isEqualTo(2)
 }

 @Test fun `agenda rejects an inverted range`() {
  val today = LocalDate.now(clock)
  assertThatThrownBy { service.agenda(floor, today, today.minusDays(1)) }.isInstanceOf(BadRequestException::class.java)
 }

 private fun auth(u: User) {
  val p = UserPrincipal(u.id!!, u.username, u.role, u.companyId)
  SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(p, null, listOf(SimpleGrantedAuthority(p.authority)))
 }
}
