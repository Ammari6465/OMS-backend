package com.sunrich.oms.workplace

import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A request to book a reservable/drop-in desk. [staffId] null means "for the
 * booking's default staff" — callers set it explicitly; booking for another
 * person requires manage rights (enforced by the controller). [dates], when
 * given, books the same slot on each date (a simple recurring series).
 */
data class BookingRequest(
    val deskId: Long,
    val staffId: Long,
    val bookingDate: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val bookingType: BookingType = BookingType.RESERVATION,
    val timeZone: String? = null,
    /** Optional extra dates for a recurring series; the same desk/time is booked on each. */
    val dates: List<LocalDate> = emptyList()
)

data class CancelBookingRequest(@field:Size(max = 1000) val reason: String? = null, val version: Long)

data class BookingResponse(
    val id: Long, val version: Long,
    val deskId: Long, val deskCode: String, val floorId: Long, val floorName: String, val buildingName: String, val officeName: String,
    val staffId: Long, val staffName: String?, val employeeCode: String?,
    val bookingDate: LocalDate, val startTime: LocalTime, val endTime: LocalTime, val timeZone: String,
    val bookingType: BookingType, val status: BookingStatus,
    val checkInTime: LocalDateTime?, val checkOutTime: LocalDateTime?, val cancellationReason: String?
)
