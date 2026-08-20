package com.sunrich.oms.workplace.detection

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class DetectedObjectResponse(
    val id: Long,
    val floorId: Long,
    val type: DetectedObjectType,
    val name: String?,
    val code: String?,
    val polygon: List<Point>,
    val bbox: BoundingBox,
    val center: Point,
    val rotation: Int,
    val area: Double,
    val confidence: Double,
    val ocrText: String?,
    val source: DetectionSource,
    val detector: String?,
    val deskId: Long?,
    val version: Long
)

data class BoundingBox(val x: Double, val y: Double, val width: Double, val height: Double)

/** One created or edited region. A null [id] creates, a present one updates. */
data class DetectedObjectRequest(
    val id: Long? = null,
    val type: DetectedObjectType = DetectedObjectType.UNKNOWN,
    @field:Size(max = 200) val name: String? = null,
    @field:Size(max = 50) val code: String? = null,
    @field:NotBlank(message = "Polygon is required")
    @field:Size(max = 4000)
    val polygon: String? = null,
    val rotation: Int = 0,
    @field:Size(max = 1000) val ocrText: String? = null
)

/** Bulk save from the edit surface: everything absent from [objects] is removed. */
data class DetectionEditRequest(
    val objects: List<DetectedObjectRequest> = emptyList(),
    val removedIds: List<Long> = emptyList()
)

data class DetectionRunResponse(
    val floorId: Long,
    val detector: String,
    val detected: Int,
    val preserved: Int,
    val objects: List<DetectedObjectResponse>,
    val message: String
)

/** Result of turning detected desks into real Desk records. */
data class DeskPromotionResponse(val created: Int, val skipped: Int, val deskIds: List<Long>)
