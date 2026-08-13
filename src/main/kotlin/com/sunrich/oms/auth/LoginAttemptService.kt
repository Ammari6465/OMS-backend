package com.sunrich.oms.auth

import com.sunrich.oms.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import com.sunrich.oms.common.enums.AuditAction
import com.sunrich.oms.systemdata.AuditTrailService

/**
 * Persists login-attempt outcomes in their OWN transaction.
 *
 * Failure bookkeeping must survive the [UnauthorizedException] that
 * `AuthService.login` throws on a bad password. If it ran inside the caller's
 * transaction the throw would roll the counter increment back and account
 * lockout would never engage — so these methods use REQUIRES_NEW and reload the
 * user by id to avoid acting on a detached/stale instance.
 */
@Service
class LoginAttemptService(
    private val userRepository: UserRepository,
    private val auditTrail: AuditTrailService,
    @Value("\${oms.security.login.max-failed-attempts}") private val maxFailedAttempts: Int,
    @Value("\${oms.security.login.lock-duration-minutes}") private val lockDurationMinutes: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordFailure(userId: Long) {
        val user = userRepository.findById(userId).orElse(null) ?: return
        user.failedLoginAttempts += 1
        if (user.failedLoginAttempts >= maxFailedAttempts) {
            user.lockedUntil = LocalDateTime.now().plusMinutes(lockDurationMinutes)
            log.warn("Account '{}' locked after {} failed login attempts", user.username, user.failedLoginAttempts)
        }
        userRepository.save(user)
        auditTrail.record(user, AuditAction.LOGIN_FAILED, "Authentication", user.id, user.companyId, "Login failed",
            after = "username=${user.username},failedAttempts=${user.failedLoginAttempts},locked=${user.isLocked}")
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordSuccess(userId: Long) {
        val user = userRepository.findById(userId).orElse(null) ?: return
        user.failedLoginAttempts = 0
        user.lockedUntil = null
        user.lastLogin = LocalDateTime.now()
        userRepository.save(user)
    }
}
