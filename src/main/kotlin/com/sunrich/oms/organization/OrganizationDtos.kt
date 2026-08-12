package com.sunrich.oms.organization

import com.sunrich.oms.common.enums.EmploymentType
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.PositionStatus
import java.time.LocalDate
import java.time.LocalDateTime

data class CompanyRequest(
    val name: String? = null,
    val regNumber: String? = null,
    val headOffice: String? = null,
    val dateEstablished: LocalDate? = null,
    val logoUrl: String? = null,
    val status: EntityStatus? = null
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
    val updatedAt: LocalDateTime?
)

data class DepartmentRequest(
    val companyId: Long? = null,
    val name: String? = null,
    val description: String? = null,
    val parentDeptId: Long? = null,
    val headStaffId: Long? = null,
    val status: EntityStatus? = null
)

data class DepartmentResponse(
    val id: Long,
    val companyId: Long,
    val name: String,
    val description: String?,
    val parentDeptId: Long?,
    val headStaffId: Long?,
    val status: EntityStatus,
    val isDeleted: Boolean,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)

data class StaffRequest(
    val companyId: Long? = null,
    val deptId: Long? = null,
    val managerId: Long? = null,
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
    val photoUrl: String? = null
)

data class StaffResponse(
    val id: Long,
    val companyId: Long,
    val deptId: Long?,
    val managerId: Long?,
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
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)

data class PositionRequest(
    val companyId: Long? = null,
    val title: String? = null,
    val deptId: Long? = null,
    val isVacant: Boolean? = null,
    val staffId: Long? = null,
    val status: PositionStatus? = null
)

data class PositionResponse(
    val id: Long,
    val companyId: Long,
    val title: String,
    val deptId: Long?,
    val isVacant: Boolean,
    val staffId: Long?,
    val status: PositionStatus,
    val isDeleted: Boolean,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)
