package com.sunrich.oms.workplace

import com.sunrich.oms.common.enums.*
import com.sunrich.oms.exception.*
import com.sunrich.oms.organization.StaffRepository
import com.sunrich.oms.security.SecurityUtils
import com.sunrich.oms.systemdata.AuditTrailService
import com.sunrich.oms.systemdata.NotificationDeliveryService
import com.sunrich.oms.user.User
import com.sunrich.oms.user.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.*

/**
 * Desk reservation and check-in. Gives the RESERVABLE / DROP_IN desk modes and
 * the RESERVED / CHECKED_IN availability states a real workflow:
 * book -> check in -> check out, plus cancel and automatic no-show release.
 *
 * Concurrency: [book] takes a pessimistic write lock on the desk row
 * ([DeskRepository.lockForBooking]) before it checks for a clashing booking and
 * inserts, so two simultaneous requests cannot both reserve the same slot — the
 * second blocks on the lock, then sees the first booking and is rejected. This
 * is a real database lock, not an application-level check-then-insert.
 */
@Service
class BookingService(
    private val desks: DeskRepository,
    private val bookings: DeskBookingRepository,
    private val assignments: DeskAssignmentRepository,
    private val staff: StaffRepository,
    private val users: UserRepository,
    private val audit: AuditTrailService,
    private val notifications: NotificationDeliveryService,
    private val clock: Clock,
    @Value("\${oms.workplace.booking.max-hours:12}") private val maxHours: Long,
    @Value("\${oms.workplace.booking.advance-days:60}") private val advanceDays: Long,
    @Value("\${oms.workplace.booking.no-show-grace-minutes:30}") private val noShowGraceMinutes: Long
) {
    private val holding = listOf(BookingStatus.SCHEDULED, BookingStatus.CHECKED_IN)

    /**
     * Books a reservable desk for one date, or for a series when [BookingRequest.dates]
     * is set. All dates in a series are validated and inserted in one transaction,
     * so a clash on any date rejects the whole request rather than leaving gaps.
     */
    @Transactional
    fun book(r: BookingRequest): List<BookingResponse> {
        val desk = desks.lockForBooking(r.deskId) ?: throw ResourceNotFoundException("Desk", r.deskId)
        val companyId = desk.floor.building.office.company.id!!
        scope(companyId)
        if (desk.status != EntityStatus.ACTIVE) throw ConflictException("Desk ${desk.code} is not active")
        if (desk.mode != DeskMode.RESERVABLE && desk.mode != DeskMode.DROP_IN)
            throw ConflictException("Desk ${desk.code} is not reservable; only RESERVABLE or DROP_IN desks can be booked")
        val person = staff.findById(r.staffId).orElseThrow { ResourceNotFoundException("Staff", r.staffId) }
        if (person.isDeleted || person.status != EntityStatus.ACTIVE) throw BadRequestException("Only active staff can hold a booking")
        if (person.company.id != companyId) throw BadRequestException("Staff and desk must belong to the same company")
        validateSlot(r)
        val zoneId = validZone(r.timeZone ?: desk.floor.building.office.timeZone)
        val today = LocalDate.now(clock)
        val dates = (listOf(r.bookingDate) + r.dates).distinct().sorted()
        val created = dates.map { date ->
            if (date.isBefore(today)) throw BadRequestException("Cannot book a date in the past ($date)")
            if (date.isAfter(today.plusDays(advanceDays))) throw BadRequestException("Booking $date is beyond the $advanceDays-day advance limit")
            if (bookings.overlapping(desk.id!!, date, r.startTime, r.endTime, holding).isNotEmpty())
                throw ConflictException("Desk ${desk.code} is already booked for that time on $date")
            if (assignments.activeForDesk(desk.id!!, date).isNotEmpty())
                throw ConflictException("Desk ${desk.code} is permanently assigned on $date and cannot be booked")
            bookings.save(DeskBooking(desk, person, date, r.startTime, r.endTime, zoneId, r.bookingType))
        }
        created.forEach {
            record(companyId, "DeskBooking", it.id, AuditAction.CREATE, null, "desk=${desk.id},staff=${person.id},date=${it.bookingDate},${it.startTime}-${it.endTime}")
        }
        notify(NotificationType.DESK_BOOKING_CONFIRMED, "${person.name} booked desk ${desk.code} on ${r.bookingDate}", deskLink(desk), created.first().id)
        syncDeskAvailability(desk)
        return created.map(::response)
    }

    @Transactional
    fun checkIn(id: Long): BookingResponse {
        val b = owned(id)
        if (b.status != BookingStatus.SCHEDULED) throw ConflictException("Only a scheduled booking can be checked in")
        b.status = BookingStatus.CHECKED_IN; b.checkInTime = LocalDateTime.now(clock); bookings.save(b)
        record(company(b), "DeskBooking", b.id, AuditAction.UPDATE, "SCHEDULED", "CHECKED_IN")
        notify(NotificationType.DESK_BOOKING_CHECKED_IN, "${b.staff.name} checked in at desk ${b.desk.code}", deskLink(b.desk), b.id)
        syncDeskAvailability(b.desk)
        return response(b)
    }

    /** QR check-in resolves the booking the QR encodes, then follows the same path. */
    @Transactional
    fun checkInByQr(bookingId: Long) = checkIn(bookingId)

    @Transactional
    fun checkOut(id: Long): BookingResponse {
        val b = owned(id)
        if (b.status != BookingStatus.CHECKED_IN) throw ConflictException("Only a checked-in booking can be checked out")
        b.status = BookingStatus.COMPLETED; b.checkOutTime = LocalDateTime.now(clock); bookings.save(b)
        record(company(b), "DeskBooking", b.id, AuditAction.UPDATE, "CHECKED_IN", "COMPLETED")
        syncDeskAvailability(b.desk)
        return response(b)
    }

    @Transactional
    fun cancel(id: Long, r: CancelBookingRequest): BookingResponse {
        val b = owned(id)
        if (b.version != r.version) throw ConflictException("Booking changed since it was opened. Reload and try again")
        if (!b.holdsDesk) throw ConflictException("Only a scheduled or checked-in booking can be cancelled")
        b.status = BookingStatus.CANCELLED; b.cancellationReason = r.reason?.trim(); bookings.save(b)
        record(company(b), "DeskBooking", b.id, AuditAction.UPDATE, "held", "CANCELLED:${b.cancellationReason}")
        notify(NotificationType.DESK_BOOKING_CANCELLED, "Desk ${b.desk.code} booking for ${b.staff.name} was cancelled", deskLink(b.desk), b.id)
        syncDeskAvailability(b.desk)
        return response(b)
    }

    fun history(staffId: Long): List<BookingResponse> =
        bookings.historyForStaff(staffId).filter { companyAllowed(company(it)) }.map(::response)

    /**
     * Cancels a staff member's future desk bookings, e.g. when they leave.
     * Returns the number cancelled so the lifecycle can record it.
     */
    @Transactional
    fun cancelUpcomingForStaff(staffId: Long, from: LocalDate, reason: String): Int {
        val upcoming = bookings.upcomingForStaff(staffId, from, holding)
        upcoming.forEach {
            it.status = BookingStatus.CANCELLED; it.cancellationReason = reason
            bookings.save(it)
            record(company(it), "DeskBooking", it.id, AuditAction.UPDATE, "held", "CANCELLED:$reason")
            syncDeskAvailability(it.desk)
        }
        return upcoming.size
    }

    /**
     * Releases desks whose scheduled booking start (plus a grace period) has
     * passed with no check-in. Runs on a schedule; safe to run repeatedly.
     */
    @Scheduled(cron = "\${oms.workplace.booking.no-show-cron:0 */10 * * * *}")
    @Transactional
    fun releaseNoShows() {
        val now = LocalDateTime.now(clock)
        bookings.scheduledStartedBy(now.toLocalDate(), now.toLocalTime())
            .filter { b -> b.bookingDate.isBefore(now.toLocalDate()) || !b.bookingDate.atTime(b.startTime).plusMinutes(noShowGraceMinutes).isAfter(now) }
            .forEach { b ->
                b.status = BookingStatus.NO_SHOW; bookings.save(b)
                record(company(b), "DeskBooking", b.id, AuditAction.UPDATE, "SCHEDULED", "NO_SHOW")
                notify(NotificationType.DESK_BOOKING_NO_SHOW, "Desk ${b.desk.code} released — ${b.staff.name} did not check in", deskLink(b.desk), b.id)
                syncDeskAvailability(b.desk)
            }
    }

    // ---- helpers -------------------------------------------------------------------------------
    private fun validateSlot(r: BookingRequest) {
        if (!r.startTime.isBefore(r.endTime)) throw BadRequestException("Booking end time must be after its start time")
        if (Duration.between(r.startTime, r.endTime).toMinutes() > maxHours * 60)
            throw BadRequestException("Booking exceeds the maximum duration of $maxHours hours")
    }

    /** Reflects today's booking state on the desk's stored availability column, for non-map reads. */
    private fun syncDeskAvailability(d: Desk) {
        if (d.mode == DeskMode.UNAVAILABLE) { d.availability = DeskAvailability.UNAVAILABLE; desks.save(d); return }
        val today = LocalDate.now(clock)
        val holdingToday = bookings.holdingForDesks(listOf(d.id!!), today, holding)
        d.availability = when {
            assignments.activeForDesk(d.id!!, today).isNotEmpty() -> DeskAvailability.ASSIGNED
            holdingToday.any { it.status == BookingStatus.CHECKED_IN } -> DeskAvailability.CHECKED_IN
            holdingToday.isNotEmpty() -> DeskAvailability.RESERVED
            else -> DeskAvailability.AVAILABLE
        }
        desks.save(d)
    }

    private fun owned(id: Long) = bookings.findById(id).filter { !it.isDeleted }
        .orElseThrow { ResourceNotFoundException("Desk booking", id) }.also { scope(company(it)) }
    private fun company(b: DeskBooking) = b.desk.floor.building.office.company.id!!
    private fun scope(companyId: Long) { val p = SecurityUtils.currentPrincipal(); if (p.role != Role.SUPER_ADMIN && p.companyId != companyId) throw ForbiddenException() }
    private fun companyAllowed(id: Long) = SecurityUtils.currentPrincipal().let { it.role == Role.SUPER_ADMIN || it.companyId == id }
    private fun actor(): User = users.findById(SecurityUtils.currentUserId()).orElseThrow { ResourceNotFoundException("Authenticated user") }
    private fun record(companyId: Long, type: String, id: Long?, action: AuditAction, before: String?, after: String?) =
        audit.record(actor(), action, type, id, companyId, type, before, after)
    private fun notify(type: NotificationType, message: String, link: String, id: Long?) =
        notifications.deliver(actor(), type, message, link, "Workplace", id)
    private fun deskLink(d: Desk) = "/workplaces/floors/${d.floor.id}/map?deskId=${d.id}"
    private fun validZone(v: String) = runCatching { ZoneId.of(v.trim()).id }.getOrElse { throw BadRequestException("Invalid time zone") }
        .let { if (it == "Asia/Calcutta") "Asia/Kolkata" else it }

    private fun response(b: DeskBooking) = BookingResponse(
        b.id!!, b.version, b.desk.id!!, b.desk.code, b.desk.floor.id!!, b.desk.floor.name,
        b.desk.floor.building.name, b.desk.floor.building.office.name,
        b.staff.id!!, b.staff.name, b.staff.employeeCode,
        b.bookingDate, b.startTime, b.endTime, b.timeZone, b.bookingType, b.status,
        b.checkInTime, b.checkOutTime, b.cancellationReason
    )
}
