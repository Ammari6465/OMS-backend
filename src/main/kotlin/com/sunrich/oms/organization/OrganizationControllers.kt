package com.sunrich.oms.organization

import com.sunrich.oms.common.dto.ApiResponse
import com.sunrich.oms.common.enums.PositionStatus
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

private const val ORG_WRITE = "hasAnyRole('SUPER_ADMIN','COMPANY_ADMIN')"

@RestController
@RequestMapping(value = ["/companies", "/records/companies"])
class CompanyController(private val service: OrganizationService) {
    @GetMapping fun list(@RequestParam(defaultValue = "false") includeDeleted: Boolean) =
        ApiResponse.ok(service.listCompanies(includeDeleted))
    @GetMapping("/group") fun group(@RequestParam(defaultValue = "false") includeDeleted: Boolean) =
        ApiResponse.ok(service.companyGroupTree(includeDeleted))
    @PostMapping @PreAuthorize(ORG_WRITE) fun create(@RequestBody request: CompanyRequest) =
        ApiResponse.ok(service.createCompany(request), "Company created")
    @PutMapping("/{id}") @PreAuthorize(ORG_WRITE) fun update(@PathVariable id: Long, @RequestBody request: CompanyRequest) =
        ApiResponse.ok(service.updateCompany(id, request), "Company updated")
    @DeleteMapping("/{id}") @PreAuthorize(ORG_WRITE) fun delete(@PathVariable id: Long): ApiResponse<Unit> {
        service.deleteCompany(id); return ApiResponse.ok("Company archived")
    }
    @PatchMapping("/{id}/restore") @PreAuthorize(ORG_WRITE) fun restore(@PathVariable id: Long) =
        ApiResponse.ok(service.restoreCompany(id), "Company restored")
}

@RestController
class PositionController(private val service: PositionService) {
    @GetMapping("/positions")
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "title") sort: String,
        @RequestParam(defaultValue = "asc") direction: String,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) companyId: Long?,
        @RequestParam(required = false) departmentId: Long?,
        @RequestParam(required = false) status: PositionStatus?,
        @RequestParam(required = false) reportsToPositionId: Long?,
        @RequestParam(required = false) assigned: Boolean?,
        @RequestParam(required = false) vacant: Boolean?,
        @RequestParam(required = false) positionId: Long?,
        @RequestParam(defaultValue = "false") includeDeleted: Boolean
    ) = ApiResponse.ok(service.list(page, size, sort, direction, search, companyId, departmentId,
        status, reportsToPositionId, assigned, vacant, includeDeleted, positionId))

    @GetMapping("/records/positions")
    fun legacyList(@RequestParam(defaultValue = "false") includeDeleted: Boolean) = ApiResponse.ok(service.listLegacy(includeDeleted))

    @GetMapping("/vacancies/summary")
    fun vacancySummary(@RequestParam(required = false) companyId: Long?) = ApiResponse.ok(service.vacancySummary(companyId))

    @GetMapping(value = ["/positions/{id}", "/records/positions/{id}"])
    fun get(@PathVariable id: Long) = ApiResponse.ok(service.get(id))

    @PostMapping("/positions") @PreAuthorize(ORG_WRITE)
    fun create(@Valid @RequestBody request: PositionCreateRequest) = ApiResponse.ok(service.create(request), "Position created")

    @PostMapping("/records/positions") @PreAuthorize(ORG_WRITE)
    fun createLegacy(@RequestBody request: PositionRequest) = ApiResponse.ok(service.createLegacy(request), "Position created")

    @PutMapping("/positions/{id}") @PreAuthorize(ORG_WRITE)
    fun update(@PathVariable id: Long, @Valid @RequestBody request: PositionUpdateRequest) =
        ApiResponse.ok(service.update(id, request), "Position updated")

    @PutMapping("/records/positions/{id}") @PreAuthorize(ORG_WRITE)
    fun updateLegacy(@PathVariable id: Long, @RequestBody request: PositionRequest) =
        ApiResponse.ok(service.updateLegacy(id, request), "Position updated")

    @DeleteMapping(value = ["/positions/{id}", "/records/positions/{id}"]) @PreAuthorize(ORG_WRITE)
    fun delete(@PathVariable id: Long): ApiResponse<Unit> { service.archive(id); return ApiResponse.ok("Position archived") }

    @PatchMapping(value = ["/positions/{id}/restore", "/records/positions/{id}/restore"]) @PreAuthorize(ORG_WRITE)
    fun restore(@PathVariable id: Long) = ApiResponse.ok(service.restore(id), "Position restored")
}
