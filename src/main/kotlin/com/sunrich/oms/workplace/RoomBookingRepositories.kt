package com.sunrich.oms.workplace

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.time.LocalTime

interface RoomBookingRepository : JpaRepository<RoomBooking, Long> {
    @Query(
        "select b from RoomBooking b where b.space.id=:spaceId and b.bookingDate=:date and b.status in :active " +
            "and b.startTime < :end and b.endTime > :start and b.isDeleted=false"
    )
    fun overlapping(
        @Param("spaceId") spaceId: Long, @Param("date") date: LocalDate,
        @Param("start") start: LocalTime, @Param("end") end: LocalTime, @Param("active") active: Collection<BookingStatus>
    ): List<RoomBooking>

    @Query(
        "select b from RoomBooking b join fetch b.space s join fetch s.floor f join fetch f.building bu join fetch bu.office o " +
            "join fetch b.organizer where b.organizer.id=:staffId and b.isDeleted=false order by b.bookingDate desc, b.startTime desc"
    )
    fun historyForOrganizer(@Param("staffId") staffId: Long): List<RoomBooking>

    /** Agenda for a floor across a date range: every non-cancelled booking, ordered for a calendar view. */
    @Query(
        "select b from RoomBooking b join fetch b.space s join fetch s.floor f join fetch f.building bu join fetch bu.office o " +
            "join fetch b.organizer where s.floor.id=:floorId and b.isDeleted=false and b.status <> com.sunrich.oms.workplace.BookingStatus.CANCELLED " +
            "and b.bookingDate between :from and :to order by b.bookingDate, b.startTime"
    )
    fun agendaForFloor(@Param("floorId") floorId: Long, @Param("from") from: LocalDate, @Param("to") to: LocalDate): List<RoomBooking>

    @Query(
        "select b from RoomBooking b join fetch b.space s join fetch s.floor f join fetch b.organizer where b.status=com.sunrich.oms.workplace.BookingStatus.SCHEDULED " +
            "and b.isDeleted=false and (b.bookingDate < :date or (b.bookingDate = :date and b.startTime <= :time))"
    )
    fun scheduledStartedBy(@Param("date") date: LocalDate, @Param("time") time: LocalTime): List<RoomBooking>

    @Query(
        "select b from RoomBooking b join fetch b.space s join fetch s.floor f join fetch b.organizer where b.organizer.id=:staffId " +
            "and b.status in :active and b.isDeleted=false and b.bookingDate >= :date"
    )
    fun upcomingForOrganizer(
        @Param("staffId") staffId: Long, @Param("date") date: LocalDate, @Param("active") active: Collection<BookingStatus>
    ): List<RoomBooking>
}

/** Separate, minimal repository so the space pessimistic lock lives in Phase-4 files and does not touch the concurrently-edited WorkplaceRepositories. */
interface WorkplaceSpaceLockRepository : Repository<WorkplaceSpace, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from WorkplaceSpace s where s.id=:id and s.isDeleted=false")
    fun lockForBooking(@Param("id") id: Long): WorkplaceSpace?
}
