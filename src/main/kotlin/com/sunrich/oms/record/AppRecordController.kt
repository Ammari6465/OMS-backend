package com.sunrich.oms.record

import com.sunrich.oms.common.dto.ApiResponse
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/records")
class AppRecordController(private val service: AppRecordService) {

    @GetMapping("/{collection}")
    fun list(
        @PathVariable collection: String,
        @RequestParam(defaultValue = "false") includeDeleted: Boolean
    ): ApiResponse<List<Map<String, Any?>>> = ApiResponse.ok(service.list(collection, includeDeleted))

    @PostMapping("/{collection}")
    @PreAuthorize("@recordAuth.canCreate(#collection)")
    fun create(
        @PathVariable collection: String,
        @RequestBody payload: Map<String, Any?>
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(service.create(collection, payload), "Record created")

    @PutMapping("/{collection}/{id}")
    @PreAuthorize("@recordAuth.canModify(#collection)")
    fun update(
        @PathVariable collection: String,
        @PathVariable id: Long,
        @RequestBody payload: Map<String, Any?>
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(service.update(collection, id, payload), "Record updated")

    @DeleteMapping("/{collection}/{id}")
    @PreAuthorize("@recordAuth.canModify(#collection)")
    fun delete(@PathVariable collection: String, @PathVariable id: Long): ApiResponse<Unit> {
        service.delete(collection, id)
        return ApiResponse.ok("Record archived")
    }

    @PatchMapping("/{collection}/{id}/restore")
    @PreAuthorize("@recordAuth.canModify(#collection)")
    fun restore(
        @PathVariable collection: String,
        @PathVariable id: Long
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(service.restore(collection, id), "Record restored")
}
