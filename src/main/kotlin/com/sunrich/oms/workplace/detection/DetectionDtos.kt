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
    val zoneId: Long?,
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

/** Result of turning detected rooms into real Zone records. */
data class RoomPromotionResponse(val created: Int, val skipped: Int, val zoneIds: List<Long>)

/** Result of deliberately emptying every editable layer while retaining the plan image. */
data class MapContentsClearResponse(
    val desks: Int,
    val zones: Int,
    val assignments: Int,
    val detectedObjects: Int
)

/**
 * What the recognition pipeline can currently do, so the UI can say so before
 * a scan rather than after one fails.
 *
 * Without this the only way to discover that vision detection is unconfigured
 * is to upload a raster plan and read the error, which sends people round a
 * loop between two formats neither of which the server can read.
 */
data class DetectionStatusResponse(
    val detector: String,
    val available: Boolean,
    /** True once a vision provider and key are set; raster plans need this. */
    val visionConfigured: Boolean,
    val readableMediaTypes: List<String>
)
