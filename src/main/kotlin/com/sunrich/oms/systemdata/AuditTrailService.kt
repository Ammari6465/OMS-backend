package com.sunrich.oms.systemdata

import com.sunrich.oms.common.enums.AuditAction
import com.sunrich.oms.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Trusted server-side writer for immutable audit events. */
@Service
class AuditTrailService(private val audits: AuditLogRepository) {
    @Transactional
    fun record(actor: User, action: AuditAction, entityType: String, entityId: Long? = null,
        companyId: Long? = actor.companyId, fieldName: String = entityType,
        before: String? = null, after: String? = null) {
        audits.save(AuditLog(changedBy = actor, changeType = action, fieldName = fieldName.take(100),
            entityType = entityType.take(50), entityId = entityId, companyId = companyId,
            oldValue = safe(before), newValue = safe(after)))
    }

    private fun safe(value: String?): String? {
        if (value == null) return null
        val lower = value.lowercase()
        require(SENSITIVE.none(lower::contains)) { "Sensitive data must not be written to the audit trail" }
        return value.take(10_000)
    }

    companion object {
        private val SENSITIVE = listOf("password=", "passwordhash", "password_hash", "token=", "jwt=", "secret=", "apikey=", "api_key=")
    }
}
