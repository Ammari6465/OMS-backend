package com.sunrich.oms.organogram

import com.sunrich.oms.common.enums.EmploymentType
import com.sunrich.oms.common.enums.EntityStatus
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.time.Instant
import java.time.LocalDate

enum class OrganogramView { EMPLOYEE, POSITION }

data class ManagerChangeRequest(
    @field:Positive(message = "Manager must be valid") val managerId: Long? = null,
    @field:NotNull(message = "Version is required") val version: Long?
)

data class OrganogramCompany(val id: Long, val name: String, val logoUrl: String?)
data class OrganogramCapabilities(val canEditHierarchy: Boolean, val canViewContactDetails: Boolean)
data class OrganogramDepartment(val id: Long, val name: String, val parentId: Long?, val headStaffId: Long?)

data class OrganogramNode(
    val id: Long, val parentId: Long?, val companyId: Long, val departmentId: Long?,
    val employeeCode: String?, val name: String, val title: String?, val photoUrl: String?,
    val status: EntityStatus?, val version: Long, val vacant: Boolean = false, val staffId: Long? = null
)

data class OrganogramVacancy(
    val id: Long, val title: String, val departmentId: Long?, val reportsToPositionId: Long?, val version: Long
)

data class OrganogramWarning(val code: String, val message: String, val nodeIds: List<Long> = emptyList())

data class OrganogramResponse(
    val company: OrganogramCompany, val view: OrganogramView, val nodes: List<OrganogramNode>,
    val rootIds: List<Long>, val orphanIds: List<Long>, val departments: List<OrganogramDepartment>,
    val vacancies: List<OrganogramVacancy>, val dataVersion: Long, val generatedAt: Instant,
    val capabilities: OrganogramCapabilities, val warnings: List<OrganogramWarning>
)

data class OrganogramStaffDetails(
    val id: Long, val name: String, val employeeCode: String?, val title: String?, val departmentId: Long?,
    val managerId: Long?, val employmentType: EmploymentType, val dateJoined: LocalDate?, val dateLeft: LocalDate?,
    val status: EntityStatus, val photoUrl: String?, val email: String?, val landline: String?,
    val cellNumber: String?, val version: Long
)
