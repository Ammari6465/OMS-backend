package com.sunrich.oms.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Simple per-IP fixed-window rate limiter to blunt brute-force / scraping.
 * (SRS 4.3 — failed logins are additionally throttled at the account level.)
 *
 * Stale windows are swept periodically so the backing map cannot grow without
 * bound as unique client IPs accumulate.
 */
@Component
class RateLimitingFilter(
    @Value("\${oms.security.rate-limit.max-requests-per-minute}") private val maxPerMinute: Int
) : OncePerRequestFilter() {

    private val windows = ConcurrentHashMap<String, Window>()
    private val lastSweepMs = AtomicLong(System.currentTimeMillis())

    private class Window(var count: Int, var startMs: Long)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val clientIp = clientIp(request)
        val now = System.currentTimeMillis()
        evictStaleWindows(now)
        val window = windows.computeIfAbsent(clientIp) { Window(0, now) }

        val exceeded = synchronized(window) {
            if (now - window.startMs > WINDOW_MS) {
                window.count = 1
                window.startMs = now
                false
            } else {
                window.count++
                window.count > maxPerMinute
            }
        }

        if (exceeded) {
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write("""{"success":false,"status":429,"message":"Rate limit exceeded. Please slow down."}""")
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun clientIp(request: HttpServletRequest): String {
        val forwarded = request.getHeader("X-Forwarded-For")
        return if (!forwarded.isNullOrBlank()) forwarded.split(",").first().trim()
        else request.remoteAddr ?: "unknown"
    }

    /**
     * Removes windows whose fixed interval has fully elapsed. Runs at most once
     * per [SWEEP_INTERVAL_MS]; a single thread wins the CAS and performs the sweep.
     */
    private fun evictStaleWindows(now: Long) {
        val last = lastSweepMs.get()
        if (now - last < SWEEP_INTERVAL_MS) return
        if (!lastSweepMs.compareAndSet(last, now)) return
        windows.entries.removeIf { (_, w) -> now - w.startMs > WINDOW_MS }
    }

    companion object {
        private const val WINDOW_MS = 60_000L
        private const val SWEEP_INTERVAL_MS = 60_000L
    }
}
