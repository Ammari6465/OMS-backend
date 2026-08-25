package com.sunrich.oms.workplace

import com.sunrich.oms.common.entity.BaseEntity
import com.sunrich.oms.organization.Staff
import jakarta.persistence.*
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A reservation of a bookable [WorkplaceSpace] (a conference or meeting room)
 * for a date and time window. Reuses [BookingStatus] for its lifecycle. Kept in
 * its own table and files, separate from the permanent space assignment feature,
 * so meeting-room booking and cabin assignment evolve independently.
 */
@Entity
@Table(
    name = "workplace_room_bookings",
    indexes = [
        Index(name = "idx_room_booking_space_date", columnList = "space_id,booking_date,status"),
        Index(name = "idx_room_booking_organizer", columnList = "organizer_staff_id,booking_date")
    ]
)
class RoomBooking(
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "space_id", nullable = false) var space: WorkplaceSpace,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "organizer_staff_id", nullable = false) var organizer: Staff,
    @Column(name = "booking_date", nullable = false) var bookingDate: LocalDate,
    @Column(name = "start_time", nullable = false) var startTime: LocalTime,
    @Column(name = "end_time", nullable = false) var endTime: LocalTime,
    @Column(name = "time_zone", nullable = false, length = 60) var timeZone: String,
    @Column(nullable = false) var participants: Int = 1,
    @Column(name = "equipment_required", length = 1000) var equipmentRequired: String? = null,
    @Column(length = 200) var title: String? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) var status: BookingStatus = BookingStatus.SCHEDULED
) : BaseEntity() {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "room_booking_id") var id: Long? = null
    @Column(name = "check_in_time") var checkInTime: LocalDateTime? = null
    @Column(name = "check_out_time") var checkOutTime: LocalDateTime? = null
    @Column(name = "cancellation_reason", length = 1000) var cancellationReason: String? = null
    val holdsRoom: Boolean get() = status == BookingStatus.SCHEDULED || status == BookingStatus.CHECKED_IN
}

// ---- DTOs ------------------------------------------------------------------------------------
data class RoomBookingRequest(
    val spaceId: Long,
    val organizerStaffId: Long,
    val bookingDate: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    @field:Min(1) val participants: Int = 1,
    @field:Size(max = 1000) val equipmentRequired: String? = null,
    @field:Size(max = 200) val title: String? = null,
    val timeZone: String? = null,
    /** Optional extra dates for a recurring series; the same room/time is booked on each. */
    val dates: List<LocalDate> = emptyList()
)

data class CancelRoomBookingRequest(@field:Size(max = 1000) val reason: String? = null, val version: Long)

data class RoomBookingResponse(
    val id: Long, val version: Long,
    val spaceId: Long, val spaceCode: String, val spaceName: String, val spaceType: SpaceType,
    val floorId: Long, val floorName: String, val buildingName: String, val officeName: String,
    val organizerStaffId: Long, val organizerName: String?, val participants: Int, val equipmentRequired: String?,
    val title: String?, val bookingDate: LocalDate, val startTime: LocalTime, val endTime: LocalTime, val timeZone: String,
    val status: BookingStatus, val checkInTime: LocalDateTime?, val checkOutTime: LocalDateTime?, val cancellationReason: String?
)
