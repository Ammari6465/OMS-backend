package com.sunrich.oms.security

import com.sunrich.oms.exception.UnauthorizedException
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Convenience accessors for the currently authenticated [UserPrincipal].
 */
object SecurityUtils {

    fun currentPrincipalOrNull(): UserPrincipal? =
        SecurityContextHolder.getContext().authentication?.principal as? UserPrincipal

    fun currentPrincipal(): UserPrincipal =
        currentPrincipalOrNull() ?: throw UnauthorizedException()

    fun currentUserId(): Long = currentPrincipal().userId

    fun currentUserIdOrNull(): Long? = currentPrincipalOrNull()?.userId
}
