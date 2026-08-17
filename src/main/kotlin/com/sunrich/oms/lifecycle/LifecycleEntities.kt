package com.sunrich.oms.lifecycle

import com.sunrich.oms.common.entity.BaseEntity
import com.sunrich.oms.common.enums.EmploymentType
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.organization.Company
import com.sunrich.oms.organization.Staff
import com.sunrich.oms.user.User
import jakarta.persistence.*
import java.time.LocalDate
import java.time.LocalDateTime

enum class LifecycleType { JOINER, MOVER, LEAVER }
enum class WorkflowStatus { DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, SCHEDULED, IN_PROGRESS, COMPLETED, FAILED, CANCELLED }
enum class PositionDisposition { OPEN, ON_HOLD, CLOSE }
enum class TaskStatus { PENDING, COMPLETED, WAIVED }
enum class ExecutionResult { SUCCEEDED, FAILED }

@Entity
@Table(name = "lifecycle_workflows", indexes = [Index(name="idx_lifecycle_company_status", columnList="company_id,status"), Index(name="idx_lifecycle_effective", columnList="effective_date,status")])
class LifecycleWorkflow(
    @Column(name="workflow_number", nullable=false, unique=true, length=40) var workflowNumber: String,
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=20) var type: LifecycleType,
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="staff_id") var staff: Staff? = null,
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="company_id", nullable=false) var company: Company,
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=30) var status: WorkflowStatus = WorkflowStatus.DRAFT,
    @Column(name="effective_date", nullable=false) var effectiveDate: LocalDate,
    @Column(length=100) var subtype: String? = null,
    @Column(length=1000) var reason: String? = null,
    @Column(length=2000) var notes: String? = null,
    @Enumerated(EnumType.STRING) @Column(name="position_disposition", length=20) var positionDisposition: PositionDisposition? = null,
    @Column(name="successor_manager_id") var successorManagerId: Long? = null,
    @Column(name="replacement_head_id") var replacementHeadId: Long? = null,
    @Column(name="responsibilities_acknowledged", nullable=false) var responsibilitiesAcknowledged: Boolean = false,
    @Column(name="target_company_id") var targetCompanyId: Long? = null,
    @Column(name="target_department_id") var targetDepartmentId: Long? = null,
    @Column(name="target_position_id") var targetPositionId: Long? = null,
    @Column(name="target_manager_id") var targetManagerId: Long? = null,
    @Column(name="target_title", length=200) var targetTitle: String? = null,
    @Column(name="joiner_name", length=200) var joinerName: String? = null,
    @Column(name="joiner_employee_code", length=100) var joinerEmployeeCode: String? = null,
    @Column(name="joiner_email", length=200) var joinerEmail: String? = null,
    @Column(name="joiner_phone", length=50) var joinerPhone: String? = null,
    @Enumerated(EnumType.STRING) @Column(name="joiner_employment_type", length=30) var joinerEmploymentType: EmploymentType? = null,
    @Column(name="create_user", nullable=false) var createUser: Boolean = false,
    @Enumerated(EnumType.STRING) @Column(name="user_role", length=30) var userRole: Role? = null,
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="requested_by") var requestedBy: User? = null
) : BaseEntity() {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="workflow_id") var id: Long? = null
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="submitted_by") var submittedBy: User? = null
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="decision_by") var decisionBy: User? = null
    @Column(name="submitted_at") var submittedAt: LocalDateTime? = null
    @Column(name="decision_at") var decisionAt: LocalDateTime? = null
    @Column(name="completed_at") var completedAt: LocalDateTime? = null
    @Column(name="rejection_reason", length=1000) var rejectionReason: String? = null
    @Column(name="failure_reason", length=1000) var failureReason: String? = null
    @Lob @Column(name="before_snapshot") var beforeSnapshot: String? = null
    @Lob @Column(name="proposed_snapshot") var proposedSnapshot: String? = null
}

@Entity @Table(name="lifecycle_tasks", indexes=[Index(name="idx_lifecycle_tasks_workflow", columnList="workflow_id,status")])
class LifecycleTask(
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="workflow_id", nullable=false) var workflow: LifecycleWorkflow,
    @Column(nullable=false, length=200) var title: String,
    @Column(length=1000) var description: String? = null,
    @Column(length=80) var category: String? = null,
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="assigned_user_id") var assignedUser: User? = null,
    @Column(name="due_date") var dueDate: LocalDate? = null,
    @Column(nullable=false) var required: Boolean = true,
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=20) var status: TaskStatus = TaskStatus.PENDING
) : BaseEntity() {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="task_id") var id: Long? = null
    @Column(name="completion_notes", length=1000) var completionNotes: String? = null
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="completed_by") var completedBy: User? = null
    @Column(name="completed_at") var completedAt: LocalDateTime? = null
}

@Entity @Table(name="lifecycle_execution_logs", indexes=[Index(name="idx_lifecycle_execution_workflow", columnList="workflow_id,attempted_at")])
class LifecycleExecutionLog(
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="workflow_id", nullable=false) var workflow: LifecycleWorkflow,
    @Column(name="attempted_at", nullable=false) var attemptedAt: LocalDateTime,
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=20) var result: ExecutionResult,
    @Column(name="completed_steps", length=2000) var completedSteps: String? = null,
    @Column(name="failed_step", length=200) var failedStep: String? = null,
    @Column(name="safe_error_message", length=1000) var safeErrorMessage: String? = null
) : BaseEntity() { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="execution_log_id") var id: Long? = null }
