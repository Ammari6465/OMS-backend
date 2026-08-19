package com.sunrich.oms.organization

import com.sunrich.oms.common.enums.EntityStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.EntityGraph

interface CompanyRepository : JpaRepository<Company, Long> {
    fun findFirstByNameIgnoreCaseAndIsDeletedFalse(name: String): Company?
    fun findFirstByParentCompanyIsNullAndIsDeletedFalseOrderByIdAsc(): Company?
    fun findAllByParentCompanyIsNullAndIsDeletedFalse(): List<Company>
    fun findAllByParentCompany_IdAndIsDeletedFalse(parentCompanyId: Long): List<Company>
    fun existsByParentCompany_IdAndIsDeletedFalse(parentCompanyId: Long): Boolean
    fun countByParentCompany_IdAndIsDeletedFalse(parentCompanyId: Long): Long
}
interface DepartmentRepository : JpaRepository<Department, Long>, JpaSpecificationExecutor<Department> {
    @EntityGraph(attributePaths = ["company", "headStaff"])
    fun findAllByCompany_IdAndIsDeletedFalse(companyId: Long): List<Department>
    fun existsByCompany_IdAndNameIgnoreCase(companyId: Long, name: String): Boolean
    fun existsByCompany_IdAndNameIgnoreCaseAndIdNot(companyId: Long, name: String, id: Long): Boolean
    fun existsByParentDepartment_IdAndIsDeletedFalse(departmentId: Long): Boolean
    fun findAllByHeadStaff_IdAndIsDeletedFalse(staffId: Long): List<Department>
}
interface StaffRepository : JpaRepository<Staff, Long>, JpaSpecificationExecutor<Staff> {
    @EntityGraph(attributePaths = ["company", "department", "manager"])
    fun findAllByCompany_IdAndIsDeletedFalse(companyId: Long): List<Staff>
    fun existsByDepartment_IdAndIsDeletedFalse(departmentId: Long): Boolean
    fun existsByCompany_IdAndEmployeeCodeIgnoreCase(companyId: Long, employeeCode: String): Boolean
    fun existsByCompany_IdAndEmployeeCodeIgnoreCaseAndIdNot(companyId: Long, employeeCode: String, id: Long): Boolean
    fun findAllByManager_IdAndIsDeletedFalse(managerId: Long): List<Staff>
    fun countByCompany_IdAndStatusAndIsDeletedFalse(companyId: Long, status: EntityStatus): Long
}
interface PositionRepository : JpaRepository<Position, Long>, JpaSpecificationExecutor<Position> {
    @EntityGraph(attributePaths = ["company", "department", "reportsToPosition", "staff"])
    fun findAllByCompany_IdAndIsDeletedFalse(companyId: Long): List<Position>
    fun existsByDepartment_IdAndIsDeletedFalse(departmentId: Long): Boolean
    fun findFirstByStaff_IdAndIsDeletedFalse(staffId: Long): Position?
    fun findAllByStaff_IdAndIsDeletedFalse(staffId: Long): List<Position>
    fun findAllByStaff_IdInAndIsDeletedFalse(staffIds: Collection<Long>): List<Position>
    fun existsByReportsToPosition_IdAndIsDeletedFalse(positionId: Long): Boolean
    fun countByReportsToPosition_IdAndIsDeletedFalse(positionId: Long): Long
    fun existsByCompany_IdAndTitleIgnoreCaseAndIsDeletedFalse(companyId: Long, title: String): Boolean
    fun existsByCompany_IdAndTitleIgnoreCaseAndIsDeletedFalseAndIdNot(companyId: Long, title: String, id: Long): Boolean
    fun countByIsDeletedFalse(): Long
    fun countByIsDeletedFalseAndStatus(status: com.sunrich.oms.common.enums.PositionStatus): Long
    fun countByIsDeletedFalseAndStatusAndIsVacantTrue(status: com.sunrich.oms.common.enums.PositionStatus): Long
    fun countByCompany_IdAndIsDeletedFalse(companyId: Long): Long
    fun countByCompany_IdAndIsDeletedFalseAndStatus(companyId: Long, status: com.sunrich.oms.common.enums.PositionStatus): Long
    fun countByCompany_IdAndIsDeletedFalseAndStatusAndIsVacantTrue(companyId: Long, status: com.sunrich.oms.common.enums.PositionStatus): Long
}
