package com.sunrich.oms.security

import com.sunrich.oms.common.enums.Role
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.Key
import java.util.Date

/**
 * Issues and validates JWTs. Mirrors the existing custom-auth JWT scheme
 * (HS256, jjwt) so tokens remain compatible when the client's auth module
 * is wired in during Phase 4.
 */
@Component
class JwtService(
    @Value("\${oms.security.jwt.secret}") secret: String,
    @Value("\${oms.security.jwt.expiration-ms}") private val expirationMs: Long
) {
    private val key: Key = Keys.hmacShaKeyFor(secret.toByteArray())

    fun generateToken(userId: Long, username: String, role: Role, companyId: Long?): String =
        Jwts.builder()
            .setSubject(username)
            .claim("userId", userId)
            .claim("role", role.name)
            .apply { companyId?.let { claim("companyId", it) } }
            .setIssuedAt(Date())
            .setExpiration(Date(System.currentTimeMillis() + expirationMs))
            .signWith(key, SignatureAlgorithm.HS256)
            .compact()

    fun parse(token: String): UserPrincipal? = try {
        val claims = claims(token)
        if (claims.expiration.before(Date())) {
            null
        } else {
            UserPrincipal(
                userId = (claims["userId"] as Number).toLong(),
                username = claims.subject,
                role = Role.fromString(claims["role"] as? String),
                companyId = (claims["companyId"] as? Number)?.toLong()
            )
        }
    } catch (ex: Exception) {
        null
    }

    private fun claims(token: String): Claims =
        Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).body
}
