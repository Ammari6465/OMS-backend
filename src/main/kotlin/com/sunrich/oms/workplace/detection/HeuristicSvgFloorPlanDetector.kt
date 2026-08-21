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
            rejectTracedBitmap(plan)
            val shapes = plan.rects
            val labels = labelOwners(shapes, plan.texts)

            // Classify by size first. A label refines the type; it never sets it,
            // because a sheet-sized rectangle containing the tag "A01" is a
            // drawing border, not a desk.
            val classified = shapes.mapIndexedNotNull { index, s ->
                val label = labels[index]
                val type = classify(s.width * s.height * 100.0, ratioOf(s), label?.text) ?: return@mapIndexedNotNull null
                DetectionCandidate(
                    type = type,
                    polygon = Polygon.rectangle(s.x, s.y, s.width, s.height),
                    name = label?.text,
                    ocrText = label?.text,
                    rotation = if (s.width < s.height) 90 else 0,
                    confidence = if (label != null) 0.9 else 0.7
                )
            }

            val candidates = suppressOverlaps(consistentDesks(classified)).take(MAX_CANDIDATES)
            log.info(
                "Classified {} shapes into {} candidates ({} desks, {} rooms)",
                shapes.size, candidates.size,
                candidates.count { it.type == DetectedObjectType.DESK },
                candidates.count { it.type != DetectedObjectType.DESK }
            )
            candidates
        } catch (ex: UnreadablePlanException) {
            // Already carries a diagnosis aimed at the user. Wrapping it in the
            // generic parse message below would bury the useful half.
            throw ex
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

    /**
     * Refuses a plan that is a picture wearing an SVG extension.
     *
     * Auto-tracers turn a bitmap into thousands of filled curves, one per blob
     * of quantised colour, with every printed label traced into outlines rather
     * than left as text. Nothing in it corresponds to a room or a desk, so the
     * geometry here can only produce confident nonsense — which is worse than
     * an honest refusal, because a floor covered in wrong boxes has to be
     * cleaned up by hand before it can be redone properly.
     *
     * The signature is specific enough not to catch a real drawing: an
     * architectural plan is built from straight segments and keeps its labels
     * as text. All curves, no lines, and no text at all is a trace.
     */
    private fun rejectTracedBitmap(plan: ParsedPlan) {
        val traced = plan.texts.isEmpty() &&
            plan.strokes.straight == 0 &&
            plan.strokes.curved >= TRACED_MIN_CURVES &&
            plan.rects.size >= TRACED_MIN_SHAPES
        if (!traced) return
        log.info(
            "Plan looks auto-traced: {} shapes, {} curves, no straight segments, no text",
            plan.rects.size, plan.strokes.curved
        )
        throw UnreadablePlanException(
            "This plan is a bitmap that has been auto-traced into ${plan.rects.size} curve outlines — " +
                "it has no text and no straight walls, so there is nothing here to recognise as rooms or desks. " +
                "Upload the original PNG or JPEG of this plan and enable vision detection, " +
                "or upload a CAD-exported SVG."
        )
    }

    /**
     * Assigns each label to the smallest shape that encloses it.
     *
     * Innermost wins, because a plan nests: a desk tag sits inside a desk,
     * inside a room, inside the sheet border. Matching the first enclosing
     * shape instead lets a drawing border adopt the first tag on the page and
     * be classified from it.
     */
    private fun labelOwners(shapes: List<SvgRect>, texts: List<SvgText>): Map<Int, SvgText> {
        val owners = HashMap<Int, SvgText>()
        for (t in texts) {
            var best = -1
            var bestArea = Double.MAX_VALUE
            shapes.forEachIndexed { i, s ->
                if (t.x >= s.x && t.x <= s.x + s.width && t.y >= s.y && t.y <= s.y + s.height) {
                    val area = s.width * s.height
                    if (area < bestArea) {
                        bestArea = area
                        best = i
                    }
                }
            }
            if (best >= 0) owners.putIfAbsent(best, t)
        }
        return owners
    }

    private fun ratioOf(s: SvgRect) =
        if (s.width > 0 && s.height > 0) kotlin.math.max(s.width / s.height, s.height / s.width) else 1.0

    /**
     * Types a shape from how much of the plan it covers, letting a label refine
     * the result rather than decide it.
     *
     * The bands exist to reject, not just to name. Below [MIN_AREA] is
     * line-work — chair glyphs, door swings, hatching — and above
     * [ZONE_MAX_AREA] is the sheet border or a background fill. Emitting those
     * buries the real objects under overlapping boxes.
     */
    private fun classify(areaPercent: Double, ratio: Double, label: String?): DetectedObjectType? {
        val labelled = label?.let(::classifyLabel)?.takeIf { it != DetectedObjectType.UNKNOWN }
        return when {
            areaPercent < MIN_AREA -> null
            areaPercent <= DESK_MAX_AREA ->
                if (ratio <= DESK_MAX_RATIO) DetectedObjectType.DESK else null
            areaPercent <= ROOM_MAX_AREA -> labelled?.takeIf { it in ROOM_TYPES } ?: DetectedObjectType.CABIN
            areaPercent <= ZONE_MAX_AREA -> labelled?.takeIf { it in ROOM_TYPES } ?: DetectedObjectType.ZONE
            else -> null
        }
    }

    /**
     * Keeps only desks that look like the other desks.
     *
     * Office desks repeat at close to one size; the stray furniture symbols
     * that survive the area band do not. Measuring against the median rather
     * than a fixed size lets this work on any plan scale.
     */
    private fun consistentDesks(candidates: List<DetectionCandidate>): List<DetectionCandidate> {
        val desks = candidates.filter { it.type == DetectedObjectType.DESK }
        if (desks.size < MIN_DESKS_FOR_MODE) return candidates
        val median = desks.map { it.polygon.area }.sorted()[desks.size / 2]
        if (median <= 0) return candidates
        return candidates.filter {
            it.type != DetectedObjectType.DESK ||
                it.polygon.area in (median / DESK_SIZE_SPREAD)..(median * DESK_SIZE_SPREAD)
        }
    }

    /**
     * Drops shapes of the same type that describe the same region twice —
     * double-drawn outlines, a fill behind its own stroke, a room repeated on
     * two layers. Nesting across types survives, because a desk genuinely does
     * sit inside a room which sits inside a zone.
     *
     * Smallest first, so the specific shape wins over the block containing it.
     */
    private fun suppressOverlaps(candidates: List<DetectionCandidate>): List<DetectionCandidate> {
        val ordered = candidates.sortedWith(compareBy({ it.polygon.area }, { -it.confidence }))
        val kept = mutableListOf<DetectionCandidate>()
        for (c in ordered) {
            // Comparing every shape against every kept one is quadratic, so the
            // ceiling that bounds the output bounds the work as well.
            if (kept.size >= MAX_CANDIDATES) break
            if (kept.none { it.type == c.type && covers(it.polygon, c.polygon) }) kept.add(c)
        }
        return kept
    }

    /** Overlap as a fraction of the smaller shape, so containment counts too. */
    private fun covers(a: Polygon, b: Polygon): Boolean {
        val w = kotlin.math.min(a.maxX, b.maxX) - kotlin.math.max(a.minX, b.minX)
        val h = kotlin.math.min(a.maxY, b.maxY) - kotlin.math.max(a.minY, b.minY)
        if (w <= 0 || h <= 0) return false
        val smaller = kotlin.math.min(a.area, b.area)
        return smaller > 0 && (w * h) / smaller > OVERLAP_LIMIT
    }

    private data class SvgText(val text: String, val x: Double, val y: Double)

    /** A rect in normalised plan coordinates: 0..1 on both axes. */
    private data class SvgRect(val x: Double, val y: Double, val width: Double, val height: Double)

    private data class ParsedPlan(
        val width: Double,
        val height: Double,
        val rects: List<SvgRect>,
        val texts: List<SvgText>,
        val strokes: PathStats
    )

    /** Tally of what the path data is made of, used to spot a traced bitmap. */
    private data class PathStats(var straight: Int = 0, var curved: Int = 0)

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
        val strokes = PathStats()
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
                            shapeOf(name, xml, strokes)?.let { points ->
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
        return ParsedPlan(viewWidth, viewHeight, rects, texts, strokes)
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
    private fun shapeOf(name: String, xml: XMLStreamReader, strokes: PathStats): List<Point>? = when (name) {
        "rect" -> {
            val x = xml.getAttributeValue(null, "x")?.toDoubleOrNull() ?: 0.0
            val y = xml.getAttributeValue(null, "y")?.toDoubleOrNull() ?: 0.0
            val w = xml.getAttributeValue(null, "width")?.toDoubleOrNull()
            val h = xml.getAttributeValue(null, "height")?.toDoubleOrNull()
            if (w == null || h == null || w <= 0 || h <= 0) null
            else listOf(Point(x, y), Point(x + w, y), Point(x + w, y + h), Point(x, y + h))
        }

        "polygon", "polyline" -> points(xml.getAttributeValue(null, "points"))

        "path" -> largestSubpath(xml.getAttributeValue(null, "d"), strokes)

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
    private fun largestSubpath(d: String?, strokes: PathStats): List<Point>? {
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

        // Scanned by hand rather than with a Regex. A traced plan carries tens
        // of thousands of curve commands, and matching them allocates a
        // MatchResult and a String per token — 297MB of churn for one 2.7MB
        // file, enough to get the container OOM-killed mid-request. Walking the
        // characters keeps a scan flat.
        val args = DoubleArray(MAX_PATH_ARGS)
        var argCount = 0
        var i = 0
        while (i < d.length) {
            val c = d[i]
            if (c == ' ' || c == ',' || c == '\t' || c == '\n' || c == '\r') {
                i++
                continue
            }
            if (c.isLetter()) {
                if (c == 'Z' || c == 'z') close()
                when (c.uppercaseChar()) {
                    'L', 'H', 'V' -> strokes.straight++
                    'C', 'S', 'Q', 'T', 'A' -> strokes.curved++
                }
                command = c
                argCount = 0
                i++
                continue
            }

            val start = i
            if (c == '+' || c == '-') i++
            var seenDot = false
            while (i < d.length) {
                val ch = d[i]
                // One decimal point per number: "1.5.5" is two numbers, which
                // exporters do write to save bytes.
                if (ch.isDigit()) i++ else if (ch == '.' && !seenDot) { seenDot = true; i++ } else break
            }
            if (i < d.length && (d[i] == 'e' || d[i] == 'E')) {
                val exponent = i
                i++
                if (i < d.length && (d[i] == '+' || d[i] == '-')) i++
                if (i < d.length && d[i].isDigit()) while (i < d.length && d[i].isDigit()) i++ else i = exponent
            }
            if (i == start) {
                i++
                continue
            }
            val value = parseNumber(d, start, i) ?: continue
            if (argCount < args.size) args[argCount++] = value

            val arity = arityOf(command)
            if (arity == 0 || argCount < arity) continue
            val relative = command.isLowerCase()
            // Curves contribute only their endpoint, which is the last pair.
            when (command.uppercaseChar()) {
                'M', 'L', 'T', 'C', 'S', 'Q', 'A' -> {
                    if (command == 'M' || command == 'm') close()
                    x = if (relative) x + args[arity - 2] else args[arity - 2]
                    y = if (relative) y + args[arity - 1] else args[arity - 1]
                    current.add(Point(x, y))
                    // Extra coordinate pairs after an M are implicit L commands.
                    if (command == 'M') command = 'L' else if (command == 'm') command = 'l'
                }
                'H' -> { x = if (relative) x + args[0] else args[0]; current.add(Point(x, y)) }
                'V' -> { y = if (relative) y + args[0] else args[0]; current.add(Point(x, y)) }
            }
            argCount = 0
        }
        close()
        return best
    }

    /**
     * Parses one number out of a path's `d` attribute.
     *
     * Deliberately not `toDoubleOrNull`: that screens every call against a
     * Regex before parsing, which at a few hundred thousand numbers per plan
     * costs more than the rest of the scan put together. The scanner above has
     * already established the character shape, so parse and catch instead.
     */
    private fun parseNumber(text: String, start: Int, end: Int): Double? = try {
        java.lang.Double.parseDouble(text.substring(start, end))
    } catch (_: NumberFormatException) {
        null
    }

    /** How many numbers each path command consumes before it can be applied. */
    private fun arityOf(command: Char) = when (command.uppercaseChar()) {
        'H', 'V' -> 1
        'M', 'L', 'T' -> 2
        'S', 'Q' -> 4
        'C' -> 6
        'A' -> 7
        else -> 0
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

        // ---- classification bands, as a percentage of the plan's surface ----
        /** Below this a shape is line-work: chair glyphs, door swings, hatching. */
        const val MIN_AREA = 0.05
        const val DESK_MAX_AREA = 1.2
        const val DESK_MAX_RATIO = 3.0
        const val ROOM_MAX_AREA = 12.0
        /** Above this is the sheet border or a background fill, not a region. */
        const val ZONE_MAX_AREA = 60.0

        /** Desks are only measured against each other once there are enough. */
        const val MIN_DESKS_FOR_MODE = 6
        /** How far from the median desk area a desk may still be. */
        const val DESK_SIZE_SPREAD = 2.5

        /** Overlap of the smaller shape above which two are the same region. */
        const val OVERLAP_LIMIT = 0.5

        /** Ceiling on what one scan puts on the map. */
        const val MAX_CANDIDATES = 1_500

        // ---- traced-bitmap signature ----
        const val TRACED_MIN_CURVES = 500
        const val TRACED_MIN_SHAPES = 100

        /** Types a printed label may assign; a desk tag never renames a room. */
        val ROOM_TYPES = setOf(
            DetectedObjectType.CABIN, DetectedObjectType.CONFERENCE_ROOM, DetectedObjectType.MEETING_ROOM,
            DetectedObjectType.RECEPTION, DetectedObjectType.PANTRY, DetectedObjectType.WASHROOM,
            DetectedObjectType.SERVER_ROOM, DetectedObjectType.STORAGE, DetectedObjectType.ZONE,
            DetectedObjectType.WALKWAY, DetectedObjectType.EXIT
        )

        /** A `d` attribute longer than this is a whole layer, not a room. */
        const val MAX_PATH_CHARS = 200_000

        /** Widest path command is the elliptical arc, at seven numbers. */
        const val MAX_PATH_ARGS = 7

        val WHITESPACE = Regex("[\\s,]+")
        val NON_NUMERIC = Regex("[^0-9.]")
        val TRANSFORM = Regex("""(\w+)\s*\(([^)]*)\)""")
    }
}
