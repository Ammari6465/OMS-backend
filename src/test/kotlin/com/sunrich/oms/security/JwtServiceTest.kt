package com.sunrich.oms.security

import com.sunrich.oms.common.enums.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JwtServiceTest {

    private val secret = "test-secret-key-that-is-at-least-32-bytes-long!!"

    @Test
    fun `generated token round-trips back to the same principal`() {
        val service = JwtService(secret, expirationMs = 60_000)

        val token = service.generateToken(userId = 42, username = "alice", role = Role.COMPANY_ADMIN, companyId = 7)
        val principal = service.parse(token)

        requireNotNull(principal)
        assertEquals(42L, principal.userId)
        assertEquals("alice", principal.username)
        assertEquals(Role.COMPANY_ADMIN, principal.role)
        assertEquals(7L, principal.companyId)
    }

    @Test
    fun `token without company still parses with null companyId`() {
        val service = JwtService(secret, expirationMs = 60_000)

        val principal = service.parse(service.generateToken(1, "root", Role.SUPER_ADMIN, companyId = null))

        requireNotNull(principal)
        assertNull(principal.companyId)
        assertEquals(Role.SUPER_ADMIN, principal.role)
    }

    @Test
    fun `expired token is rejected`() {
        val service = JwtService(secret, expirationMs = -1_000) // already expired

        assertNull(service.parse(service.generateToken(1, "bob", Role.STAFF, companyId = null)))
    }

    @Test
    fun `token signed with a different secret is rejected`() {
        val issuer = JwtService(secret, expirationMs = 60_000)
        val verifier = JwtService("a-completely-different-secret-key-32-bytes!!", expirationMs = 60_000)

        assertNull(verifier.parse(issuer.generateToken(1, "eve", Role.STAFF, companyId = null)))
    }

    @Test
    fun `garbage token is rejected without throwing`() {
        val service = JwtService(secret, expirationMs = 60_000)

        assertNull(service.parse("not-a-jwt"))
    }
}
