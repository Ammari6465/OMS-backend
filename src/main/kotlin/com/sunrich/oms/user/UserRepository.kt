package com.sunrich.oms.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface UserRepository : JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    fun findByUsernameIgnoreCaseAndIsDeletedFalse(username: String): Optional<User>

    fun findByEmailIgnoreCaseAndIsDeletedFalse(email: String): Optional<User>

    fun findByUsernameIgnoreCase(username: String): Optional<User>

    fun findByEmailIgnoreCase(email: String): Optional<User>

    fun findByPasswordResetTokenAndIsDeletedFalse(token: String): Optional<User>

    fun existsByUsernameIgnoreCaseAndIsDeletedFalse(username: String): Boolean

    fun existsByEmailIgnoreCaseAndIsDeletedFalse(email: String): Boolean

    fun existsByStaffIdAndIsDeletedFalseAndIsActiveTrue(staffId: Long): Boolean
    fun findFirstByStaffIdAndIsDeletedFalse(staffId: Long): User?

    fun countByIsDeletedFalse(): Long

    fun countByIsDeletedFalseAndIsActiveTrue(): Long

    fun countByIsDeletedFalseAndIsActiveFalse(): Long

    fun countByIsDeletedFalseAndRole(role: com.sunrich.oms.common.enums.Role): Long

    fun countByIsDeletedFalseAndRoleAndIsActiveTrue(role: com.sunrich.oms.common.enums.Role): Long

    fun countByCompanyIdAndIsDeletedFalse(companyId: Long): Long

    fun countByCompanyIdAndIsDeletedFalseAndIsActiveTrue(companyId: Long): Long

    fun countByCompanyIdAndIsDeletedFalseAndIsActiveFalse(companyId: Long): Long

    fun countByCompanyIdAndIsDeletedFalseAndRole(companyId: Long, role: com.sunrich.oms.common.enums.Role): Long
}
