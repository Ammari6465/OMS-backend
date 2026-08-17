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
    ON_HOLD,
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
    LOGIN_FAILED,
    LOGOUT,
    PASSWORD_CHANGE,
    PASSWORD_RESET,
    IMPORT,
    WORKFLOW_SUBMIT,
    WORKFLOW_APPROVE,
    WORKFLOW_REJECT,
    WORKFLOW_EXECUTE,
    WORKFLOW_CANCEL
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
    WORKFLOW_SUBMITTED,
    WORKFLOW_APPROVED,
    WORKFLOW_REJECTED,
    WORKFLOW_SCHEDULED,
    WORKFLOW_COMPLETED,
    WORKFLOW_FAILED,
    POSITION_ON_HOLD,
    POSITION_CLOSED,
    USER_DEACTIVATED,
    JOINER_ACTIVATED,
    TRANSFER_COMPLETED,
    SYSTEM
}
