package com.sunrich.oms.workplace.detection

import com.sunrich.oms.common.entity.BaseEntity
import com.sunrich.oms.workplace.Floor
import jakarta.persistence.*

/**
 * What a detected region represents. Mirrors the workplace vocabulary so a
 * detection can be promoted into a real OMS entity: DESK becomes a Desk, ZONE
 * becomes a Zone, and the room types describe spaces the hierarchy does not
 * model yet.
 */
enum class DetectedObjectType {
    DESK, CABIN, CONFERENCE_ROOM, MEETING_ROOM, RECEPTION, PANTRY, WASHROOM,
    SERVER_ROOM, STORAGE, ZONE, WALKWAY, DOOR, STAIRCASE, ELEVATOR, EXIT, UNKNOWN;

    companion object {
        /** Lenient parse for model output, which is free text however hard we prompt. */
        fun parse(value: String?): DetectedObjectType {
            val key = value?.trim()?.uppercase()?.replace(' ', '_')?.replace('-', '_') ?: return UNKNOWN
            entries.firstOrNull { it.name == key }?.let { return it }
            return when {
                key.contains("CONFERENCE") -> CONFERENCE_ROOM
                key.contains("MEETING") || key.contains("HUDDLE") -> MEETING_ROOM
                key.contains("CABIN") || key.contains("OFFICE") -> CABIN
                key.contains("RECEPTION") || key.contains("LOBBY") -> RECEPTION
                key.contains("PANTRY") || key.contains("KITCHEN") || key.contains("BREAK") -> PANTRY
                key.contains("WASHROOM") || key.contains("TOILET") || key.contains("REST") -> WASHROOM
                key.contains("SERVER") || key.contains("IT_") -> SERVER_ROOM
                key.contains("STORE") || key.contains("STORAGE") -> STORAGE
                key.contains("CORRIDOR") || key.contains("WALKWAY") || key.contains("PASSAGE") -> WALKWAY
                key.contains("DOOR") || key.contains("ENTRANCE") -> DOOR
                key.contains("STAIR") -> STAIRCASE
                key.contains("ELEVATOR") || key.contains("LIFT") -> ELEVATOR
                key.contains("EXIT") || key.contains("EVACUATION") -> EXIT
                key.contains("DESK") || key.contains("WORKSTATION") || key.contains("SEAT") -> DESK
                key.contains("ZONE") || key.contains("OPEN") -> ZONE
                else -> UNKNOWN
            }
        }
    }
}

/** Where a detection came from, so automated re-runs never discard human work. */
enum class DetectionSource {
    /** Produced by a detector and not touched since. Replaced on re-run. */
    AUTO,

    /** Drawn by a person. Never replaced by a detector. */
    MANUAL,

    /** Detected, then corrected by a person. Never replaced by a detector. */
    EDITED
}

/**
 * One region recognised on a floor plan, stored separately from the uploaded
 * image so the original file is never modified. Geometry is normalised to the
 * 0..1 range on both axes, which keeps overlays aligned at any zoom level and
 * independent of the stored image's pixel dimensions.
 */
@Entity
@Table(
    name = "workplace_detected_objects",
    indexes = [
        Index(name = "idx_detected_floor", columnList = "floor_id,is_deleted"),
        Index(name = "idx_detected_type", columnList = "floor_id,object_type")
    ]
)
class DetectedObject(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "floor_id", nullable = false)
    var floor: Floor,

    @Enumerated(EnumType.STRING)
    @Column(name = "object_type", nullable = false, length = 40)
    var type: DetectedObjectType,

    /** Display name, from OCR where available ("Conference Room A"). */
    @Column(length = 200)
    var name: String? = null,

    /** Generated identifier such as D-001 or A01, unique per floor when set. */
    @Column(name = "object_code", length = 50)
    var code: String? = null,

    /** Polygon boundary as normalised "x,y x,y ..." pairs. */
    @Column(name = "polygon", nullable = false, length = 4000)
    var polygon: String,

    @Column(name = "bbox_x", nullable = false) var bboxX: Double = 0.0,
    @Column(name = "bbox_y", nullable = false) var bboxY: Double = 0.0,
    @Column(name = "bbox_width", nullable = false) var bboxWidth: Double = 0.0,
    @Column(name = "bbox_height", nullable = false) var bboxHeight: Double = 0.0,
    @Column(name = "center_x", nullable = false) var centerX: Double = 0.0,
    @Column(name = "center_y", nullable = false) var centerY: Double = 0.0,

    @Column(nullable = false) var rotation: Int = 0,

    /** Normalised area, 0..1 of the plan surface. */
    @Column(nullable = false) var area: Double = 0.0,

    /** Detector confidence, 0..1. Manual objects are certain by definition. */
    @Column(nullable = false) var confidence: Double = 1.0,

    /** Raw OCR text found inside the region, kept for later reclassification. */
    @Column(name = "ocr_text", length = 1000)
    var ocrText: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var source: DetectionSource = DetectionSource.AUTO,

    /** Detector that produced this object, for auditing a re-run. */
    @Column(name = "detector", length = 60)
    var detector: String? = null,

    /** Desk created from this detection, once promoted into the hierarchy. */
    @Column(name = "desk_id")
    var deskId: Long? = null
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "detected_object_id")
    var id: Long? = null

    /** Human corrections survive re-detection; untouched automatic ones do not. */
    val isProtected: Boolean get() = source != DetectionSource.AUTO
}
