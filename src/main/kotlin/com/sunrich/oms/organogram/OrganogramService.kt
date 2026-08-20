package com.sunrich.oms.organogram

import com.sunrich.oms.common.enums.AuditAction
import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.PositionStatus
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.exception.ConflictException
import com.sunrich.oms.exception.ForbiddenException
import com.sunrich.oms.exception.ResourceNotFoundException
import com.sunrich.oms.organization.*
import com.sunrich.oms.realtime.OrganogramUpdatePublisher
import com.sunrich.oms.security.SecurityUtils
import com.sunrich.oms.systemdata.AuditTrailService
import com.sunrich.oms.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class OrganogramService(
    private val companies: CompanyRepository,
    private val departments: DepartmentRepository,
    private val staff: StaffRepository,
    private val assignments: StaffCompanyAssignmentRepository,
    private val positions: PositionRepository,
    private val users: UserRepository,
    private val audit: AuditTrailService,
    private val updates: OrganogramUpdatePublisher
) {
    @Transactional(readOnly = true)
    fun get(companyId: Long, view: OrganogramView, includeVacancies: Boolean): OrganogramResponse {
        scope(companyId)
        val company = companies.findById(companyId).orElseThrow { ResourceNotFoundException("Company", companyId) }
        if (company.isDeleted || company.status != EntityStatus.ACTIVE) throw ResourceNotFoundException("Active company", companyId)
        val depts = departments.findAllByCompany_IdAndIsDeletedFalse(companyId).filter { it.status == EntityStatus.ACTIVE }
        val assignedPeople = assignments.findAllByCompany_IdAndStatusAndIsDeletedFalse(companyId, EntityStatus.ACTIVE)
            .filter { !it.staff.isDeleted && it.staff.status == EntityStatus.ACTIVE }
        val assignedIds = assignedPeople.mapTo(hashSetOf()) { it.staff.id }
        val legacyPrimaryPeople = staff.findAllByCompany_IdAndIsDeletedFalse(companyId)
            .filter { it.status == EntityStatus.ACTIVE && it.id !in assignedIds }
            .map { person -> StaffCompanyAssignment(
                staff = person, company = company, department = person.department, manager = person.manager,
                title = person.title, isPrimary = true, effectiveFrom = person.dateJoined, effectiveTo = person.dateLeft,
                status = person.status
            ) }
        val people = assignedPeople + legacyPrimaryPeople
        val roles = positions.findAllByCompany_IdAndIsDeletedFalse(companyId).filter { it.status != PositionStatus.CLOSED }

        val nodes = if (view == OrganogramView.EMPLOYEE) people.map { assignment ->
            val person = assignment.staff
            OrganogramNode(person.id!!, assignment.manager?.id, companyId, assignment.department?.id, person.employeeCode,
                person.name, assignment.title ?: person.title, person.photoUrl, person.status,
                maxOf(person.version, assignment.version), staffId = person.id)
        } else roles.filter { includeVacancies || !it.isVacant }.map { role ->
            OrganogramNode(role.id!!, role.reportsToPosition?.id, companyId, role.department?.id,
                role.staff?.employeeCode, role.staff?.name ?: role.title, role.title, role.staff?.photoUrl,
                role.staff?.status, role.version, role.isVacant, role.staff?.id)
        }

        val topology = topology(nodes)
        val vacancies = if (includeVacancies) roles.filter { it.isVacant && it.status == PositionStatus.OPEN }.map {
            OrganogramVacancy(it.id!!, it.title, it.department?.id, it.reportsToPosition?.id, it.version)
        } else emptyList()
        val versions = nodes.map { it.version } + depts.map { it.version } + vacancies.map { it.version }
        val principal = SecurityUtils.currentPrincipal()
        val canEdit = principal.role == Role.SUPER_ADMIN || principal.role == Role.COMPANY_ADMIN
        val canContact = canEdit || principal.role == Role.MANAGER

        return OrganogramResponse(
            OrganogramCompany(company.id!!, company.name, company.logoUrl), view, nodes, topology.roots, topology.orphans,
            depts.map { OrganogramDepartment(it.id!!, it.name, it.parentDepartment?.id, it.headStaff?.id) },
            vacancies, versions.maxOrNull() ?: 0, Instant.now(), OrganogramCapabilities(canEdit, canContact), topology.warnings
        )
    }

    @Transactional(readOnly = true)
    fun staffDetails(staffId: Long): OrganogramStaffDetails {
        val person = staff.findById(staffId).orElseThrow { ResourceNotFoundException("Staff", staffId) }
        scopeStaff(person)
        if (person.isDeleted) throw ResourceNotFoundException("Staff", staffId)
        val canContact = SecurityUtils.currentPrincipal().role in setOf(Role.SUPER_ADMIN, Role.COMPANY_ADMIN, Role.MANAGER)
        return OrganogramStaffDetails(person.id!!, person.name, person.employeeCode, person.title, person.department?.id,
            person.manager?.id, person.empType, person.dateJoined, person.dateLeft, person.status, person.photoUrl,
            if (canContact) person.email else null, if (canContact) person.landline else null,
            if (canContact) person.cellNumber else null, person.version)
    }

    @Transactional
    fun changeManager(staffId: Long, request: ManagerChangeRequest): OrganogramNode {
        val principal = SecurityUtils.currentPrincipal()
        if (principal.role !in setOf(Role.SUPER_ADMIN, Role.COMPANY_ADMIN)) throw ForbiddenException()
        val person = staff.findById(staffId).orElseThrow { ResourceNotFoundException("Staff", staffId) }
        val companyId = person.company.id!!
        scope(companyId)
        if (person.isDeleted || person.status != EntityStatus.ACTIVE) throw BadRequestException("Only active staff can be reassigned")
        val sentVersion = request.version ?: throw BadRequestException("Version is required")
        if (person.version != sentVersion) throw ConflictException("Staff record was modified by another user. Refresh and try again.")
        if (request.managerId == staffId) throw BadRequestException("An employee cannot manage themselves")
        val oldManagerId = person.manager?.id
        val manager = request.managerId?.let { id ->
            staff.findById(id).orElseThrow { ResourceNotFoundException("Manager", id) }.also {
                if (it.isDeleted || it.status != EntityStatus.ACTIVE) throw BadRequestException("Manager must be an active staff member")
                if (it.company.id != companyId) throw BadRequestException("Manager must belong to the same company")
                ensureNoCycle(person, it)
            }
        }
        person.manager = manager
        val saved = staff.saveAndFlush(person)
        users.findById(principal.userId).orElse(null)?.let { actor ->
            audit.record(actor, AuditAction.REPARENT, "Staff", saved.id, companyId, "Manager",
                "managerId=$oldManagerId", "managerId=${saved.manager?.id}")
        }
        updates.publish(companyId, "STAFF", "REPARENT", saved.id!!, saved.version)
        return node(saved)
    }

    private fun node(person: Staff) = OrganogramNode(person.id!!, person.manager?.id, person.company.id!!,
        person.department?.id, person.employeeCode, person.name, person.title, person.photoUrl, person.status,
        person.version, staffId = person.id)

    private fun ensureNoCycle(person: Staff, proposed: Staff) {
        val seen = mutableSetOf<Long>()
        var current: Staff? = proposed
        while (current != null && seen.add(current.id!!)) {
            if (current.id == person.id) throw BadRequestException("Invalid reporting relationship. This change would create a circular hierarchy.")
            current = current.manager
        }
    }

    private data class Topology(val roots: List<Long>, val orphans: List<Long>, val warnings: List<OrganogramWarning>)

    private fun topology(nodes: List<OrganogramNode>): Topology {
        val ids = nodes.mapTo(hashSetOf()) { it.id }
        val orphans = nodes.filter { it.parentId != null && it.parentId !in ids }.map { it.id }
        val cycleIds = linkedSetOf<Long>()
        val parent = nodes.associate { it.id to it.parentId }
        nodes.forEach { start ->
            val path = linkedSetOf<Long>()
            var current: Long? = start.id
            while (current != null && current in ids) {
                if (!path.add(current)) { cycleIds += path.dropWhile { it != current }; break }
                current = parent[current]
            }
        }
        val roots = nodes.filter { it.parentId == null || it.id in orphans || it.id in cycleIds }.map { it.id }.distinct()
        val warnings = buildList {
            if (orphans.isNotEmpty()) add(OrganogramWarning("MISSING_MANAGER", "Some nodes reference a missing or inactive parent.", orphans))
            if (cycleIds.isNotEmpty()) add(OrganogramWarning("CYCLE", "Circular hierarchy relationships were isolated safely.", cycleIds.toList()))
            if (roots.size > 1) add(OrganogramWarning("MULTIPLE_ROOTS", "The hierarchy contains multiple root nodes.", roots))
        }
        return Topology(roots, orphans, warnings)
    }

    private fun scope(companyId: Long) {
        val principal = SecurityUtils.currentPrincipal()
        if (!principal.canAccessCompany(companyId)) {
            throw ForbiddenException("You cannot access another company's organogram")
        }
    }

    private fun scopeStaff(person: Staff) {
        val principal = SecurityUtils.currentPrincipal()
        if (principal.role == Role.SUPER_ADMIN) return
        if (person.company.id !in principal.companyIds && principal.companyIds.none {
                assignments.existsByStaff_IdAndCompany_IdAndIsDeletedFalse(person.id!!, it)
            }) {
            throw ForbiddenException("You cannot access another company's staff profile")
        }
    }
}
