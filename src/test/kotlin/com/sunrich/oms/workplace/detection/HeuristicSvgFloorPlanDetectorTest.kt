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

    @Test
    fun `ignores a raster plan rather than claiming it is empty markup`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)
        assertThat(detector.supports(plan(png, "image/png"))).isFalse()
        assertThat(detector.detect(plan(png, "image/png"))).isEmpty()
    }
}

