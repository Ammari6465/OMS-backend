package com.sunrich.oms.workplace

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.time.LocalTime

interface DeskBookingRepository : JpaRepository<DeskBooking, Long> {
    /** Bookings that hold [deskId] on [date] overlapping [start,end). Overlap is start<end AND end>start. */
    @Query(
        "select b from DeskBooking b where b.desk.id=:deskId and b.bookingDate=:date and b.status in :active " +
            "and b.startTime < :end and b.endTime > :start and b.isDeleted=false"
    )
    fun overlapping(
        @Param("deskId") deskId: Long, @Param("date") date: LocalDate,
        @Param("start") start: LocalTime, @Param("end") end: LocalTime, @Param("active") active: Collection<BookingStatus>
    ): List<DeskBooking>

    /** Full booking history for a staff member, newest first, with the desk hierarchy fetched. */
    @Query(
        "select b from DeskBooking b join fetch b.desk d join fetch d.floor f join fetch f.building bu join fetch bu.office o " +
            "join fetch b.staff where b.staff.id=:staffId and b.isDeleted=false order by b.bookingDate desc, b.startTime desc"
    )
    fun historyForStaff(@Param("staffId") staffId: Long): List<DeskBooking>

    /** Holding bookings across a set of desks on a date, for availability derivation. */
    @Query(
        "select b from DeskBooking b join fetch b.staff where b.desk.id in :deskIds and b.bookingDate=:date " +
            "and b.status in :active and b.isDeleted=false"
    )
    fun holdingForDesks(
        @Param("deskIds") deskIds: Collection<Long>, @Param("date") date: LocalDate, @Param("active") active: Collection<BookingStatus>
    ): List<DeskBooking>

    /** SCHEDULED bookings whose start time has already passed, i.e. no-show candidates. */
    @Query(
        "select b from DeskBooking b join fetch b.desk d join fetch d.floor f join fetch b.staff where b.status=com.sunrich.oms.workplace.BookingStatus.SCHEDULED " +
            "and b.isDeleted=false and (b.bookingDate < :date or (b.bookingDate = :date and b.startTime <= :time))"
    )
    fun scheduledStartedBy(@Param("date") date: LocalDate, @Param("time") time: LocalTime): List<DeskBooking>

    /** Future or same-day holding bookings owned by a staff member, for lifecycle cancellation. */
    @Query(
        "select b from DeskBooking b join fetch b.desk d join fetch d.floor f join fetch b.staff where b.staff.id=:staffId " +
            "and b.status in :active and b.isDeleted=false and b.bookingDate >= :date"
    )
    fun upcomingForStaff(
        @Param("staffId") staffId: Long, @Param("date") date: LocalDate, @Param("active") active: Collection<BookingStatus>
    ): List<DeskBooking>

    fun countByStaff_IdAndBookingDateAndStatusInAndIsDeletedFalse(staffId: Long, date: LocalDate, statuses: Collection<BookingStatus>): Long
}
