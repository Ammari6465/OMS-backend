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
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.*

/** Proves the room pessimistic lock serialises two simultaneous bookings; only one wins. */
@SpringBootTest @ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RoomBookingConcurrencyTest {
 @Autowired lateinit var service: RoomBookingService
 @Autowired lateinit var workplace: WorkplaceService
 @Autowired lateinit var org: OrganizationService
 @Autowired lateinit var staffService: StaffService
 @Autowired lateinit var users: UserRepository
 @Autowired lateinit var bookings: RoomBookingRepository
 @Autowired lateinit var clock: Clock

 @Test fun `two simultaneous room bookings for the same slot cannot both succeed`() {
  val n = System.nanoTime()
  val admin = users.save(User("rcc-$n", "rcc-$n@example.com", "hash", Role.SUPER_ADMIN, "RCC Admin"))
  val principal = UserPrincipal(admin.id!!, admin.username, admin.role, admin.companyId)
  authenticate(principal)
  val company = org.createCompany(CompanyRequest(name = "RCC $n")).id
  val s1 = staffService.create(StaffCreateRequest(companyId = company, employeeCode = "RC1-$n", name = "P1", dateJoined = LocalDate.now(clock).minusYears(1))).id
  val s2 = staffService.create(StaffCreateRequest(companyId = company, employeeCode = "RC2-$n", name = "P2", dateJoined = LocalDate.now(clock).minusYears(1))).id
  val office = workplace.createOffice(OfficeRequest(company, "HQ", "HQ-$n", timeZone = "Asia/Kolkata"))
  val building = workplace.createBuilding(BuildingRequest(office.id, "T", "T-$n"))
  val floor = workplace.createFloor(FloorRequest(building.id, "L1", 1)).id
  val room = workplace.createSpace(SpaceRequest(floorId = floor, type = SpaceType.MEETING_ROOM, name = "Huddle", code = "MR-$n", bookable = true, capacity = 6)).id
  SecurityContextHolder.clearContext()

  val date = LocalDate.now(clock); val start = LocalTime.of(9, 0); val end = LocalTime.of(10, 0)
  val ready = CountDownLatch(2); val go = CountDownLatch(1)
  val pool = Executors.newFixedThreadPool(2)
  fun task(staffId: Long) = Callable {
   authenticate(principal)
   try { ready.countDown(); go.await(5, TimeUnit.SECONDS); service.book(RoomBookingRequest(spaceId = room, organizerStaffId = staffId, bookingDate = date, startTime = start, endTime = end, participants = 2)); "OK" }
   catch (e: Exception) { e.javaClass.simpleName } finally { SecurityContextHolder.clearContext() }
  }
  val f1 = pool.submit(task(s1)); val f2 = pool.submit(task(s2))
  ready.await(5, TimeUnit.SECONDS); go.countDown()
  val r1 = f1.get(20, TimeUnit.SECONDS); val r2 = f2.get(20, TimeUnit.SECONDS)
  pool.shutdown()

  authenticate(principal)
  try {
   assertThat(bookings.overlapping(room, date, start, end, listOf(BookingStatus.SCHEDULED, BookingStatus.CHECKED_IN))).hasSize(1)
   assertThat(listOf(r1, r2)).containsExactlyInAnyOrder("OK", "ConflictException")
   bookings.deleteAll(bookings.findAll().filter { it.space.id == room })
  } finally { SecurityContextHolder.clearContext() }
 }

 private fun authenticate(p: UserPrincipal) {
  SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(p, null, listOf(SimpleGrantedAuthority(p.authority)))
 }
}
