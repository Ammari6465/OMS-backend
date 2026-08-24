package com.sunrich.oms.workplace

import com.sunrich.oms.common.enums.*
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
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.*

/**
 * Not @Transactional: the two booking attempts must run in their own committed
 * transactions on separate threads for the pessimistic desk lock to serialise
 * them. If the lock did nothing, both would insert and the assertion would fail.
 */
@SpringBootTest @ActiveProfiles("test")
class BookingConcurrencyTest {
 @Autowired lateinit var service: BookingService
 @Autowired lateinit var workplace: WorkplaceService
 @Autowired lateinit var org: OrganizationService
 @Autowired lateinit var staffService: StaffService
 @Autowired lateinit var users: UserRepository
 @Autowired lateinit var bookings: DeskBookingRepository
 @Autowired lateinit var clock: Clock

 @Test fun `two simultaneous bookings for the same slot cannot both succeed`() {
  val n = System.nanoTime()
  val admin = users.save(User("cc-$n", "cc-$n@example.com", "hash", Role.SUPER_ADMIN, "CC Admin"))
  val principal = UserPrincipal(admin.id!!, admin.username, admin.role, admin.companyId)
  authenticate(principal)
  val company = org.createCompany(CompanyRequest(name = "CC $n")).id
  val s1 = staffService.create(StaffCreateRequest(companyId = company, employeeCode = "C1-$n", name = "P1", dateJoined = LocalDate.now(clock).minusYears(1))).id
  val s2 = staffService.create(StaffCreateRequest(companyId = company, employeeCode = "C2-$n", name = "P2", dateJoined = LocalDate.now(clock).minusYears(1))).id
  val office = workplace.createOffice(OfficeRequest(company, "HQ", "HQ-$n", timeZone = "Asia/Kolkata"))
  val building = workplace.createBuilding(BuildingRequest(office.id, "T", "T-$n"))
  val floor = workplace.createFloor(FloorRequest(building.id, "L1", 1)).id
  val desk = workplace.createDesk(DeskRequest(floor, code = "RC-$n", x = BigDecimal(10), y = BigDecimal(10), width = BigDecimal(4), height = BigDecimal(3), mode = DeskMode.RESERVABLE)).id
  SecurityContextHolder.clearContext()

  val date = LocalDate.now(clock); val start = LocalTime.of(9, 0); val end = LocalTime.of(17, 0)
  val ready = CountDownLatch(2); val go = CountDownLatch(1)
  val pool = Executors.newFixedThreadPool(2)
  fun task(staffId: Long) = Callable {
   authenticate(principal)
   try {
    ready.countDown(); go.await(5, TimeUnit.SECONDS)
    service.book(BookingRequest(deskId = desk, staffId = staffId, bookingDate = date, startTime = start, endTime = end))
    "OK"
   } catch (e: Exception) { e.javaClass.simpleName } finally { SecurityContextHolder.clearContext() }
  }
  val f1 = pool.submit(task(s1)); val f2 = pool.submit(task(s2))
  ready.await(5, TimeUnit.SECONDS); go.countDown()
  val r1 = f1.get(20, TimeUnit.SECONDS); val r2 = f2.get(20, TimeUnit.SECONDS)
  pool.shutdown()

  authenticate(principal)
  try {
   val held = bookings.overlapping(desk, date, start, end, listOf(BookingStatus.SCHEDULED, BookingStatus.CHECKED_IN))
   assertThat(held).hasSize(1)
   assertThat(listOf(r1, r2)).containsExactlyInAnyOrder("OK", "ConflictException")
   bookings.deleteAll(bookings.findAll().filter { it.desk.id == desk })
  } finally { SecurityContextHolder.clearContext() }
 }

 private fun authenticate(p: UserPrincipal) {
  SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(p, null, listOf(SimpleGrantedAuthority(p.authority)))
 }
}
