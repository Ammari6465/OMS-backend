package com.sunrich.oms.lifecycle

import com.fasterxml.jackson.databind.ObjectMapper
import com.sunrich.oms.common.enums.*
import com.sunrich.oms.exception.*
import com.sunrich.oms.organization.*
import com.sunrich.oms.user.*
import com.sunrich.oms.systemdata.AuditTrailService
import com.sunrich.oms.workplace.WorkplaceService
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.*
import java.util.UUID

@Service
class LifecycleExecutionService(
    private val workflows: LifecycleWorkflowRepository, private val executionLogs: LifecycleExecutionLogRepository,
    private val staff: StaffRepository, private val companies: CompanyRepository, private val departments: DepartmentRepository,
    private val positions: PositionRepository, private val users: UserRepository, private val encoder: PasswordEncoder,
    private val mapper: ObjectMapper, private val clock: Clock, private val audit: AuditTrailService,
    private val workplaces: WorkplaceService,
    private val deskBookings: com.sunrich.oms.workplace.BookingService,
    private val roomBookings: com.sunrich.oms.workplace.RoomBookingService
) {
    @Transactional
    fun execute(id: Long): LifecycleWorkflow {
        val w = workflows.findLocked(id) ?: throw ResourceNotFoundException("Lifecycle workflow", id)
        if (w.status == WorkflowStatus.COMPLETED) return w
        if (w.status !in setOf(WorkflowStatus.APPROVED, WorkflowStatus.SCHEDULED, WorkflowStatus.FAILED))
            throw ConflictException("Only an approved, scheduled, or failed workflow can be executed")
        w.status = WorkflowStatus.IN_PROGRESS; w.failureReason = null
        val steps = mutableListOf<String>()
        w.beforeSnapshot = snapshot(w)
        when (w.type) {
            LifecycleType.LEAVER -> executeLeaver(w, steps)
            LifecycleType.MOVER -> executeMover(w, steps)
            LifecycleType.JOINER -> executeJoiner(w, steps)
        }
        w.status = WorkflowStatus.COMPLETED; w.completedAt = LocalDateTime.now(clock)
        executionLogs.save(LifecycleExecutionLog(w, LocalDateTime.now(clock), ExecutionResult.SUCCEEDED, steps.joinToString(", ")))
        w.requestedBy?.let { audit.record(it, AuditAction.WORKFLOW_EXECUTE, "LifecycleWorkflow", w.id, w.company.id, "${w.type.name} workflow", w.beforeSnapshot, "workflow=${w.workflowNumber},status=COMPLETED") }
        return workflows.save(w)
    }

    private fun executeLeaver(w: LifecycleWorkflow, steps: MutableList<String>) {
        val person = requireStaff(w)
        if (person.status == EntityStatus.INACTIVE && person.dateLeft == w.effectiveDate) { steps += "staff already inactive"; return }
        val reports = staff.findAllByManager_IdAndIsDeletedFalse(person.id!!).filter { it.status == EntityStatus.ACTIVE }
        if (reports.isNotEmpty() && w.successorManagerId == null && !w.responsibilitiesAcknowledged)
            throw ConflictException("Choose a successor manager or acknowledge that direct reports will be unassigned")
        val successor = w.successorManagerId?.let { activeStaff(it, w.company.id!!) }
        reports.forEach { it.manager = successor }; staff.saveAll(reports); steps += "reporting lines reassigned"
        val headed = departments.findAllByHeadStaff_IdAndIsDeletedFalse(person.id!!)
        if (headed.isNotEmpty() && w.replacementHeadId == null && !w.responsibilitiesAcknowledged)
            throw ConflictException("Choose a replacement department head or acknowledge that the role will be cleared")
        val replacement = w.replacementHeadId?.let { activeStaff(it, w.company.id!!) }
        headed.forEach { it.headStaff = replacement }; departments.saveAll(headed); steps += "department ownership updated"
        positions.findAllByStaff_IdAndIsDeletedFalse(person.id!!).forEach { releasePosition(it, w.positionDisposition ?: PositionDisposition.OPEN) }
        steps += "position disposition applied"
        workplaces.releaseForStaff(person.id!!, w.effectiveDate, "Staff exit via ${w.workflowNumber}")
        steps += "workplace assignment released"
        // Cancel the leaver's future desk and meeting-room bookings so they do not hold space after they have gone.
        val cancelledDesk = deskBookings.cancelUpcomingForStaff(person.id!!, w.effectiveDate, "Staff exit via ${w.workflowNumber}")
        val cancelledRoom = roomBookings.cancelUpcomingForStaff(person.id!!, w.effectiveDate, "Staff exit via ${w.workflowNumber}")
        if (cancelledDesk + cancelledRoom > 0) steps += "cancelled $cancelledDesk desk and $cancelledRoom room bookings"
        person.status = EntityStatus.INACTIVE; person.dateLeft = w.effectiveDate; staff.save(person); steps += "staff deactivated"
        users.findFirstByStaffIdAndIsDeletedFalse(person.id!!)?.let {
            it.isActive = false; it.status = EntityStatus.INACTIVE; it.passwordResetToken = null; it.passwordResetExpires = null
            it.lockedUntil = null; it.failedLoginAttempts = 0; users.save(it); steps += "login access revoked"
        }
    }

    private fun executeMover(w: LifecycleWorkflow, steps: MutableList<String>) {
        val person = requireStaff(w); val targetCompany = companies.findById(w.targetCompanyId ?: w.company.id!!).orElseThrow { ResourceNotFoundException("Target company") }
        val targetDepartment = w.targetDepartmentId?.let { departments.findById(it).orElseThrow { ResourceNotFoundException("Target department", it) } }
        if (targetDepartment != null && targetDepartment.company.id != targetCompany.id) throw BadRequestException("Target department does not belong to target company")
        val manager = w.targetManagerId?.let { activeStaff(it, targetCompany.id!!) }
        if (manager?.id == person.id || managerChainContains(manager, person.id!!)) throw BadRequestException("The selected manager creates a reporting cycle")
        val targetPosition = w.targetPositionId?.let { positions.findById(it).orElseThrow { ResourceNotFoundException("Target position", it) } }
        if (targetPosition != null) {
            if (targetPosition.company.id != targetCompany.id || targetPosition.department?.id != targetDepartment?.id) throw BadRequestException("Target position does not match the selected organisation")
            if (targetPosition.staff != null && targetPosition.staff?.id != person.id) throw ConflictException("Target position is already occupied")
            if (targetPosition.status in setOf(PositionStatus.CLOSED, PositionStatus.ON_HOLD)) throw ConflictException("Target position is not available")
        }
        positions.findAllByStaff_IdAndIsDeletedFalse(person.id!!).filter { it.id != targetPosition?.id }.forEach { releasePosition(it, w.positionDisposition ?: PositionDisposition.OPEN) }
        person.company = targetCompany; person.department = targetDepartment; person.manager = manager; w.targetTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { person.title = it }
        staff.save(person)
        targetPosition?.let { it.staff = person; it.status = PositionStatus.FILLED; it.isVacant = false; positions.save(it) }
        users.findFirstByStaffIdAndIsDeletedFalse(person.id!!)?.let { it.companyId = targetCompany.id; users.save(it) }
        steps += listOf("previous position released", "staff organisation updated", "target position assigned")
    }

    private fun executeJoiner(w: LifecycleWorkflow, steps: MutableList<String>) {
        val company = companies.findById(w.targetCompanyId ?: w.company.id!!).orElseThrow { ResourceNotFoundException("Company") }
        val department = w.targetDepartmentId?.let { departments.findById(it).orElseThrow { ResourceNotFoundException("Department", it) } }
        if (department != null && department.company.id != company.id) throw BadRequestException("Department does not belong to company")
        val name = w.joinerName?.trim().takeUnless { it.isNullOrEmpty() } ?: throw BadRequestException("Joiner name is required")
        val email = w.joinerEmail?.trim()?.lowercase()
        val person = Staff(company, department, w.targetManagerId?.let { activeStaff(it, company.id!!) }, w.joinerEmployeeCode?.trim(), name,
            w.targetTitle?.trim(), w.joinerEmploymentType ?: EmploymentType.PERMANENT, email, null, w.joinerPhone?.trim(), w.effectiveDate, null, EntityStatus.ACTIVE)
        val saved = staff.save(person); w.staff = saved; steps += "staff record activated"
        w.targetPositionId?.let {
            val position = positions.findById(it).orElseThrow { ResourceNotFoundException("Position", it) }
            if (position.company.id != company.id || position.staff != null || position.status != PositionStatus.OPEN) throw ConflictException("Target position is not available")
            position.staff = saved; position.status = PositionStatus.FILLED; position.isVacant = false; positions.save(position); steps += "position assigned"
        }
        if (w.createUser) {
            val accountEmail = email ?: throw BadRequestException("Email is required when creating user access")
            if (users.existsByEmailIgnoreCaseAndIsDeletedFalse(accountEmail)) throw ConflictException("A user with this email already exists")
            val username = uniqueUsername(accountEmail.substringBefore('@'))
            val token = UUID.randomUUID().toString()
            val account = User(username, accountEmail, encoder.encode(UUID.randomUUID().toString()), w.userRole ?: Role.STAFF, name, saved.id, company.id)
            account.passwordResetToken = token; account.passwordResetExpires = LocalDateTime.now(clock).plusDays(2); users.save(account); steps += "user access created"
        }
    }

    private fun releasePosition(p: Position, disposition: PositionDisposition) {
        if (disposition == PositionDisposition.CLOSE && positions.existsByReportsToPosition_IdAndIsDeletedFalse(p.id!!)) throw ConflictException("Position cannot close while other positions report to it")
        p.staff = null
        when (disposition) {
            PositionDisposition.OPEN -> { p.status = PositionStatus.OPEN; p.isVacant = true }
            PositionDisposition.ON_HOLD -> { p.status = PositionStatus.ON_HOLD; p.isVacant = false }
            PositionDisposition.CLOSE -> { p.status = PositionStatus.CLOSED; p.isVacant = false }
        }; positions.save(p)
    }
    private fun requireStaff(w: LifecycleWorkflow) = w.staff ?: throw BadRequestException("Staff is required for ${w.type.name.lowercase()} workflow")
    private fun activeStaff(id: Long, companyId: Long): Staff = staff.findById(id).orElseThrow { ResourceNotFoundException("Staff", id) }.also {
        if (it.isDeleted || it.status != EntityStatus.ACTIVE || it.company.id != companyId) throw BadRequestException("Selected staff member is not active in the company")
    }
    private fun managerChainContains(start: Staff?, id: Long): Boolean { var current=start; val seen=mutableSetOf<Long>(); while(current != null && seen.add(current.id!!)){ if(current.id==id)return true; current=current.manager }; return false }
    private fun uniqueUsername(base: String): String { var value=base.lowercase().replace(Regex("[^a-z0-9._-]"), "").ifBlank { "user" }; var n=1; while(users.existsByUsernameIgnoreCaseAndIsDeletedFalse(value)) value="$base${n++}"; return value }
    private fun snapshot(w: LifecycleWorkflow): String = mapper.writeValueAsString(mapOf("staffId" to w.staff?.id, "name" to w.staff?.name, "companyId" to w.staff?.company?.id, "departmentId" to w.staff?.department?.id, "managerId" to w.staff?.manager?.id, "title" to w.staff?.title, "status" to w.staff?.status?.name))
}

@Service
class LifecycleFailureRecorder(private val workflows: LifecycleWorkflowRepository, private val logs: LifecycleExecutionLogRepository, private val clock: Clock) {
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    fun record(id: Long, error: Throwable) { workflows.findById(id).ifPresent { w -> val safe=(error.message ?: "Execution failed").take(1000); w.status=WorkflowStatus.FAILED; w.failureReason=safe; workflows.save(w); logs.save(LifecycleExecutionLog(w, LocalDateTime.now(clock), ExecutionResult.FAILED, failedStep="execution", safeErrorMessage=safe)) } }
}
