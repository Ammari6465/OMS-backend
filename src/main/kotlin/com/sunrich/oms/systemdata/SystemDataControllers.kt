package com.sunrich.oms.systemdata

import com.sunrich.oms.common.dto.ApiResponse
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(value = ["/audit-logs", "/records/audit"])
class AuditLogController(private val service: SystemDataService) {
    @GetMapping fun list(@RequestParam(defaultValue = "false") includeDeleted: Boolean) =
        ApiResponse.ok(service.listAudit())
    @PostMapping fun create(@RequestBody request: AuditRequest) =
        ApiResponse.ok(service.createAudit(request), "Audit entry created")
}

@RestController
@RequestMapping(value = ["/notifications", "/records/notifications"])
class NotificationController(private val service: SystemDataService) {
    @GetMapping fun list(@RequestParam(defaultValue = "false") includeDeleted: Boolean) =
        ApiResponse.ok(service.listNotifications())
    @PostMapping fun create(@RequestBody request: NotificationRequest) =
        ApiResponse.ok(service.createNotification(request), "Notification created")
    @PutMapping("/{id}") fun update(@PathVariable id: Long, @RequestBody request: NotificationRequest) =
        ApiResponse.ok(service.updateNotification(id, request), "Notification updated")
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
