package com.sunrich.oms.auth

import com.sunrich.oms.common.enums.Role
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class LoginRequest(
    @field:NotBlank(message = "Username or email is required")
    val username: String,

    @field:NotBlank(message = "Password is required")
    val password: String
)

data class LoginResponse(
    val token: String,
    val tokenType: String = "Bearer",
    val expiresInMs: Long,
    val user: CurrentUserResponse
)

data class CurrentUserResponse(
    val userId: Long,
    val username: String,
    val email: String,
    val fullName: String?,
    val role: Role,
    val companyId: Long?,
    val companyIds: Set<Long>,
    val staffId: Long?
)

data class UpdateProfileRequest(
    @field:NotBlank(message = "Full name is required")
    @field:Size(max = 200)
    val fullName: String,

    @field:NotBlank(message = "Email is required")
    @field:Email(message = "A valid email is required")
    val email: String
)

data class ChangePasswordRequest(
    @field:NotBlank(message = "Current password is required")
    val currentPassword: String,

    @field:NotBlank(message = "New password is required")
    @field:Size(min = 8, message = "New password must be at least 8 characters")
    val newPassword: String
)

data class ForgotPasswordRequest(
    @field:NotBlank(message = "Email is required")
    @field:Email(message = "A valid email is required")
    val email: String
)

data class ResetPasswordRequest(
    @field:NotBlank(message = "Reset token is required")
    val token: String,

    @field:NotBlank(message = "New password is required")
    @field:Size(min = 8, message = "New password must be at least 8 characters")
    val newPassword: String
)
