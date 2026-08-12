package com.sunrich.oms.user

import com.sunrich.oms.common.dto.ApiResponse
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/users")
@PreAuthorize("hasRole('SUPER_ADMIN')")
class UserAdminController(private val service: UserAdminService) {
    @GetMapping
    fun list(@RequestParam(defaultValue = "false") includeDeleted: Boolean) =
        ApiResponse.ok(service.list(includeDeleted))

    @PostMapping
    fun create(@Valid @RequestBody request: CreateUserRequest) =
        ApiResponse.ok(service.create(request), "User created")

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateUserRequest) =
        ApiResponse.ok(service.update(id, request), "User updated")

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ApiResponse<Unit> {
        service.delete(id)
        return ApiResponse.ok("User archived")
    }

    @PatchMapping("/{id}/restore")
    fun restore(@PathVariable id: Long) = ApiResponse.ok(service.restore(id), "User restored")
}
