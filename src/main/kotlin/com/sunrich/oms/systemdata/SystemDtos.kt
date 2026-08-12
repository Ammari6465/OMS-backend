package com.sunrich.oms.systemdata

import com.sunrich.oms.common.enums.AuditAction
import com.sunrich.oms.common.enums.NotificationType
import java.time.LocalDateTime

data class AuditRequest(
    val staffId: Long? = null,
    val changeType: AuditAction? = null,
    val fieldName: String? = null,
    val oldValue: String? = null,
    val newValue: String? = null,
    // Compatibility fields used by the existing Angular audit screen.
    val entityType: String? = null,
    val entityId: Long? = null,
    val action: AuditAction? = null,
    val summary: String? = null,
    val changedBy: String? = null,
    val changedAt: LocalDateTime? = null
)

data class AuditResponse(
    val id: Long,
    val staffId: Long?,
    val changedByUserId: Long,
    val changeType: AuditAction,
    val fieldName: String,
    val oldValue: String?,
    val newValue: String?,
    val entityType: String,
    val entityId: Long?,
    val action: AuditAction,
    val summary: String,
    val changedBy: String,
    val changedAt: LocalDateTime,
    val isDeleted: Boolean = false,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)

data class NotificationRequest(
    val type: NotificationType? = null,
    val title: String? = null,
    val message: String? = null,
    val icon: String? = null,
    val color: String? = null,
    val isRead: Boolean? = null
)

data class NotificationResponse(
    val id: Long,
    val type: NotificationType,
    val title: String,
    val message: String,
    val icon: String,
    val color: String,
    val isRead: Boolean,
    val isDeleted: Boolean = false,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)

data class SettingRequest(val kind: String? = null, val values: Map<String, Boolean> = emptyMap())
data class SettingResponse(
    val id: Long,
    val kind: String,
    val values: Map<String, Boolean>,
    val isDeleted: Boolean,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)
