package com.sunrich.oms.user

import com.sunrich.oms.common.enums.Role
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class UserAdminResponse(
    val id: Long,
    val username: String,
    val fullName: String,
    val email: String,
    val role: Role,
    val companyId: Long?,
    val companyName: String?,
    val staffId: Long?,
    val staffName: String?,
    val departmentId: Long?,
    val departmentName: String?,
    val employeeCode: String?,
    val isActive: Boolean,
    val isLocked: Boolean,
    val lockedUntil: LocalDateTime?,
    val failedLoginAttempts: Int,
    val lastLogin: LocalDateTime?,
    val isDeleted: Boolean,
    val version: Long,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)

data class CreateUserRequest(
    @field:NotBlank @field:Size(max = 100) val username: String,
    @field:NotBlank @field:Size(max = 200) val fullName: String,
    @field:NotBlank @field:Email val email: String,
    val role: Role,
    val companyId: Long? = null,
    val staffId: Long? = null,
    val isActive: Boolean = true,
    @field:NotBlank @field:Size(min = 8) val password: String
)

data class UpdateUserRequest(
    @field:NotBlank @field:Size(max = 100) val username: String,
    @field:NotBlank @field:Size(max = 200) val fullName: String,
    @field:NotBlank @field:Email val email: String,
    val role: Role,
    val companyId: Long? = null,
    val staffId: Long? = null,
    val isActive: Boolean = true,
    val version: Long
)

data class UserStatusRequest(val isActive: Boolean, val version: Long)
data class UserRoleRequest(val role: Role, val version: Long)
data class UserVersionRequest(val version: Long)

data class UserSummaryResponse(
    val total: Long,
    val active: Long,
    val inactive: Long,
    val locked: Long,
    val administrators: Long
)

data class RoleResponse(
    val role: Role,
    val description: String,
    val accessLevel: String,
    val permissions: List<String>,
    val assignedUsers: Long
)

fun User.toAdminResponse() = UserAdminResponse(
    id = id!!,
    username = username,
    fullName = fullName ?: username,
    email = email,
    role = role,
    companyId = companyId,
    companyName = company?.name,
    staffId = staffId,
    staffName = staff?.name,
    departmentId = staff?.department?.id,
    departmentName = staff?.department?.name,
    employeeCode = staff?.employeeCode,
    isActive = isActive,
    isLocked = isLocked,
    lockedUntil = lockedUntil,
    failedLoginAttempts = failedLoginAttempts,
    lastLogin = lastLogin,
    isDeleted = isDeleted,
    version = version,
    createdAt = createdAt,
    updatedAt = updatedAt
)
