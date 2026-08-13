package com.sunrich.oms.systemdata

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.repository.Repository

/** Append/read-only repository: intentionally exposes no update or delete operations. */
interface AuditLogRepository : Repository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
    fun save(entity: AuditLog): AuditLog
    fun findAll(): List<AuditLog>
    fun count(): Long
}
interface NotificationRepository : JpaRepository<Notification, Long>
interface SystemSettingRepository : JpaRepository<SystemSetting, Long> {
    fun findByKind(kind: String): SystemSetting?
}
