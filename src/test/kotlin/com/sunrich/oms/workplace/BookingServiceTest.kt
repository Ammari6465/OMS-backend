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
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

@SpringBootTest @ActiveProfiles("test") @Transactional
class BookingServiceTest {
 @Autowired lateinit var service: BookingService
 @Autowired lateinit var workplace: WorkplaceService
 @Autowired lateinit var org: OrganizationService
 @Autowired lateinit var staffService: StaffService
 @Autowired lateinit var users: UserRepository
 @Autowired lateinit var bookings: DeskBookingRepository
 @Autowired lateinit var clock: Clock

 lateinit var admin: User
 var company = 0L; var staffId = 0L; var floor = 0L; var desk = 0L

 @BeforeEach fun setup() {
  val n = System.nanoTime()
  admin = users.save(User("bk-$n", "bk-$n@example.com", "hash", Role.SUPER_ADMIN, "Booking Admin"))
  auth(admin)
  company = org.createCompany(CompanyRequest(name = "Booking Co $n")).id
  staffId = staffService.create(StaffCreateRequest(companyId = company, employeeCode = "BK-$n", name = "Booker One", dateJoined = LocalDate.now(clock).minusYears(1))).id
  val office = workplace.createOffice(OfficeRequest(company, "HQ", "HQ", timeZone = "Asia/Kolkata"))
  val building = workplace.createBuilding(BuildingRequest(office.id, "Tower", "T"))
  floor = workplace.createFloor(FloorRequest(building.id, "L1", 1)).id
  desk = workplace.createDesk(DeskRequest(floor, code = "R-1", x = BigDecimal(10), y = BigDecimal(10), width = BigDecimal(4), height = BigDecimal(3), mode = DeskMode.RESERVABLE)).id
 }
 @AfterEach fun clear() = SecurityContextHolder.clearContext()

 private fun req(start: String, end: String, date: LocalDate = LocalDate.now(clock), dates: List<LocalDate> = emptyList()) =
  BookingRequest(deskId = desk, staffId = staffId, bookingDate = date, startTime = LocalTime.parse(start), endTime = LocalTime.parse(end), dates = dates)

 @Test fun `books a reservable desk and shows it reserved on the map`() {
  val booking = service.book(req("09:00", "17:00")).single()
  assertThat(booking.status).isEqualTo(BookingStatus.SCHEDULED)
  assertThat(booking.bookingType).isEqualTo(BookingType.RESERVATION)
  assertThat(workplace.map(floor).desks.first { it.id == desk }.availability).isEqualTo(DeskAvailability.RESERVED)
 }

 @Test fun `rejects an overlapping booking but allows an adjacent one`() {
  service.book(req("09:00", "12:00"))
  assertThatThrownBy { service.book(req("11:00", "13:00")) }.isInstanceOf(ConflictException::class.java)
  // Adjacent (starts exactly when the first ends) does not overlap.
  assertThat(service.book(req("12:00", "13:00")).single().status).isEqualTo(BookingStatus.SCHEDULED)
 }

 @Test fun `a non-reservable desk cannot be booked`() {
  val assigned = workplace.createDesk(DeskRequest(floor, code = "A-1", x = BigDecimal(20), y = BigDecimal(10), width = BigDecimal(4), height = BigDecimal(3), mode = DeskMode.ASSIGNED)).id
  assertThatThrownBy { service.book(req("09:00", "10:00").copy(deskId = assigned)) }.isInstanceOf(ConflictException::class.java)
 }

 @Test fun `check-in then check-out moves the booking through its lifecycle`() {
  val b = service.book(req("09:00", "17:00")).single()
  val checkedIn = service.checkIn(b.id)
  assertThat(checkedIn.status).isEqualTo(BookingStatus.CHECKED_IN)
  assertThat(checkedIn.checkInTime).isNotNull()
  assertThat(workplace.map(floor).desks.first { it.id == desk }.availability).isEqualTo(DeskAvailability.CHECKED_IN)
  val done = service.checkOut(b.id)
  assertThat(done.status).isEqualTo(BookingStatus.COMPLETED)
  assertThat(workplace.map(floor).desks.first { it.id == desk }.availability).isEqualTo(DeskAvailability.AVAILABLE)
 }

 @Test fun `cancelling frees the desk and keeps history`() {
  val b = service.book(req("09:00", "17:00")).single()
  service.cancel(b.id, CancelBookingRequest("Plans changed", b.version))
  assertThat(workplace.map(floor).desks.first { it.id == desk }.availability).isEqualTo(DeskAvailability.AVAILABLE)
  assertThat(service.history(staffId).single().status).isEqualTo(BookingStatus.CANCELLED)
 }

 @Test fun `enforces max duration, advance limit and past dates`() {
  assertThatThrownBy { service.book(req("06:00", "23:00").copy(endTime = LocalTime.of(23, 0), startTime = LocalTime.of(6, 0))) }
   .isInstanceOf(BadRequestException::class.java) // 17h > 12h max
  assertThatThrownBy { service.book(req("09:00", "10:00", LocalDate.now(clock).plusDays(400))) }.isInstanceOf(BadRequestException::class.java)
  assertThatThrownBy { service.book(req("09:00", "10:00", LocalDate.now(clock).minusDays(1))) }.isInstanceOf(BadRequestException::class.java)
  assertThatThrownBy { service.book(req("10:00", "09:00")) }.isInstanceOf(BadRequestException::class.java) // end before start
 }

 @Test fun `a scheduled booking whose start passed is released as a no-show`() {
  val b = service.book(req("09:00", "17:00")).single()
  // Simulate a booking whose date is already in the past, so no-show is deterministic.
  val entity = bookings.findById(b.id).orElseThrow()
  entity.bookingDate = LocalDate.now(clock).minusDays(1); bookings.save(entity)
  service.releaseNoShows()
  assertThat(bookings.findById(b.id).orElseThrow().status).isEqualTo(BookingStatus.NO_SHOW)
 }

 @Test fun `a recurring series books each date and the leaver hook cancels the future ones`() {
  val nextWeek = LocalDate.now(clock).plusDays(7)
  val series = service.book(req("09:00", "12:00", dates = listOf(nextWeek)))
  assertThat(series).hasSize(2)
  assertThat(series.map { it.bookingDate }).containsExactlyInAnyOrder(LocalDate.now(clock), nextWeek)
  val cancelled = service.cancelUpcomingForStaff(staffId, LocalDate.now(clock), "Staff exit")
  assertThat(cancelled).isEqualTo(2)
  assertThat(service.history(staffId)).allSatisfy { assertThat(it.status).isEqualTo(BookingStatus.CANCELLED) }
 }

 private fun auth(u: User) {
  val p = UserPrincipal(u.id!!, u.username, u.role, u.companyId)
  SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(p, null, listOf(SimpleGrantedAuthority(p.authority)))
 }
}
