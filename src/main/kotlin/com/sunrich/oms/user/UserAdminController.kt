package com.sunrich.oms.user

import com.sunrich.oms.common.dto.ApiResponse
import com.sunrich.oms.common.enums.Role
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/users")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','COMPANY_ADMIN')")
class UserAdminController(private val service: UserAdminService) {
    @GetMapping
    fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "fullName") sort: String, @RequestParam(defaultValue = "asc") direction: String,
        @RequestParam(required = false) search: String?, @RequestParam(required = false) role: Role?,
        @RequestParam(required = false) companyId: Long?, @RequestParam(required = false) departmentId: Long?,
        @RequestParam(required = false) active: Boolean?, @RequestParam(required = false) locked: Boolean?,
        @RequestParam(defaultValue = "false") includeDeleted: Boolean) =
        ApiResponse.ok(service.list(page, size, sort, direction, search, role, companyId, departmentId, active, locked, includeDeleted))

    @GetMapping("/summary") fun summary(@RequestParam(required = false) companyId: Long?) = ApiResponse.ok(service.summary(companyId))
    @GetMapping("/roles") fun roles(@RequestParam(required = false) companyId: Long?) = ApiResponse.ok(service.roles(companyId))
    @GetMapping("/{id}") fun get(@PathVariable id: Long) = ApiResponse.ok(service.get(id))
    @PostMapping fun create(@Valid @RequestBody request: CreateUserRequest) = ApiResponse.ok(service.create(request), "User created")
    @PutMapping("/{id}") fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateUserRequest) = ApiResponse.ok(service.update(id, request), "User updated")
    @PatchMapping("/{id}/status") fun status(@PathVariable id: Long, @RequestBody request: UserStatusRequest) = ApiResponse.ok(service.changeStatus(id, request), "User status updated")
    @PatchMapping("/{id}/role") fun role(@PathVariable id: Long, @RequestBody request: UserRoleRequest) = ApiResponse.ok(service.changeRole(id, request), "User role updated")
    @PatchMapping("/{id}/unlock") fun unlock(@PathVariable id: Long, @RequestBody request: UserVersionRequest) = ApiResponse.ok(service.unlock(id, request), "User unlocked")
    @PostMapping("/{id}/password-reset") fun passwordReset(@PathVariable id: Long): ApiResponse<Unit> { service.requestPasswordReset(id); return ApiResponse.ok("Password reset requested") }
    @DeleteMapping("/{id}") fun delete(@PathVariable id: Long): ApiResponse<Unit> { service.delete(id); return ApiResponse.ok("User archived") }
    @PatchMapping("/{id}/restore") fun restore(@PathVariable id: Long, @RequestBody request: UserVersionRequest) = ApiResponse.ok(service.restore(id, request), "User restored as inactive")
}
