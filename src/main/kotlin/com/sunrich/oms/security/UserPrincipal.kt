package com.sunrich.oms.security

import com.sunrich.oms.common.enums.Role

/**
 * Authenticated principal placed into the SecurityContext by [JwtAuthenticationFilter].
 * Carries just enough identity to enforce RBAC and company scoping without a DB hit.
 */
data class UserPrincipal(
    val userId: Long,
    val username: String,
    val role: Role,
    val companyId: Long?
) {
    val isSuperAdmin: Boolean get() = role == Role.SUPER_ADMIN
    val authority: String get() = "ROLE_${role.name}"
}
