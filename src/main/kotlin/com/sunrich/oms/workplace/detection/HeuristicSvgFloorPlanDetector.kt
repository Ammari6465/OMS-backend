package com.sunrich.oms.workplace.detection

import org.slf4j.LoggerFactory
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * High-speed algorithmic detector for SVG vector floor plans.
 * Parses SVG structure directly to detect desks, enclosed rooms, cabins,
 * conference rooms, reception, pantry, washrooms, server rooms, storage,
 * walkways, and emergency exits without requiring external vision API calls.
 */
class HeuristicSvgFloorPlanDetector : FloorPlanDetector {
    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "heuristic:svg"
    override val available = true

    /** Reads SVG markup only — a raster plan is invisible to this engine. */
    override fun supports(image: PlanImage) =
        image.mediaType == "image/svg+xml" || SvgSource.looksLikeSvg(image.bytes)

    override fun detect(image: PlanImage): List<DetectionCandidate> {
        if (!supports(image)) {
            log.debug("Image is not an SVG file; skipping heuristic SVG detector")
            return emptyList()
        }
        return try {
            // Stream the document rather than building a DOM. Plans run to the
            // 10MB upload limit and only <rect> and <text> are of interest, so
            // holding the whole tree in memory buys nothing and costs enough to
            // get the container OOM-killed part-way through the request.
            val plan = parse(image.bytes)
            val texts = plan.texts

            val candidates = mutableListOf<DetectionCandidate>()

            // Rects arrive already normalised against the plan frame.
            for (rect in plan.rects) {
                val nx = rect.x
                val ny = rect.y
                val nw = rect.width
                val nh = rect.height

                val areaPercent = nw * nh * 100.0
                val ratio = if (nw > 0 && nh > 0) kotlin.math.max(nw / nh, nh / nw) else 1.0

                // Match with text label inside or near bounds
                val matchedText = texts.find { t ->
                    t.x >= nx - 0.02 && t.x <= nx + nw + 0.02 &&
                    t.y >= ny - 0.02 && t.y <= ny + nh + 0.02
                }

                val type = when {
                    matchedText != null -> classifyLabel(matchedText.text)
                    areaPercent in 0.02..2.5 && ratio in 0.8..3.5 -> DetectedObjectType.DESK
                    areaPercent in 2.5..12.0 -> DetectedObjectType.CABIN
                    areaPercent > 12.0 -> DetectedObjectType.ZONE
                    else -> null
                } ?: continue

                val polygon = Polygon.ofClamped(
                    listOf(
                        Point(nx, ny),
                        Point(nx + nw, ny),
                        Point(nx + nw, ny + nh),
                        Point(nx, ny + nh)
                    )
                )

                candidates.add(
                    DetectionCandidate(
                        type = type,
                        polygon = polygon,
                        name = matchedText?.text,
                        ocrText = matchedText?.text,
                        rotation = if (nw < nh) 90 else 0,
                        confidence = if (matchedText != null) 0.9 else 0.7
                    )
                )
            }

            // Also emit candidates from explicit text labels if not captured by rects
            for (t in texts) {
                val labelType = classifyLabel(t.text)
                if (labelType != DetectedObjectType.UNKNOWN) {
                    val alreadyCovered = candidates.any { c ->
                        val center = c.polygon.center
                        kotlin.math.abs(center.x - t.x) < 0.05 && kotlin.math.abs(center.y - t.y) < 0.05
                    }
                    if (!alreadyCovered) {
                        val w = 0.12
                        val h = 0.08
                        val nx = (t.x - w / 2).coerceIn(0.0, 1.0 - w)
                        val ny = (t.y - h / 2).coerceIn(0.0, 1.0 - h)
                        candidates.add(
                            DetectionCandidate(
                                type = labelType,
                                polygon = Polygon.ofClamped(
                                    listOf(
                                        Point(nx, ny),
                                        Point(nx + w, ny),
                                        Point(nx + w, ny + h),
                                        Point(nx, ny + h)
                                    )
                                ),
                                name = t.text,
                                ocrText = t.text,
                                rotation = 0,
                                confidence = 0.85
                            )
                        )
                    }
                }
            }
            candidates.distinctBy { Pair(it.type, String.format("%.2f,%.2f", it.polygon.center.x, it.polygon.center.y)) }
        } catch (ex: Exception) {
            // The file announced itself as SVG and then would not parse. That is
            // a broken plan, not an empty one, and the caller has to be able to
            // tell the two apart before telling anyone to redraw a floor by hand.
            log.warn("Heuristic SVG detection failed for {}: {}", image.originalName, ex.message)
            throw UnreadablePlanException(
                "The plan file could not be read as SVG (${ex.message}). Re-export it as valid SVG, " +
                    "or upload it as PNG or JPEG and enable vision detection.",
                ex
            )
        }
    }

    private data class SvgText(val text: String, val x: Double, val y: Double)

    /** A rect in normalised plan coordinates: 0..1 on both axes. */
    private data class SvgRect(val x: Double, val y: Double, val width: Double, val height: Double)

    private data class ParsedPlan(
        val width: Double,
        val height: Double,
        val rects: List<SvgRect>,
        val texts: List<SvgText>
    )

    /**
     * One streaming pass over the document, collecting only what the heuristics
     * read. Memory stays proportional to the number of rects and labels rather
     * than to the size of the file.
     *
     * The frame comes from the root element, which arrives first, so rects can
     * be normalised as they are encountered instead of being buffered raw.
     */
    private fun parse(bytes: ByteArray): ParsedPlan {
        val factory = XMLInputFactory.newInstance().apply {
            // Nothing in a floor plan needs external entities, and resolving
            // them would let an uploaded file reach the filesystem or network.
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_COALESCING, true)
        }
        val rects = mutableListOf<SvgRect>()
        val texts = mutableListOf<SvgText>()
        var viewWidth = 0.0
        var viewHeight = 0.0
        var framed = false

        SvgSource.reader(bytes).use { input ->
            val xml = factory.createXMLStreamReader(input)
            try {
                while (xml.hasNext()) {
                    if (xml.next() != XMLStreamConstants.START_ELEMENT) continue
                    when (xml.localName.lowercase()) {
                        "svg" -> if (!framed) {
                            framed = true
                            viewWidth = parseDim(xml.getAttributeValue(null, "width"))
                            viewHeight = parseDim(xml.getAttributeValue(null, "height"))
                            if (viewWidth <= 0 || viewHeight <= 0) {
                                val box = xml.getAttributeValue(null, "viewBox")?.trim()
                                if (!box.isNullOrEmpty()) {
                                    val parts = box.split(WHITESPACE).mapNotNull { it.toDoubleOrNull() }
                                    if (parts.size >= 4) {
                                        viewWidth = parts[2]
                                        viewHeight = parts[3]
                                    }
                                }
                            }
                            if (viewWidth <= 0) viewWidth = DEFAULT_FRAME
                            if (viewHeight <= 0) viewHeight = DEFAULT_FRAME
                        }

                        "rect" -> if (framed && rects.size < MAX_ELEMENTS) {
                            val x = xml.getAttributeValue(null, "x")?.toDoubleOrNull()
                            val y = xml.getAttributeValue(null, "y")?.toDoubleOrNull()
                            val w = xml.getAttributeValue(null, "width")?.toDoubleOrNull()
                            val h = xml.getAttributeValue(null, "height")?.toDoubleOrNull()
                            if (x != null && y != null && w != null && h != null && w > 0 && h > 0) {
                                val nx = (x / viewWidth).coerceIn(0.0, 1.0)
                                val ny = (y / viewHeight).coerceIn(0.0, 1.0)
                                rects.add(
                                    SvgRect(
                                        nx, ny,
                                        (w / viewWidth).coerceIn(0.01, 1.0 - nx),
                                        (h / viewHeight).coerceIn(0.01, 1.0 - ny)
                                    )
                                )
                            }
                        }

                        "text" -> if (framed && texts.size < MAX_ELEMENTS) {
                            val x = xml.getAttributeValue(null, "x")?.toDoubleOrNull() ?: 0.0
                            val y = xml.getAttributeValue(null, "y")?.toDoubleOrNull() ?: 0.0
                            // Consume the element so nested <tspan> runs are
                            // read as one label, the way getTextContent did.
                            val label = textOf(xml)
                            if (label.isNotEmpty()) {
                                texts.add(
                                    SvgText(
                                        label.take(MAX_LABEL_CHARS),
                                        (x / viewWidth).coerceIn(0.0, 1.0),
                                        (y / viewHeight).coerceIn(0.0, 1.0)
                                    )
                                )
                            }
                        }
                    }
                }
            } finally {
                xml.close()
            }
        }
        if (!framed) throw UnreadablePlanException("The plan file has no <svg> root element.")
        return ParsedPlan(viewWidth, viewHeight, rects, texts)
    }

    /** Collects the character data of the element the reader is positioned on. */
    private fun textOf(xml: XMLStreamReader): String {
        val builder = StringBuilder()
        var depth = 1
        while (xml.hasNext() && depth > 0) {
            when (xml.next()) {
                XMLStreamConstants.START_ELEMENT -> depth++
                XMLStreamConstants.END_ELEMENT -> depth--
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA ->
                    if (builder.length < MAX_LABEL_CHARS) builder.append(xml.text)
            }
        }
        return builder.toString().trim()
    }

    private fun classifyLabel(label: String): DetectedObjectType {
        val u = label.uppercase()
        return when {
            u.contains("CONF") || u.contains("BOARDROOM") -> DetectedObjectType.CONFERENCE_ROOM
            u.contains("MEETING") || u.contains("HUDDLE") -> DetectedObjectType.MEETING_ROOM
            u.contains("CABIN") || u.contains("EXECUTIVE") || u.contains("DIRECTOR") || u.contains("CEO") -> DetectedObjectType.CABIN
            u.contains("RECEPTION") || u.contains("LOBBY") -> DetectedObjectType.RECEPTION
            u.contains("PANTRY") || u.contains("CAFETERIA") || u.contains("KITCHEN") || u.contains("BREAK") -> DetectedObjectType.PANTRY
            u.contains("WASHROOM") || u.contains("RESTROOM") || u.contains("TOILET") || u.contains("WC") -> DetectedObjectType.WASHROOM
            u.contains("SERVER") || u.contains("IT ROOM") || u.contains("DATA") -> DetectedObjectType.SERVER_ROOM
            u.contains("STORAGE") || u.contains("STORE") || u.contains("ARCHIVE") -> DetectedObjectType.STORAGE
            u.contains("ZONE") || u.contains("BAY") || u.contains("WORKSPACE") -> DetectedObjectType.ZONE
            u.contains("CORRIDOR") || u.contains("WALKWAY") || u.contains("PASSAGE") -> DetectedObjectType.WALKWAY
            u.contains("EXIT") || u.contains("FIRE") -> DetectedObjectType.EXIT
            u.startsWith("D-") || u.matches(Regex("^[A-Z]\\d+$")) -> DetectedObjectType.DESK
            else -> DetectedObjectType.UNKNOWN
        }
    }

    private fun parseDim(v: String?): Double =
        v?.replace(NON_NUMERIC, "")?.toDoubleOrNull() ?: 0.0

    private companion object {
        /** Used when a plan states neither dimensions nor a viewBox. */
        const val DEFAULT_FRAME = 1000.0

        /**
         * Ceiling on rects and labels kept from one plan. A drawing with more
         * shapes than this is line-work, not rooms, and collecting all of it is
         * how a 10MB upload turns into an OOM kill mid-request.
         */
        const val MAX_ELEMENTS = 20_000

        const val MAX_LABEL_CHARS = 200

        val WHITESPACE = Regex("[\\s,]+")
        val NON_NUMERIC = Regex("[^0-9.]")
    }
}
