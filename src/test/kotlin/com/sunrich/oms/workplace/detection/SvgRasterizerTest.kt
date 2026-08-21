package com.sunrich.oms.workplace.detection

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import javax.imageio.ImageIO

/**
 * Rasterising is the only route a vector plan has to a vision engine, which
 * reads images and cannot open markup. Without it an SVG upload that the
 * built-in parser cannot interpret has nowhere else to go.
 */
class SvgRasterizerTest {

    private val rasterizer = SvgRasterizer(maxEdge = 400)

    private fun plan(svg: String, width: Int? = null, height: Int? = null) =
        PlanImage(svg.toByteArray(StandardCharsets.UTF_8), "image/svg+xml", "floor.svg", width, height)

    private val simple = """
        <svg xmlns="http://www.w3.org/2000/svg" width="800" height="600">
          <rect x="100" y="100" width="300" height="200" fill="#eeeeee" stroke="#000"/>
          <text x="120" y="130" font-size="18">Conference Room</text>
        </svg>
    """.trimIndent()

    @Test
    fun `renders a vector plan to a PNG the vision engine will accept`() {
        val png = rasterizer.toPng(plan(simple, 800, 600))

        assertThat(png).isNotNull
        assertThat(png!!.mediaType).isEqualTo("image/png")
        assertThat(VISION_TYPES).contains(png.mediaType)
        val decoded = ImageIO.read(png.bytes.inputStream())
        assertThat(decoded).isNotNull
        assertThat(maxOf(decoded.width, decoded.height)).isLessThanOrEqualTo(400)
    }

    @Test
    fun `keeps the plan's aspect ratio so overlay coordinates still line up`() {
        val decoded = ImageIO.read(rasterizer.toPng(plan(simple, 800, 600))!!.bytes.inputStream())

        assertThat(decoded.width.toDouble() / decoded.height)
            .isCloseTo(800.0 / 600.0, org.assertj.core.data.Offset.offset(0.02))
    }

    @Test
    fun `renders on a white ground so ink on transparency does not come out black`() {
        val decoded = ImageIO.read(rasterizer.toPng(plan(simple, 800, 600))!!.bytes.inputStream())

        // A corner of the sheet carries no drawing and must be paper, not void.
        assertThat(java.awt.Color(decoded.getRGB(2, 2)).red).isGreaterThan(200)
    }

    @Test
    fun `reads a plan whose declared encoding does not match its bytes`() {
        // The same repair SvgSource makes for the parser has to apply here, or
        // rasterising fails on exactly the files that most need it.
        val body = """<?xml version="1.0" encoding="UTF-8"?>""" +
            simple.replace("Conference Room", "Café Meeting")
        val bytes = body.toByteArray(java.nio.charset.Charset.forName("windows-1252"))

        assertThat(rasterizer.toPng(PlanImage(bytes, "image/svg+xml", "floor.svg", 800, 600))).isNotNull
    }

    @Test
    fun `returns null rather than throwing when the markup cannot be rendered`() {
        // A failure must leave the caller with whatever the parser found, not
        // take the whole scan down.
        assertThat(rasterizer.toPng(plan("<svg><rect x='1'"))).isNull()
    }

    private companion object {
        val VISION_TYPES = setOf("image/png", "image/jpeg")
    }
}
