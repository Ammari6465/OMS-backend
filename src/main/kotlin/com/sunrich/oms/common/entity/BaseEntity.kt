package com.sunrich.oms.common.entity

import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Version
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

/**
 * Base class for all persistent, soft-deletable, auditable entities.
 *
 * Provides:
 *  - optimistic locking (version)
 *  - automatic created/updated timestamps and actor ids (JPA auditing)
 *  - soft delete flags (isDeleted / deletedAt) so data can be restored
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class BaseEntity {

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    var createdBy: Long? = null

    @LastModifiedBy
    @Column(name = "updated_by")
    var updatedBy: Long? = null

    @Column(name = "is_deleted", nullable = false)
    var isDeleted: Boolean = false

    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null

    fun markDeleted() {
        this.isDeleted = true
        this.deletedAt = LocalDateTime.now()
    }

    fun restore() {
        this.isDeleted = false
        this.deletedAt = null
    }
}
