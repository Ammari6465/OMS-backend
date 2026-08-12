package com.sunrich.oms.common.enums

/**
 * System roles (RBAC). Authorities are exposed to Spring Security as
 * "ROLE_<name>" so that hasRole("SUPER_ADMIN") etc. resolve correctly.
 */
enum class Role {
    SUPER_ADMIN,
    COMPANY_ADMIN,
    MANAGER,
    STAFF,
    READ_ONLY;

    companion object {
        fun fromString(value: String?): Role =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: READ_ONLY
    }
}

/** Employment type of a staff member. */
enum class EmploymentType {
    PERMANENT,
    CONTRACT,
    INTERN;

    companion object {
        fun fromString(value: String?): EmploymentType =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: PERMANENT
    }
}

/** Generic active/inactive status for companies, departments, staff, users. */
enum class EntityStatus {
    ACTIVE,
    INACTIVE
}

/** Lifecycle status of a position. */
enum class PositionStatus {
    OPEN,
    FILLED,
    CLOSED
}

/** Type of change recorded in the audit log. */
enum class AuditAction {
    CREATE,
    UPDATE,
    DELETE,
    RESTORE,
    TRANSFER,
    REPARENT,
    LOGIN,
    LOGOUT,
    PASSWORD_RESET,
    IMPORT
}

/** Categories of notifications the system can emit. */
enum class NotificationType {
    STAFF_ONBOARDED,
    STAFF_EXITED,
    COMPANY_ADDED,
    DEPARTMENT_CHANGE,
    PROMOTION,
    TITLE_CHANGE,
    DEPARTMENT_TRANSFER,
    COMPANY_TRANSFER,
    REPORTING_LINE_CHANGE,
    VACANCY_OPENED,
    VACANCY_CLOSED,
    SYSTEM
}
