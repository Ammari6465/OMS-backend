package com.sunrich.oms.auth

import com.sunrich.oms.common.dto.ApiResponse
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth")
class AuthController(
    private val authService: AuthService
) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): ApiResponse<LoginResponse> =
        ApiResponse.ok(authService.login(request), "Login successful")

    @GetMapping("/me")
    fun me(): ApiResponse<CurrentUserResponse> =
        ApiResponse.ok(authService.currentUser())

    @PutMapping("/me")
    fun updateProfile(@Valid @RequestBody request: UpdateProfileRequest): ApiResponse<CurrentUserResponse> =
        ApiResponse.ok(authService.updateProfile(request), "Profile updated")

    @PostMapping("/change-password")
    fun changePassword(@Valid @RequestBody request: ChangePasswordRequest): ApiResponse<Unit> {
        authService.changePassword(request)
        return ApiResponse.ok("Password changed successfully")
    }

    @PostMapping("/forgot-password")
    fun forgotPassword(@Valid @RequestBody request: ForgotPasswordRequest): ApiResponse<Unit> {
        authService.requestPasswordReset(request)
        return ApiResponse.ok("If an account exists for that email, a reset link has been sent.")
    }

    @PostMapping("/reset-password")
    fun resetPassword(@Valid @RequestBody request: ResetPasswordRequest): ApiResponse<Unit> {
        authService.resetPassword(request)
        return ApiResponse.ok("Password reset successfully. You can now sign in.")
    }
}
