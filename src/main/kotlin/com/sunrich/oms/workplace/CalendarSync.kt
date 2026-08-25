package com.sunrich.oms.workplace

import org.springframework.stereotype.Component

/**
 * Seam for external calendar providers (Google Calendar, Outlook). The core
 * room-booking domain depends only on this interface, so a provider can be added
 * later without changing booking logic. The default is a no-op so bookings work
 * out of the box; a real provider is a drop-in @Primary/@Component replacement.
 */
interface CalendarSync {
    fun onBooked(booking: RoomBooking) {}
    fun onCancelled(booking: RoomBooking) {}
    fun onChanged(booking: RoomBooking) {}
}

/** Default: records nothing externally. Replaced by a provider-backed bean when one is configured. */
@Component
class NoopCalendarSync : CalendarSync
