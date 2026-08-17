package com.sunrich.oms.lifecycle

import com.sunrich.oms.common.enums.EmploymentType
import com.sunrich.oms.common.enums.Role
import jakarta.validation.constraints.*
import java.time.LocalDate
import java.time.LocalDateTime

data class LifecycleRequest(
    val type: LifecycleType, val staffId: Long? = null, val companyId: Long,
    @field:FutureOrPresent val effectiveDate: LocalDate, val subtype: String? = null,
    @field:Size(max=1000) val reason: String? = null, @field:Size(max=2000) val notes: String? = null,
    val positionDisposition: PositionDisposition? = null, val successorManagerId: Long? = null,
    val replacementHeadId: Long? = null, val responsibilitiesAcknowledged: Boolean = false,
    val targetCompanyId: Long? = null, val targetDepartmentId: Long? = null, val targetPositionId: Long? = null,
    val targetManagerId: Long? = null, val targetTitle: String? = null,
    val joinerName: String? = null, val joinerEmployeeCode: String? = null, val joinerEmail: String? = null,
    val joinerPhone: String? = null, val joinerEmploymentType: EmploymentType? = null,
    val createUser: Boolean = false, val userRole: Role? = null
)
data class DecisionRequest(@field:NotBlank @field:Size(max=1000) val reason: String)
data class VersionRequest(val version: Long)
data class TaskUpdateRequest(val status: TaskStatus, @field:Size(max=1000) val notes: String? = null)
data class LifecycleTaskResponse(val id: Long, val title: String, val description: String?, val category: String?, val dueDate: LocalDate?, val required: Boolean, val status: TaskStatus, val completionNotes: String?)
data class ExecutionLogResponse(val id: Long, val attemptedAt: LocalDateTime, val result: ExecutionResult, val completedSteps: String?, val failedStep: String?, val safeErrorMessage: String?)
data class LifecycleResponse(
    val id: Long, val version: Long, val workflowNumber: String, val type: LifecycleType, val status: WorkflowStatus,
    val staffId: Long?, val staffName: String?, val companyId: Long, val companyName: String, val effectiveDate: LocalDate,
    val subtype: String?, val reason: String?, val notes: String?, val positionDisposition: PositionDisposition?,
    val targetCompanyId: Long?, val targetDepartmentId: Long?, val targetPositionId: Long?, val targetManagerId: Long?, val targetTitle: String?,
    val joinerName: String?, val joinerEmail: String?, val rejectionReason: String?, val failureReason: String?,
    val requestedByName: String?, val submittedByName: String?, val decisionByName: String?,
    val createdAt: LocalDateTime?, val submittedAt: LocalDateTime?, val decisionAt: LocalDateTime?, val completedAt: LocalDateTime?,
    val tasks: List<LifecycleTaskResponse> = emptyList(), val executionHistory: List<ExecutionLogResponse> = emptyList()
)
data class LifecycleSummary(val draft: Long, val pendingApproval: Long, val scheduled: Long, val failed: Long)
