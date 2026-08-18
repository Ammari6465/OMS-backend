package com.sunrich.oms.organization

import com.sunrich.oms.common.dto.PageResponse
import com.sunrich.oms.common.enums.AuditAction
import com.sunrich.oms.common.enums.EmploymentType
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.PositionStatus
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
import jakarta.persistence.criteria.JoinType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
class StaffService(
    private val staff: StaffRepository,
    private val companies: CompanyRepository,
    private val departments: DepartmentRepository,
    private val positions: PositionRepository,
    private val users: UserRepository,
    private val audits: AuditLogRepository,
    private val updates: OrganogramUpdatePublisher
) {
    @Transactional(readOnly = true)
    fun list(
        page: Int,
        size: Int,
        sort: String,
        direction: String,
        search: String?,
        companyId: Long?,
        departmentId: Long?,
        positionId: Long?,
        managerId: Long?,
        status: EntityStatus?,
        includeDeleted: Boolean,
        employmentType: EmploymentType? = null,
        joinedFrom: LocalDate? = null,
        joinedTo: LocalDate? = null
    ): PageResponse<StaffResponse> {
        if (joinedFrom != null && joinedTo != null && joinedTo.isBefore(joinedFrom)) {
            throw BadRequestException("Joining date end cannot be before joining date start")
        }
        val effectiveCompanyId = scopedCompanyId(companyId)
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, 200)
        val sortPath = SORT_FIELDS[sort] ?: "name"
        val sortDirection = if (direction.equals("desc", true)) Sort.Direction.DESC else Sort.Direction.ASC
        val pageable = PageRequest.of(safePage, safeSize, Sort.by(sortDirection, sortPath))
        val term = search?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
        val positionedStaffId = positionId?.let { requestedPositionId ->
            val position = positions.findById(requestedPositionId)
                .orElseThrow { ResourceNotFoundException("Position", requestedPositionId) }
            requireCompanyAccess(position.company.id!!)
            position.staff?.id ?: -1L
        }

        val specification = Specification<Staff> { root, query, cb ->
            val predicates = mutableListOf<jakarta.persistence.criteria.Predicate>()
            if (!includeDeleted) predicates += cb.isFalse(root.get("isDeleted"))
            effectiveCompanyId?.let { predicates += cb.equal(root.get<Company>("company").get<Long>("id"), it) }
            departmentId?.let { predicates += cb.equal(root.get<Department>("department").get<Long>("id"), it) }
            managerId?.let { predicates += cb.equal(root.get<Staff>("manager").get<Long>("id"), it) }
            positionedStaffId?.let { predicates += cb.equal(root.get<Long>("id"), it) }
            status?.let { predicates += cb.equal(root.get<EntityStatus>("status"), it) }
            employmentType?.let { predicates += cb.equal(root.get<EmploymentType>("empType"), it) }
            joinedFrom?.let { predicates += cb.greaterThanOrEqualTo(root.get("dateJoined"), it) }
            joinedTo?.let { predicates += cb.lessThanOrEqualTo(root.get("dateJoined"), it) }
            term?.let {
                val pattern = "%${escapeLike(it)}%"
                val department = root.join<Staff, Department>("department", JoinType.LEFT)
                val manager = root.join<Staff, Staff>("manager", JoinType.LEFT)
                val positionTitle = query.subquery(Long::class.java)
                val position = positionTitle.from(Position::class.java)
                positionTitle.select(position.get<Staff>("staff").get("id")).where(
                    cb.isFalse(position.get("isDeleted")),
                    cb.like(cb.lower(position.get("title")), pattern, '\\')
                )
                predicates += cb.or(
                    cb.like(cb.lower(root.get("name")), pattern, '\\'),
                    cb.like(cb.lower(root.get("employeeCode")), pattern, '\\'),
                    cb.like(cb.lower(root.get("email")), pattern, '\\'),
                    cb.like(cb.lower(root.get("title")), pattern, '\\'),
                    cb.like(cb.lower(root.get("cellNumber")), pattern, '\\'),
                    cb.like(cb.lower(root.get("landline")), pattern, '\\'),
                    cb.like(cb.lower(department.get("name")), pattern, '\\'),
                    cb.like(cb.lower(manager.get("name")), pattern, '\\'),
                    root.get<Long>("id").`in`(positionTitle)
                )
            }
            cb.and(*predicates.toTypedArray())
        }

        val result = staff.findAll(specification, pageable)
        val positionByStaff = positionsFor(result.content)
        return PageResponse.from(result) { entity -> toResponse(entity, positionByStaff[entity.id]) }
    }

    /** Compatibility list for dashboard, profile, organogram, and other existing modules. */
    @Transactional(readOnly = true)
    fun listLegacy(includeDeleted: Boolean): List<StaffResponse> {
        val companyId = scopedCompanyId(null)
        val entities = staff.findAll(Sort.by("name")).asSequence()
            .filter { includeDeleted || !it.isDeleted }
            .filter { companyId == null || it.company.id == companyId }
            .toList()
        val positionByStaff = positionsFor(entities)
        return entities.map { toResponse(it, positionByStaff[it.id]) }
    }

    @Transactional(readOnly = true)
    fun get(id: Long): StaffResponse {
        val entity = accessibleStaff(id, includeDeleted = false)
        return toResponse(entity, positions.findFirstByStaff_IdAndIsDeletedFalse(id))
    }

    @Transactional
    fun create(request: StaffCreateRequest): StaffResponse {
        val companyId = request.companyId ?: throw BadRequestException("Company is required")
        requireCompanyAccess(companyId)
        val entity = Staff(
            company = activeCompany(companyId),
            department = request.deptId?.let { activeDepartment(it, companyId) },
            manager = request.managerId?.let { activeManager(it, companyId) },
            employeeCode = normalizeEmployeeCode(request.employeeCode),
            name = requiredName(request.name),
            title = request.title.clean(),
            empType = request.empType ?: EmploymentType.PERMANENT,
            email = request.email.clean()?.lowercase(),
            landline = request.landline.clean(),
            cellNumber = request.cellNumber.clean(),
            dateJoined = request.dateJoined,
            dateLeft = request.dateLeft,
            status = request.status ?: EntityStatus.ACTIVE,
            photoUrl = request.photoUrl.clean()
        )
        normalizeLifecycle(entity)
        validateStaff(entity, null)
        ensureUniqueEmployeeCode(companyId, entity.employeeCode, null)
        val targetPosition = if (canOccupyPosition(entity)) request.positionId?.let { validPosition(it, entity, null) } else null
        val saved = staff.saveAndFlush(entity)
        synchronizePosition(saved, targetPosition)
        return toResponse(saved, targetPosition).also {
            recordAudit(AuditAction.CREATE, saved, null)
            updates.publish(it.companyId, "STAFF", "CREATE", it.id, it.version)
        }
    }

    @Transactional
    fun update(id: Long, request: StaffUpdateRequest): StaffResponse {
        val entity = accessibleStaff(id, includeDeleted = false)
        val version = request.version ?: throw BadRequestException("Version is required")
        if (entity.version != version) throw ConflictException("Staff record was modified by another user. Reload and try again.")
        val companyId = request.companyId ?: throw BadRequestException("Company is required")
        requireCompanyAccess(companyId)
        val oldCompanyId = entity.company.id
        val oldManagerId = entity.manager?.id
        val oldValue = auditValue(entity, positions.findFirstByStaff_IdAndIsDeletedFalse(id))

        entity.company = activeCompany(companyId)
        entity.department = request.deptId?.let { activeDepartment(it, companyId) }
        entity.manager = request.managerId?.let { activeManager(it, companyId) }
        entity.employeeCode = normalizeEmployeeCode(request.employeeCode)
        entity.name = requiredName(request.name)
        entity.title = request.title.clean()
        entity.empType = request.empType ?: EmploymentType.PERMANENT
        entity.email = request.email.clean()?.lowercase()
        entity.landline = request.landline.clean()
        entity.cellNumber = request.cellNumber.clean()
        entity.dateJoined = request.dateJoined
        entity.dateLeft = request.dateLeft
        entity.status = request.status ?: EntityStatus.ACTIVE
        entity.photoUrl = request.photoUrl.clean()

        normalizeLifecycle(entity)
        validateStaff(entity, id)
        ensureUniqueEmployeeCode(companyId, entity.employeeCode, id)
        val targetPosition = if (canOccupyPosition(entity)) request.positionId?.let { validPosition(it, entity, id) } else null
        val saved = staff.saveAndFlush(entity)
        synchronizePosition(saved, targetPosition)
        val action = when {
            oldCompanyId != companyId -> AuditAction.TRANSFER
            oldManagerId != saved.manager?.id -> AuditAction.REPARENT
            else -> AuditAction.UPDATE
        }
        return toResponse(saved, targetPosition).also {
            recordAudit(action, saved, oldValue)
            updates.publish(it.companyId, "STAFF", "UPDATE", it.id, it.version)
        }
    }

    @Transactional
    fun archive(id: Long) {
        val entity = accessibleStaff(id, includeDeleted = false)
        if (users.existsByStaffIdAndIsDeletedFalseAndIsActiveTrue(id)) {
            throw ConflictException("Staff cannot be archived while an active user account is linked to this record.")
        }
        val currentPosition = positions.findFirstByStaff_IdAndIsDeletedFalse(id)
        val oldValue = auditValue(entity, currentPosition)
        synchronizePosition(entity, null)
        staff.findAllByManager_IdAndIsDeletedFalse(id).forEach { it.manager = null }
        departments.findAllByHeadStaff_IdAndIsDeletedFalse(id).forEach { it.headStaff = null }
        entity.status = EntityStatus.INACTIVE
        if (entity.dateLeft == null) entity.dateLeft = LocalDate.now()
        staff.save(entity.apply { markDeleted() })
        recordAudit(AuditAction.DELETE, entity, oldValue)
        updates.publish(entity.company.id!!, "STAFF", "DELETE", id, entity.version)
    }

    @Transactional
    fun restore(id: Long): StaffResponse {
        val entity = accessibleStaff(id, includeDeleted = true)
        activeCompany(entity.company.id!!)
        entity.department?.let { activeDepartment(it.id!!, entity.company.id!!) }
        entity.manager?.let { activeManager(it.id!!, entity.company.id!!) }
        validateStaff(entity, id)
        ensureUniqueEmployeeCode(entity.company.id!!, entity.employeeCode, id)
        entity.status = EntityStatus.ACTIVE
        entity.dateLeft = null
        val saved = staff.saveAndFlush(entity.apply { restore() })
        return toResponse(saved, null).also {
            recordAudit(AuditAction.RESTORE, saved, null)
            updates.publish(it.companyId, "STAFF", "RESTORE", id, it.version)
        }
    }

    @Transactional
    fun createLegacy(request: StaffRequest): StaffResponse = create(StaffCreateRequest(
        companyId = request.companyId,
        deptId = request.deptId,
        managerId = request.managerId,
        positionId = request.positionId,
        employeeCode = request.employeeCode,
        name = request.name,
        title = request.title,
        empType = request.empType,
        email = request.email,
        landline = request.landline,
        cellNumber = request.cellNumber,
        dateJoined = request.dateJoined,
        dateLeft = request.dateLeft,
        status = request.status,
        photoUrl = request.photoUrl
    ))

    /** Partial compatibility update used by drag/drop and older clients. */
    @Transactional
    fun updateLegacy(id: Long, request: StaffRequest): StaffResponse {
        val entity = accessibleStaff(id, includeDeleted = false)
        request.version?.let {
            if (entity.version != it) throw ConflictException("Staff record was modified by another user. Reload and try again.")
        }
        val oldCompanyId = entity.company.id
        val oldManagerId = entity.manager?.id
        val oldPosition = positions.findFirstByStaff_IdAndIsDeletedFalse(id)
        val oldValue = auditValue(entity, oldPosition)
        request.companyId?.let { entity.company = activeCompany(it).also { _ -> requireCompanyAccess(it) } }
        request.deptId?.let { entity.department = activeDepartment(it, entity.company.id!!) }
        request.managerId?.let { entity.manager = activeManager(it, entity.company.id!!) }
        request.employeeCode?.let { entity.employeeCode = normalizeEmployeeCode(it) }
        request.name?.let { entity.name = requiredName(it) }
        request.title?.let { entity.title = it.clean() }
        request.empType?.let { entity.empType = it }
        request.email?.let { entity.email = it.clean()?.lowercase() }
        request.landline?.let { entity.landline = it.clean() }
        request.cellNumber?.let { entity.cellNumber = it.clean() }
        request.dateJoined?.let { entity.dateJoined = it }
        request.dateLeft?.let { entity.dateLeft = it }
        request.status?.let { entity.status = it }
        request.photoUrl?.let { entity.photoUrl = it.clean() }
        normalizeLifecycle(entity)
        validateStaff(entity, id)
        ensureUniqueEmployeeCode(entity.company.id!!, entity.employeeCode, id)
        val targetPosition = if (canOccupyPosition(entity)) request.positionId?.let { validPosition(it, entity, id) } else null
        val saved = staff.saveAndFlush(entity)
        if (request.positionId != null || !canOccupyPosition(saved)) synchronizePosition(saved, targetPosition)
        val position = if (canOccupyPosition(saved)) targetPosition ?: oldPosition else null
        val action = when {
            oldCompanyId != saved.company.id -> AuditAction.TRANSFER
            oldManagerId != saved.manager?.id -> AuditAction.REPARENT
            else -> AuditAction.UPDATE
        }
        return toResponse(saved, position).also {
            recordAudit(action, saved, oldValue)
            updates.publish(it.companyId, "STAFF", "UPDATE", it.id, it.version)
        }
    }

    private fun accessibleStaff(id: Long, includeDeleted: Boolean): Staff {
        val entity = staff.findById(id).orElseThrow { ResourceNotFoundException("Staff", id) }
        requireCompanyAccess(entity.company.id!!)
        if (!includeDeleted && entity.isDeleted) throw ResourceNotFoundException("Staff", id)
        return entity
    }

    private fun activeCompany(id: Long): Company {
        val entity = companies.findById(id).orElseThrow { ResourceNotFoundException("Company", id) }
        if (entity.isDeleted || entity.status != EntityStatus.ACTIVE) throw ResourceNotFoundException("Active company", id)
        return entity
    }

    private fun activeDepartment(id: Long, companyId: Long): Department {
        val entity = departments.findById(id).orElseThrow { ResourceNotFoundException("Department", id) }
        if (entity.isDeleted || entity.status != EntityStatus.ACTIVE) throw ResourceNotFoundException("Active department", id)
        if (entity.company.id != companyId) throw BadRequestException("Department must belong to the selected company")
        return entity
    }

    private fun activeManager(id: Long, companyId: Long): Staff {
        val entity = staff.findById(id).orElseThrow { ResourceNotFoundException("Manager", id) }
        if (entity.isDeleted || entity.status != EntityStatus.ACTIVE) throw BadRequestException("Manager must be an active staff member")
        if (entity.company.id != companyId) throw BadRequestException("Manager must belong to the selected company")
        return entity
    }

    private fun validPosition(id: Long, entity: Staff, currentStaffId: Long?): Position {
        val position = positions.findById(id).orElseThrow { ResourceNotFoundException("Position", id) }
        if (position.isDeleted || position.status == PositionStatus.CLOSED || position.status == PositionStatus.ON_HOLD) throw BadRequestException("Position is not available")
        if (position.company.id != entity.company.id) throw BadRequestException("Position must belong to the selected company")
        if (position.department != null && position.department?.id != entity.department?.id) {
            throw BadRequestException("Position must belong to the selected department")
        }
        val occupantId = position.staff?.id
        if (occupantId != null && occupantId != currentStaffId) throw ConflictException("Position is already assigned to another staff member.")
        return position
    }

    private fun validateStaff(entity: Staff, staffId: Long?) {
        if (entity.department != null && entity.department?.company?.id != entity.company.id) {
            throw BadRequestException("Department must belong to the selected company")
        }
        if (entity.manager != null && entity.manager?.company?.id != entity.company.id) {
            throw BadRequestException("Manager must belong to the selected company")
        }
        ensureNoReportingCycle(staffId, entity.manager)
        if (entity.dateJoined != null && entity.dateLeft != null && entity.dateLeft!!.isBefore(entity.dateJoined)) {
            throw BadRequestException("Date left cannot be before date joined")
        }
    }

    /** Keeps departure state and position occupancy consistent. */
    private fun normalizeLifecycle(entity: Staff) {
        val today = LocalDate.now()
        if (entity.dateLeft != null && !entity.dateLeft!!.isAfter(today)) {
            entity.status = EntityStatus.INACTIVE
        }
        if (entity.status == EntityStatus.INACTIVE && entity.dateLeft == null) {
            entity.dateLeft = today
        }
    }

    private fun canOccupyPosition(entity: Staff): Boolean =
        entity.status == EntityStatus.ACTIVE && (entity.dateLeft == null || entity.dateLeft!!.isAfter(LocalDate.now()))

    private fun ensureNoReportingCycle(staffId: Long?, manager: Staff?) {
        var current = manager
        val visited = mutableSetOf<Long>()
        while (current != null) {
            val currentId = current.id ?: break
            if (currentId == staffId || !visited.add(currentId)) {
                throw BadRequestException("Invalid reporting relationship. This change would create a circular hierarchy.")
            }
            current = current.manager
        }
    }

    private fun synchronizePosition(entity: Staff, target: Position?) {
        val current = positions.findAllByStaff_IdAndIsDeletedFalse(entity.id!!)
        current.filter { it.id != target?.id }.forEach {
            it.staff = null
            it.isVacant = true
            if (it.status == PositionStatus.FILLED) it.status = PositionStatus.OPEN
        }
        target?.let {
            it.staff = entity
            it.isVacant = false
            it.status = PositionStatus.FILLED
        }
        positions.saveAll(current + listOfNotNull(target))
    }

    private fun ensureUniqueEmployeeCode(companyId: Long, code: String?, currentId: Long?) {
        if (code == null) return
        val exists = if (currentId == null) {
            staff.existsByCompany_IdAndEmployeeCodeIgnoreCase(companyId, code)
        } else {
            staff.existsByCompany_IdAndEmployeeCodeIgnoreCaseAndIdNot(companyId, code, currentId)
        }
        if (exists) throw ConflictException("Employee code already exists for this company.")
    }

    private fun scopedCompanyId(requestedCompanyId: Long?): Long? {
        val principal = SecurityUtils.currentPrincipal()
        if (principal.isSuperAdmin) return requestedCompanyId
        val ownCompanyId = principal.companyId ?: throw ForbiddenException("Your account is not assigned to a company")
        if (requestedCompanyId != null && requestedCompanyId != ownCompanyId) {
            throw ForbiddenException("You cannot access staff belonging to another company")
        }
        return ownCompanyId
    }

    private fun requireCompanyAccess(companyId: Long) {
        scopedCompanyId(companyId)
    }

    private fun positionsFor(entities: Collection<Staff>): Map<Long, Position> {
        val ids = entities.mapNotNull(Staff::id)
        if (ids.isEmpty()) return emptyMap()
        return positions.findAllByStaff_IdInAndIsDeletedFalse(ids).associateBy { it.staff!!.id!! }
    }

    private fun toResponse(entity: Staff, position: Position?) = StaffResponse(
        id = entity.id!!,
        companyId = entity.company.id!!,
        companyName = entity.company.name,
        deptId = entity.department?.id,
        departmentName = entity.department?.name,
        managerId = entity.manager?.id,
        managerName = entity.manager?.name,
        positionId = position?.id,
        positionTitle = position?.title,
        employeeCode = entity.employeeCode,
        name = entity.name,
        title = entity.title,
        empType = entity.empType,
        email = entity.email,
        landline = entity.landline,
        cellNumber = entity.cellNumber,
        dateJoined = entity.dateJoined,
        dateLeft = entity.dateLeft,
        status = entity.status,
        photoUrl = entity.photoUrl,
        isDeleted = entity.isDeleted,
        version = entity.version,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt
    )

    private fun recordAudit(action: AuditAction, entity: Staff, oldValue: String?) {
        val principal = SecurityUtils.currentPrincipalOrNull() ?: return
        val actor = users.findById(principal.userId).orElse(null) ?: return
        val position = entity.id?.let(positions::findFirstByStaff_IdAndIsDeletedFalse)
        audits.save(AuditLog(
            staff = entity,
            changedBy = actor,
            changeType = action,
            fieldName = "Staff",
            entityType = "Staff",
            entityId = entity.id,
            companyId = entity.company.id,
            oldValue = oldValue,
            newValue = auditValue(entity, position)
        ))
    }

    private fun auditValue(entity: Staff, position: Position?): String =
        "id=${entity.id},companyId=${entity.company.id},departmentId=${entity.department?.id}," +
            "positionId=${position?.id},managerId=${entity.manager?.id},employeeCode=${entity.employeeCode}," +
            "name=${entity.name},status=${entity.status},deleted=${entity.isDeleted}"

    private fun requiredName(value: String?): String = value?.trim()?.takeIf(String::isNotEmpty)
        ?: throw BadRequestException("Staff name is required")

    private fun normalizeEmployeeCode(value: String?): String? = value.clean()?.uppercase()

    private fun String?.clean(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private fun escapeLike(value: String): String = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    companion object {
        private val SORT_FIELDS = mapOf(
            "name" to "name",
            "employeeCode" to "employeeCode",
            "title" to "title",
            "dateJoined" to "dateJoined",
            "status" to "status",
            "createdAt" to "createdAt",
            "updatedAt" to "updatedAt"
        )
    }
}
