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
 * Meeting-room booking for bookable spaces. Same concurrency guarantee as desk
 * booking: [book] pessimistically locks the space row before checking for a
 * clash and inserting, so two simultaneous requests cannot double-book a room.
 * Capacity and (optionally) equipment are validated against the space.
 */
@Service
class RoomBookingService(
    private val spaces: WorkplaceSpaceRepository,
    private val spaceLock: WorkplaceSpaceLockRepository,
    private val bookings: RoomBookingRepository,
    private val staff: StaffRepository,
    private val users: UserRepository,
    private val audit: AuditTrailService,
    private val notifications: NotificationDeliveryService,
    private val calendar: CalendarSync,
    private val clock: Clock,
    @Value("\${oms.workplace.booking.max-hours:12}") private val maxHours: Long,
    @Value("\${oms.workplace.booking.advance-days:60}") private val advanceDays: Long,
    @Value("\${oms.workplace.booking.no-show-grace-minutes:30}") private val noShowGraceMinutes: Long
) {
    private val holding = listOf(BookingStatus.SCHEDULED, BookingStatus.CHECKED_IN)

    @Transactional
    fun book(r: RoomBookingRequest): List<RoomBookingResponse> {
        val space = spaceLock.lockForBooking(r.spaceId) ?: throw ResourceNotFoundException("Space", r.spaceId)
        val companyId = space.floor.building.office.company.id!!
        scope(companyId)
        if (space.status != EntityStatus.ACTIVE) throw ConflictException("Space ${space.code} is not active")
        if (!space.bookable) throw ConflictException("Space ${space.name} is not bookable")
        val organizer = staff.findById(r.organizerStaffId).orElseThrow { ResourceNotFoundException("Staff", r.organizerStaffId) }
        if (organizer.isDeleted || organizer.status != EntityStatus.ACTIVE) throw BadRequestException("Only active staff can organise a booking")
        if (organizer.company.id != companyId) throw BadRequestException("Organizer and room must belong to the same company")
        if (!r.startTime.isBefore(r.endTime)) throw BadRequestException("Booking end time must be after its start time")
        if (Duration.between(r.startTime, r.endTime).toMinutes() > maxHours * 60) throw BadRequestException("Booking exceeds the maximum duration of $maxHours hours")
        space.capacity?.let { cap -> if (r.participants > cap) throw BadRequestException("Room ${space.name} holds $cap; ${r.participants} requested") }
        val zoneId = validZone(r.timeZone ?: space.floor.building.office.timeZone)
        val today = LocalDate.now(clock)
        val dates = (listOf(r.bookingDate) + r.dates).distinct().sorted()
        val created = dates.map { date ->
            if (date.isBefore(today)) throw BadRequestException("Cannot book a date in the past ($date)")
            if (date.isAfter(today.plusDays(advanceDays))) throw BadRequestException("Booking $date is beyond the $advanceDays-day advance limit")
            if (bookings.overlapping(space.id!!, date, r.startTime, r.endTime, holding).isNotEmpty())
                throw ConflictException("Room ${space.name} is already booked for that time on $date")
            bookings.save(RoomBooking(space, organizer, date, r.startTime, r.endTime, zoneId, r.participants, r.equipmentRequired?.trim(), r.title?.trim()))
        }
        created.forEach {
            record(companyId, "RoomBooking", it.id, AuditAction.CREATE, null, "space=${space.id},organizer=${organizer.id},date=${it.bookingDate},${it.startTime}-${it.endTime}")
            calendar.onBooked(it)
        }
        notify(NotificationType.ROOM_BOOKING_CONFIRMED, "${organizer.name} booked ${space.name} on ${r.bookingDate}", roomLink(space), created.first().id)
        return created.map(::response)
    }

    @Transactional
    fun checkIn(id: Long): RoomBookingResponse {
        val b = owned(id)
        if (b.status != BookingStatus.SCHEDULED) throw ConflictException("Only a scheduled booking can be checked in")
        b.status = BookingStatus.CHECKED_IN; b.checkInTime = LocalDateTime.now(clock); bookings.save(b)
        record(company(b), "RoomBooking", b.id, AuditAction.UPDATE, "SCHEDULED", "CHECKED_IN")
        return response(b)
    }

    @Transactional
    fun checkOut(id: Long): RoomBookingResponse {
        val b = owned(id)
        if (b.status != BookingStatus.CHECKED_IN) throw ConflictException("Only a checked-in booking can be checked out")
        b.status = BookingStatus.COMPLETED; b.checkOutTime = LocalDateTime.now(clock); bookings.save(b)
        record(company(b), "RoomBooking", b.id, AuditAction.UPDATE, "CHECKED_IN", "COMPLETED")
        return response(b)
    }

    @Transactional
    fun cancel(id: Long, r: CancelRoomBookingRequest): RoomBookingResponse {
        val b = owned(id)
        if (b.version != r.version) throw ConflictException("Booking changed since it was opened. Reload and try again")
        if (!b.holdsRoom) throw ConflictException("Only a scheduled or checked-in booking can be cancelled")
        b.status = BookingStatus.CANCELLED; b.cancellationReason = r.reason?.trim(); bookings.save(b)
        record(company(b), "RoomBooking", b.id, AuditAction.UPDATE, "held", "CANCELLED:${b.cancellationReason}")
        notify(NotificationType.ROOM_BOOKING_CANCELLED, "${b.space.name} booking for ${b.organizer.name} was cancelled", roomLink(b.space), b.id)
        calendar.onCancelled(b)
        return response(b)
    }

    fun history(staffId: Long): List<RoomBookingResponse> =
        bookings.historyForOrganizer(staffId).filter { companyAllowed(company(it)) }.map(::response)

    /** Calendar / agenda view for a floor across a date range. */
    fun agenda(floorId: Long, from: LocalDate, to: LocalDate): List<RoomBookingResponse> {
        if (to.isBefore(from)) throw BadRequestException("Agenda end date cannot precede its start date")
        if (from.plusDays(366).isBefore(to)) throw BadRequestException("Agenda range cannot exceed a year")
        return bookings.agendaForFloor(floorId, from, to).filter { companyAllowed(company(it)) }.map(::response)
    }

    /** Cancels an organizer's future room bookings, e.g. when they leave. Returns the number cancelled. */
    @Transactional
    fun cancelUpcomingForStaff(staffId: Long, from: LocalDate, reason: String): Int {
        val upcoming = bookings.upcomingForOrganizer(staffId, from, holding)
        upcoming.forEach {
            it.status = BookingStatus.CANCELLED; it.cancellationReason = reason; bookings.save(it)
            record(company(it), "RoomBooking", it.id, AuditAction.UPDATE, "held", "CANCELLED:$reason")
            calendar.onCancelled(it)
        }
        return upcoming.size
    }

    @Scheduled(cron = "\${oms.workplace.booking.no-show-cron:0 */10 * * * *}")
    @Transactional
    fun releaseNoShows() {
        val now = LocalDateTime.now(clock)
        bookings.scheduledStartedBy(now.toLocalDate(), now.toLocalTime())
            .filter { b -> b.bookingDate.isBefore(now.toLocalDate()) || !b.bookingDate.atTime(b.startTime).plusMinutes(noShowGraceMinutes).isAfter(now) }
            .forEach { b ->
                b.status = BookingStatus.NO_SHOW; bookings.save(b)
                record(company(b), "RoomBooking", b.id, AuditAction.UPDATE, "SCHEDULED", "NO_SHOW")
                notify(NotificationType.ROOM_BOOKING_CHANGED, "${b.space.name} released — ${b.organizer.name} did not check in", roomLink(b.space), b.id)
            }
    }

    // ---- helpers -------------------------------------------------------------------------------
    private fun owned(id: Long) = bookings.findById(id).filter { !it.isDeleted }
        .orElseThrow { ResourceNotFoundException("Room booking", id) }.also { scope(company(it)) }
    private fun company(b: RoomBooking) = b.space.floor.building.office.company.id!!
    private fun scope(companyId: Long) { val p = SecurityUtils.currentPrincipal(); if (p.role != Role.SUPER_ADMIN && p.companyId != companyId) throw ForbiddenException() }
    private fun companyAllowed(id: Long) = SecurityUtils.currentPrincipal().let { it.role == Role.SUPER_ADMIN || it.companyId == id }
    private fun actor(): User = users.findById(SecurityUtils.currentUserId()).orElseThrow { ResourceNotFoundException("Authenticated user") }
    private fun record(companyId: Long, type: String, id: Long?, action: AuditAction, before: String?, after: String?) =
        audit.record(actor(), action, type, id, companyId, type, before, after)
    private fun notify(type: NotificationType, message: String, link: String, id: Long?) =
        notifications.deliver(actor(), type, message, link, "Workplace", id)
    private fun roomLink(s: WorkplaceSpace) = "/workplaces/floors/${s.floor.id}/map?spaceId=${s.id}"
    private fun validZone(v: String) = runCatching { ZoneId.of(v.trim()).id }.getOrElse { throw BadRequestException("Invalid time zone") }
        .let { if (it == "Asia/Calcutta") "Asia/Kolkata" else it }

    private fun response(b: RoomBooking) = RoomBookingResponse(
        b.id!!, b.version, b.space.id!!, b.space.code, b.space.name, b.space.type,
        b.space.floor.id!!, b.space.floor.name, b.space.floor.building.name, b.space.floor.building.office.name,
        b.organizer.id!!, b.organizer.name, b.participants, b.equipmentRequired, b.title,
        b.bookingDate, b.startTime, b.endTime, b.timeZone, b.status, b.checkInTime, b.checkOutTime, b.cancellationReason
    )
}
