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

    fun countByIsDeletedFalse(): Long
}
