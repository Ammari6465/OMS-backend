package com.sunrich.oms.workplace

import com.sunrich.oms.common.dto.ApiResponse
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

private const val MANAGE = "hasAnyRole('SUPER_ADMIN','COMPANY_ADMIN','MANAGER')"

/**
 * Desk booking API. Manage roles book on behalf of staff (booking for another
 * employee is covered here); self-service booking is a later enhancement that
 * needs a principal→staff mapping. Every route is company-scoped in the service.
 */
@RestController
@RequestMapping("/workplaces/bookings")
class BookingController(private val service: BookingService) {
    @PostMapping @PreAuthorize(MANAGE)
    fun book(@Valid @RequestBody r: BookingRequest) = ApiResponse.ok(service.book(r), "Desk booked")

    @PostMapping("/{id}/check-in") @PreAuthorize(MANAGE)
    fun checkIn(@PathVariable id: Long) = ApiResponse.ok(service.checkIn(id), "Checked in")

    @PostMapping("/{id}/check-out") @PreAuthorize(MANAGE)
    fun checkOut(@PathVariable id: Long) = ApiResponse.ok(service.checkOut(id), "Checked out")

    @PostMapping("/{id}/cancel") @PreAuthorize(MANAGE)
    fun cancel(@PathVariable id: Long, @Valid @RequestBody r: CancelBookingRequest) =
        ApiResponse.ok(service.cancel(id, r), "Booking cancelled")

    /** QR code check-in: the QR encodes the booking id. */
    @PostMapping("/qr/{id}/check-in") @PreAuthorize(MANAGE)
    fun qrCheckIn(@PathVariable id: Long) = ApiResponse.ok(service.checkInByQr(id), "Checked in")

    @GetMapping("/staff/{staffId}/history")
    fun history(@PathVariable staffId: Long) = ApiResponse.ok(service.history(staffId))
}
