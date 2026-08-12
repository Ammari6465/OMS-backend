package com.sunrich.oms.common.dto

import java.time.LocalDateTime

/**
 * Standard success envelope returned by all REST endpoints.
 */
data class ApiResponse<T>(
    val success: Boolean = true,
    val message: String? = null,
    val data: T? = null,
    val timestamp: LocalDateTime = LocalDateTime.now()
) {
    companion object {
        fun <T> ok(data: T, message: String? = null): ApiResponse<T> =
            ApiResponse(success = true, message = message, data = data)

        fun ok(message: String): ApiResponse<Unit> =
            ApiResponse(success = true, message = message, data = null)
    }
}
