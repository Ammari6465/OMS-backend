package com.sunrich.oms.workplace.detection

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * The parser sees whatever a drawing tool exported, which is regularly not the
 * clean UTF-8 the declaration promises. These cover the encodings that were
 * silently turning a real floor plan into "no objects were recognised".
 */
class HeuristicSvgFloorPlanDetectorTest {

    private val detector = HeuristicSvgFloorPlanDetector()

    private fun plan(bytes: ByteArray, mediaType: String = "image/svg+xml") =
        PlanImage(bytes, mediaType, "floor.svg", 1000, 1000)

    /** Two labelled rooms and a desk-sized rect, in a 1000x1000 frame. */
    private fun svg(label: String = "Conference Room") = """
        <svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1000">
          <rect x="100" y="100" width="300" height="200"/>
          <text x="150" y="150">$label</text>
          <rect x="600" y="600" width="60" height="40"/>
        </svg>
    """.trimIndent()

    @Test
    fun `reads a plain UTF-8 plan`() {
        val found = detector.detect(plan(svg().toByteArray(StandardCharsets.UTF_8)))
        assertThat(found).isNotEmpty
        assertThat(found.map { it.type }).contains(DetectedObjectType.CONFERENCE_ROOM)
    }

    @Test
    fun `reads a plan whose declaration says UTF-8 but whose bytes are Windows-1252`() {
        // Exactly the export that produced "Invalid byte 1 of 1-byte UTF-8
        // sequence" in production: a curly quote or accent written as a single
        // high byte under a UTF-8 declaration.
        val body = """<?xml version="1.0" encoding="UTF-8"?>""" + svg("Café Meeting")
        val bytes = body.toByteArray(Charset.forName("windows-1252"))
        assertThat(bytes).contains(0xE9.toByte()) // the lone high byte that broke the parse

        val found = detector.detect(plan(bytes))

        assertThat(found).isNotEmpty
        assertThat(found.map { it.type }).contains(DetectedObjectType.MEETING_ROOM)
    }

    @Test
    fun `reads a plan with a UTF-8 byte order mark`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val found = detector.detect(plan(bom + svg().toByteArray(StandardCharsets.UTF_8)))
        assertThat(found).isNotEmpty
    }

    @Test
    fun `reads a UTF-16 plan`() {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val bytes = bom + svg().toByteArray(StandardCharsets.UTF_16LE)
        assertThat(detector.supports(plan(bytes))).isTrue()
        assertThat(detector.detect(plan(bytes))).isNotEmpty
    }

    @Test
    fun `reports an SVG it cannot parse instead of reporting an empty floor`() {
        val broken = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect x=\"1\"".toByteArray()
        assertThatThrownBy { detector.detect(plan(broken)) }
            .isInstanceOf(UnreadablePlanException::class.java)
            .hasMessageContaining("could not be read as SVG")
    }

    /**
     * The production crash: a plan at the 10MB upload limit was DOM-parsed
     * inside a heap-capped container, and the kernel killed the process
     * part-way through the scan. Streaming keeps the cost proportional to the
     * shapes kept, not the file, so this runs in a tight heap.
     */
    @Test
    fun `scans a plan at the upload size limit without exhausting memory`() {
        val body = StringBuilder(11 * 1024 * 1024)
        body.append("""<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1000">""")
        body.append("<rect x=\"100\" y=\"100\" width=\"300\" height=\"200\"/>")
        body.append("<text x=\"150\" y=\"150\">Conference Room</text>")
        // Line-work: the bulk of a real CAD export, and none of it is a room.
        while (body.length < 10 * 1024 * 1024) {
            body.append("<path d=\"M0,0 L10,10 L20,20 L30,30 L40,40 L50,50 L60,60 Z\"/>")
        }
        body.append("</svg>")
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        assertThat(bytes.size).isGreaterThan(10 * 1024 * 1024)

        val runtime = Runtime.getRuntime()
        System.gc()
        val before = runtime.totalMemory() - runtime.freeMemory()
        val found = detector.detect(plan(bytes))
        // Measure what the scan holds on to, not what it allocated on the way.
        // Transient garbage is collectible; a DOM was not, which is what put
        // the container over its limit.
        System.gc()
        val retained = runtime.totalMemory() - runtime.freeMemory() - before

        assertThat(found.map { it.type }).contains(DetectedObjectType.CONFERENCE_ROOM)
        assertThat(retained).isLessThan(bytes.size.toLong())
    }

    @Test
    fun `caps the shapes it keeps from a pathological plan`() {
        val body = StringBuilder()
        body.append("""<svg xmlns="http://www.w3.org/2000/svg" width="100000" height="100000">""")
        repeat(30_000) { body.append("""<rect x="$it" y="$it" width="50" height="50"/>""") }
        body.append("</svg>")

        val found = detector.detect(plan(body.toString().toByteArray(StandardCharsets.UTF_8)))

        assertThat(found.size).isLessThanOrEqualTo(20_000)
    }

    /**
     * A CAD export is the case that matters: walls and furniture arrive as
     * paths and polylines under transformed groups, and there is rarely a
     * single <rect> in the file.
     */
    @Test
    fun `reads rooms drawn as paths rather than rects`() {
        val cad = """
            <svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1000">
              <g>
                <path d="M 100 100 L 400 100 L 400 300 L 100 300 Z"/>
                <text x="150" y="150">Conference Room</text>
              </g>
            </svg>
        """.trimIndent()

        val found = detector.detect(plan(cad.toByteArray(StandardCharsets.UTF_8)))

        assertThat(found.map { it.type }).contains(DetectedObjectType.CONFERENCE_ROOM)
    }

    @Test
    fun `reads rooms drawn as polygons and polylines`() {
        val cad = """
            <svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1000">
              <polygon points="100,100 400,100 400,300 100,300"/>
              <polyline points="600,600 900,600 900,850 600,850"/>
            </svg>
        """.trimIndent()

        assertThat(detector.detect(plan(cad.toByteArray(StandardCharsets.UTF_8)))).hasSize(2)
    }

    @Test
    fun `applies group transforms so shapes land where they are drawn`() {
        // Without transform handling this room reports at the top-left corner
        // instead of the middle of the plan.
        val cad = """
            <svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1000">
              <g transform="translate(500 500) scale(2)">
                <rect x="0" y="0" width="100" height="100"/>
              </g>
            </svg>
        """.trimIndent()

        val found = detector.detect(plan(cad.toByteArray(StandardCharsets.UTF_8)))

        assertThat(found).hasSize(1)
        val box = found.single().polygon
        assertThat(box.minX).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.01))
        assertThat(box.minY).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.01))
        assertThat(box.width).isCloseTo(0.2, org.assertj.core.data.Offset.offset(0.01))
    }

    @Test
    fun `folds a viewBox origin into the frame`() {
        val cad = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="1000 1000 1000 1000">
              <rect x="1100" y="1100" width="300" height="200"/>
            </svg>
        """.trimIndent()

        val found = detector.detect(plan(cad.toByteArray(StandardCharsets.UTF_8)))

        assertThat(found).hasSize(1)
        assertThat(found.single().polygon.minX)
            .isCloseTo(0.1, org.assertj.core.data.Offset.offset(0.01))
    }

    @Test
    fun `takes the largest subpath when an exporter merges a layer into one path`() {
        // One <path> holding a room outline plus a scattering of furniture
        // line-work must not report the bounding box of the whole floor.
        val cad = """
            <svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1000">
              <path d="M 10 10 L 20 10 L 20 20 L 10 20 Z M 100 100 L 400 100 L 400 300 L 100 300 Z M 900 900 L 910 900 L 910 910 Z"/>
            </svg>
        """.trimIndent()

        val found = detector.detect(plan(cad.toByteArray(StandardCharsets.UTF_8)))

        assertThat(found).hasSize(1)
        val box = found.single().polygon
        assertThat(box.minX).isCloseTo(0.1, org.assertj.core.data.Offset.offset(0.01))
        assertThat(box.width).isCloseTo(0.3, org.assertj.core.data.Offset.offset(0.01))
    }

    /**
     * The clutter case. A CAD plan draws the same region several times — a fill
     * behind its stroke, an outline repeated across layers — and emitting each
     * one buries the floor under stacked boxes.
     */
    @Test
    fun `collapses a region drawn more than once into a single object`() {
        val duplicated = """
            <svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1000">
              <rect x="100" y="100" width="300" height="200"/>
              <rect x="102" y="101" width="298" height="199"/>
              <path d="M 100 100 L 400 100 L 400 300 L 100 300 Z"/>
            </svg>
        """.trimIndent()

        assertThat(detector.detect(plan(duplicated.toByteArray(StandardCharsets.UTF_8)))).hasSize(1)
    }

    @Test
    fun `keeps a desk that sits inside a room, because nesting is real`() {
        val nested = """
            <svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1000">
              <rect x="100" y="100" width="300" height="200"/>
              <rect x="150" y="150" width="90" height="60"/>
            </svg>
        """.trimIndent()

        val found = detector.detect(plan(nested.toByteArray(StandardCharsets.UTF_8)))

        assertThat(found.map { it.type })
            .containsExactlyInAnyOrder(DetectedObjectType.CABIN, DetectedObjectType.DESK)
    }

    @Test
    fun `ignores the sheet border and the hairlines, keeping what is between`() {
        val sheet = """
            <svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1000">
              <rect x="0" y="0" width="1000" height="1000"/>
              <rect x="10" y="10" width="6" height="6"/>
              <rect x="400" y="400" width="90" height="60"/>
            </svg>
        """.trimIndent()

        val found = detector.detect(plan(sheet.toByteArray(StandardCharsets.UTF_8)))

        // The border covers everything and the hairline covers nothing; only
        // the desk-sized rectangle is a real object.
        assertThat(found.map { it.type }).containsExactly(DetectedObjectType.DESK)
    }

    /**
     * A desk tag printed on a drawing border must not turn the border into a
     * desk. Size decides the type; the label only refines it.
     */
    @Test
    fun `does not let a desk tag reclassify the shape that encloses the page`() {
        val tagged = """
            <svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1000">
              <rect x="0" y="0" width="980" height="980"/>
              <text x="20" y="20">A01</text>
            </svg>
        """.trimIndent()

        assertThat(detector.detect(plan(tagged.toByteArray(StandardCharsets.UTF_8)))).isEmpty()
    }

    @Test
    fun `a label names the smallest shape enclosing it, not the outermost`() {
        val nested = """
            <svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1000">
              <rect x="100" y="100" width="600" height="400"/>
              <rect x="150" y="150" width="250" height="200"/>
              <text x="200" y="200">Conference Room</text>
            </svg>
        """.trimIndent()

        val found = detector.detect(plan(nested.toByteArray(StandardCharsets.UTF_8)))
        val conference = found.single { it.type == DetectedObjectType.CONFERENCE_ROOM }

        // The inner room carries the label; the outer block stays a zone.
        assertThat(conference.polygon.width).isCloseTo(0.25, org.assertj.core.data.Offset.offset(0.01))
    }

    @Test
    fun `drops furniture symbols that do not match the size the desks repeat at`() {
        val body = StringBuilder("""<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1000">""")
        // Twelve desks on a regular grid, all the same size.
        repeat(12) { body.append("""<rect x="${100 + it * 60}" y="200" width="50" height="40"/>""") }
        // Inside the desk size band, but three times the area the desks repeat
        // at — a furniture symbol, not another workstation.
        body.append("""<rect x="100" y="600" width="90" height="80"/>""")
        body.append("</svg>")

        val found = detector.detect(plan(body.toString().toByteArray(StandardCharsets.UTF_8)))

        assertThat(found).hasSize(12)
    }

    /**
     * Modelled on a plan a user actually uploaded: an image auto-traced into
     * SVG. 2857 filled curves, not one straight segment, no text. Nothing in it
     * maps to a room or a desk, and the geometry heuristics happily produced
     * 133 confident boxes of nonsense over the top of the plan.
     *
     * Refusing is the useful answer — a floor covered in wrong objects has to
     * be cleared by hand before it can be done properly.
     */
    @Test
    fun `refuses an auto-traced bitmap instead of inventing objects from it`() {
        val body = StringBuilder("""<svg xmlns="http://www.w3.org/2000/svg" width="2752" height="1566">""")
        repeat(300) { i ->
            val x = 100 + i * 3
            // All curves, no lines: the shape of a tracer's output.
            body.append("""<path d="M$x 100 C${x + 40} 100 ${x + 60} 140 ${x + 60} 180 C${x + 60} 220 ${x + 20} 240 $x 240 Z" fill="#FCFCFC"/>""")
        }
        body.append("</svg>")

        assertThatThrownBy { detector.detect(plan(body.toString().toByteArray(StandardCharsets.UTF_8))) }
            .isInstanceOf(UnreadablePlanException::class.java)
            .hasMessageContaining("auto-traced")
            .hasMessageContaining("PNG or JPEG")
    }

    @Test
    fun `still reads a curved but properly drafted plan that keeps its labels`() {
        // Curves alone are not the signal — a real drawing has straight walls
        // and live text, and must not be caught by the tracer guard.
        val body = StringBuilder("""<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1000">""")
        repeat(200) { body.append("""<path d="M100 100 C150 100 200 140 200 180 L200 300 L100 300 Z"/>""") }
        body.append("""<text x="150" y="150">Conference Room</text></svg>""")

        assertThat(detector.detect(plan(body.toString().toByteArray(StandardCharsets.UTF_8)))).isNotEmpty
    }

    @Test
    fun `ignores a raster plan rather than claiming it is empty markup`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)
        assertThat(detector.supports(plan(png, "image/png"))).isFalse()
        assertThat(detector.detect(plan(png, "image/png"))).isEmpty()
    }
}

