package com.sunrich.oms.exception

import java.time.LocalDateTime

/**
 * Standard error envelope returned for every failed request.
 */
data class ErrorResponse(
    val success: Boolean = false,
    val status: Int,
    val error: String,
    val message: String,
    val path: String,
    val fieldErrors: List<FieldError> = emptyList(),
    val timestamp: LocalDateTime = LocalDateTime.now()
) {
    data class FieldError(
        val field: String,
        val message: String
    )
}
