package com.sunrich.oms.systemdata

import com.sunrich.oms.common.enums.NotificationType
import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.exception.ResourceNotFoundException
import com.sunrich.oms.security.SecurityUtils
import com.sunrich.oms.organization.StaffRepository
import com.sunrich.oms.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SystemDataService(
    private val audits: AuditLogRepository,
    private val notifications: NotificationRepository,
    private val settings: SystemSettingRepository,
    private val users: UserRepository,
    private val staff: StaffRepository
) {
    @Transactional(readOnly = true)
    fun listAudit(): List<AuditResponse> = audits.findAll()
        .sortedByDescending { it.changedAt }
        .map(::auditResponse)

    @Transactional
    fun createAudit(request: AuditRequest): AuditResponse {
        val actor = currentUser()
        val staffId = request.staffId ?: request.entityId.takeIf { request.entityType.equals("Staff", true) }
        val entity = AuditLog(
            staff = staffId?.let { staff.findById(it).orElseThrow { ResourceNotFoundException("Staff", it) } },
            changedBy = actor,
            changeType = request.changeType ?: request.action ?: throw BadRequestException("changeType is required"),
            fieldName = requiredText(request.fieldName ?: request.entityType, "fieldName"),
            oldValue = request.oldValue,
            newValue = request.newValue ?: request.summary,
            changedAt = request.changedAt ?: java.time.LocalDateTime.now()
        )
        return auditResponse(audits.save(entity))
    }

    @Transactional(readOnly = true)
    fun listNotifications(): List<NotificationResponse> {
        val userId = SecurityUtils.currentUserId()
        return notifications.findAll()
            .filter { it.recipient.id == userId }
            .sortedByDescending { it.createdAt }
            .map(::notificationResponse)
    }

    @Transactional
    fun createNotification(request: NotificationRequest): NotificationResponse = notificationResponse(notifications.save(
        Notification(
            recipient = currentUser(),
            type = request.type ?: NotificationType.SYSTEM,
            message = requiredText(request.message, "Notification message"),
            isRead = request.isRead ?: false
        )
    ))

    @Transactional
    fun updateNotification(id: Long, request: NotificationRequest): NotificationResponse {
        val entity = ownedNotification(id)
        request.type?.let { entity.type = it }
        request.message?.let { entity.message = requiredText(it, "Notification message") }
        request.isRead?.let { entity.isRead = it }
        return notificationResponse(notifications.save(entity))
    }

    @Transactional
    fun deleteNotification(id: Long) = notifications.delete(ownedNotification(id))

    @Transactional(readOnly = true)
    fun listSettings(includeDeleted: Boolean): List<SettingResponse> = settings.findAll()
        .filter { includeDeleted || !it.isDeleted }.map(::settingResponse)

    @Transactional
    fun createSetting(request: SettingRequest): SettingResponse {
        val kind = validateKind(request.kind)
        if (settings.findByKind(kind) != null) throw BadRequestException("Setting '$kind' already exists")
        return settingResponse(settings.save(applyValues(SystemSetting(kind), request.values)))
    }

    @Transactional
    fun updateSetting(id: Long, request: SettingRequest): SettingResponse {
        val entity = setting(id)
        request.kind?.let { entity.kind = validateKind(it) }
        return settingResponse(settings.save(applyValues(entity, request.values)))
    }

    private fun currentUser() = users.findById(SecurityUtils.currentUserId())
        .orElseThrow { ResourceNotFoundException("Authenticated user", SecurityUtils.currentUserId()) }

    private fun ownedNotification(id: Long): Notification {
        val entity = notifications.findById(id).orElseThrow { ResourceNotFoundException("Notification", id) }
        val userId = SecurityUtils.currentUserId()
        if (entity.recipient.id != userId) throw ResourceNotFoundException("Notification", id)
        return entity
    }

    private fun setting(id: Long) = settings.findById(id)
        .orElseThrow { ResourceNotFoundException("Setting", id) }

    private fun applyValues(entity: SystemSetting, values: Map<String, Boolean>): SystemSetting {
        if (entity.kind == "notification-preferences") {
            values["onboarding"]?.let { entity.onboarding = it }
            values["exits"]?.let { entity.exits = it }
            values["transfers"]?.let { entity.transfers = it }
            values["vacancies"]?.let { entity.vacancies = it }
        } else {
            values["SUPER_ADMIN"]?.let { entity.superAdmin = it }
            values["COMPANY_ADMIN"]?.let { entity.companyAdmin = it }
            values["MANAGER"]?.let { entity.manager = it }
            values["STAFF"]?.let { entity.staff = it }
            values["READ_ONLY"]?.let { entity.readOnly = it }
        }
        return entity
    }

    private fun validateKind(kind: String?): String {
        val value = kind?.trim() ?: throw BadRequestException("kind is required")
        if (value !in setOf("notification-preferences", "password-reset-roles")) {
            throw BadRequestException("Unsupported setting kind: $value")
        }
        return value
    }

    private fun auditResponse(e: AuditLog) = AuditResponse(
        id = e.id!!,
        staffId = e.staff?.id,
        changedByUserId = e.changedBy.id!!,
        changeType = e.changeType,
        fieldName = e.fieldName,
        oldValue = e.oldValue,
        newValue = e.newValue,
        entityType = e.fieldName,
        entityId = e.staff?.id,
        action = e.changeType,
        summary = e.newValue ?: "${e.changeType} ${e.fieldName}",
        changedBy = e.changedBy.fullName ?: e.changedBy.username,
        changedAt = e.changedAt,
        createdAt = e.changedAt,
        updatedAt = e.changedAt
    )

    private fun notificationResponse(e: Notification): NotificationResponse {
        val display = notificationDisplay(e.type)
        return NotificationResponse(
            e.id!!, e.type, display.first, e.message, display.second, display.third,
            e.isRead, false, e.createdAt, e.createdAt
        )
    }

    private fun notificationDisplay(type: NotificationType): Triple<String, String, String> = when (type) {
        NotificationType.STAFF_ONBOARDED -> Triple("New staff onboarded", "pi pi-user-plus", "#34d399")
        NotificationType.STAFF_EXITED -> Triple("Staff exited", "pi pi-sign-out", "#f87171")
        NotificationType.COMPANY_ADDED -> Triple("Company registered", "pi pi-building", "#0f8bfd")
        NotificationType.VACANCY_OPENED -> Triple("Vacancy opened", "pi pi-inbox", "#fbbf24")
        NotificationType.VACANCY_CLOSED -> Triple("Vacancy closed", "pi pi-check-circle", "#34d399")
        NotificationType.DEPARTMENT_TRANSFER, NotificationType.DEPARTMENT_CHANGE ->
            Triple("Department changed", "pi pi-sitemap", "#8b5cf6")
        NotificationType.COMPANY_TRANSFER -> Triple("Company transfer", "pi pi-building", "#8b5cf6")
        NotificationType.PROMOTION, NotificationType.TITLE_CHANGE -> Triple("Staff updated", "pi pi-star", "#fbbf24")
        NotificationType.REPORTING_LINE_CHANGE -> Triple("Reporting line changed", "pi pi-share-alt", "#8b5cf6")
        NotificationType.SYSTEM -> Triple("System notification", "pi pi-bell", "#0f8bfd")
    }

    private fun settingResponse(e: SystemSetting) = SettingResponse(
        e.id!!,
        e.kind,
        if (e.kind == "notification-preferences") mapOf(
            "onboarding" to (e.onboarding ?: true),
            "exits" to (e.exits ?: true),
            "transfers" to (e.transfers ?: true),
            "vacancies" to (e.vacancies ?: false)
        ) else mapOf(
            "SUPER_ADMIN" to (e.superAdmin ?: true),
            "COMPANY_ADMIN" to (e.companyAdmin ?: true),
            "MANAGER" to (e.manager ?: false),
            "STAFF" to (e.staff ?: false),
            "READ_ONLY" to (e.readOnly ?: false)
        ),
        e.isDeleted,
        e.createdAt,
        e.updatedAt
    )

    private fun requiredText(value: String?, field: String): String =
        value?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("$field is required")
}
