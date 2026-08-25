package com.sunrich.oms.workplace

import com.sunrich.oms.common.dto.ApiResponse
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

private const val MANAGE = "hasAnyRole('SUPER_ADMIN','COMPANY_ADMIN','MANAGER')"

/** Meeting-room booking API for bookable spaces. Company-scoped in the service. */
@RestController
@RequestMapping("/workplaces/room-bookings")
class RoomBookingController(private val service: RoomBookingService) {
    @PostMapping @PreAuthorize(MANAGE)
    fun book(@Valid @RequestBody r: RoomBookingRequest) = ApiResponse.ok(service.book(r), "Room booked")

    @PostMapping("/{id}/check-in") @PreAuthorize(MANAGE)
    fun checkIn(@PathVariable id: Long) = ApiResponse.ok(service.checkIn(id), "Checked in")

    @PostMapping("/{id}/check-out") @PreAuthorize(MANAGE)
    fun checkOut(@PathVariable id: Long) = ApiResponse.ok(service.checkOut(id), "Checked out")

    @PostMapping("/{id}/cancel") @PreAuthorize(MANAGE)
    fun cancel(@PathVariable id: Long, @Valid @RequestBody r: CancelRoomBookingRequest) =
        ApiResponse.ok(service.cancel(id, r), "Booking cancelled")

    @GetMapping("/staff/{staffId}/history")
    fun history(@PathVariable staffId: Long) = ApiResponse.ok(service.history(staffId))

    /** Calendar / agenda view for a floor over a date range. */
    @GetMapping("/agenda")
    fun agenda(
        @RequestParam floorId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate
    ) = ApiResponse.ok(service.agenda(floorId, from, to))
}
