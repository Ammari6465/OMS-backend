package com.sunrich.oms.workplace.detection

import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

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

    override fun detect(image: PlanImage): List<DetectionCandidate> {
        val content = String(image.bytes, Charsets.UTF_8).trim()
        if (!content.contains("<svg", ignoreCase = true)) {
            log.debug("Image is not an SVG file; skipping heuristic SVG detector")
            return emptyList()
        }
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(image.bytes))
            val svg = doc.documentElement

            var viewWidth = parseDim(svg.getAttribute("width"))
            var viewHeight = parseDim(svg.getAttribute("height"))
            val viewBox = svg.getAttribute("viewBox").trim()
            if ((viewWidth <= 0 || viewHeight <= 0) && viewBox.isNotEmpty()) {
                val parts = viewBox.split(Regex("[\\s,]+")).mapNotNull { it.toDoubleOrNull() }
                if (parts.size >= 4) {
                    viewWidth = parts[2]
                    viewHeight = parts[3]
                }
            }
            if (viewWidth <= 0) viewWidth = 1000.0
            if (viewHeight <= 0) viewHeight = 1000.0

            val candidates = mutableListOf<DetectionCandidate>()
            val textNodes = svg.getElementsByTagName("text")
            val texts = extractTexts(textNodes, viewWidth, viewHeight)

            val rectNodes = svg.getElementsByTagName("rect")
            for (i in 0 until rectNodes.length) {
                val rect = rectNodes.item(i) as? Element ?: continue
                val x = rect.getAttribute("x").toDoubleOrNull() ?: continue
                val y = rect.getAttribute("y").toDoubleOrNull() ?: continue
                val w = rect.getAttribute("width").toDoubleOrNull() ?: continue
                val h = rect.getAttribute("height").toDoubleOrNull() ?: continue

                if (w <= 0 || h <= 0) continue

                val nx = (x / viewWidth).coerceIn(0.0, 1.0)
                val ny = (y / viewHeight).coerceIn(0.0, 1.0)
                val nw = (w / viewWidth).coerceIn(0.01, 1.0 - nx)
                val nh = (h / viewHeight).coerceIn(0.01, 1.0 - ny)

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
            log.warn("Heuristic SVG detection error: {}", ex.message)
            emptyList()
        }
    }

    private data class SvgText(val text: String, val x: Double, val y: Double)

    private fun extractTexts(nodes: NodeList, viewW: Double, viewH: Double): List<SvgText> {
        val list = mutableListOf<SvgText>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val str = el.textContent?.trim() ?: continue
            if (str.isEmpty()) continue
            val x = el.getAttribute("x").toDoubleOrNull() ?: 0.0
            val y = el.getAttribute("y").toDoubleOrNull() ?: 0.0
            list.add(SvgText(str, (x / viewW).coerceIn(0.0, 1.0), (y / viewH).coerceIn(0.0, 1.0)))
        }
        return list
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
        v?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull() ?: 0.0
}
