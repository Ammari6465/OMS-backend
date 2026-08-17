package com.sunrich.oms.lifecycle

import com.sunrich.oms.common.dto.ApiResponse
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController @RequestMapping("/lifecycle-workflows")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','COMPANY_ADMIN')")
class LifecycleController(private val service:LifecycleWorkflowService) {
 @GetMapping fun list(@RequestParam(defaultValue="0")page:Int,@RequestParam(defaultValue="20")size:Int,@RequestParam(defaultValue="createdAt")sort:String,@RequestParam(defaultValue="desc")direction:String,@RequestParam(required=false)type:LifecycleType?,@RequestParam(required=false)status:WorkflowStatus?,@RequestParam(required=false)companyId:Long?,@RequestParam(required=false)staffId:Long?)=ApiResponse.ok(service.list(page,size,sort,direction,type,status,companyId,staffId))
 @GetMapping("/summary") fun summary()=ApiResponse.ok(service.summary())
 @GetMapping("/{id}") fun get(@PathVariable id:Long)=ApiResponse.ok(service.get(id))
 @PostMapping fun create(@Valid @RequestBody r:LifecycleRequest)=ApiResponse.ok(service.create(r),"Workflow created")
 @PutMapping("/{id}") fun update(@PathVariable id:Long,@RequestParam version:Long,@Valid @RequestBody r:LifecycleRequest)=ApiResponse.ok(service.update(id,r,version),"Workflow updated")
 @PostMapping("/{id}/submit") fun submit(@PathVariable id:Long,@RequestBody v:VersionRequest)=ApiResponse.ok(service.submit(id,v),"Workflow submitted")
 @PostMapping("/{id}/approve") fun approve(@PathVariable id:Long,@RequestBody v:VersionRequest)=ApiResponse.ok(service.approve(id,v),"Workflow approved")
 @PostMapping("/{id}/reject") fun reject(@PathVariable id:Long,@RequestParam version:Long,@Valid @RequestBody r:DecisionRequest)=ApiResponse.ok(service.reject(id,r,version),"Workflow rejected")
 @PostMapping("/{id}/cancel") fun cancel(@PathVariable id:Long,@RequestBody v:VersionRequest)=ApiResponse.ok(service.cancel(id,v),"Workflow cancelled")
 @PostMapping("/{id}/execute") fun execute(@PathVariable id:Long)=ApiResponse.ok(service.execute(id),"Workflow executed")
 @PatchMapping("/{id}/tasks/{taskId}") fun task(@PathVariable id:Long,@PathVariable taskId:Long,@RequestBody r:TaskUpdateRequest)=ApiResponse.ok(service.updateTask(id,taskId,r),"Task updated")
}
