package com.sunrich.oms.systemdata

import com.sunrich.oms.common.entity.BaseEntity
import com.sunrich.oms.common.enums.AuditAction
import com.sunrich.oms.common.enums.NotificationType
import com.sunrich.oms.organization.Staff
import com.sunrich.oms.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.Lob
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.LocalDateTime

/** SRS audit_log: append-only; this entity intentionally has no setters or soft-delete fields. */
@Entity
@Immutable
@Table(
    name = "audit_log",
    indexes = [
        Index(name = "idx_audit_staff", columnList = "staff_id"),
        Index(name = "idx_audit_changed_by", columnList = "changed_by"),
        Index(name = "idx_audit_changed_at", columnList = "changed_at")
    ]
)
class AuditLog(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id", foreignKey = ForeignKey(name = "fk_audit_staff"))
    val staff: Staff? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by", nullable = false, foreignKey = ForeignKey(name = "fk_audit_changed_by"))
    val changedBy: User,

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 30)
    val changeType: AuditAction,

    @Column(name = "field_name", nullable = false, length = 100)
    val fieldName: String,

    @Column(name = "entity_type", nullable = false, length = 50)
    val entityType: String = fieldName.substringBefore(" #"),

    @Column(name = "entity_id")
    val entityId: Long? = staff?.id,

    @Column(name = "company_id")
    val companyId: Long? = staff?.company?.id ?: changedBy.companyId,

    @Lob
    @Column(name = "old_value", columnDefinition = "TEXT")
    val oldValue: String? = null,

    @Lob
    @Column(name = "new_value", columnDefinition = "TEXT")
    val newValue: String? = null,

    @Column(name = "changed_at", nullable = false, updatable = false)
    val changedAt: LocalDateTime = LocalDateTime.now()
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    var id: Long? = null
        protected set
}

/** SRS notifications table. UI decoration is derived from [type], not persisted. */
@Entity
@Table(
    name = "notifications",
    indexes = [Index(name = "idx_notifications_recipient", columnList = "recipient_user_id,is_read")]
)
class Notification(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "recipient_user_id",
        nullable = false,
        foreignKey = ForeignKey(name = "fk_notifications_recipient")
    )
    var recipient: User,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    var type: NotificationType,

    @Column(nullable = false, length = 1000)
    var message: String,

    @Column(length = 500)
    var link: String? = null,

    @Column(name = "entity_type", length = 50)
    var entityType: String? = null,

    @Column(name = "entity_id")
    var entityId: Long? = null,

    @Column(name = "is_read", nullable = false)
    var isRead: Boolean = false,

    @Column(name = "read_at")
    var readAt: LocalDateTime? = null,

    @Column(name = "email_sent", nullable = false)
    var emailSent: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notif_id")
    var id: Long? = null
        protected set
}

@Entity
@Table(name = "system_settings")
class SystemSetting(
    @Column(name = "setting_kind", nullable = false, length = 80, unique = true)
    var kind: String,

    @Column(name = "onboarding_enabled") var onboarding: Boolean? = null,
    @Column(name = "exits_enabled") var exits: Boolean? = null,
    @Column(name = "transfers_enabled") var transfers: Boolean? = null,
    @Column(name = "vacancies_enabled") var vacancies: Boolean? = null,
    @Column(name = "super_admin_reset") var superAdmin: Boolean? = null,
    @Column(name = "company_admin_reset") var companyAdmin: Boolean? = null,
    @Column(name = "manager_reset") var manager: Boolean? = null,
    @Column(name = "staff_reset") var staff: Boolean? = null,
    @Column(name = "read_only_reset") var readOnly: Boolean? = null
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "setting_id")
    var id: Long? = null
}
