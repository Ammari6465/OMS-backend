package com.sunrich.oms.workplace.detection

import com.sunrich.oms.common.dto.ApiResponse
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

private const val MANAGE = "hasAnyRole('SUPER_ADMIN','COMPANY_ADMIN')"

/**
 * Floor plan recognition. Reading detected geometry follows the same visibility
 * rules as the floor itself; changing it requires manage rights on the owning
 * company.
 */
@RestController
@RequestMapping("/workplaces")
class DetectionStatusController(private val service: FloorPlanDetectionService) {

    /** Floor-independent: what recognition can read on this deployment. */
    @GetMapping("/detection/status")
    fun status() = ApiResponse.ok(service.status())
}

@RestController
@RequestMapping("/workplaces/floors/{floorId}")
class DetectionController(private val service: FloorPlanDetectionService) {

    @GetMapping("/objects")
    fun list(@PathVariable floorId: Long) = ApiResponse.ok(service.list(floorId))

    @PostMapping("/detect")
    @PreAuthorize(MANAGE)
    fun detect(@PathVariable floorId: Long): ApiResponse<DetectionRunResponse> {
        val result = service.detect(floorId)
        return ApiResponse.ok(result, result.message)
    }

    @PutMapping("/objects")
    @PreAuthorize(MANAGE)
    fun save(@PathVariable floorId: Long, @Valid @RequestBody request: DetectionEditRequest) =
        ApiResponse.ok(service.applyEdits(floorId, request), "Floor plan objects saved")

    @PostMapping("/objects/promote-desks")
    @PreAuthorize(MANAGE)
    fun promote(@PathVariable floorId: Long): ApiResponse<DeskPromotionResponse> {
        val result = service.promoteDesks(floorId)
        return ApiResponse.ok(result, "Created ${result.created} desks from detected workstations")
    }
}
