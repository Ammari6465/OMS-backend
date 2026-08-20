package com.sunrich.oms.auth

import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.exception.AccountLockedException
import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.exception.UnauthorizedException
import com.sunrich.oms.integration.email.NotificationEmailService
import com.sunrich.oms.security.JwtService
import com.sunrich.oms.security.SecurityUtils
import com.sunrich.oms.user.User
import com.sunrich.oms.user.UserRepository
import com.sunrich.oms.organization.StaffCompanyAssignmentRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID
import com.sunrich.oms.common.enums.AuditAction
import com.sunrich.oms.systemdata.AuditTrailService

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val assignments: StaffCompanyAssignmentRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val loginAttemptService: LoginAttemptService,
    private val notificationEmailService: NotificationEmailService,
    private val auditTrail: AuditTrailService,
    @Value("\${oms.security.jwt.expiration-ms}") private val jwtExpirationMs: Long,
    @Value("\${oms.security.password-reset.token-ttl-minutes}") private val resetTokenTtlMinutes: Long,
    @Value("\${oms.frontend.base-url}") private val frontendBaseUrl: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Not @Transactional on purpose: attempt bookkeeping is delegated to
     * [LoginAttemptService] (REQUIRES_NEW) so a failed-login counter increment
     * commits independently of the exception thrown on bad credentials.
     */
    fun login(request: LoginRequest): LoginResponse {
        // Sign-in is by username only; email is used for password reset, not login.
        val user = userRepository.findByUsernameIgnoreCaseAndIsDeletedFalse(request.username)
            .orElseThrow { UnauthorizedException("Invalid username or password") }

        if (user.isLocked) {
            throw AccountLockedException(
                "Account is locked due to repeated failed logins. Try again after ${user.lockedUntil}."
            )
        }

        if (!user.isActive || user.status != EntityStatus.ACTIVE) {
            throw UnauthorizedException("This account is deactivated. Please contact your administrator.")
        }

        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            loginAttemptService.recordFailure(user.id!!)
            throw UnauthorizedException("Invalid username or password")
        }

        loginAttemptService.recordSuccess(user.id!!)
        auditTrail.record(user, AuditAction.LOGIN, "Authentication", user.id, user.companyId, "Login",
            after = "username=${user.username},result=SUCCESS")

        val companyIds = user.accessibleCompanyIds()
        val token = jwtService.generateToken(user.id!!, user.username, user.role, user.companyId, companyIds)
        return LoginResponse(
            token = token,
            expiresInMs = jwtExpirationMs,
            user = user.toCurrentUserResponse(companyIds)
        )
    }

    @Transactional(readOnly = true)
    fun currentUser(): CurrentUserResponse {
        val principal = SecurityUtils.currentPrincipal()
        val user = userRepository.findById(principal.userId)
            .filter { !it.isDeleted }
            .orElseThrow { UnauthorizedException("Session user no longer exists") }
        return user.toCurrentUserResponse()
    }

    @Transactional
    fun updateProfile(request: UpdateProfileRequest): CurrentUserResponse {
        val principal = SecurityUtils.currentPrincipal()
        val user = userRepository.findById(principal.userId)
            .filter { !it.isDeleted }
            .orElseThrow { UnauthorizedException("Session user no longer exists") }
        val duplicate = userRepository.findByEmailIgnoreCaseAndIsDeletedFalse(request.email).orElse(null)
        if (duplicate != null && duplicate.id != user.id) {
            throw BadRequestException("That email address is already in use")
        }
        val before = "fullName=${user.fullName},email=${user.email}"
        user.fullName = request.fullName.trim()
        user.email = request.email.trim()
        val saved = userRepository.save(user)
        auditTrail.record(saved, AuditAction.UPDATE, "Profile", saved.id, saved.companyId, "Profile updated",
            before, "fullName=${saved.fullName},email=${saved.email}")
        return saved.toCurrentUserResponse()
    }

    @Transactional
    fun changePassword(request: ChangePasswordRequest) {
        val principal = SecurityUtils.currentPrincipal()
        val user = userRepository.findById(principal.userId)
            .orElseThrow { UnauthorizedException("Session user no longer exists") }

        if (!passwordEncoder.matches(request.currentPassword, user.passwordHash)) {
            throw BadRequestException("Current password is incorrect")
        }
        user.passwordHash = passwordEncoder.encode(request.newPassword)
        userRepository.save(user)
        auditTrail.record(user, AuditAction.PASSWORD_CHANGE, "Authentication", user.id, user.companyId, "Password changed",
            after = "username=${user.username},result=SUCCESS")
    }

    /**
     * Self-service reset request. Always returns quietly to avoid user
     * enumeration. When a matching active account exists a one-time token is
     * generated and delivered through the configured Gmail API service.
     */
    @Transactional
    fun requestPasswordReset(request: ForgotPasswordRequest) {
        val user = userRepository.findByEmailIgnoreCaseAndIsDeletedFalse(request.email).orElse(null)
        if (user == null || !user.isActive) {
            log.info("Password reset requested for unknown/inactive email: {}", request.email)
            return
        }
        val token = UUID.randomUUID().toString().replace("-", "")
        user.passwordResetToken = token
        user.passwordResetExpires = LocalDateTime.now().plusMinutes(resetTokenTtlMinutes)
        userRepository.save(user)
        auditTrail.record(user, AuditAction.PASSWORD_RESET, "Authentication", user.id, user.companyId, "Password reset requested",
            after = "username=${user.username},result=REQUESTED")
        notificationEmailService.sendPasswordReset(
            user.email,
            "${frontendBaseUrl.trimEnd('/')}/auth/reset-password?token=$token"
        )
    }

    @Transactional
    fun resetPassword(request: ResetPasswordRequest) {
        val user = userRepository.findByPasswordResetTokenAndIsDeletedFalse(request.token)
            .orElseThrow { BadRequestException("Invalid or expired reset token") }

        if (user.passwordResetExpires == null || user.passwordResetExpires!!.isBefore(LocalDateTime.now())) {
            throw BadRequestException("This reset token has expired. Please request a new one.")
        }

        user.passwordHash = passwordEncoder.encode(request.newPassword)
        user.passwordResetToken = null
        user.passwordResetExpires = null
        user.failedLoginAttempts = 0
        user.lockedUntil = null
        userRepository.save(user)
        auditTrail.record(user, AuditAction.PASSWORD_RESET, "Authentication", user.id, user.companyId, "Password reset completed",
            after = "username=${user.username},result=SUCCESS")
    }

    private fun User.accessibleCompanyIds(): Set<Long> {
        val primary = setOfNotNull(companyId)
        if (role !in setOf(com.sunrich.oms.common.enums.Role.MANAGER, com.sunrich.oms.common.enums.Role.STAFF) || staffId == null) {
            return primary
        }
        return assignments.findAllByStaff_IdAndIsDeletedFalseOrderByIsPrimaryDescCompany_NameAsc(staffId!!)
            .filter { it.status == EntityStatus.ACTIVE }
            .mapTo(linkedSetOf()) { it.company.id!! }
            .ifEmpty { primary }
    }

    private fun User.toCurrentUserResponse(companyIds: Set<Long> = accessibleCompanyIds()) = CurrentUserResponse(
        userId = id!!,
        username = username,
        email = email,
        fullName = fullName,
        role = role,
        companyId = companyId,
        companyIds = companyIds,
        staffId = staffId
    )
}
