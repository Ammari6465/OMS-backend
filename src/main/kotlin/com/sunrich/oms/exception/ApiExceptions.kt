package com.sunrich.oms.exception

import org.springframework.http.HttpStatus

/**
 * Base type for all application exceptions that map to a specific HTTP status.
 */
sealed class ApiException(
    message: String,
    val status: HttpStatus
) : RuntimeException(message)

/** 404 — a requested resource does not exist (or is soft-deleted). */
class ResourceNotFoundException(message: String) : ApiException(message, HttpStatus.NOT_FOUND) {
    constructor(resource: String, id: Any) : this("$resource with id $id was not found")
}

/** 400 — client sent invalid data that validation annotations could not catch. */
class BadRequestException(message: String) : ApiException(message, HttpStatus.BAD_REQUEST)

/** 409 — the request conflicts with the current state (e.g. duplicate unique key). */
class ConflictException(message: String) : ApiException(message, HttpStatus.CONFLICT)

/** 401 — authentication failed or credentials are invalid. */
class UnauthorizedException(message: String = "Authentication required") :
    ApiException(message, HttpStatus.UNAUTHORIZED)

/** 403 — authenticated but not permitted to perform the action. */
class ForbiddenException(message: String = "You do not have permission to perform this action") :
    ApiException(message, HttpStatus.FORBIDDEN)

/** 423 — the account is temporarily locked after too many failed logins. */
class AccountLockedException(message: String) : ApiException(message, HttpStatus.LOCKED)
