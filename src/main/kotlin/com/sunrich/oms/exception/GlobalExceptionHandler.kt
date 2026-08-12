package com.sunrich.oms.exception

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.validation.BindException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * Centralised translation of exceptions into consistent [ErrorResponse] bodies.
 * Controllers and services never build error payloads themselves.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApi(ex: ApiException, req: HttpServletRequest): ResponseEntity<ErrorResponse> =
        build(ex.status, ex.message ?: ex.status.reasonPhrase, req)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException, req: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val fieldErrors = ex.bindingResult.fieldErrors.map {
            ErrorResponse.FieldError(it.field, it.defaultMessage ?: "is invalid")
        }
        return build(HttpStatus.BAD_REQUEST, "Validation failed", req, fieldErrors)
    }

    @ExceptionHandler(BindException::class)
    fun handleBind(ex: BindException, req: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val fieldErrors = ex.bindingResult.fieldErrors.map {
            ErrorResponse.FieldError(it.field, it.defaultMessage ?: "is invalid")
        }
        return build(HttpStatus.BAD_REQUEST, "Validation failed", req, fieldErrors)
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException, req: HttpServletRequest): ResponseEntity<ErrorResponse> =
        build(HttpStatus.BAD_REQUEST, "Parameter '${ex.name}' has an invalid value", req)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException, req: HttpServletRequest): ResponseEntity<ErrorResponse> =
        build(HttpStatus.BAD_REQUEST, "Malformed or missing request body", req)

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrity(ex: DataIntegrityViolationException, req: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.warn("Data integrity violation: {}", ex.mostSpecificCause.message)
        return build(HttpStatus.CONFLICT, "The operation violates a data constraint (possible duplicate or referenced record)", req)
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException::class)
    fun handleOptimisticLock(ex: ObjectOptimisticLockingFailureException, req: HttpServletRequest): ResponseEntity<ErrorResponse> =
        build(HttpStatus.CONFLICT, "This record was modified by someone else. Please reload and try again.", req)

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException, req: HttpServletRequest): ResponseEntity<ErrorResponse> =
        build(HttpStatus.FORBIDDEN, "You do not have permission to perform this action", req)

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthentication(ex: AuthenticationException, req: HttpServletRequest): ResponseEntity<ErrorResponse> =
        build(HttpStatus.UNAUTHORIZED, ex.message ?: "Authentication required", req)

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception, req: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.error("Unhandled exception on {} {}", req.method, req.requestURI, ex)
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", req)
    }

    private fun build(
        status: HttpStatus,
        message: String,
        req: HttpServletRequest,
        fieldErrors: List<ErrorResponse.FieldError> = emptyList()
    ): ResponseEntity<ErrorResponse> = ResponseEntity.status(status).body(
        ErrorResponse(
            status = status.value(),
            error = status.reasonPhrase,
            message = message,
            path = req.requestURI,
            fieldErrors = fieldErrors
        )
    )
}
