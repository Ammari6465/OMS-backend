package com.sunrich.oms.record

import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.security.SecurityUtils
import org.springframework.stereotype.Component

@Component("recordAuth")
class RecordAuthorization {
    private val organisationCollections = setOf("companies", "departments", "staff", "positions")

    fun canCreate(collection: String): Boolean {
        val role = SecurityUtils.currentPrincipal().role
        return when (collection) {
            in organisationCollections -> role == Role.SUPER_ADMIN || role == Role.COMPANY_ADMIN
            "settings" -> role == Role.SUPER_ADMIN
            "audit", "notifications" -> true
            else -> false
        }
    }

    fun canModify(collection: String): Boolean {
        val role = SecurityUtils.currentPrincipal().role
        return when (collection) {
            in organisationCollections -> role == Role.SUPER_ADMIN || role == Role.COMPANY_ADMIN
            "settings" -> role == Role.SUPER_ADMIN
            "notifications" -> true
            else -> false
        }
    }
}
