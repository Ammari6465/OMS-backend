package com.sunrich.oms.organization

import com.sunrich.oms.common.enums.EmploymentType
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.PositionStatus
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.time.LocalDateTime

data class CompanyRequest(
    val name: String? = null,
    val regNumber: String? = null,
    val headOffice: String? = null,
    val dateEstablished: LocalDate? = null,
    val logoUrl: String? = null,
    val status: EntityStatus? = null,
    /**
     * Holding company this sister concern belongs to. Null means "group parent";
     * only one company in the group may sit at the top.
     */
    val parentCompanyId: Long? = null
)

data class CompanyResponse(
    val id: Long,
    val name: String,
    val regNumber: String?,
    val headOffice: String?,
    val dateEstablished: LocalDate?,
    val logoUrl: String?,
    val status: EntityStatus,
    val isDeleted: Boolean,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
    val parentCompanyId: Long?,
    val parentCompanyName: String?,
    val isGroupParent: Boolean,
    val sisterConcernCount: Long
)

/** One node of the group tree: the holding company and its sister concerns. */
data class CompanyGroupNode(
    val company: CompanyResponse,
    val sisterConcerns: List<CompanyGroupNode>
)

data class DepartmentCreateRequest(
    @field:NotNull(message = "Company is required")
    @field:Positive(message = "Company must be valid")
    val companyId: Long?,

    @field:NotBlank(message = "Department name is required")
    @field:Size(max = 200, message = "Department name must not exceed 200 characters")
    val name: String?,

    @field:Size(max = 1000, message = "Description must not exceed 1000 characters")
    val description: String? = null,
    val parentDeptId: Long? = null,
    val headStaffId: Long? = null,
    val status: EntityStatus? = null
)

data class DepartmentUpdateRequest(
    @field:NotNull(message = "Company is required")
    @field:Positive(message = "Company must be valid")
    val companyId: Long?,

    @field:NotBlank(message = "Department name is required")
    @field:Size(max = 200, message = "Department name must not exceed 200 characters")
    val name: String?,

    @field:Size(max = 1000, message = "Description must not exceed 1000 characters")
    val description: String? = null,
    val parentDeptId: Long? = null,
    val headStaffId: Long? = null,
    val status: EntityStatus? = null,

    @field:NotNull(message = "Version is required")
    val version: Long?
)

data class DepartmentResponse(
    val id: Long,
    val companyId: Long,
    val companyName: String,
    val name: String,
    val description: String?,
    val parentDeptId: Long?,
    val headStaffId: Long?,
    val status: EntityStatus,
    val isDeleted: Boolean,
    val version: Long,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)

/** Compatibility request used by existing /records/staff consumers. */
data class StaffRequest(
    val companyId: Long? = null,
    val deptId: Long? = null,
    val managerId: Long? = null,
    val positionId: Long? = null,
    val employeeCode: String? = null,
    val name: String? = null,
    val title: String? = null,
    val empType: EmploymentType? = null,
    val email: String? = null,
    val landline: String? = null,
    val cellNumber: String? = null,
    val dateJoined: LocalDate? = null,
    val dateLeft: LocalDate? = null,
    val status: EntityStatus? = null,
    val photoUrl: String? = null,
    val version: Long? = null
)

data class StaffCreateRequest(
    @field:NotNull(message = "Company is required")
    @field:Positive(message = "Company must be valid")
    val companyId: Long?,

    @field:Positive(message = "Department must be valid")
    val deptId: Long? = null,

    @field:Positive(message = "Manager must be valid")
    val managerId: Long? = null,

    @field:Positive(message = "Position must be valid")
    val positionId: Long? = null,

    @field:Size(max = 100, message = "Employee code must not exceed 100 characters")
    @field:Pattern(
        regexp = "^[A-Za-z0-9][A-Za-z0-9_-]*$",
        message = "Employee code may only contain letters, numbers, hyphens, and underscores"
    )
    val employeeCode: String? = null,

    @field:NotBlank(message = "Staff name is required")
    @field:Size(max = 200, message = "Staff name must not exceed 200 characters")
    val name: String?,

    @field:Size(max = 200, message = "Job title must not exceed 200 characters")
    val title: String? = null,
    val empType: EmploymentType? = null,

    @field:Email(message = "Email address is invalid")
    @field:Size(max = 200, message = "Email must not exceed 200 characters")
    val email: String? = null,

    @field:Pattern(regexp = "^[0-9+(). -]{3,50}$", message = "Landline number is invalid")
    val landline: String? = null,

    @field:Pattern(regexp = "^[0-9+(). -]{3,50}$", message = "Mobile number is invalid")
    val cellNumber: String? = null,
    val dateJoined: LocalDate? = null,
    val dateLeft: LocalDate? = null,
    val status: EntityStatus? = null,

    @field:Size(max = 500, message = "Photo URL must not exceed 500 characters")
    val photoUrl: String? = null
)

data class StaffUpdateRequest(
    @field:NotNull(message = "Company is required")
    @field:Positive(message = "Company must be valid")
    val companyId: Long?,

    @field:Positive(message = "Department must be valid")
    val deptId: Long? = null,

    @field:Positive(message = "Manager must be valid")
    val managerId: Long? = null,

    @field:Positive(message = "Position must be valid")
    val positionId: Long? = null,

    @field:Size(max = 100, message = "Employee code must not exceed 100 characters")
    @field:Pattern(
        regexp = "^[A-Za-z0-9][A-Za-z0-9_-]*$",
        message = "Employee code may only contain letters, numbers, hyphens, and underscores"
    )
    val employeeCode: String? = null,

    @field:NotBlank(message = "Staff name is required")
    @field:Size(max = 200, message = "Staff name must not exceed 200 characters")
    val name: String?,

    @field:Size(max = 200, message = "Job title must not exceed 200 characters")
    val title: String? = null,
    val empType: EmploymentType? = null,

    @field:Email(message = "Email address is invalid")
    @field:Size(max = 200, message = "Email must not exceed 200 characters")
    val email: String? = null,

    @field:Pattern(regexp = "^[0-9+(). -]{3,50}$", message = "Landline number is invalid")
    val landline: String? = null,

    @field:Pattern(regexp = "^[0-9+(). -]{3,50}$", message = "Mobile number is invalid")
    val cellNumber: String? = null,
    val dateJoined: LocalDate? = null,
    val dateLeft: LocalDate? = null,
    val status: EntityStatus? = null,

    @field:Size(max = 500, message = "Photo URL must not exceed 500 characters")
    val photoUrl: String? = null,

    @field:NotNull(message = "Version is required")
    val version: Long?
)

data class StaffResponse(
    val id: Long,
    val companyId: Long,
    val companyName: String,
    val deptId: Long?,
    val departmentName: String?,
    val managerId: Long?,
    val managerName: String?,
    val positionId: Long?,
    val positionTitle: String?,
    val employeeCode: String?,
    val name: String,
    val title: String?,
    val empType: EmploymentType,
    val email: String?,
    val landline: String?,
    val cellNumber: String?,
    val dateJoined: LocalDate?,
    val dateLeft: LocalDate?,
    val status: EntityStatus,
    val photoUrl: String?,
    val isDeleted: Boolean,
    val version: Long,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)

data class PositionRequest(
    val companyId: Long? = null,
    val title: String? = null,
    val deptId: Long? = null,
    val reportsToPositionId: Long? = null,
    val isVacant: Boolean? = null,
    val staffId: Long? = null,
    val status: PositionStatus? = null
)

data class PositionCreateRequest(
    @field:NotNull(message = "Company is required")
    @field:Positive(message = "Company must be valid")
    val companyId: Long?,
    @field:NotBlank(message = "Position title is required")
    @field:Size(max = 200, message = "Position title must not exceed 200 characters")
    val title: String?,
    @field:Positive(message = "Department must be valid")
    val deptId: Long? = null,
    @field:Positive(message = "Reporting position must be valid")
    val reportsToPositionId: Long? = null,
    @field:Positive(message = "Staff member must be valid")
    val staffId: Long? = null,
    val status: PositionStatus? = null
)

data class PositionUpdateRequest(
    @field:NotNull(message = "Company is required")
    @field:Positive(message = "Company must be valid")
    val companyId: Long?,
    @field:NotBlank(message = "Position title is required")
    @field:Size(max = 200, message = "Position title must not exceed 200 characters")
    val title: String?,
    @field:Positive(message = "Department must be valid")
    val deptId: Long? = null,
    @field:Positive(message = "Reporting position must be valid")
    val reportsToPositionId: Long? = null,
    @field:Positive(message = "Staff member must be valid")
    val staffId: Long? = null,
    val status: PositionStatus? = null,
    @field:NotNull(message = "Version is required")
    val version: Long?
)

data class PositionResponse(
    val id: Long,
    val companyId: Long,
    val title: String,
    val companyName: String? = null,
    val deptId: Long?,
    val departmentName: String? = null,
    val reportsToPositionId: Long? = null,
    val reportsToPositionTitle: String? = null,
    val isVacant: Boolean,
    val staffId: Long?,
    val staffName: String? = null,
    val status: PositionStatus,
    val isDeleted: Boolean,
    val version: Long = 0,
    val subordinateCount: Long = 0,
    val createdBy: Long? = null,
    val updatedBy: Long? = null,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)

data class VacancySummaryResponse(
    val total: Long,
    val open: Long,
    val filled: Long,
    val closed: Long
)
