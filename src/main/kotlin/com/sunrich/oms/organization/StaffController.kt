package com.sunrich.oms.organization

import com.sunrich.oms.common.dto.ApiResponse
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.EmploymentType
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
import java.time.LocalDate

private const val STAFF_WRITE = "hasAnyRole('SUPER_ADMIN','COMPANY_ADMIN')"

@RestController
class StaffController(private val service: StaffService) {
    @GetMapping("/staff")
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "name") sort: String,
        @RequestParam(defaultValue = "asc") direction: String,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) companyId: Long?,
        @RequestParam(required = false) departmentId: Long?,
        @RequestParam(required = false) positionId: Long?,
        @RequestParam(required = false) managerId: Long?,
        @RequestParam(required = false) status: EntityStatus?,
        @RequestParam(required = false) employmentType: EmploymentType?,
        @RequestParam(required = false) joinedFrom: LocalDate?,
        @RequestParam(required = false) joinedTo: LocalDate?,
        @RequestParam(defaultValue = "false") includeDeleted: Boolean
    ) = ApiResponse.ok(service.list(
        page, size, sort, direction, search, companyId, departmentId,
        positionId, managerId, status, includeDeleted, employmentType, joinedFrom, joinedTo
    ))

    @GetMapping("/records/staff")
    fun legacyList(@RequestParam(defaultValue = "false") includeDeleted: Boolean) =
        ApiResponse.ok(service.listLegacy(includeDeleted))

    @GetMapping(value = ["/staff/{id}", "/records/staff/{id}"])
    fun get(@PathVariable id: Long) = ApiResponse.ok(service.get(id))

    @PostMapping("/staff")
    @PreAuthorize(STAFF_WRITE)
    fun create(@Valid @RequestBody request: StaffCreateRequest) =
        ApiResponse.ok(service.create(request), "Staff created")

    @PostMapping("/records/staff")
    @PreAuthorize(STAFF_WRITE)
    fun createLegacy(@RequestBody request: StaffRequest) =
        ApiResponse.ok(service.createLegacy(request), "Staff created")

    @PutMapping("/staff/{id}")
    @PreAuthorize(STAFF_WRITE)
    fun update(@PathVariable id: Long, @Valid @RequestBody request: StaffUpdateRequest) =
        ApiResponse.ok(service.update(id, request), "Staff updated")

    @PutMapping("/records/staff/{id}")
    @PreAuthorize(STAFF_WRITE)
    fun updateLegacy(@PathVariable id: Long, @RequestBody request: StaffRequest) =
        ApiResponse.ok(service.updateLegacy(id, request), "Staff updated")

    @DeleteMapping(value = ["/staff/{id}", "/records/staff/{id}"])
    @PreAuthorize(STAFF_WRITE)
    fun archive(@PathVariable id: Long): ApiResponse<Unit> {
        service.archive(id)
        return ApiResponse.ok("Staff archived")
    }

    @PatchMapping(value = ["/staff/{id}/restore", "/records/staff/{id}/restore"])
    @PreAuthorize(STAFF_WRITE)
    fun restore(@PathVariable id: Long) = ApiResponse.ok(service.restore(id), "Staff restored")
}
