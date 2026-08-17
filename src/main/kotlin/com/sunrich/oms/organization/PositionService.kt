package com.sunrich.oms.organization

import com.sunrich.oms.common.dto.PageResponse
import com.sunrich.oms.common.enums.AuditAction
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.PositionStatus
import com.sunrich.oms.common.enums.NotificationType
import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.exception.ConflictException
import com.sunrich.oms.exception.ForbiddenException
import com.sunrich.oms.exception.ResourceNotFoundException
import com.sunrich.oms.realtime.OrganogramUpdatePublisher
import com.sunrich.oms.security.SecurityUtils
import com.sunrich.oms.systemdata.AuditLog
import com.sunrich.oms.systemdata.AuditLogRepository
import com.sunrich.oms.systemdata.NotificationDeliveryService
import com.sunrich.oms.user.UserRepository
import jakarta.persistence.criteria.JoinType
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PositionService(
    private val positions: PositionRepository,
    private val companies: CompanyRepository,
    private val departments: DepartmentRepository,
    private val staff: StaffRepository,
    private val users: UserRepository,
    private val audits: AuditLogRepository,
    private val notifications: NotificationDeliveryService,
    private val updates: OrganogramUpdatePublisher
) {
    @Transactional(readOnly = true)
    fun list(
        page: Int, size: Int, sort: String, direction: String, search: String?,
        companyId: Long?, departmentId: Long?, status: PositionStatus?,
        reportsToPositionId: Long?, assigned: Boolean?, vacant: Boolean?, includeDeleted: Boolean,
        positionId: Long? = null
    ): PageResponse<PositionResponse> {
        val effectiveCompanyId = scopedCompanyId(companyId)
        val pageable = PageRequest.of(
            page.coerceAtLeast(0), size.coerceIn(1, 200),
            Sort.by(if (direction.equals("desc", true)) Sort.Direction.DESC else Sort.Direction.ASC,
                SORT_FIELDS[sort] ?: "title")
        )
        val term = search?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
        val specification = Specification<Position> { root, _, cb ->
            val predicates = mutableListOf<jakarta.persistence.criteria.Predicate>()
            if (!includeDeleted) predicates += cb.isFalse(root.get("isDeleted"))
            positionId?.let { predicates += cb.equal(root.get<Long>("id"), it) }
            effectiveCompanyId?.let { predicates += cb.equal(root.get<Company>("company").get<Long>("id"), it) }
            departmentId?.let { predicates += cb.equal(root.get<Department>("department").get<Long>("id"), it) }
            status?.let { predicates += cb.equal(root.get<PositionStatus>("status"), it) }
            reportsToPositionId?.let { predicates += cb.equal(root.get<Position>("reportsToPosition").get<Long>("id"), it) }
            assigned?.let { predicates += if (it) cb.isNotNull(root.get<Staff>("staff")) else cb.isNull(root.get<Staff>("staff")) }
            vacant?.let { predicates += cb.equal(root.get<Boolean>("isVacant"), it) }
            term?.let {
                val pattern = "%${escapeLike(it)}%"
                val company = root.join<Position, Company>("company", JoinType.INNER)
                val department = root.join<Position, Department>("department", JoinType.LEFT)
                val parent = root.join<Position, Position>("reportsToPosition", JoinType.LEFT)
                val occupant = root.join<Position, Staff>("staff", JoinType.LEFT)
                val searchable = mutableListOf(
                    cb.like(cb.lower(root.get("title")), pattern, '\\'),
                    cb.like(cb.lower(company.get("name")), pattern, '\\'),
                    cb.like(cb.lower(department.get("name")), pattern, '\\'),
                    cb.like(cb.lower(parent.get("title")), pattern, '\\'),
                    cb.like(cb.lower(occupant.get("name")), pattern, '\\')
                )
                term.toLongOrNull()?.let { id -> searchable += cb.equal(root.get<Long>("id"), id) }
                predicates += cb.or(*searchable.toTypedArray())
            }
            cb.and(*predicates.toTypedArray())
        }
        val result = positions.findAll(specification, pageable)
        return PageResponse.from(result, ::toResponse)
    }

    @Transactional(readOnly = true)
    fun listLegacy(includeDeleted: Boolean): List<PositionResponse> {
        val companyId = scopedCompanyId(null)
        return positions.findAll(Sort.by("title")).asSequence()
            .filter { includeDeleted || !it.isDeleted }
            .filter { companyId == null || it.company.id == companyId }
            .map(::toResponse).toList()
    }

    @Transactional(readOnly = true)
    fun get(id: Long): PositionResponse = toResponse(accessiblePosition(id, false), includeChildCount = true)

    @Transactional(readOnly = true)
    fun vacancySummary(companyId: Long?): VacancySummaryResponse {
        val effectiveCompanyId = scopedCompanyId(companyId)
        return if (effectiveCompanyId == null) VacancySummaryResponse(
            total = positions.countByIsDeletedFalse(),
            open = positions.countByIsDeletedFalseAndStatusAndIsVacantTrue(PositionStatus.OPEN),
            filled = positions.countByIsDeletedFalseAndStatus(PositionStatus.FILLED),
            closed = positions.countByIsDeletedFalseAndStatus(PositionStatus.CLOSED)
        ) else VacancySummaryResponse(
            total = positions.countByCompany_IdAndIsDeletedFalse(effectiveCompanyId),
            open = positions.countByCompany_IdAndIsDeletedFalseAndStatusAndIsVacantTrue(effectiveCompanyId, PositionStatus.OPEN),
            filled = positions.countByCompany_IdAndIsDeletedFalseAndStatus(effectiveCompanyId, PositionStatus.FILLED),
            closed = positions.countByCompany_IdAndIsDeletedFalseAndStatus(effectiveCompanyId, PositionStatus.CLOSED)
        )
    }

    @Transactional
    fun create(request: PositionCreateRequest): PositionResponse {
        val companyId = request.companyId ?: throw BadRequestException("Company is required")
        requireCompanyAccess(companyId)
        val entity = Position(
            company = activeCompany(companyId),
            title = requiredTitle(request.title),
            department = request.deptId?.let { activeDepartment(it, companyId) },
            reportsToPosition = request.reportsToPositionId?.let { activeParent(it, companyId) },
            staff = request.staffId?.let { activeStaff(it, companyId) },
            status = request.status ?: PositionStatus.OPEN
        )
        validate(entity, null)
        ensureUniqueTitle(companyId, entity.title, null)
        applyOccupancyState(entity)
        val saved = positions.saveAndFlush(entity)
        return toResponse(saved).also {
            recordAudit(AuditAction.CREATE, saved, null, if (saved.isVacant) "Vacancy" else "Position")
            if (saved.isVacant) notifyActor(NotificationType.VACANCY_OPENED, "${saved.title} vacancy was opened.")
            updates.publish("Position", "CREATE", it.id)
        }
    }

    @Transactional
    fun update(id: Long, request: PositionUpdateRequest): PositionResponse {
        val entity = accessiblePosition(id, false)
        if (entity.version != request.version) throw ConflictException("Position was modified by another user. Reload and try again.")
        val oldValue = auditValue(entity)
        val oldStatus = entity.status
        val oldParentId = entity.reportsToPosition?.id
        val companyId = request.companyId ?: throw BadRequestException("Company is required")
        requireCompanyAccess(companyId)
        entity.company = activeCompany(companyId)
        entity.title = requiredTitle(request.title)
        entity.department = request.deptId?.let { activeDepartment(it, companyId) }
        entity.reportsToPosition = request.reportsToPositionId?.let { activeParent(it, companyId) }
        entity.staff = request.staffId?.let { activeStaff(it, companyId) }
        entity.status = request.status ?: PositionStatus.OPEN
        validate(entity, id)
        ensureUniqueTitle(companyId, entity.title, id)
        if (entity.status == PositionStatus.CLOSED && positions.existsByReportsToPosition_IdAndIsDeletedFalse(id)) {
            throw ConflictException("This position cannot be closed because other positions report to it.")
        }
        applyOccupancyState(entity)
        val saved = positions.saveAndFlush(entity)
        return toResponse(saved).also {
            val vacancyTransition = oldStatus != saved.status
            recordAudit(if (oldParentId != saved.reportsToPosition?.id) AuditAction.REPARENT else AuditAction.UPDATE,
                saved, oldValue, if (vacancyTransition) "Vacancy" else "Position")
            if (vacancyTransition) {
                val type = if (saved.status == PositionStatus.OPEN) NotificationType.VACANCY_OPENED else NotificationType.VACANCY_CLOSED
                notifyActor(type, "${saved.title} vacancy is now ${saved.status.name.lowercase()}.")
            }
            updates.publish("Position", "UPDATE", it.id)
        }
    }

    @Transactional
    fun archive(id: Long) {
        val entity = accessiblePosition(id, false)
        if (entity.staff != null) throw ConflictException("This position cannot be archived because it is currently assigned to staff.")
        if (positions.existsByReportsToPosition_IdAndIsDeletedFalse(id)) {
            throw ConflictException("This position cannot be archived because other positions report to it.")
        }
        val oldValue = auditValue(entity)
        entity.status = PositionStatus.CLOSED
        positions.save(entity.apply { markDeleted() })
        recordAudit(AuditAction.DELETE, entity, oldValue)
        updates.publish("Position", "DELETE", id)
    }

    @Transactional
    fun restore(id: Long): PositionResponse {
        val entity = accessiblePosition(id, true)
        activeCompany(entity.company.id!!)
        entity.department?.let { activeDepartment(it.id!!, entity.company.id!!) }
        entity.reportsToPosition?.let { activeParent(it.id!!, entity.company.id!!) }
        validate(entity, id)
        ensureUniqueTitle(entity.company.id!!, entity.title, id)
        entity.status = if (entity.staff == null) PositionStatus.OPEN else PositionStatus.FILLED
        val saved = positions.saveAndFlush(entity.apply { restore() })
        return toResponse(saved).also {
            recordAudit(AuditAction.RESTORE, saved, null)
            updates.publish("Position", "RESTORE", id)
        }
    }

    @Transactional
    fun createLegacy(request: PositionRequest) = create(PositionCreateRequest(
        request.companyId, request.title, request.deptId, request.reportsToPositionId, request.staffId, request.status
    ))

    @Transactional
    fun updateLegacy(id: Long, request: PositionRequest): PositionResponse {
        val current = accessiblePosition(id, false)
        return update(id, PositionUpdateRequest(
            companyId = request.companyId ?: current.company.id,
            title = request.title ?: current.title,
            deptId = if (request.deptId != null) request.deptId else current.department?.id,
            reportsToPositionId = if (request.reportsToPositionId != null) request.reportsToPositionId else current.reportsToPosition?.id,
            staffId = request.staffId,
            status = request.status ?: current.status,
            version = current.version
        ))
    }

    private fun validate(entity: Position, currentId: Long?) {
        if (entity.department != null && entity.department?.company?.id != entity.company.id) {
            throw BadRequestException("The selected department does not belong to the selected company.")
        }
        if (entity.staff != null && entity.staff?.company?.id != entity.company.id) {
            throw BadRequestException("Assigned staff member must belong to the selected company")
        }
        if (entity.staff != null && entity.department != null && entity.staff?.department?.id != entity.department?.id) {
            throw BadRequestException("Assigned staff member must belong to the position's department")
        }
        if (entity.reportsToPosition?.company?.id != null && entity.reportsToPosition?.company?.id != entity.company.id) {
            throw BadRequestException("Reporting position must belong to the selected company")
        }
        ensureNoHierarchyCycle(currentId, entity.reportsToPosition)
        entity.staff?.id?.let { staffId ->
            val occupied = positions.findFirstByStaff_IdAndIsDeletedFalse(staffId)
            if (occupied != null && occupied.id != currentId) throw ConflictException("Staff member is already assigned to another position.")
        }
    }

    private fun ensureNoHierarchyCycle(positionId: Long?, parent: Position?) {
        var current = parent
        val visited = mutableSetOf<Long>()
        while (current != null) {
            val currentId = current.id ?: break
            if (currentId == positionId || !visited.add(currentId)) {
                throw BadRequestException("Invalid reporting relationship. This change would create a circular position hierarchy.")
            }
            current = current.reportsToPosition
        }
    }

    private fun applyOccupancyState(entity: Position) {
        if (entity.staff != null) {
            if (entity.status == PositionStatus.CLOSED) throw BadRequestException("A closed position cannot have assigned staff")
            if (entity.status == PositionStatus.ON_HOLD) throw BadRequestException("An on-hold position cannot have assigned staff")
            entity.isVacant = false
            entity.status = PositionStatus.FILLED
        } else {
            entity.isVacant = entity.status == PositionStatus.OPEN
            if (entity.status == PositionStatus.FILLED) entity.status = PositionStatus.OPEN
        }
    }

    private fun activeCompany(id: Long): Company = companies.findById(id)
        .orElseThrow { ResourceNotFoundException("Company", id) }
        .also { if (it.isDeleted || it.status != EntityStatus.ACTIVE) throw ResourceNotFoundException("Active company", id) }

    private fun activeDepartment(id: Long, companyId: Long): Department = departments.findById(id)
        .orElseThrow { ResourceNotFoundException("Department", id) }
        .also {
            if (it.isDeleted || it.status != EntityStatus.ACTIVE) throw ResourceNotFoundException("Active department", id)
            if (it.company.id != companyId) throw BadRequestException("The selected department does not belong to the selected company.")
        }

    private fun activeStaff(id: Long, companyId: Long): Staff = staff.findById(id)
        .orElseThrow { ResourceNotFoundException("Staff", id) }
        .also {
            if (it.isDeleted || it.status != EntityStatus.ACTIVE) throw BadRequestException("Assigned staff member must be active")
            if (it.company.id != companyId) throw BadRequestException("Assigned staff member must belong to the selected company")
        }

    private fun activeParent(id: Long, companyId: Long): Position = positions.findById(id)
        .orElseThrow { ResourceNotFoundException("Reporting position", id) }
        .also {
            if (it.isDeleted || it.status == PositionStatus.CLOSED || it.status == PositionStatus.ON_HOLD) throw BadRequestException("Reporting position must be active")
            if (it.company.id != companyId) throw BadRequestException("Reporting position must belong to the selected company")
        }

    private fun accessiblePosition(id: Long, includeDeleted: Boolean): Position {
        val entity = positions.findById(id).orElseThrow { ResourceNotFoundException("Position", id) }
        requireCompanyAccess(entity.company.id!!)
        if (!includeDeleted && entity.isDeleted) throw ResourceNotFoundException("Position", id)
        return entity
    }

    private fun ensureUniqueTitle(companyId: Long, title: String, currentId: Long?) {
        val exists = if (currentId == null) positions.existsByCompany_IdAndTitleIgnoreCaseAndIsDeletedFalse(companyId, title)
        else positions.existsByCompany_IdAndTitleIgnoreCaseAndIsDeletedFalseAndIdNot(companyId, title, currentId)
        if (exists) throw ConflictException("A position with this title already exists for the selected company.")
    }

    private fun scopedCompanyId(requested: Long?): Long? {
        val principal = SecurityUtils.currentPrincipal()
        if (principal.isSuperAdmin) return requested
        val own = principal.companyId ?: throw ForbiddenException("Your account is not assigned to a company")
        if (requested != null && requested != own) throw ForbiddenException("You cannot access positions belonging to another company")
        return own
    }

    private fun requireCompanyAccess(companyId: Long) { scopedCompanyId(companyId) }

    private fun toResponse(entity: Position, includeChildCount: Boolean = false) = PositionResponse(
        id = entity.id!!, companyId = entity.company.id!!, title = entity.title, companyName = entity.company.name,
        deptId = entity.department?.id, departmentName = entity.department?.name,
        reportsToPositionId = entity.reportsToPosition?.id, reportsToPositionTitle = entity.reportsToPosition?.title,
        isVacant = entity.isVacant, staffId = entity.staff?.id, staffName = entity.staff?.name,
        status = entity.status, isDeleted = entity.isDeleted, version = entity.version,
        subordinateCount = if (includeChildCount) positions.countByReportsToPosition_IdAndIsDeletedFalse(entity.id!!) else 0,
        createdBy = entity.createdBy, updatedBy = entity.updatedBy, createdAt = entity.createdAt, updatedAt = entity.updatedAt
    )

    private fun recordAudit(action: AuditAction, entity: Position, oldValue: String?, fieldName: String = "Position") {
        val principal = SecurityUtils.currentPrincipalOrNull() ?: return
        val actor = users.findById(principal.userId).orElse(null) ?: return
        audits.save(AuditLog(changedBy = actor, changeType = action, fieldName = fieldName,
            entityType = if (fieldName == "Vacancy") "Vacancy" else "Position", entityId = entity.id, companyId = entity.company.id,
            oldValue = oldValue, newValue = auditValue(entity)))
    }

    private fun notifyActor(type: NotificationType, message: String) {
        val principal = SecurityUtils.currentPrincipalOrNull() ?: return
        val actor = users.findById(principal.userId).orElse(null) ?: return
        notifications.deliver(actor, type, message, "/positions", "Position")
    }

    private fun auditValue(entity: Position) =
        "id=${entity.id},companyId=${entity.company.id},departmentId=${entity.department?.id}," +
            "reportsToPositionId=${entity.reportsToPosition?.id},staffId=${entity.staff?.id}," +
            "title=${entity.title},status=${entity.status},deleted=${entity.isDeleted}"

    private fun requiredTitle(value: String?): String = value?.trim()?.takeIf(String::isNotEmpty)
        ?: throw BadRequestException("Position title is required")
    private fun escapeLike(value: String) = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    companion object {
        private val SORT_FIELDS = mapOf("title" to "title", "status" to "status", "createdAt" to "createdAt", "updatedAt" to "updatedAt")
    }
}
