package com.sunrich.oms.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.user.UserRepository

/**
 * Reads the Bearer token, validates it and, on success, populates the
 * SecurityContext with a [UserPrincipal] and the corresponding ROLE_* authority.
 */
@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val users: UserRepository
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith(BEARER_PREFIX) &&
            SecurityContextHolder.getContext().authentication == null
        ) {
            val token = header.substring(BEARER_PREFIX.length)
            val principal = jwtService.parse(token)
            val currentUser = principal?.let { users.findById(it.userId).orElse(null) }
            // JWTs are stateless, but account authorization is mutable. Re-check the
            // security-critical account state so deactivation, locking, deletion,
            // role changes, and company-scope changes invalidate old tokens immediately.
            if (principal != null && currentUser != null && !currentUser.isDeleted && currentUser.isActive &&
                currentUser.status == EntityStatus.ACTIVE && !currentUser.isLocked &&
                currentUser.username == principal.username && currentUser.role == principal.role &&
                currentUser.companyId == principal.companyId) {
                val authentication = UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    listOf(SimpleGrantedAuthority(principal.authority))
                )
                authentication.details = WebAuthenticationDetailsSource().buildDetails(request)
                SecurityContextHolder.getContext().authentication = authentication
            }
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
    }
}
