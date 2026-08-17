package com.sunrich.oms.systemdata

import com.sunrich.oms.common.dto.PageResponse
import com.sunrich.oms.common.enums.AuditAction
import com.sunrich.oms.common.enums.NotificationType
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.exception.ForbiddenException
import com.sunrich.oms.exception.ResourceNotFoundException
import com.sunrich.oms.organization.Staff
import com.sunrich.oms.organization.StaffRepository
import com.sunrich.oms.security.SecurityUtils
import com.sunrich.oms.user.User
import com.sunrich.oms.user.UserRepository
import jakarta.persistence.criteria.JoinType
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

@Service
class SystemDataService(
    private val audits: AuditLogRepository,
    private val notifications: NotificationRepository,
    private val settings: SystemSettingRepository,
    private val users: UserRepository,
    private val staff: StaffRepository
) {
    @Transactional(readOnly = true)
    fun listAudit(page: Int, size: Int, sort: String, direction: String, search: String?, action: AuditAction?,
        module: String?, userId: Long?, role: Role?, companyId: Long?, result: String?,
        from: LocalDateTime?, to: LocalDateTime?): PageResponse<AuditResponse> {
        if (from != null && to != null && to.isBefore(from)) throw BadRequestException("Audit end time cannot be before start time")
        val scope = scopedCompanyId(companyId)
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 200),
            Sort.by(if (direction.equals("asc", true)) Sort.Direction.ASC else Sort.Direction.DESC,
                SORT_FIELDS[sort] ?: "changedAt"))
        val specification = auditSpecification(search, action, module, userId, role, scope, result, from, to, fetch = true)
        return PageResponse.from(audits.findAll(specification, pageable), ::auditResponse)
    }

    @Transactional(readOnly = true)
    fun getAudit(id: Long): AuditResponse {
        val scope = scopedCompanyId(null)
        val spec = auditSpecification(null, null, null, null, null, scope, null, null, null, fetch = true)
            .and { root, _, cb -> cb.equal(root.get<Long>("id"), id) }
        return audits.findOne(spec).map(::auditResponse).orElseThrow { ResourceNotFoundException("Audit event", id) }
    }

    @Transactional(readOnly = true)
    fun auditSummary(companyId: Long?): AuditSummaryResponse {
        val scope = scopedCompanyId(companyId)
        val base = auditSpecification(null, null, null, null, null, scope, null, null, null, fetch = false)
        val today = LocalDate.now().atStartOfDay()
        val security = Specification<AuditLog> { root, _, cb -> root.get<AuditAction>("changeType").`in`(SECURITY_ACTIONS) }
        val total = audits.count(base)
        val failed = audits.count(base.and { root, _, cb -> cb.equal(root.get<AuditAction>("changeType"), AuditAction.LOGIN_FAILED) })
        return AuditSummaryResponse(total, audits.count(base.and { root, _, cb -> cb.greaterThanOrEqualTo(root.get("changedAt"), today) }),
            total - failed, failed, audits.count(base.and(security)))
    }

    @Transactional(readOnly = true)
    fun exportAudit(search: String?, action: AuditAction?, module: String?, userId: Long?, role: Role?, companyId: Long?,
        result: String?, from: LocalDateTime?, to: LocalDateTime?): String {
        val scope = scopedCompanyId(companyId)
        val page = audits.findAll(auditSpecification(search, action, module, userId, role, scope, result, from, to, true),
            PageRequest.of(0, 10_000, Sort.by(Sort.Direction.DESC, "changedAt")))
        return buildString {
            appendLine("Timestamp,User,Username,Role,Action,Module,Entity,Record ID,Company ID,Status,Description,Before,After")
            page.content.map(::auditResponse).forEach { e ->
                appendLine(listOf(e.timestamp, e.actorName, e.actorUsername, e.actorRole, e.action, e.module,
                    e.entityType, e.entityId, e.companyId, e.status, e.description, e.beforeValue, e.afterValue)
                    .joinToString(",") { csv(it?.toString()) })
            }
        }
    }

    private fun auditSpecification(search: String?, action: AuditAction?, module: String?, userId: Long?, role: Role?,
        companyId: Long?, result: String?, from: LocalDateTime?, to: LocalDateTime?, fetch: Boolean) = Specification<AuditLog> { root, query, cb ->
        if (fetch && query.resultType != java.lang.Long::class.java && query.resultType != Long::class.java) {
            root.fetch<AuditLog, User>("changedBy", JoinType.INNER)
            root.fetch<AuditLog, Staff>("staff", JoinType.LEFT)
            query.distinct(true)
        }
        val predicates = mutableListOf<jakarta.persistence.criteria.Predicate>()
        companyId?.let {
            val member = root.join<AuditLog, Staff>("staff", JoinType.LEFT)
            val actor = root.join<AuditLog, User>("changedBy", JoinType.INNER)
            predicates += cb.or(
                cb.equal(root.get<Long>("companyId"), it),
                cb.equal(member.get<Any>("company").get<Long>("id"), it),
                cb.and(cb.isNull(root.get<Long>("companyId")), cb.isNull(root.get<Staff>("staff")), cb.equal(actor.get<Long>("companyId"), it))
            )
        }
        action?.let { predicates += cb.equal(root.get<AuditAction>("changeType"), it) }
        module?.trim()?.takeIf(String::isNotEmpty)?.let {
            val pattern = "%${escapeLike(it.lowercase())}%"
            predicates += cb.or(cb.like(cb.lower(root.get("entityType")), pattern, '\\'), cb.like(cb.lower(root.get("fieldName")), pattern, '\\'))
        }
        userId?.let { predicates += cb.equal(root.get<User>("changedBy").get<Long>("id"), it) }
        role?.let { predicates += cb.equal(root.get<User>("changedBy").get<Role>("role"), it) }
        from?.let { predicates += cb.greaterThanOrEqualTo(root.get("changedAt"), it) }
        to?.let { predicates += cb.lessThan(root.get("changedAt"), it) }
        if (result.equals("FAILED", true)) predicates += cb.equal(root.get<AuditAction>("changeType"), AuditAction.LOGIN_FAILED)
        if (result.equals("SUCCESS", true)) predicates += cb.notEqual(root.get<AuditAction>("changeType"), AuditAction.LOGIN_FAILED)
        search?.trim()?.lowercase()?.takeIf(String::isNotEmpty)?.let {
            val pattern = "%${escapeLike(it)}%"
            val actor = root.join<AuditLog, User>("changedBy", JoinType.INNER)
            predicates += cb.or(
                cb.like(cb.lower(actor.get("fullName")), pattern, '\\'), cb.like(cb.lower(actor.get("username")), pattern, '\\'),
                cb.like(cb.lower(actor.get("email")), pattern, '\\'), cb.like(cb.lower(root.get("entityType")), pattern, '\\'),
                cb.like(cb.lower(root.get("fieldName")), pattern, '\\'),
                cb.like(root.get<Long>("entityId").`as`(String::class.java), pattern, '\\'),
                cb.like(cb.lower(root.get<AuditAction>("changeType").`as`(String::class.java)), pattern, '\\')
            )
        }
        cb.and(*predicates.toTypedArray())
    }

    @Transactional(readOnly = true)
    fun listNotifications(page: Int, size: Int, search: String?, type: NotificationType?, category: String?, priority: String?,
        read: Boolean?, from: LocalDateTime?, to: LocalDateTime?): PageResponse<NotificationResponse> {
        if (from != null && to != null && to.isBefore(from)) throw BadRequestException("Notification end time cannot be before start time")
        val specification = notificationSpecification(SecurityUtils.currentUserId(), search, type, category, priority, read, from, to)
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100), Sort.by(Sort.Direction.DESC, "createdAt"))
        return PageResponse.from(notifications.findAll(specification, pageable), ::notificationResponse)
    }
    @Transactional(readOnly = true) fun getNotification(id: Long) = notificationResponse(ownedNotification(id))
    @Transactional(readOnly = true) fun notificationSummary(): NotificationSummaryResponse {
        val userId = SecurityUtils.currentUserId(); val base = notificationSpecification(userId, null, null, null, null, null, null, null)
        val unread = base.and { root, _, cb -> cb.isFalse(root.get("isRead")) }
        val today = base.and { root, _, cb -> cb.greaterThanOrEqualTo(root.get("createdAt"), LocalDate.now().atStartOfDay()) }
        return NotificationSummaryResponse(notifications.count(base), notifications.count(unread), notifications.count(today))
    }
    @Transactional fun updateNotification(id: Long, request: NotificationRequest): NotificationResponse {
        val entity = ownedNotification(id); val read = request.isRead ?: throw BadRequestException("isRead is required")
        entity.isRead = read; entity.readAt = if (read) LocalDateTime.now() else null
        return notificationResponse(notifications.save(entity))
    }
    @Transactional fun markAllNotificationsRead(): NotificationSummaryResponse {
        notifications.findAll(notificationSpecification(SecurityUtils.currentUserId(), null, null, null, null, false, null, null)).forEach {
            it.isRead = true; it.readAt = LocalDateTime.now()
        }
        return notificationSummary()
    }
    @Transactional fun deleteNotification(id: Long) = notifications.delete(ownedNotification(id))

    private fun notificationSpecification(userId: Long, search: String?, type: NotificationType?, category: String?, priority: String?,
        read: Boolean?, from: LocalDateTime?, to: LocalDateTime?) = Specification<Notification> { root, _, cb ->
        val predicates = mutableListOf<jakarta.persistence.criteria.Predicate>(cb.equal(root.get<User>("recipient").get<Long>("id"), userId))
        type?.let { predicates += cb.equal(root.get<NotificationType>("type"), it) }
        read?.let { predicates += cb.equal(root.get<Boolean>("isRead"), it) }
        from?.let { predicates += cb.greaterThanOrEqualTo(root.get("createdAt"), it) }
        to?.let { predicates += cb.lessThan(root.get("createdAt"), it) }
        search?.trim()?.lowercase()?.takeIf(String::isNotEmpty)?.let {
            val pattern = "%${escapeLike(it)}%"
            predicates += cb.or(cb.like(cb.lower(root.get("message")), pattern, '\\'), cb.like(cb.lower(root.get<NotificationType>("type").`as`(String::class.java)), pattern, '\\'))
        }
        category?.uppercase()?.takeIf { it != "ALL" }?.let { wanted ->
            val types = NotificationType.entries.filter { notificationMeta(it).category == wanted }
            predicates += if (types.isEmpty()) cb.disjunction() else root.get<NotificationType>("type").`in`(types)
        }
        priority?.uppercase()?.takeIf { it != "ALL" }?.let { wanted ->
            val types = NotificationType.entries.filter { notificationMeta(it).priority == wanted }
            predicates += if (types.isEmpty()) cb.disjunction() else root.get<NotificationType>("type").`in`(types)
        }
        cb.and(*predicates.toTypedArray())
    }

    @Transactional(readOnly = true)
    fun listSettings(includeDeleted: Boolean) = settings.findAll()
        .filter { it.kind == NOTIFICATION_SETTING_KIND }
        .filter { includeDeleted || !it.isDeleted }
        .map(::settingResponse)

    /** Global event rules used by the trusted notification delivery path. */
    @Transactional(readOnly = true)
    fun isNotificationEnabled(type: NotificationType): Boolean {
        val rules = settings.findByKind(NOTIFICATION_SETTING_KIND)
        return when (type) {
            NotificationType.STAFF_ONBOARDED -> rules?.onboarding ?: true
            NotificationType.STAFF_EXITED -> rules?.exits ?: true
            NotificationType.DEPARTMENT_CHANGE,
            NotificationType.PROMOTION,
            NotificationType.TITLE_CHANGE,
            NotificationType.DEPARTMENT_TRANSFER,
            NotificationType.COMPANY_TRANSFER,
            NotificationType.REPORTING_LINE_CHANGE -> rules?.transfers ?: true
            NotificationType.VACANCY_OPENED,
            NotificationType.VACANCY_CLOSED -> rules?.vacancies ?: false
            // Security and system messages must never be suppressed by a
            // workforce preference.
            NotificationType.COMPANY_ADDED,
            NotificationType.SYSTEM -> true
        }
    }
    @Transactional fun createSetting(request: SettingRequest): SettingResponse {
        val kind = validateKind(request.kind); if (settings.findByKind(kind) != null) throw BadRequestException("Setting '$kind' already exists")
        val saved = settings.save(applyValues(SystemSetting(kind), request.values)); recordSettingAudit(saved, AuditAction.CREATE, null); return settingResponse(saved)
    }
    @Transactional fun updateSetting(id: Long, request: SettingRequest): SettingResponse {
        val entity = setting(id); val old = safeSettingValue(entity); request.kind?.let { entity.kind = validateKind(it) }
        val saved = settings.save(applyValues(entity, request.values)); recordSettingAudit(saved, AuditAction.UPDATE, old); return settingResponse(saved)
    }

    private fun recordSettingAudit(setting: SystemSetting, action: AuditAction, old: String?) = audits.save(AuditLog(
        changedBy = currentUser(), changeType = action, fieldName = "Settings", entityType = "Setting", entityId = setting.id,
        oldValue = old, newValue = safeSettingValue(setting)))
    private fun safeSettingValue(e: SystemSetting) = "kind=${e.kind},values=${settingResponse(e).values}"
    private fun scopedCompanyId(requested: Long?): Long? { val principal = SecurityUtils.currentPrincipal(); if (principal.isSuperAdmin) return requested
        val own = principal.companyId ?: throw ForbiddenException("Your account is not assigned to a company"); if (requested != null && requested != own) throw ForbiddenException("You cannot access audit events from another company"); return own }
    private fun currentUser() = users.findById(SecurityUtils.currentUserId()).orElseThrow { ResourceNotFoundException("Authenticated user", SecurityUtils.currentUserId()) }
    private fun ownedNotification(id: Long): Notification = notifications.findByIdAndRecipientId(id, SecurityUtils.currentUserId())
        ?: throw ResourceNotFoundException("Notification",id)
    private fun setting(id: Long)=settings.findById(id).orElseThrow{ResourceNotFoundException("Setting",id)}
    private fun auditResponse(e: AuditLog): AuditResponse {
        val entityType = e.entityType.ifBlank { e.fieldName.substringBefore(" #") }
        val before = redact(e.oldValue); val after = redact(e.newValue); val module = moduleName(entityType, e.fieldName)
        return AuditResponse(e.id!!, e.changedBy.id!!, e.changedBy.fullName ?: e.changedBy.username, e.changedBy.username,
            e.changedBy.email, e.changedBy.role, e.changeType, module, entityType, e.entityId ?: e.staff?.id,
            e.companyId ?: e.staff?.company?.id ?: e.changedBy.companyId, e.staff?.id,
            after?.take(500) ?: "${e.changeType} $entityType${e.entityId?.let { id -> " #$id" } ?: ""}", before, after,
            status = if (e.changeType == AuditAction.LOGIN_FAILED) "FAILED" else "SUCCESS", timestamp = e.changedAt)
    }
    private fun moduleName(entity: String, field: String) = when {
        entity.contains("User", true) || field.contains("User", true) -> "Users & Roles"
        entity.contains("Auth", true) || entity.contains("Security", true) || field.contains("Login", true) || field.contains("Password", true) -> "Authentication"
        entity.contains("Vacancy", true) -> "Vacancies"
        entity.contains("Position", true) -> "Positions"
        entity.contains("Staff", true) -> "Staff"
        entity.contains("Department", true) -> "Departments"
        entity.contains("Company", true) -> "Companies"
        entity.contains("Setting", true) -> "Settings"
        else -> field.substringBefore(" #")
    }
    private fun redact(value: String?): String? { if (value == null) return null; var safe: String=value
        SENSITIVE_KEYS.forEach { key -> safe=safe.replace(Regex("(?i)($key\\s*[=:]\\s*)[^,;\\s}]+"), "$1[REDACTED]") }
        safe=safe.replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._~-]+"), "Bearer [REDACTED]"); return safe.take(10_000) }
    private fun applyValues(e:SystemSetting,v:Map<String,Boolean>):SystemSetting{v["onboarding"]?.let{e.onboarding=it};v["exits"]?.let{e.exits=it};v["transfers"]?.let{e.transfers=it};v["vacancies"]?.let{e.vacancies=it};return e}
    private fun validateKind(k:String?):String{val v=k?.trim()?:throw BadRequestException("kind is required");if(v != NOTIFICATION_SETTING_KIND)throw BadRequestException("Unsupported setting kind: $v");return v}
    private fun notificationResponse(e:Notification):NotificationResponse{val d=notificationDisplay(e.type);val m=notificationMeta(e.type);return NotificationResponse(e.id!!,e.type,d.first,e.message,d.second,d.third,m.category,m.priority,e.link,e.entityType,e.entityId,e.isRead,e.readAt,false,e.createdAt,e.createdAt)}
    fun toNotificationResponse(entity: Notification): NotificationResponse = notificationResponse(entity)
    private data class NotificationMeta(val category:String,val priority:String)
    private fun notificationMeta(t:NotificationType)=when(t){NotificationType.STAFF_EXITED,NotificationType.VACANCY_CLOSED->NotificationMeta("WORKFORCE","HIGH");NotificationType.SYSTEM->NotificationMeta("SYSTEM","NORMAL");NotificationType.VACANCY_OPENED->NotificationMeta("VACANCY","NORMAL");NotificationType.COMPANY_ADDED->NotificationMeta("ORGANIZATION","NORMAL");NotificationType.STAFF_ONBOARDED->NotificationMeta("WORKFORCE","NORMAL");else->NotificationMeta("ORGANIZATION","NORMAL")}
    private fun notificationDisplay(t:NotificationType)=when(t){NotificationType.STAFF_ONBOARDED->Triple("New staff onboarded","pi pi-user-plus","#34d399");NotificationType.STAFF_EXITED->Triple("Staff exited","pi pi-sign-out","#f87171");NotificationType.COMPANY_ADDED->Triple("Company registered","pi pi-building","#0f8bfd");NotificationType.VACANCY_OPENED->Triple("Vacancy opened","pi pi-inbox","#fbbf24");NotificationType.VACANCY_CLOSED->Triple("Vacancy closed","pi pi-check-circle","#34d399");NotificationType.DEPARTMENT_TRANSFER,NotificationType.DEPARTMENT_CHANGE->Triple("Department changed","pi pi-sitemap","#8b5cf6");NotificationType.COMPANY_TRANSFER->Triple("Company transfer","pi pi-building","#8b5cf6");NotificationType.PROMOTION,NotificationType.TITLE_CHANGE->Triple("Staff updated","pi pi-star","#fbbf24");NotificationType.REPORTING_LINE_CHANGE->Triple("Reporting line changed","pi pi-share-alt","#8b5cf6");NotificationType.SYSTEM->Triple("System notification","pi pi-bell","#0f8bfd")}
    private fun settingResponse(e:SystemSetting)=SettingResponse(e.id!!,e.kind,mapOf("onboarding" to(e.onboarding?:true),"exits" to(e.exits?:true),"transfers" to(e.transfers?:true),"vacancies" to(e.vacancies?:false)),e.isDeleted,e.createdAt,e.updatedAt)
    private fun requiredText(v:String?,f:String)=v?.trim()?.takeIf{it.isNotEmpty()}?:throw BadRequestException("$f is required")
    private fun escapeLike(v:String)=v.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")
    private fun csv(v:String?):String { var safe=(v?:"").replace("\r"," ").replace("\n"," ");if(safe.firstOrNull() in setOf('=','+','-','@','\t'))safe="'$safe";return "\"${safe.replace("\"","\"\"")}\"" }
    companion object { private const val NOTIFICATION_SETTING_KIND="notification-preferences";private val SORT_FIELDS=mapOf("timestamp" to "changedAt","changedAt" to "changedAt","user" to "changedBy.fullName","action" to "changeType","module" to "entityType");private val SECURITY_ACTIONS=setOf(AuditAction.LOGIN,AuditAction.LOGIN_FAILED,AuditAction.LOGOUT,AuditAction.PASSWORD_CHANGE,AuditAction.PASSWORD_RESET);private val SENSITIVE_KEYS=listOf("password","passwordHash","password_hash","token","jwt","secret","apiKey","api_key","clientSecret","credential") }
}
