package com.sunrich.oms.systemdata

import com.sunrich.oms.common.enums.AuditAction
import com.sunrich.oms.common.enums.NotificationType
import com.sunrich.oms.common.enums.Role
import java.time.LocalDateTime

data class AuditResponse(
    val id: Long,
    val actorId: Long,
    val actorName: String,
    val actorUsername: String,
    val actorEmail: String,
    val actorRole: Role,
    val action: AuditAction,
    val module: String,
    val entityType: String,
    val entityId: Long?,
    val companyId: Long?,
    val staffId: Long?,
    val description: String,
    val beforeValue: String?,
    val afterValue: String?,
    val status: String = "SUCCESS",
    val timestamp: LocalDateTime,
    val changedByUserId: Long = actorId,
    val changeType: AuditAction = action,
    val fieldName: String = module,
    val oldValue: String? = beforeValue,
    val newValue: String? = afterValue,
    val summary: String = description,
    val changedBy: String = actorName,
    val changedAt: LocalDateTime = timestamp,
    val isDeleted: Boolean = false,
    val createdAt: LocalDateTime = timestamp,
    val updatedAt: LocalDateTime = timestamp
)

data class AuditSummaryResponse(
    val totalEvents: Long,
    val todayEvents: Long,
    val successfulActions: Long,
    val failedActions: Long,
    val securityEvents: Long
)

data class NotificationRequest(
    val isRead: Boolean? = null
)
data class NotificationResponse(
    val id: Long, val type: NotificationType, val title: String, val message: String,
    val icon: String, val color: String, val category: String, val priority: String,
    val link: String?, val entityType: String?, val entityId: Long?,
    val isRead: Boolean, val readAt: LocalDateTime?, val isDeleted: Boolean = false,
    val createdAt: LocalDateTime?, val updatedAt: LocalDateTime?
)
data class NotificationSummaryResponse(val total: Long, val unread: Long, val today: Long)
data class SettingRequest(val kind: String? = null, val values: Map<String, Boolean> = emptyMap())
data class SettingResponse(
    val id: Long, val kind: String, val values: Map<String, Boolean>, val isDeleted: Boolean,
    val createdAt: LocalDateTime?, val updatedAt: LocalDateTime?
)
