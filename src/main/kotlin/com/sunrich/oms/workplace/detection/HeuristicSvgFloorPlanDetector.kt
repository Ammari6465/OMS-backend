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
        val census = HashMap<String, Int>()
        // Cumulative transform in effect at the current depth. CAD exporters
        // nest the whole drawing under transformed groups, so coordinates read
        // off an element mean nothing until this is applied.
        val transforms = ArrayDeque<Affine>().apply { addLast(Affine.IDENTITY) }
        var viewWidth = 0.0
        var viewHeight = 0.0
        var framed = false

        SvgSource.reader(bytes).use { input ->
            val xml = factory.createXMLStreamReader(input)
            try {
                while (xml.hasNext()) {
                    when (xml.next()) {
                        XMLStreamConstants.END_ELEMENT -> {
                            if (transforms.size > 1) transforms.removeLast()
                            continue
                        }
                        XMLStreamConstants.START_ELEMENT -> Unit
                        else -> continue
                    }

                    val name = xml.localName.lowercase()
                    census[name] = (census[name] ?: 0) + 1
                    val here = transforms.last() * Affine.parse(xml.getAttributeValue(null, "transform"))
                    transforms.addLast(here)

                    when (name) {
                        "svg" -> if (!framed) {
                            framed = true
                            viewWidth = parseDim(xml.getAttributeValue(null, "width"))
                            viewHeight = parseDim(xml.getAttributeValue(null, "height"))
                            val box = xml.getAttributeValue(null, "viewBox")?.trim()
                            val parts = if (box.isNullOrEmpty()) emptyList()
                            else box.split(WHITESPACE).mapNotNull { it.toDoubleOrNull() }
                            if ((viewWidth <= 0 || viewHeight <= 0) && parts.size >= 4) {
                                viewWidth = parts[2]
                                viewHeight = parts[3]
                            }
                            if (viewWidth <= 0) viewWidth = DEFAULT_FRAME
                            if (viewHeight <= 0) viewHeight = DEFAULT_FRAME
                            // A viewBox with a non-zero origin shifts the whole
                            // drawing; fold it in so shapes land in the frame.
                            if (parts.size >= 4 && (parts[0] != 0.0 || parts[1] != 0.0)) {
                                transforms[transforms.size - 1] =
                                    here * Affine(1.0, 0.0, 0.0, 1.0, -parts[0], -parts[1])
                            }
                        }

                        "text" -> {
                            val x = xml.getAttributeValue(null, "x")?.toDoubleOrNull() ?: 0.0
                            val y = xml.getAttributeValue(null, "y")?.toDoubleOrNull() ?: 0.0
                            // Consume the element so nested <tspan> runs read as
                            // one label. That swallows this element's END too,
                            // so drop the frame we just pushed.
                            val label = textOf(xml)
                            transforms.removeLast()
                            if (framed && texts.size < MAX_ELEMENTS && label.isNotEmpty()) {
                                val (px, py) = here.apply(x, y)
                                texts.add(
                                    SvgText(
                                        label.take(MAX_LABEL_CHARS),
                                        (px / viewWidth).coerceIn(0.0, 1.0),
                                        (py / viewHeight).coerceIn(0.0, 1.0)
                                    )
                                )
                            }
                        }

                        else -> if (framed && rects.size < MAX_ELEMENTS) {
                            shapeOf(name, xml)?.let { points ->
                                normalise(points, here, viewWidth, viewHeight)?.let(rects::add)
                            }
                        }
                    }
                }
            } finally {
                xml.close()
            }
        }
        if (!framed) throw UnreadablePlanException("The plan file has no <svg> root element.")
        // A plan that yields nothing is usually a plan drawn with geometry this
        // parser does not read. Recording what was actually in the file turns
        // that from a guess into a look at one log line.
        log.info(
            "Parsed plan: frame {}x{}, {} shapes, {} labels, elements {}",
            viewWidth.toInt(), viewHeight.toInt(), rects.size, texts.size,
            census.entries.sortedByDescending { it.value }.take(CENSUS_KINDS).joinToString { "${it.key}=${it.value}" }
        )
        return ParsedPlan(viewWidth, viewHeight, rects, texts)
    }

    /**
     * Outline points for the shape elements a floor plan is actually drawn
     * with. CAD and Illustrator exports contain almost no `<rect>`: walls and
     * furniture come through as paths and polylines, so reading only rects sees
     * an empty drawing where a person sees a full floor.
     *
     * Curves contribute their endpoints only. That is enough for a bounding
     * box, which is all the classification below uses.
     */
    private fun shapeOf(name: String, xml: XMLStreamReader): List<Point>? = when (name) {
        "rect" -> {
            val x = xml.getAttributeValue(null, "x")?.toDoubleOrNull() ?: 0.0
            val y = xml.getAttributeValue(null, "y")?.toDoubleOrNull() ?: 0.0
            val w = xml.getAttributeValue(null, "width")?.toDoubleOrNull()
            val h = xml.getAttributeValue(null, "height")?.toDoubleOrNull()
            if (w == null || h == null || w <= 0 || h <= 0) null
            else listOf(Point(x, y), Point(x + w, y), Point(x + w, y + h), Point(x, y + h))
        }

        "polygon", "polyline" -> points(xml.getAttributeValue(null, "points"))

        "path" -> largestSubpath(xml.getAttributeValue(null, "d"))

        else -> null
    }

    /** Parses a `points` list: "x1,y1 x2,y2" in any of its permitted spacings. */
    private fun points(raw: String?): List<Point>? {
        if (raw.isNullOrBlank()) return null
        val numbers = raw.trim().split(WHITESPACE).mapNotNull { it.toDoubleOrNull() }
        if (numbers.size < 6) return null
        return (0 until numbers.size / 2).map { Point(numbers[it * 2], numbers[it * 2 + 1]) }
    }

    /**
     * The largest subpath of a `d` attribute.
     *
     * Exporters routinely merge an entire layer into one `<path>` of hundreds
     * of disjoint subpaths. Taking the bounding box of all of them would
     * describe the whole floor rather than a room, and emitting every subpath
     * would flood the map with line-work, so the biggest one wins.
     */
    private fun largestSubpath(d: String?): List<Point>? {
        if (d.isNullOrBlank() || d.length > MAX_PATH_CHARS) return null
        var best: List<Point>? = null
        var bestArea = 0.0
        var current = mutableListOf<Point>()
        var x = 0.0
        var y = 0.0
        var command = ' '

        fun close() {
            if (current.size >= 3) {
                val area = (current.maxOf { it.x } - current.minOf { it.x }) *
                    (current.maxOf { it.y } - current.minOf { it.y })
                if (area > bestArea) {
                    bestArea = area
                    best = current
                }
            }
            current = mutableListOf()
        }

        val tokens = PATH_TOKEN.findAll(d).map { it.value }.iterator()
        val pending = ArrayDeque<Double>()
        while (tokens.hasNext()) {
            val token = tokens.next()
            val letter = token[0]
            if (letter.isLetter()) {
                if (letter == 'Z' || letter == 'z') close()
                command = letter
                pending.clear()
                continue
            }
            val value = token.toDoubleOrNull() ?: continue
            pending.addLast(value)
            val relative = command.isLowerCase()
            when (command.uppercaseChar()) {
                'M', 'L', 'T' -> if (pending.size == 2) {
                    if (command == 'M' || command == 'm') close()
                    x = if (relative) x + pending.removeFirst() else pending.removeFirst()
                    y = if (relative) y + pending.removeFirst() else pending.removeFirst()
                    current.add(Point(x, y))
                    // Extra coordinate pairs after an M are implicit L commands.
                    if (command == 'M') command = 'L' else if (command == 'm') command = 'l'
                }
                'H' -> { x = if (relative) x + pending.removeFirst() else pending.removeFirst(); current.add(Point(x, y)) }
                'V' -> { y = if (relative) y + pending.removeFirst() else pending.removeFirst(); current.add(Point(x, y)) }
                // Curves: keep only the endpoint, which is the final pair.
                'C' -> if (pending.size == 6) { repeat(4) { pending.removeFirst() }; x = if (relative) x + pending.removeFirst() else pending.removeFirst(); y = if (relative) y + pending.removeFirst() else pending.removeFirst(); current.add(Point(x, y)) }
                'S', 'Q' -> if (pending.size == 4) { repeat(2) { pending.removeFirst() }; x = if (relative) x + pending.removeFirst() else pending.removeFirst(); y = if (relative) y + pending.removeFirst() else pending.removeFirst(); current.add(Point(x, y)) }
                'A' -> if (pending.size == 7) { repeat(5) { pending.removeFirst() }; x = if (relative) x + pending.removeFirst() else pending.removeFirst(); y = if (relative) y + pending.removeFirst() else pending.removeFirst(); current.add(Point(x, y)) }
                else -> pending.clear()
            }
        }
        close()
        return best
    }

    /** Transforms a shape into the plan frame and reduces it to a 0..1 box. */
    private fun normalise(points: List<Point>, transform: Affine, frameW: Double, frameH: Double): SvgRect? {
        if (points.isEmpty()) return null
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (p in points) {
            val (tx, ty) = transform.apply(p.x, p.y)
            if (tx < minX) minX = tx
            if (tx > maxX) maxX = tx
            if (ty < minY) minY = ty
            if (ty > maxY) maxY = ty
        }
        val w = maxX - minX
        val h = maxY - minY
        if (w <= 0 || h <= 0) return null
        val nx = (minX / frameW).coerceIn(0.0, 1.0)
        val ny = (minY / frameH).coerceIn(0.0, 1.0)
        return SvgRect(
            nx, ny,
            (w / frameW).coerceIn(0.01, 1.0 - nx),
            (h / frameH).coerceIn(0.01, 1.0 - ny)
        )
    }

    /**
     * A 2D affine transform, matching SVG's `matrix(a b c d e f)` ordering.
     * Only what a bounding box needs: no skew decomposition, no units.
     */
    private data class Affine(
        val a: Double, val b: Double, val c: Double,
        val d: Double, val e: Double, val f: Double
    ) {
        fun apply(x: Double, y: Double) = Pair(a * x + c * y + e, b * x + d * y + f)

        operator fun times(o: Affine) = Affine(
            a * o.a + c * o.b, b * o.a + d * o.b,
            a * o.c + c * o.d, b * o.c + d * o.d,
            a * o.e + c * o.f + e, b * o.e + d * o.f + f
        )

        companion object {
            val IDENTITY = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

            /** Composes a `transform` attribute left to right, as SVG applies it. */
            fun parse(raw: String?): Affine {
                if (raw.isNullOrBlank()) return IDENTITY
                var result = IDENTITY
                for (match in TRANSFORM.findAll(raw)) {
                    val args = match.groupValues[2].trim().split(WHITESPACE).mapNotNull { it.toDoubleOrNull() }
                    val step = when (match.groupValues[1].lowercase()) {
                        "translate" -> when {
                            args.isEmpty() -> IDENTITY
                            else -> Affine(1.0, 0.0, 0.0, 1.0, args[0], args.getOrElse(1) { 0.0 })
                        }
                        "scale" -> when {
                            args.isEmpty() -> IDENTITY
                            else -> Affine(args[0], 0.0, 0.0, args.getOrElse(1) { args[0] }, 0.0, 0.0)
                        }
                        "matrix" -> if (args.size < 6) IDENTITY
                        else Affine(args[0], args[1], args[2], args[3], args[4], args[5])
                        "rotate" -> if (args.isEmpty()) IDENTITY else {
                            val rad = Math.toRadians(args[0])
                            val cos = kotlin.math.cos(rad)
                            val sin = kotlin.math.sin(rad)
                            val spin = Affine(cos, sin, -sin, cos, 0.0, 0.0)
                            // rotate(angle cx cy) spins about a point.
                            if (args.size < 3) spin else
                                Affine(1.0, 0.0, 0.0, 1.0, args[1], args[2]) * spin *
                                    Affine(1.0, 0.0, 0.0, 1.0, -args[1], -args[2])
                        }
                        else -> IDENTITY
                    }
                    result *= step
                }
                return result
            }
        }
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

        /** Element kinds named in the diagnostic census line. */
        const val CENSUS_KINDS = 8

        /** A `d` attribute longer than this is a whole layer, not a room. */
        const val MAX_PATH_CHARS = 200_000

        val WHITESPACE = Regex("[\\s,]+")
        val NON_NUMERIC = Regex("[^0-9.]")
        val TRANSFORM = Regex("""(\w+)\s*\(([^)]*)\)""")
        /** One path token: a command letter, or a number in any SVG spelling. */
        val PATH_TOKEN = Regex("""[A-Za-z]|[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?""")
    }
}
