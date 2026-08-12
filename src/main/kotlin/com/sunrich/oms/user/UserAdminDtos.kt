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
    val staffId: Long?,
    val isActive: Boolean,
    val lastLogin: LocalDateTime?,
    val isDeleted: Boolean,
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
    val isActive: Boolean = true
)

fun User.toAdminResponse() = UserAdminResponse(
    id = id!!,
    username = username,
    fullName = fullName ?: username,
    email = email,
    role = role,
    companyId = companyId,
    staffId = staffId,
    isActive = isActive,
    lastLogin = lastLogin,
    isDeleted = isDeleted,
    createdAt = createdAt,
    updatedAt = updatedAt
)
