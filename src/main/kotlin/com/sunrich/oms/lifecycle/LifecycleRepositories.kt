package com.sunrich.oms.lifecycle

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.*
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface LifecycleWorkflowRepository : JpaRepository<LifecycleWorkflow, Long>, JpaSpecificationExecutor<LifecycleWorkflow> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from LifecycleWorkflow w where w.id=:id and w.isDeleted=false")
    fun findLocked(@Param("id") id: Long): LifecycleWorkflow?
    fun findAllByStatusInAndEffectiveDateLessThanEqualAndIsDeletedFalse(statuses: Collection<WorkflowStatus>, date: LocalDate): List<LifecycleWorkflow>
    fun countByIsDeletedFalseAndStatus(status: WorkflowStatus): Long
    fun countByCompany_IdAndIsDeletedFalseAndStatus(companyId: Long, status: WorkflowStatus): Long
}
interface LifecycleTaskRepository : JpaRepository<LifecycleTask, Long> { fun findAllByWorkflow_IdAndIsDeletedFalseOrderByIdAsc(id: Long): List<LifecycleTask> }
interface LifecycleExecutionLogRepository : JpaRepository<LifecycleExecutionLog, Long> { fun findAllByWorkflow_IdAndIsDeletedFalseOrderByAttemptedAtDesc(id: Long): List<LifecycleExecutionLog> }
