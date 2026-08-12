package com.sunrich.oms.user

import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.exception.ConflictException
import com.sunrich.oms.exception.ResourceNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserAdminService(
    private val repository: UserRepository,
    private val passwordEncoder: PasswordEncoder
) {
    @Transactional(readOnly = true)
    fun list(includeDeleted: Boolean): List<UserAdminResponse> = repository.findAll()
        .asSequence()
        .filter { includeDeleted || !it.isDeleted }
        .sortedBy { it.fullName?.lowercase() ?: it.username.lowercase() }
        .map(User::toAdminResponse)
        .toList()

    @Transactional
    fun create(request: CreateUserRequest): UserAdminResponse {
        ensureUnique(request.username, request.email, null)
        val user = User(
            username = request.username.trim(),
            email = request.email.trim(),
            passwordHash = passwordEncoder.encode(request.password),
            role = request.role,
            fullName = request.fullName.trim(),
            staffId = request.staffId,
            companyId = request.companyId,
            status = if (request.isActive) EntityStatus.ACTIVE else EntityStatus.INACTIVE,
            isActive = request.isActive
        )
        return repository.save(user).toAdminResponse()
    }

    @Transactional
    fun update(id: Long, request: UpdateUserRequest): UserAdminResponse {
        val user = find(id)
        ensureUnique(request.username, request.email, id)
        user.username = request.username.trim()
        user.email = request.email.trim()
        user.fullName = request.fullName.trim()
        user.role = request.role
        user.companyId = request.companyId
        user.staffId = request.staffId
        user.isActive = request.isActive
        user.status = if (request.isActive) EntityStatus.ACTIVE else EntityStatus.INACTIVE
        return repository.save(user).toAdminResponse()
    }

    @Transactional
    fun delete(id: Long) {
        val user = find(id)
        user.markDeleted()
        user.isActive = false
        repository.save(user)
    }

    @Transactional
    fun restore(id: Long): UserAdminResponse {
        val user = find(id)
        user.restore()
        return repository.save(user).toAdminResponse()
    }

    private fun find(id: Long): User = repository.findById(id)
        .orElseThrow { ResourceNotFoundException("User", id) }

    private fun ensureUnique(username: String, email: String, currentId: Long?) {
        val byUsername = repository.findByUsernameIgnoreCase(username).orElse(null)
        if (byUsername != null && byUsername.id != currentId) throw ConflictException("Username is already in use")
        val byEmail = repository.findByEmailIgnoreCase(email).orElse(null)
        if (byEmail != null && byEmail.id != currentId) throw ConflictException("Email is already in use")
    }
}
