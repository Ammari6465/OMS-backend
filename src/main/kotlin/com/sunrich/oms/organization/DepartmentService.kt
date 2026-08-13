package com.sunrich.oms.organization

import com.sunrich.oms.common.dto.PageResponse
import com.sunrich.oms.common.enums.AuditAction
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.exception.ConflictException
import com.sunrich.oms.exception.ForbiddenException
import com.sunrich.oms.exception.ResourceNotFoundException
import com.sunrich.oms.realtime.OrganogramUpdatePublisher
import com.sunrich.oms.security.SecurityUtils
import com.sunrich.oms.systemdata.AuditLog
import com.sunrich.oms.systemdata.AuditLogRepository
import com.sunrich.oms.user.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DepartmentService(
    private val departments: DepartmentRepository,
    private val companies: CompanyRepository,
    private val staff: StaffRepository,
    private val positions: PositionRepository,
    private val audits: AuditLogRepository,
    private val users: UserRepository,
    private val updates: OrganogramUpdatePublisher
) {
    @Transactional(readOnly = true)
    fun list(
        page: Int,
        size: Int,
        sort: String,
        direction: String,
        search: String?,
        status: EntityStatus?,
        companyId: Long?,
        includeDeleted: Boolean
    ): PageResponse<DepartmentResponse> {
        val effectiveCompanyId = scopedCompanyId(companyId)
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, 200)
        val sortField = sort.takeIf { it in SORT_FIELDS } ?: "name"
        val sortDirection = if (direction.equals("desc", true)) Sort.Direction.DESC else Sort.Direction.ASC
        val pageable = PageRequest.of(safePage, safeSize, Sort.by(sortDirection, sortField))
        val term = search?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        val specification = Specification<Department> { root, _, cb ->
            val predicates = mutableListOf<jakarta.persistence.criteria.Predicate>()
            if (!includeDeleted) predicates += cb.isFalse(root.get("isDeleted"))
            effectiveCompanyId?.let { predicates += cb.equal(root.get<Company>("company").get<Long>("id"), it) }
            status?.let { predicates += cb.equal(root.get<EntityStatus>("status"), it) }
            term?.let {
                val pattern = "%${it.replace("%", "\\%").replace("_", "\\_")}%"
                predicates += cb.or(
                    cb.like(cb.lower(root.get("name")), pattern, '\\'),
                    cb.like(cb.lower(root.get("description")), pattern, '\\')
                )
            }
            cb.and(*predicates.toTypedArray())
        }
        return PageResponse.from(departments.findAll(specification, pageable), ::toResponse)
    }

    /** Compatibility list for existing modules while the dedicated Angular screen uses paged /departments. */
    @Transactional(readOnly = true)
    fun listLegacy(includeDeleted: Boolean): List<DepartmentResponse> {
        val companyId = scopedCompanyId(null)
        return departments.findAll(Sort.by("name")).asSequence()
            .filter { includeDeleted || !it.isDeleted }
            .filter { companyId == null || it.company.id == companyId }
            .map(::toResponse)
            .toList()
    }

    @Transactional(readOnly = true)
    fun get(id: Long): DepartmentResponse = toResponse(accessibleDepartment(id, includeDeleted = false))

    @Transactional
    fun create(request: DepartmentCreateRequest): DepartmentResponse {
        val companyId = request.companyId ?: throw BadRequestException("Company is required")
        requireCompanyAccess(companyId)
        val company = activeCompany(companyId)
        val name = requiredName(request.name)
        ensureUnique(companyId, name, null)
        val entity = Department(
            company = company,
            name = name,
            description = request.description.clean(),
            parentDepartment = request.parentDeptId?.let { linkedDepartment(it, companyId) },
            status = request.status ?: EntityStatus.ACTIVE
        )
        entity.headStaff = request.headStaffId?.let { linkedStaff(it, companyId) }
        return toResponse(departments.save(entity)).also {
            recordAudit(AuditAction.CREATE, entity, null)
            updates.publish("Department", "CREATE", it.id)
        }
    }

    @Transactional
    fun update(id: Long, request: DepartmentUpdateRequest): DepartmentResponse {
        val entity = accessibleDepartment(id, includeDeleted = false)
        val companyId = request.companyId ?: throw BadRequestException("Company is required")
        requireCompanyAccess(companyId)
        val requestVersion = request.version ?: throw BadRequestException("Version is required")
        if (entity.version != requestVersion) {
            throw ConflictException("Department was modified by another user. Reload and try again.")
        }
        val name = requiredName(request.name)
        ensureUnique(companyId, name, id)
        val previousValue = auditValue(entity)
        entity.company = activeCompany(companyId)
        entity.name = name
        entity.description = request.description.clean()
        entity.parentDepartment = request.parentDeptId?.let {
            if (it == id) throw BadRequestException("A department cannot be its own parent")
            linkedDepartment(it, companyId).also { parent -> ensureNoParentCycle(id, parent) }
        }
        entity.headStaff = request.headStaffId?.let { linkedStaff(it, companyId) }
        request.status?.let { entity.status = it }
        // Flush so the response contains the incremented @Version value that
        // the next client update must send back.
        return toResponse(departments.saveAndFlush(entity)).also {
            recordAudit(AuditAction.UPDATE, entity, previousValue)
            updates.publish("Department", "UPDATE", it.id)
        }
    }

    @Transactional
    fun archive(id: Long) {
        val entity = accessibleDepartment(id, includeDeleted = false)
        if (staff.existsByDepartment_IdAndIsDeletedFalse(id)) {
            throw ConflictException("Department cannot be archived because employees are assigned to it.")
        }
        if (positions.existsByDepartment_IdAndIsDeletedFalse(id)) {
            throw ConflictException("Department cannot be archived because positions are assigned to it.")
        }
        if (departments.existsByParentDepartment_IdAndIsDeletedFalse(id)) {
            throw ConflictException("Department cannot be archived because it has active sub-departments.")
        }
        val previousValue = auditValue(entity)
        departments.save(entity.apply { markDeleted() })
        recordAudit(AuditAction.DELETE, entity, previousValue)
        updates.publish("Department", "DELETE", id)
    }

    @Transactional
    fun restore(id: Long): DepartmentResponse {
        val entity = accessibleDepartment(id, includeDeleted = true)
        ensureUnique(entity.company.id!!, entity.name, id)
        return toResponse(departments.save(entity.apply { restore() })).also {
            recordAudit(AuditAction.RESTORE, entity, null)
            updates.publish("Department", "RESTORE", id)
        }
    }

    private fun accessibleDepartment(id: Long, includeDeleted: Boolean): Department {
        val entity = departments.findById(id).orElseThrow { ResourceNotFoundException("Department", id) }
        requireCompanyAccess(entity.company.id!!)
        if (!includeDeleted && entity.isDeleted) throw ResourceNotFoundException("Department", id)
        return entity
    }

    private fun activeCompany(id: Long): Company {
        val entity = companies.findById(id).orElseThrow { ResourceNotFoundException("Company", id) }
        if (entity.isDeleted) throw ResourceNotFoundException("Company", id)
        return entity
    }

    private fun linkedDepartment(id: Long, companyId: Long): Department {
        val entity = departments.findById(id).orElseThrow { ResourceNotFoundException("Parent department", id) }
        if (entity.isDeleted) throw ResourceNotFoundException("Parent department", id)
        if (entity.company.id != companyId) throw BadRequestException("Parent department must belong to the same company")
        return entity
    }

    private fun linkedStaff(id: Long, companyId: Long): Staff {
        val entity = staff.findById(id).orElseThrow { ResourceNotFoundException("Staff", id) }
        if (entity.isDeleted) throw ResourceNotFoundException("Staff", id)
        if (entity.company.id != companyId) throw BadRequestException("Department head must belong to the same company")
        return entity
    }

    private fun ensureNoParentCycle(departmentId: Long, parent: Department) {
        var current: Department? = parent
        val visited = mutableSetOf<Long>()
        while (current != null) {
            val currentId = current.id ?: break
            if (currentId == departmentId || !visited.add(currentId)) {
                throw BadRequestException("Department hierarchy cannot contain a cycle")
            }
            current = current.parentDepartment
        }
    }

    private fun scopedCompanyId(requestedCompanyId: Long?): Long? {
        val principal = SecurityUtils.currentPrincipal()
        if (principal.isSuperAdmin) return requestedCompanyId
        val ownCompanyId = principal.companyId ?: throw ForbiddenException("Your account is not assigned to a company")
        if (requestedCompanyId != null && requestedCompanyId != ownCompanyId) {
            throw ForbiddenException("You cannot access departments belonging to another company")
        }
        return ownCompanyId
    }

    private fun requireCompanyAccess(companyId: Long) {
        scopedCompanyId(companyId)
    }

    private fun ensureUnique(companyId: Long, name: String, currentId: Long?) {
        val exists = if (currentId == null) {
            departments.existsByCompany_IdAndNameIgnoreCase(companyId, name)
        } else {
            departments.existsByCompany_IdAndNameIgnoreCaseAndIdNot(companyId, name, currentId)
        }
        if (exists) throw ConflictException("Department already exists for this company.")
    }

    private fun requiredName(value: String?): String = value?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw BadRequestException("Department name is required")

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun recordAudit(action: AuditAction, entity: Department, oldValue: String?) {
        val principal = SecurityUtils.currentPrincipalOrNull() ?: return
        val actor = users.findById(principal.userId).orElse(null) ?: return
        audits.save(AuditLog(
            changedBy = actor,
            changeType = action,
            fieldName = "Department",
            entityType = "Department",
            entityId = entity.id,
            companyId = entity.company.id,
            oldValue = oldValue,
            newValue = auditValue(entity)
        ))
    }

    private fun auditValue(entity: Department): String =
        "id=${entity.id},companyId=${entity.company.id},name=${entity.name},status=${entity.status},deleted=${entity.isDeleted}"

    private fun toResponse(entity: Department) = DepartmentResponse(
        id = entity.id!!,
        companyId = entity.company.id!!,
        companyName = entity.company.name,
        name = entity.name,
        description = entity.description,
        parentDeptId = entity.parentDepartment?.id,
        headStaffId = entity.headStaff?.id,
        status = entity.status,
        isDeleted = entity.isDeleted,
        version = entity.version,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt
    )

    companion object {
        private val SORT_FIELDS = setOf("name", "status", "createdAt", "updatedAt")
    }
}
