package com.sunrich.oms.systemdata

import com.sunrich.oms.common.dto.ApiResponse
import com.sunrich.oms.common.enums.AuditAction
import com.sunrich.oms.common.enums.Role
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(value = ["/audit-logs", "/records/audit"])
class AuditLogController(private val service: SystemDataService) {
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','COMPANY_ADMIN')")
    fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "timestamp") sort: String, @RequestParam(defaultValue = "desc") direction: String,
        @RequestParam(required = false) search: String?, @RequestParam(required = false) action: AuditAction?,
        @RequestParam(required = false) module: String?, @RequestParam(required = false) userId: Long?,
        @RequestParam(required = false) role: Role?, @RequestParam(required = false) companyId: Long?,
        @RequestParam(required = false) result: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: java.time.LocalDateTime?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: java.time.LocalDateTime?) =
        ApiResponse.ok(service.listAudit(page,size,sort,direction,search,action,module,userId,role,companyId,result,from,to))

    @GetMapping("/summary") @PreAuthorize("hasAnyRole('SUPER_ADMIN','COMPANY_ADMIN')")
    fun summary(@RequestParam(required = false) companyId: Long?) = ApiResponse.ok(service.auditSummary(companyId))

    @GetMapping("/{id}") @PreAuthorize("hasAnyRole('SUPER_ADMIN','COMPANY_ADMIN')")
    fun get(@PathVariable id: Long) = ApiResponse.ok(service.getAudit(id))

    @GetMapping("/export") @PreAuthorize("hasAnyRole('SUPER_ADMIN','COMPANY_ADMIN')")
    fun export(@RequestParam(required = false) search: String?, @RequestParam(required = false) action: AuditAction?,
        @RequestParam(required = false) module: String?, @RequestParam(required = false) userId: Long?,
        @RequestParam(required = false) role: Role?, @RequestParam(required = false) companyId: Long?,
        @RequestParam(required = false) result: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: java.time.LocalDateTime?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: java.time.LocalDateTime?): ResponseEntity<String> =
        ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=oms-audit-log.csv")
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .body(service.exportAudit(search,action,module,userId,role,companyId,result,from,to))
}

@RestController
@RequestMapping(value = ["/notifications", "/records/notifications"])
class NotificationController(private val service: SystemDataService, private val realtime: NotificationRealtimePublisher) {
    @GetMapping fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) search: String?, @RequestParam(required = false) type: com.sunrich.oms.common.enums.NotificationType?,
        @RequestParam(required = false) category: String?, @RequestParam(required = false) priority: String?,
        @RequestParam(required = false) read: Boolean?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: java.time.LocalDateTime?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: java.time.LocalDateTime?) =
        ApiResponse.ok(service.listNotifications(page,size,search,type,category,priority,read,from,to))
    @GetMapping("/summary") fun summary() = ApiResponse.ok(service.notificationSummary())
    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE]) fun stream() = realtime.subscribe(com.sunrich.oms.security.SecurityUtils.currentUserId())
    @GetMapping("/{id}") fun get(@PathVariable id: Long) = ApiResponse.ok(service.getNotification(id))
    @PatchMapping("/{id}/read") fun update(@PathVariable id: Long, @RequestBody request: NotificationRequest) =
        ApiResponse.ok(service.updateNotification(id, request), "Notification updated")
    @PatchMapping("/read-all") fun markAllRead() = ApiResponse.ok(service.markAllNotificationsRead(), "All notifications marked read")
    @DeleteMapping("/{id}") fun delete(@PathVariable id: Long): ApiResponse<Unit> {
        service.deleteNotification(id); return ApiResponse.ok("Notification removed")
    }
}

@RestController
@RequestMapping(value = ["/settings", "/records/settings"])
class SystemSettingController(private val service: SystemDataService) {
    @GetMapping fun list(@RequestParam(defaultValue = "false") includeDeleted: Boolean) =
        ApiResponse.ok(service.listSettings(includeDeleted))
    @PostMapping @PreAuthorize("hasRole('SUPER_ADMIN')") fun create(@RequestBody request: SettingRequest) =
        ApiResponse.ok(service.createSetting(request), "Setting created")
    @PutMapping("/{id}") @PreAuthorize("hasRole('SUPER_ADMIN')") fun update(@PathVariable id: Long, @RequestBody request: SettingRequest) =
        ApiResponse.ok(service.updateSetting(id, request), "Setting updated")
}
