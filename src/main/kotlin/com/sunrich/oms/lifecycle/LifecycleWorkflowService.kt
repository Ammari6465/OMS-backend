package com.sunrich.oms.lifecycle

import com.sunrich.oms.common.dto.PageResponse
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.common.enums.AuditAction
import com.sunrich.oms.exception.*
import com.sunrich.oms.organization.*
import com.sunrich.oms.security.SecurityUtils
import com.sunrich.oms.user.*
import com.sunrich.oms.systemdata.AuditTrailService
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.*
import org.springframework.data.jpa.domain.Specification
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.*

@Service
class LifecycleWorkflowService(
    private val workflows: LifecycleWorkflowRepository, private val tasks: LifecycleTaskRepository,
    private val logs: LifecycleExecutionLogRepository, private val staff: StaffRepository, private val companies: CompanyRepository,
    private val users: UserRepository, private val executor: LifecycleExecutionService, private val failureRecorder: LifecycleFailureRecorder, private val audit: AuditTrailService,
    private val clock: Clock, @Value("\${oms.lifecycle.separate-approver-required:true}") private val separateApprover: Boolean
) {
    fun list(page:Int,size:Int,sort:String,direction:String,type:LifecycleType?,status:WorkflowStatus?,companyId:Long?,staffId:Long?): PageResponse<LifecycleResponse> {
        val principal=SecurityUtils.currentPrincipal(); val effectiveCompany=if(principal.role==Role.SUPER_ADMIN) companyId else principal.companyId
        val allowed=setOf("workflowNumber","type","status","effectiveDate","createdAt","updatedAt"); val field=sort.takeIf{it in allowed}?:"createdAt"
        val pageable=PageRequest.of(page.coerceAtLeast(0),size.coerceIn(1,100),Sort.by(if(direction.equals("asc",true)) Sort.Direction.ASC else Sort.Direction.DESC,field))
        val spec=Specification<LifecycleWorkflow>{root,_,cb ->
            val p=mutableListOf(cb.isFalse(root.get("isDeleted"))); type?.let{p+=cb.equal(root.get<LifecycleType>("type"),it)}; status?.let{p+=cb.equal(root.get<WorkflowStatus>("status"),it)}
            effectiveCompany?.let{p+=cb.equal(root.get<Company>("company").get<Long>("id"),it)}; staffId?.let{p+=cb.equal(root.get<Staff>("staff").get<Long>("id"),it)}; cb.and(*p.toTypedArray()) }
        return PageResponse.from(workflows.findAll(spec,pageable),::response)
    }
    fun get(id:Long):LifecycleResponse { val w=owned(id); return response(w,true) }
    fun summary():LifecycleSummary {
        val principal=SecurityUtils.currentPrincipal(); val companyId=if(principal.role==Role.SUPER_ADMIN)null else principal.companyId
        fun count(status:WorkflowStatus)=companyId?.let{workflows.countByCompany_IdAndIsDeletedFalseAndStatus(it,status)}?:workflows.countByIsDeletedFalseAndStatus(status)
        return LifecycleSummary(count(WorkflowStatus.DRAFT),count(WorkflowStatus.PENDING_APPROVAL),count(WorkflowStatus.SCHEDULED),count(WorkflowStatus.FAILED))
    }

    @Transactional fun create(r:LifecycleRequest):LifecycleResponse {
        validate(r); val actor=currentUser(); val company=companies.findById(r.companyId).orElseThrow{ResourceNotFoundException("Company",r.companyId)}; enforceCompany(company.id!!)
        val person=r.staffId?.let{staff.findById(it).orElseThrow{ResourceNotFoundException("Staff",it)}}
        if(r.type!=LifecycleType.JOINER && person==null) throw BadRequestException("Staff is required")
        if(person!=null && person.company.id!=company.id) throw BadRequestException("Staff does not belong to company")
        val w=LifecycleWorkflow(number(r.type),r.type,person,company,WorkflowStatus.DRAFT,r.effectiveDate,r.subtype?.trim(),r.reason?.trim(),r.notes?.trim(),r.positionDisposition,r.successorManagerId,r.replacementHeadId,r.responsibilitiesAcknowledged,r.targetCompanyId,r.targetDepartmentId,r.targetPositionId,r.targetManagerId,r.targetTitle?.trim(),r.joinerName?.trim(),r.joinerEmployeeCode?.trim(),r.joinerEmail?.trim(),r.joinerPhone?.trim(),r.joinerEmploymentType,r.createUser,r.userRole,actor)
        val saved=workflows.saveAndFlush(w); defaultTasks(saved).forEach(tasks::save); record(saved,AuditAction.CREATE,"DRAFT"); return response(saved,true)
    }
    @Transactional fun update(id:Long,r:LifecycleRequest,version:Long):LifecycleResponse { val old=owned(id); requireVersion(old,version); if(old.status!=WorkflowStatus.DRAFT)throw ConflictException("Only draft workflows can be edited"); if(old.type!=r.type)throw BadRequestException("Workflow type cannot be changed"); validate(r)
        old.effectiveDate=r.effectiveDate;old.subtype=r.subtype;old.reason=r.reason;old.notes=r.notes;old.positionDisposition=r.positionDisposition;old.successorManagerId=r.successorManagerId;old.replacementHeadId=r.replacementHeadId;old.responsibilitiesAcknowledged=r.responsibilitiesAcknowledged;old.targetCompanyId=r.targetCompanyId;old.targetDepartmentId=r.targetDepartmentId;old.targetPositionId=r.targetPositionId;old.targetManagerId=r.targetManagerId;old.targetTitle=r.targetTitle;old.joinerName=r.joinerName;old.joinerEmployeeCode=r.joinerEmployeeCode;old.joinerEmail=r.joinerEmail;old.joinerPhone=r.joinerPhone;old.joinerEmploymentType=r.joinerEmploymentType;old.createUser=r.createUser;old.userRole=r.userRole; return response(workflows.saveAndFlush(old),true) }
    @Transactional fun submit(id:Long,v:VersionRequest):LifecycleResponse { val w=owned(id);requireVersion(w,v.version);if(w.status!=WorkflowStatus.DRAFT)throw ConflictException("Only a draft can be submitted"); validateForSubmit(w); w.status=WorkflowStatus.PENDING_APPROVAL;w.submittedBy=currentUser();w.submittedAt=LocalDateTime.now(clock);val saved=workflows.saveAndFlush(w);record(saved,AuditAction.WORKFLOW_SUBMIT,"PENDING_APPROVAL");return response(saved,true) }
    @Transactional fun approve(id:Long,v:VersionRequest):LifecycleResponse { val w=owned(id);requireVersion(w,v.version);if(w.status!=WorkflowStatus.PENDING_APPROVAL)throw ConflictException("Only a pending workflow can be approved");val actor=currentUser();if(separateApprover&&w.submittedBy?.id==actor.id)throw ForbiddenException("The submitter cannot approve this workflow");w.decisionBy=actor;w.decisionAt=LocalDateTime.now(clock);w.status=if(w.effectiveDate>LocalDate.now(clock))WorkflowStatus.SCHEDULED else WorkflowStatus.APPROVED;val saved=workflows.saveAndFlush(w);record(saved,AuditAction.WORKFLOW_APPROVE,saved.status.name);return response(saved,true) }
    @Transactional fun reject(id:Long,r:DecisionRequest,v:Long):LifecycleResponse { val w=owned(id);requireVersion(w,v);if(w.status!=WorkflowStatus.PENDING_APPROVAL)throw ConflictException("Only a pending workflow can be rejected");w.status=WorkflowStatus.REJECTED;w.rejectionReason=r.reason.trim();w.decisionBy=currentUser();w.decisionAt=LocalDateTime.now(clock);val saved=workflows.saveAndFlush(w);record(saved,AuditAction.WORKFLOW_REJECT,"REJECTED: ${r.reason.trim()}");return response(saved,true) }
    @Transactional fun cancel(id:Long,v:VersionRequest):LifecycleResponse { val w=owned(id);requireVersion(w,v.version);if(w.status in setOf(WorkflowStatus.IN_PROGRESS,WorkflowStatus.COMPLETED,WorkflowStatus.CANCELLED))throw ConflictException("This workflow cannot be cancelled");w.status=WorkflowStatus.CANCELLED;val saved=workflows.saveAndFlush(w);record(saved,AuditAction.WORKFLOW_CANCEL,"CANCELLED");return response(saved,true) }
    fun execute(id:Long):LifecycleResponse { owned(id); return try { response(executor.execute(id),true) } catch(e:RuntimeException){ failureRecorder.record(id,e); throw e } }
    @Transactional fun updateTask(workflowId:Long,taskId:Long,r:TaskUpdateRequest):LifecycleTaskResponse { owned(workflowId);val task=tasks.findById(taskId).orElseThrow{ResourceNotFoundException("Lifecycle task",taskId)};if(task.workflow.id!=workflowId)throw ResourceNotFoundException("Lifecycle task",taskId);task.status=r.status;task.completionNotes=r.notes?.trim();task.completedBy=if(r.status==TaskStatus.COMPLETED)currentUser() else null;task.completedAt=if(r.status==TaskStatus.COMPLETED)LocalDateTime.now(clock) else null;return taskResponse(tasks.save(task)) }

    @Scheduled(fixedDelayString="\${oms.lifecycle.scheduler-delay-ms:60000}")
    fun runDue() { workflows.findAllByStatusInAndEffectiveDateLessThanEqualAndIsDeletedFalse(setOf(WorkflowStatus.APPROVED,WorkflowStatus.SCHEDULED),LocalDate.now(clock)).forEach { try{executor.execute(it.id!!)}catch(e:RuntimeException){failureRecorder.record(it.id!!,e)} } }
    private fun owned(id:Long):LifecycleWorkflow=workflows.findById(id).filter{!it.isDeleted}.orElseThrow{ResourceNotFoundException("Lifecycle workflow",id)}.also{enforceCompany(it.company.id!!)}
    private fun enforceCompany(companyId:Long){val p=SecurityUtils.currentPrincipal();if(p.role!=Role.SUPER_ADMIN&&p.companyId!=companyId)throw ForbiddenException()}
    private fun currentUser()=users.findById(SecurityUtils.currentUserId()).orElseThrow{ResourceNotFoundException("Authenticated user")}
    private fun requireVersion(w:LifecycleWorkflow,v:Long){if(w.version!=v)throw ConflictException("Workflow changed since it was opened. Refresh and try again")}
    private fun validate(r:LifecycleRequest){if(r.type==LifecycleType.JOINER&&r.joinerName.isNullOrBlank())throw BadRequestException("Joiner name is required");if(r.type==LifecycleType.LEAVER&&r.positionDisposition==null)throw BadRequestException("Position disposition is required for a leaver")}
    private fun validateForSubmit(w:LifecycleWorkflow){if(w.type==LifecycleType.LEAVER&&w.staff==null)throw BadRequestException("Staff is required");if(w.type==LifecycleType.MOVER&&(w.targetDepartmentId==null&&w.targetPositionId==null&&w.targetTitle.isNullOrBlank()))throw BadRequestException("Provide the mover's target organisation, position, or title")}
    private fun number(t:LifecycleType)="${t.name.take(1)}-${LocalDate.now(clock).year}-${System.currentTimeMillis().toString().takeLast(8)}"
    private fun record(w:LifecycleWorkflow,action:AuditAction,after:String){audit.record(currentUser(),action,"LifecycleWorkflow",w.id,w.company.id,fieldName="${w.type.name} workflow",after="workflow=${w.workflowNumber},status=$after")}
    private fun defaultTasks(w:LifecycleWorkflow)=when(w.type){LifecycleType.LEAVER->listOf("Recover company assets" to "OFFBOARDING","Complete knowledge handover" to "HANDOVER","Confirm access revocation" to "ACCESS");LifecycleType.MOVER->listOf("Confirm responsibilities handover" to "HANDOVER","Update access permissions" to "ACCESS");LifecycleType.JOINER->listOf("Prepare workplace" to "WORKPLACE","Complete induction" to "ONBOARDING","Confirm access setup" to "ACCESS")}.map{LifecycleTask(w,it.first,category=it.second,dueDate=w.effectiveDate)}
    private fun response(w:LifecycleWorkflow,details:Boolean=false)=LifecycleResponse(w.id!!,w.version,w.workflowNumber,w.type,w.status,w.staff?.id,w.staff?.name,w.company.id!!,w.company.name,w.effectiveDate,w.subtype,w.reason,w.notes,w.positionDisposition,w.targetCompanyId,w.targetDepartmentId,w.targetPositionId,w.targetManagerId,w.targetTitle,w.joinerName,w.joinerEmail,w.rejectionReason,w.failureReason,w.requestedBy?.fullName,w.submittedBy?.fullName,w.decisionBy?.fullName,w.createdAt,w.submittedAt,w.decisionAt,w.completedAt,if(details)tasks.findAllByWorkflow_IdAndIsDeletedFalseOrderByIdAsc(w.id!!).map(::taskResponse)else emptyList(),if(details)logs.findAllByWorkflow_IdAndIsDeletedFalseOrderByAttemptedAtDesc(w.id!!).map{ExecutionLogResponse(it.id!!,it.attemptedAt,it.result,it.completedSteps,it.failedStep,it.safeErrorMessage)}else emptyList())
    private fun taskResponse(t:LifecycleTask)=LifecycleTaskResponse(t.id!!,t.title,t.description,t.category,t.dueDate,t.required,t.status,t.completionNotes)
}
