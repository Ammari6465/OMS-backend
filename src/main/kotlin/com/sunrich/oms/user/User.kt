package com.sunrich.oms.user

import com.sunrich.oms.common.entity.BaseEntity
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.organization.Company
import com.sunrich.oms.organization.Staff
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * A system login account. Linked (optionally) to a staff record.
 */
@Entity
@Table(name = "users")
class User(

    @Column(name = "username", nullable = false, length = 100, unique = true)
    var username: String,

    @Column(name = "email", nullable = false, length = 200, unique = true)
    var email: String,

    @Column(name = "password_hash", nullable = false, length = 255)
    var passwordHash: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    var role: Role = Role.READ_ONLY,

    @Column(name = "full_name", length = 200)
    var fullName: String? = null,

    @Column(name = "staff_id")
    var staffId: Long? = null,

    @Column(name = "company_id")
    var companyId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: EntityStatus = EntityStatus.ACTIVE,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "last_login")
    var lastLogin: LocalDateTime? = null,

    @Column(name = "failed_login_attempts", nullable = false)
    var failedLoginAttempts: Int = 0,

    @Column(name = "locked_until")
    var lockedUntil: LocalDateTime? = null,

    @Column(name = "password_reset_token", length = 100)
    var passwordResetToken: String? = null,

    @Column(name = "password_reset_expires")
    var passwordResetExpires: LocalDateTime? = null

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    var id: Long? = null

    /** Read-only associations make the existing scalar API fields real FK columns. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "company_id",
        insertable = false,
        updatable = false,
        foreignKey = ForeignKey(name = "fk_users_company")
    )
    var company: Company? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "staff_id",
        insertable = false,
        updatable = false,
        foreignKey = ForeignKey(name = "fk_users_staff")
    )
    var staff: Staff? = null

    val isLocked: Boolean
        get() = lockedUntil?.isAfter(LocalDateTime.now()) == true
}
