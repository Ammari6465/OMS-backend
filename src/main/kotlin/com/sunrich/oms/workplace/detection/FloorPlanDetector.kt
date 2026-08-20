package com.sunrich.oms.workplace.detection

import org.slf4j.LoggerFactory

/**
 * One region a detector believes it has found. Deliberately a plain value type
 * with no persistence concerns, so a detector can be implemented against any
 * engine — a vision model, OpenCV, YOLO, or a CAD parser — without touching the
 * rest of the module.
 */
data class DetectionCandidate(
    val type: DetectedObjectType,
    val polygon: Polygon,
    val name: String? = null,
    val ocrText: String? = null,
    val rotation: Int = 0,
    val confidence: Double = 0.5
)

/** The image handed to a detector, already read from storage. */
data class PlanImage(
    val bytes: ByteArray,
    val mediaType: String,
    val originalName: String,
    val width: Int?,
    val height: Int?
) {
    // ByteArray gives these structural equality, which the data class would
    // otherwise get wrong by comparing references.
    override fun equals(other: Any?) = this === other ||
        (other is PlanImage && bytes.contentEquals(other.bytes) && mediaType == other.mediaType)

    override fun hashCode() = 31 * bytes.contentHashCode() + mediaType.hashCode()
}

/**
 * Turns a floor plan image into candidate regions.
 *
 * Implementations must be side-effect free: persistence, numbering, and merging
 * with existing human edits are the service's job, not the detector's. That
 * separation is what keeps engines interchangeable.
 */
interface FloorPlanDetector {
    /** Short stable identifier recorded against each object, e.g. "vision:claude". */
    val name: String

    /** Whether this detector is usable right now (configured, keyed, reachable). */
    val available: Boolean get() = true

    fun detect(image: PlanImage): List<DetectionCandidate>
}

/**
 * Fallback used when no engine is configured. Returns nothing rather than
 * guessing, so an unconfigured system shows an honest "no objects detected"
 * instead of fabricated desks.
 */
class UnavailableDetector(private val reason: String) : FloorPlanDetector {
    private val log = LoggerFactory.getLogger(javaClass)
    override val name = "unavailable"
    override val available = false
    override fun detect(image: PlanImage): List<DetectionCandidate> {
        log.warn("Floor plan detection requested but no engine is configured: {}", reason)
        return emptyList()
    }
}
