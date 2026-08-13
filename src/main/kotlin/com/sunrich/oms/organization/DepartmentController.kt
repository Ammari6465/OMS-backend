package com.sunrich.oms.organization

import com.sunrich.oms.common.dto.ApiResponse
import com.sunrich.oms.common.enums.EntityStatus
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private const val DEPARTMENT_WRITE = "hasAnyRole('SUPER_ADMIN','COMPANY_ADMIN')"

@RestController
class DepartmentController(private val service: DepartmentService) {
    @GetMapping("/departments")
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "name") sort: String,
        @RequestParam(defaultValue = "asc") direction: String,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) status: EntityStatus?,
        @RequestParam(required = false) companyId: Long?,
        @RequestParam(defaultValue = "false") includeDeleted: Boolean
    ) = ApiResponse.ok(service.list(page, size, sort, direction, search, status, companyId, includeDeleted))

    @GetMapping("/records/departments")
    fun legacyList(@RequestParam(defaultValue = "false") includeDeleted: Boolean) =
        ApiResponse.ok(service.listLegacy(includeDeleted))

    @GetMapping(value = ["/departments/{id}", "/records/departments/{id}"])
    fun get(@PathVariable id: Long) = ApiResponse.ok(service.get(id))

    @PostMapping(value = ["/departments", "/records/departments"])
    @PreAuthorize(DEPARTMENT_WRITE)
    fun create(@Valid @RequestBody request: DepartmentCreateRequest) =
        ApiResponse.ok(service.create(request), "Department created")

    @PutMapping(value = ["/departments/{id}", "/records/departments/{id}"])
    @PreAuthorize(DEPARTMENT_WRITE)
    fun update(@PathVariable id: Long, @Valid @RequestBody request: DepartmentUpdateRequest) =
        ApiResponse.ok(service.update(id, request), "Department updated")

    @DeleteMapping(value = ["/departments/{id}", "/records/departments/{id}"])
    @PreAuthorize(DEPARTMENT_WRITE)
    fun archive(@PathVariable id: Long): ApiResponse<Unit> {
        service.archive(id)
        return ApiResponse.ok("Department archived")
    }

    @PatchMapping(value = ["/departments/{id}/restore", "/records/departments/{id}/restore"])
    @PreAuthorize(DEPARTMENT_WRITE)
    fun restore(@PathVariable id: Long) = ApiResponse.ok(service.restore(id), "Department restored")
}
