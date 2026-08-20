package com.sunrich.oms.user

import com.sunrich.oms.common.dto.PageResponse
import com.sunrich.oms.common.enums.AuditAction
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.NotificationType
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.exception.ConflictException
import com.sunrich.oms.exception.ForbiddenException
import com.sunrich.oms.exception.ResourceNotFoundException
import com.sunrich.oms.integration.email.NotificationEmailService
import com.sunrich.oms.organization.Company
import com.sunrich.oms.organization.CompanyRepository
import com.sunrich.oms.organization.Staff
import com.sunrich.oms.organization.StaffRepository
import com.sunrich.oms.security.SecurityUtils
import com.sunrich.oms.systemdata.AuditLog
import com.sunrich.oms.systemdata.AuditLogRepository
import com.sunrich.oms.systemdata.NotificationDeliveryService
import jakarta.persistence.criteria.JoinType
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class UserAdminService(
    private val repository: UserRepository,
    private val companies: CompanyRepository,
    private val staff: StaffRepository,
    private val passwordEncoder: PasswordEncoder,
    private val audits: AuditLogRepository,
    private val notifications: NotificationDeliveryService,
    private val email: NotificationEmailService,
    @Value("\${oms.security.password-reset.token-ttl-minutes}") private val resetTokenTtlMinutes: Long,
    @Value("\${oms.frontend.base-url}") private val frontendBaseUrl: String
) {
    @Transactional(readOnly = true)
    fun list(
        page: Int, size: Int, sort: String, direction: String, search: String?, role: Role?,
        companyId: Long?, departmentId: Long?, active: Boolean?, locked: Boolean?, includeDeleted: Boolean
    ): PageResponse<UserAdminResponse> {
        val companyScope = scopedCompanyId(companyId)
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 200),
            Sort.by(if (direction.equals("desc", true)) Sort.Direction.DESC else Sort.Direction.ASC,
                SORT_FIELDS[sort] ?: "fullName"))
        val term = search?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
        val now = LocalDateTime.now()
        val specification = Specification<User> { root, query, cb ->
            if (query.resultType != java.lang.Long::class.java && query.resultType != Long::class.java) {
                root.fetch<User, Company>("company", JoinType.LEFT)
                root.fetch<User, Staff>("staff", JoinType.LEFT).fetch<Staff, Any>("department", JoinType.LEFT)
                query.distinct(true)
            }
            val predicates = mutableListOf<jakarta.persistence.criteria.Predicate>()
            if (!includeDeleted) predicates += cb.isFalse(root.get("isDeleted"))
            companyScope?.let { predicates += cb.equal(root.get<Long>("companyId"), it) }
            role?.let { predicates += cb.equal(root.get<Role>("role"), it) }
            active?.let { predicates += cb.equal(root.get<Boolean>("isActive"), it) }
            departmentId?.let {
                val member = root.join<User, Staff>("staff", JoinType.LEFT)
                predicates += cb.equal(member.get<Any>("department").get<Long>("id"), it)
            }
            locked?.let {
                val lockPredicate = cb.greaterThan(root.get("lockedUntil"), now)
                predicates += if (it) lockPredicate else cb.or(cb.isNull(root.get<LocalDateTime>("lockedUntil")), cb.lessThanOrEqualTo(root.get("lockedUntil"), now))
            }
            term?.let {
                val pattern = "%${escapeLike(it)}%"
                val company = root.join<User, Company>("company", JoinType.LEFT)
                val member = root.join<User, Staff>("staff", JoinType.LEFT)
                predicates += cb.or(
                    cb.like(cb.lower(root.get("fullName")), pattern, '\\'),
                    cb.like(cb.lower(root.get("username")), pattern, '\\'),
                    cb.like(cb.lower(root.get("email")), pattern, '\\'),
                    cb.like(cb.lower(company.get("name")), pattern, '\\'),
                    cb.like(cb.lower(member.get("employeeCode")), pattern, '\\'),
                    cb.like(cb.lower(root.get<Role>("role").`as`(String::class.java)), pattern, '\\')
                )
            }
            cb.and(*predicates.toTypedArray())
        }
        return PageResponse.from(repository.findAll(specification, pageable), this::response)
    }

    @Transactional(readOnly = true)
    fun get(id: Long): UserAdminResponse = response(accessibleUser(id, true))

    @Transactional(readOnly = true)
    fun summary(companyId: Long?): UserSummaryResponse {
        val scope = scopedCompanyId(companyId)
        fun count(extra: (jakarta.persistence.criteria.Root<User>, jakarta.persistence.criteria.CriteriaBuilder) -> jakarta.persistence.criteria.Predicate) =
            repository.count(Specification { root, _, cb -> cb.and(
                cb.isFalse(root.get("isDeleted")),
                scope?.let { cb.equal(root.get<Long>("companyId"), it) } ?: cb.conjunction(),
                extra(root, cb)
            ) })
        return UserSummaryResponse(
            total = count { _, cb -> cb.conjunction() },
            active = count { root, cb -> cb.isTrue(root.get("isActive")) },
            inactive = count { root, cb -> cb.isFalse(root.get("isActive")) },
            locked = count { root, cb -> cb.greaterThan(root.get("lockedUntil"), LocalDateTime.now()) },
            administrators = count { root, cb -> root.get<Role>("role").`in`(Role.SUPER_ADMIN, Role.COMPANY_ADMIN) }
        )
    }

    @Transactional(readOnly = true)
    fun roles(companyId: Long?): List<RoleResponse> {
        val scope = scopedCompanyId(companyId)
        return ROLE_DETAILS.map { (role, detail) ->
            val assigned = repository.count(Specification { root, _, cb -> cb.and(
                cb.isFalse(root.get("isDeleted")), cb.equal(root.get<Role>("role"), role),
                scope?.let { cb.equal(root.get<Long>("companyId"), it) } ?: cb.conjunction()
            ) })
            RoleResponse(role, detail.description, detail.accessLevel, detail.permissions, assigned)
        }.filter { SecurityUtils.currentPrincipal().isSuperAdmin || it.role != Role.SUPER_ADMIN }
    }

    @Transactional
    fun create(request: CreateUserRequest): UserAdminResponse {
        ensureRoleAllowed(request.role)
        val companyId = validatedCompanyForRole(request.role, request.companyId)
        val member = validateStaff(request.staffId, companyId, null)
        ensureUnique(request.username, request.email, null)
        val user = repository.saveAndFlush(User(
            username = request.username.trim(), email = request.email.trim().lowercase(),
            passwordHash = passwordEncoder.encode(request.password), role = request.role,
            fullName = request.fullName.trim(), staffId = member?.id, companyId = companyId,
            status = if (request.isActive) EntityStatus.ACTIVE else EntityStatus.INACTIVE, isActive = request.isActive
        ))
        recordAudit(AuditAction.CREATE, user, null)
        notify(user, "Account created", "Your OMS account has been created with the ${user.role.name} role.")
        return response(user)
    }

    @Transactional
    fun update(id: Long, request: UpdateUserRequest): UserAdminResponse {
        val user = accessibleUser(id, false)
        requireMutableTarget(user)
        requireVersion(user, request.version)
        if (request.role != user.role) requireRoleChange(user, request.role)
        if (request.isActive != user.isActive) requireStatusChange(user, request.isActive)
        val companyId = validatedCompanyForRole(request.role, request.companyId)
        val member = validateStaff(request.staffId, companyId, id)
        ensureUnique(request.username, request.email, id)
        val old = auditValue(user)
        user.username = request.username.trim()
        user.email = request.email.trim().lowercase()
        user.fullName = request.fullName.trim()
        user.role = request.role
        user.companyId = companyId
        user.staffId = member?.id
        user.isActive = request.isActive
        user.status = if (request.isActive) EntityStatus.ACTIVE else EntityStatus.INACTIVE
        val saved = repository.saveAndFlush(user)
        recordAudit(AuditAction.UPDATE, saved, old)
        notify(saved, "Account updated", "Your OMS account access details were updated.")
        return response(saved)
    }

    @Transactional
    fun changeStatus(id: Long, request: UserStatusRequest): UserAdminResponse {
        val user = accessibleUser(id, false)
        requireMutableTarget(user)
        requireVersion(user, request.version)
        requireStatusChange(user, request.isActive)
        val old = auditValue(user)
        user.isActive = request.isActive
        user.status = if (request.isActive) EntityStatus.ACTIVE else EntityStatus.INACTIVE
        if (!request.isActive) clearSecurityState(user)
        val saved = repository.saveAndFlush(user)
        recordAudit(AuditAction.UPDATE, saved, old, if (request.isActive) "User activated" else "User deactivated")
        notify(saved, if (request.isActive) "Account activated" else "Account deactivated",
            if (request.isActive) "Your OMS account is active." else "Your OMS account has been deactivated.")
        return response(saved)
    }

    @Transactional
    fun changeRole(id: Long, request: UserRoleRequest): UserAdminResponse {
        val user = accessibleUser(id, false)
        requireMutableTarget(user)
        requireVersion(user, request.version)
        requireRoleChange(user, request.role)
        val old = auditValue(user)
        user.role = request.role
        user.companyId = validatedCompanyForRole(request.role, user.companyId)
        val saved = repository.saveAndFlush(user)
        recordAudit(AuditAction.UPDATE, saved, old, "User role changed")
        notify(saved, "Role changed", "Your OMS role is now ${saved.role.name}.")
        return response(saved)
    }

    @Transactional
    fun unlock(id: Long, request: UserVersionRequest): UserAdminResponse {
        val user = accessibleUser(id, false)
        requireMutableTarget(user)
        requireVersion(user, request.version)
        val old = auditValue(user)
        user.failedLoginAttempts = 0
        user.lockedUntil = null
        val saved = repository.saveAndFlush(user)
        recordAudit(AuditAction.UPDATE, saved, old, "User unlocked")
        notify(saved, "Account unlocked", "Your OMS account has been unlocked.")
        return response(saved)
    }

    @Transactional
    fun requestPasswordReset(id: Long) {
        val user = accessibleUser(id, false)
        requireMutableTarget(user)
        if (!user.isActive) throw BadRequestException("Password reset is only available for active accounts")
        val token = UUID.randomUUID().toString().replace("-", "")
        user.passwordResetToken = token
        user.passwordResetExpires = LocalDateTime.now().plusMinutes(resetTokenTtlMinutes)
        repository.save(user)
        email.sendPasswordReset(user.email, "${frontendBaseUrl.trimEnd('/')}/auth/reset-password?token=$token")
        recordAudit(AuditAction.PASSWORD_RESET, user, null, "Password reset requested")
        notify(user, "Password reset requested", "A password reset link was sent to your registered email address.")
    }

    @Transactional
    fun delete(id: Long) {
        val user = accessibleUser(id, false)
        requireMutableTarget(user)
        if (id == SecurityUtils.currentUserId()) throw ForbiddenException("You cannot archive your own account")
        protectLastSuperAdmin(user, removesAccess = true)
        val old = auditValue(user)
        user.markDeleted()
        user.isActive = false
        user.status = EntityStatus.INACTIVE
        clearSecurityState(user)
        repository.save(user)
        recordAudit(AuditAction.DELETE, user, old)
    }

    @Transactional
    fun restore(id: Long, request: UserVersionRequest): UserAdminResponse {
        val user = accessibleUser(id, true)
        requireMutableTarget(user)
        requireVersion(user, request.version)
        ensureRoleAllowed(user.role)
        validatedCompanyForRole(user.role, user.companyId)
        ensureUnique(user.username, user.email, id)
        validateStaff(user.staffId, user.companyId, id)
        user.restore()
        user.isActive = false
        user.status = EntityStatus.INACTIVE
        val saved = repository.saveAndFlush(user)
        recordAudit(AuditAction.RESTORE, saved, null)
        return response(saved)
    }

    private fun accessibleUser(id: Long, includeDeleted: Boolean): User {
        val user = repository.findById(id).orElseThrow { ResourceNotFoundException("User", id) }
        if (!includeDeleted && user.isDeleted) throw ResourceNotFoundException("User", id)
        val principal = SecurityUtils.currentPrincipal()
        if (!principal.isSuperAdmin && (user.companyId != principal.companyId || user.role == Role.SUPER_ADMIN)) {
            throw ForbiddenException("You cannot access a user outside your company scope")
        }
        return user
    }

    private fun scopedCompanyId(requested: Long?): Long? {
        val principal = SecurityUtils.currentPrincipal()
        if (principal.isSuperAdmin) return requested
        val own = principal.companyId ?: throw ForbiddenException("Your account is not assigned to a company")
        if (requested != null && requested != own) throw ForbiddenException("You cannot access users from another company")
        return own
    }

    private fun ensureRoleAllowed(role: Role) {
        val principal = SecurityUtils.currentPrincipal()
        if (!principal.isSuperAdmin && role in setOf(Role.SUPER_ADMIN, Role.COMPANY_ADMIN)) {
            throw ForbiddenException("Only a SUPER_ADMIN can assign administrative roles")
        }
    }

    private fun requireMutableTarget(user: User) {
        if (!SecurityUtils.currentPrincipal().isSuperAdmin && user.role in setOf(Role.SUPER_ADMIN, Role.COMPANY_ADMIN)) {
            throw ForbiddenException("Only a SUPER_ADMIN can modify an administrator account")
        }
    }

    private fun requireRoleChange(user: User, newRole: Role) {
        if (user.id == SecurityUtils.currentUserId()) throw ForbiddenException("You cannot change your own role")
        ensureRoleAllowed(newRole)
        if (!SecurityUtils.currentPrincipal().isSuperAdmin && user.role in setOf(Role.SUPER_ADMIN, Role.COMPANY_ADMIN)) {
            throw ForbiddenException("You cannot modify an administrator role")
        }
        protectLastSuperAdmin(user, removesAccess = newRole != Role.SUPER_ADMIN)
    }

    private fun requireStatusChange(user: User, active: Boolean) {
        if (user.id == SecurityUtils.currentUserId() && !active) throw ForbiddenException("You cannot deactivate your own account")
        protectLastSuperAdmin(user, removesAccess = !active)
    }

    private fun protectLastSuperAdmin(user: User, removesAccess: Boolean) {
        if (removesAccess && user.role == Role.SUPER_ADMIN && user.isActive && !user.isDeleted &&
            repository.countByIsDeletedFalseAndRoleAndIsActiveTrue(Role.SUPER_ADMIN) <= 1) {
            throw ConflictException("The system must have at least one active SUPER_ADMIN.")
        }
    }

    private fun validatedCompanyForRole(role: Role, requested: Long?): Long? {
        val scoped = scopedCompanyId(requested)
        if (role == Role.SUPER_ADMIN) {
            if (!SecurityUtils.currentPrincipal().isSuperAdmin) throw ForbiddenException("Only a SUPER_ADMIN can assign this role")
            return null
        }
        val companyId = scoped ?: throw BadRequestException("Company is required for non-SUPER_ADMIN users")
        val company = companies.findById(companyId).orElseThrow { ResourceNotFoundException("Company", companyId) }
        if (company.isDeleted || company.status != EntityStatus.ACTIVE) throw BadRequestException("User company must be active")
        return companyId
    }

    private fun validateStaff(staffId: Long?, companyId: Long?, currentUserId: Long?): Staff? {
        if (staffId == null) return null
        val member = staff.findById(staffId).orElseThrow { ResourceNotFoundException("Staff", staffId) }
        if (member.isDeleted || member.status != EntityStatus.ACTIVE) throw BadRequestException("Staff association must be active")
        if (member.company.id != companyId) throw BadRequestException("Staff association must belong to the user's company")
        val duplicate = repository.findAll(Specification { root, _, cb -> cb.and(
            cb.equal(root.get<Long>("staffId"), staffId), cb.isFalse(root.get("isDeleted")), cb.isTrue(root.get("isActive")),
            currentUserId?.let { cb.notEqual(root.get<Long>("id"), it) } ?: cb.conjunction()
        ) }).isNotEmpty()
        if (duplicate) throw ConflictException("This staff member already has an active user account")
        return member
    }

    private fun ensureUnique(username: String, email: String, currentId: Long?) {
        if (username.trim().isEmpty()) throw BadRequestException("Username is required")
        repository.findByUsernameIgnoreCase(username.trim()).orElse(null)?.let {
            if (it.id != currentId) throw ConflictException("Username is already in use")
        }
        repository.findByEmailIgnoreCase(email.trim()).orElse(null)?.let {
            if (it.id != currentId) throw ConflictException("Email is already in use")
        }
    }

    private fun requireVersion(user: User, version: Long) {
        if (user.version != version) throw ConflictException("User record was modified by another administrator. Reload and try again.")
    }

    private fun clearSecurityState(user: User) {
        user.failedLoginAttempts = 0
        user.lockedUntil = null
        user.passwordResetToken = null
        user.passwordResetExpires = null
    }

    private fun recordAudit(action: AuditAction, target: User, old: String?, label: String = "User") {
        val actor = repository.findById(SecurityUtils.currentUserId()).orElse(null) ?: return
        audits.save(AuditLog(staff = target.staff, changedBy = actor, changeType = action, fieldName = label,
            entityType = "User", entityId = target.id, companyId = target.companyId,
            oldValue = old, newValue = auditValue(target)))
    }

    private fun notify(target: User, title: String, message: String) {
        notifications.deliver(target, NotificationType.SYSTEM, "$title: $message", "/users/${target.id}", "User", target.id)
    }

    private fun response(user: User): UserAdminResponse {
        val member = user.staff ?: user.staffId?.let { staff.findById(it).orElse(null) }
        val companyName = user.company?.name ?: user.companyId?.let { companies.findById(it).orElse(null)?.name }
        return user.toAdminResponse().copy(
            companyName = companyName,
            staffName = member?.name,
            departmentId = member?.department?.id,
            departmentName = member?.department?.name,
            employeeCode = member?.employeeCode
        )
    }

    private fun auditValue(user: User): String =
        "id=${user.id},username=${user.username},role=${user.role},companyId=${user.companyId},staffId=${user.staffId}," +
            "active=${user.isActive},locked=${user.isLocked},deleted=${user.isDeleted}"

    private fun escapeLike(value: String) = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private data class RoleDetail(val description: String, val accessLevel: String, val permissions: List<String>)

    companion object {
        private val SORT_FIELDS = mapOf("fullName" to "fullName", "username" to "username", "email" to "email",
            "role" to "role", "lastLogin" to "lastLogin", "createdAt" to "createdAt", "isActive" to "isActive")
        private val ROLE_DETAILS = linkedMapOf(
            Role.SUPER_ADMIN to RoleDetail("Group-level IT or HR head with authority across Sunrich Companies and every sister concern.", "Group", listOf("Full access across all companies", "Manage users and roles", "Configure the system", "View all audit logs")),
            Role.COMPANY_ADMIN to RoleDetail("HR or administration manager for an assigned company.", "Assigned company", listOf("Create, edit, and deactivate staff", "Manage departments and reporting lines", "Manage company users", "View company audit history")),
            Role.MANAGER to RoleDetail("Department head, director, or people manager.", "Assigned companies", listOf("View the full company organogram", "View direct reports' profiles", "View reporting lines", "No organization editing")),
            Role.STAFF to RoleDetail("Employee working in one or more Sunrich group companies.", "Own assignments", listOf("View assigned-company organograms", "View own profile", "View own reporting line", "No management actions")),
            Role.READ_ONLY to RoleDetail("Board member, auditor, or external stakeholder.", "Specified organograms", listOf("View permitted organograms", "No staff profile management", "No organization editing"))
        )
    }
}
