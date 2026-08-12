package com.sunrich.oms.organization

import com.sunrich.oms.common.dto.ApiResponse
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
@RequestMapping(value = ["/departments", "/records/departments"])
class DepartmentController(private val service: OrganizationService) {
    @GetMapping fun list(@RequestParam(defaultValue = "false") includeDeleted: Boolean) =
        ApiResponse.ok(service.listDepartments(includeDeleted))
    @PostMapping @PreAuthorize(ORG_WRITE) fun create(@RequestBody request: DepartmentRequest) =
        ApiResponse.ok(service.createDepartment(request), "Department created")
    @PutMapping("/{id}") @PreAuthorize(ORG_WRITE) fun update(@PathVariable id: Long, @RequestBody request: DepartmentRequest) =
        ApiResponse.ok(service.updateDepartment(id, request), "Department updated")
    @DeleteMapping("/{id}") @PreAuthorize(ORG_WRITE) fun delete(@PathVariable id: Long): ApiResponse<Unit> {
        service.deleteDepartment(id); return ApiResponse.ok("Department archived")
    }
    @PatchMapping("/{id}/restore") @PreAuthorize(ORG_WRITE) fun restore(@PathVariable id: Long) =
        ApiResponse.ok(service.restoreDepartment(id), "Department restored")
}

@RestController
@RequestMapping(value = ["/staff", "/records/staff"])
class StaffController(private val service: OrganizationService) {
    @GetMapping fun list(@RequestParam(defaultValue = "false") includeDeleted: Boolean) =
        ApiResponse.ok(service.listStaff(includeDeleted))
    @PostMapping @PreAuthorize(ORG_WRITE) fun create(@RequestBody request: StaffRequest) =
        ApiResponse.ok(service.createStaff(request), "Staff created")
    @PutMapping("/{id}") @PreAuthorize(ORG_WRITE) fun update(@PathVariable id: Long, @RequestBody request: StaffRequest) =
        ApiResponse.ok(service.updateStaff(id, request), "Staff updated")
    @DeleteMapping("/{id}") @PreAuthorize(ORG_WRITE) fun delete(@PathVariable id: Long): ApiResponse<Unit> {
        service.deleteStaff(id); return ApiResponse.ok("Staff archived")
    }
    @PatchMapping("/{id}/restore") @PreAuthorize(ORG_WRITE) fun restore(@PathVariable id: Long) =
        ApiResponse.ok(service.restoreStaff(id), "Staff restored")
}

@RestController
@RequestMapping(value = ["/positions", "/records/positions"])
class PositionController(private val service: OrganizationService) {
    @GetMapping fun list(@RequestParam(defaultValue = "false") includeDeleted: Boolean) =
        ApiResponse.ok(service.listPositions(includeDeleted))
    @PostMapping @PreAuthorize(ORG_WRITE) fun create(@RequestBody request: PositionRequest) =
        ApiResponse.ok(service.createPosition(request), "Position created")
    @PutMapping("/{id}") @PreAuthorize(ORG_WRITE) fun update(@PathVariable id: Long, @RequestBody request: PositionRequest) =
        ApiResponse.ok(service.updatePosition(id, request), "Position updated")
    @DeleteMapping("/{id}") @PreAuthorize(ORG_WRITE) fun delete(@PathVariable id: Long): ApiResponse<Unit> {
        service.deletePosition(id); return ApiResponse.ok("Position archived")
    }
    @PatchMapping("/{id}/restore") @PreAuthorize(ORG_WRITE) fun restore(@PathVariable id: Long) =
        ApiResponse.ok(service.restorePosition(id), "Position restored")
}
