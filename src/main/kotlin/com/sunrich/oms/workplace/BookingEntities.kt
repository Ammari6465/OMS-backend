package com.sunrich.oms.workplace

import com.sunrich.oms.common.entity.BaseEntity
import com.sunrich.oms.organization.Staff
import jakarta.persistence.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** How a desk was taken: a booked-ahead RESERVATION or a walk-up DROP_IN. */
enum class BookingType { RESERVATION, DROP_IN }

/**
 * The lifecycle of a desk booking.
 * SCHEDULED -> CHECKED_IN -> COMPLETED, or SCHEDULED -> CANCELLED / NO_SHOW.
 */
enum class BookingStatus { SCHEDULED, CHECKED_IN, COMPLETED, CANCELLED, NO_SHOW }

/**
 * One reservation of a reservable/drop-in desk for a date and time window.
 *
 * Distinct from a [DeskAssignment] (permanent seating): a booking is short-lived,
 * has a check-in/check-out cycle and can lapse to NO_SHOW. Concurrency is
 * enforced in [BookingService] by pessimistically locking the desk row before
 * the overlap check, so two simultaneous requests cannot both win the same slot.
 */
@Entity
@Table(
    name = "workplace_desk_bookings",
    indexes = [
        Index(name = "idx_desk_booking_desk_date", columnList = "desk_id,booking_date,status"),
        Index(name = "idx_desk_booking_staff_date", columnList = "staff_id,booking_date")
    ]
)
class DeskBooking(
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "desk_id", nullable = false) var desk: Desk,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "staff_id", nullable = false) var staff: Staff,
    @Column(name = "booking_date", nullable = false) var bookingDate: LocalDate,
    @Column(name = "start_time", nullable = false) var startTime: LocalTime,
    @Column(name = "end_time", nullable = false) var endTime: LocalTime,
    @Column(name = "time_zone", nullable = false, length = 60) var timeZone: String,
    @Enumerated(EnumType.STRING) @Column(name = "booking_type", nullable = false, length = 20) var bookingType: BookingType,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) var status: BookingStatus = BookingStatus.SCHEDULED
) : BaseEntity() {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "booking_id") var id: Long? = null
    @Column(name = "check_in_time") var checkInTime: LocalDateTime? = null
    @Column(name = "check_out_time") var checkOutTime: LocalDateTime? = null
    @Column(name = "cancellation_reason", length = 1000) var cancellationReason: String? = null

    /** A booking still holds the desk while it is scheduled or someone is checked in. */
    val holdsDesk: Boolean get() = status == BookingStatus.SCHEDULED || status == BookingStatus.CHECKED_IN
}
