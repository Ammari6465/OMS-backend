package com.sunrich.oms.workplace.detection

import com.sunrich.oms.common.enums.AuditAction
import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.exception.ResourceNotFoundException
import com.sunrich.oms.workplace.DeskRequest
import com.sunrich.oms.workplace.WorkplaceService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Runs the recognition pipeline and owns everything the detector deliberately
 * does not: persistence, spatial numbering, protecting human corrections, and
 * promotion into the workplace hierarchy.
 *
 * The uploaded plan itself is never touched. Detected geometry lives in its own
 * table, so re-running detection, editing regions, or deleting every object all
 * leave the original image exactly as uploaded.
 */
@Service
class FloorPlanDetectionService(
    private val objects: DetectedObjectRepository,
    private val workplace: WorkplaceService,
    private val detector: FloorPlanDetector
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun list(floorId: Long): List<DetectedObjectResponse> {
        workplace.requireReadableFloor(floorId)
        return objects.findAllByFloor_IdAndIsDeletedFalseOrderByIdAsc(floorId).map(::response)
    }

    /**
     * Re-runs detection for a floor. Automatic objects from a previous run are
     * replaced; anything a person drew or corrected is preserved, because the
     * point of the edit surface is that corrections stick.
     */
    @Transactional
    fun detect(floorId: Long): DetectionRunResponse {
        val floor = workplace.requireManageableFloor(floorId)
        if (!detector.available) {
            throw BadRequestException(
                "Floor plan recognition is not configured. Set oms.workplace.detection.provider and an API key."
            )
        }
        val (bytes, mediaType, originalName) = workplace.plan(floorId)
        val existing = objects.findAllByFloor_IdAndIsDeletedFalseOrderByIdAsc(floorId)
        val protected = existing.filter { it.isProtected }
        existing.filterNot { it.isProtected }.forEach { it.markDeleted() }
        objects.saveAll(existing)

        val candidates = detector.detect(PlanImage(bytes, mediaType, originalName, floor.planWidth, floor.planHeight))
        val saved = objects.saveAll(numbered(candidates).map { (candidate, code) ->
            DetectedObject(
                floor = floor,
                type = candidate.type,
                name = candidate.name,
                code = code,
                polygon = candidate.polygon.serialise(),
                rotation = candidate.rotation,
                ocrText = candidate.ocrText,
                confidence = candidate.confidence,
                source = DetectionSource.AUTO,
                detector = detector.name
            ).apply { applyGeometry(this, candidate.polygon) }
        })
        workplace.recordFloorAudit(floorId, AuditAction.UPDATE, "detected=${saved.size},detector=${detector.name}")
        log.info("Detected {} objects on floor {} using {}", saved.size, floorId, detector.name)
        return DetectionRunResponse(
            floorId = floorId,
            detector = detector.name,
            detected = saved.size,
            preserved = protected.size,
            objects = (protected + saved).sortedBy { it.id }.map(::response),
            message = if (saved.isEmpty()) {
                "No objects were recognised on this plan. You can draw them manually."
            } else {
                "Recognised ${saved.size} objects. ${protected.size} manual corrections were kept."
            }
        )
    }

    /** Bulk save from the edit surface. Every write marks the object human-owned. */
    @Transactional
    fun applyEdits(floorId: Long, request: DetectionEditRequest): List<DetectedObjectResponse> {
        val floor = workplace.requireManageableFloor(floorId)
        val byId = objects.findAllByFloor_IdAndIsDeletedFalseOrderByIdAsc(floorId).associateBy { it.id }
        request.removedIds.distinct().forEach { id ->
            byId[id]?.let { objects.save(it.apply { markDeleted() }) }
                ?: throw ResourceNotFoundException("Detected object", id)
        }
        val saved = request.objects.map { edit ->
            val polygon = Polygon.parse(edit.polygon ?: throw BadRequestException("Polygon is required"))
            val entity = edit.id?.let { byId[it] ?: throw ResourceNotFoundException("Detected object", it) }
                ?: DetectedObject(floor = floor, type = edit.type, polygon = polygon.serialise())
            entity.type = edit.type
            entity.name = edit.name?.trim()?.takeIf { it.isNotEmpty() }
            entity.code = edit.code?.trim()?.takeIf { it.isNotEmpty() }
            entity.polygon = polygon.serialise()
            entity.rotation = edit.rotation.mod(360)
            entity.ocrText = edit.ocrText?.trim()?.takeIf { it.isNotEmpty() }
            entity.confidence = 1.0
            // A detected object a person has touched becomes EDITED; one they
            // drew themselves is MANUAL. Either way a re-run must not remove it.
            entity.source = if (edit.id == null) DetectionSource.MANUAL else DetectionSource.EDITED
            applyGeometry(entity, polygon)
            objects.save(entity)
        }
        workplace.recordFloorAudit(
            floorId, AuditAction.UPDATE, "edited=${saved.size},removed=${request.removedIds.size}"
        )
        return objects.findAllByFloor_IdAndIsDeletedFalseOrderByIdAsc(floorId).map(::response)
    }

    /**
     * Turns detected desks into real Desk records so they take part in
     * assignments, search and occupancy. Detections already promoted are
     * skipped, so running this twice does not duplicate the floor.
     */
    @Transactional
    fun promoteDesks(floorId: Long): DeskPromotionResponse {
        workplace.requireManageableFloor(floorId)
        val candidates = objects.findAllByFloor_IdAndIsDeletedFalseOrderByIdAsc(floorId)
            .filter { it.type == DetectedObjectType.DESK && it.deskId == null }
        val created = mutableListOf<Long>()
        var skipped = 0
        candidates.forEach { detected ->
            val code = detected.code ?: detected.name ?: "D-${detected.id}"
            try {
                val desk = workplace.createDesk(
                    DeskRequest(
                        floorId = floorId,
                        code = code,
                        displayName = detected.name,
                        x = scale(detected.bboxX), y = scale(detected.bboxY),
                        width = scale(detected.bboxWidth).coerceAtLeast(MIN_DESK_SIZE),
                        height = scale(detected.bboxHeight).coerceAtLeast(MIN_DESK_SIZE),
                        rotation = detected.rotation
                    )
                )
                detected.deskId = desk.id
                objects.save(detected)
                created += desk.id
            } catch (ex: Exception) {
                // A clashing desk code or an out-of-bounds region should not
                // abandon the rest of the floor.
                log.info("Skipped promoting detected object {}: {}", detected.id, ex.message)
                skipped++
            }
        }
        return DeskPromotionResponse(created.size, skipped, created)
    }

    /**
     * Assigns spatial codes: desks are grouped into rows by vertical position
     * and lettered top to bottom, then numbered left to right, so A01 sits
     * beside A02 on the plan rather than wherever the detector happened to
     * emit it. Rooms receive type-prefixed sequential codes (C1, CR1, MR1…)
     * so the overlay labels are meaningful before the user names them.
     */
    private fun numbered(candidates: List<DetectionCandidate>): List<Pair<DetectionCandidate, String?>> {
        val codes = HashMap<DetectionCandidate, String>()

        // ---- Desk row-assignment ----
        val desks = candidates.filter { it.type == DetectedObjectType.DESK }
        val rows = mutableListOf<MutableList<DetectionCandidate>>()
        desks.sortedBy { it.polygon.center.y }.forEach { desk ->
            val row = rows.lastOrNull()
            val sameRow = row != null && kotlin.math.abs(row.first().polygon.center.y - desk.polygon.center.y) <= ROW_TOLERANCE
            if (sameRow) row!!.add(desk) else rows.add(mutableListOf(desk))
        }
        rows.forEachIndexed { index, row ->
            val letter = rowLetter(index)
            row.sortedBy { it.polygon.center.x }.forEachIndexed { seat, desk ->
                codes[desk] = "%s%02d".format(letter, seat + 1)
            }
        }

        // ---- Room code generation ----
        // Each room type gets a prefix and a sequential number sorted by
        // position (left-to-right, top-to-bottom) for consistency.
        val roomPrefixes = mapOf(
            DetectedObjectType.CABIN to "C",
            DetectedObjectType.CONFERENCE_ROOM to "CR",
            DetectedObjectType.MEETING_ROOM to "MR",
            DetectedObjectType.RECEPTION to "REC",
            DetectedObjectType.PANTRY to "PAN",
            DetectedObjectType.WASHROOM to "WR",
            DetectedObjectType.SERVER_ROOM to "SR",
            DetectedObjectType.STORAGE to "ST"
        )
        for ((type, prefix) in roomPrefixes) {
            candidates
                .filter { it.type == type }
                .sortedWith(compareBy({ it.polygon.center.y }, { it.polygon.center.x }))
                .forEachIndexed { index, candidate ->
                    // Use the candidate's own name if it has one (from OCR).
                    if (candidate.name == null) {
                        codes[candidate] = "$prefix${index + 1}"
                    }
                }
        }

        return candidates.map { it to codes[it] }
    }

    /** A, B ... Z, then AA, AB ... for floors with more than 26 rows. */
    private fun rowLetter(index: Int): String {
        var remaining = index
        val builder = StringBuilder()
        do {
            builder.insert(0, 'A' + remaining % 26)
            remaining = remaining / 26 - 1
        } while (remaining >= 0)
        return builder.toString()
    }

    private fun applyGeometry(entity: DetectedObject, polygon: Polygon) {
        val center = polygon.center
        entity.bboxX = polygon.minX
        entity.bboxY = polygon.minY
        entity.bboxWidth = polygon.width
        entity.bboxHeight = polygon.height
        entity.centerX = center.x
        entity.centerY = center.y
        entity.area = polygon.area
    }

    /** Detection space is 0..1; the interactive map's desks are 0..100. */
    private fun scale(value: Double): BigDecimal =
        BigDecimal(value * 100).setScale(4, RoundingMode.HALF_UP)

    private fun response(e: DetectedObject) = DetectedObjectResponse(
        id = e.id!!, floorId = e.floor.id!!, type = e.type, name = e.name, code = e.code,
        polygon = Polygon.parse(e.polygon).points,
        bbox = BoundingBox(e.bboxX, e.bboxY, e.bboxWidth, e.bboxHeight),
        center = Point(e.centerX, e.centerY), rotation = e.rotation, area = e.area,
        confidence = e.confidence, ocrText = e.ocrText, source = e.source,
        detector = e.detector, deskId = e.deskId, version = e.version
    )

    private companion object {
        /**
         * Desk centres within this fraction of the plan height count as one row.
         * Lowered from 0.02 to prevent merging adjacent rows on dense floors.
         */
        const val ROW_TOLERANCE = 0.012
        val MIN_DESK_SIZE: BigDecimal = BigDecimal("0.5")
    }
}
