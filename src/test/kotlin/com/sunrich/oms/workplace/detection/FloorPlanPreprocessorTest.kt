package com.sunrich.oms.workplace.detection

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * The evacuation map's decoration is coloured and its architecture is not, so
 * these cases pin that separation down with the actual colours involved.
 */
class FloorPlanPreprocessorTest {

    private val preprocessor = FloorPlanPreprocessor()

    @Test
    fun `keeps walls and furniture, drops evacuation decoration`() {
        val source = BufferedImage(9, 1, BufferedImage.TYPE_INT_RGB)
        val pixels = listOf(
            Color.BLACK to true,                  // exterior wall
            Color(40, 40, 42) to true,            // interior wall, near-black
            Color(110, 112, 115) to true,         // furniture linework, mid grey
            Color(255, 255, 255) to false,        // paper
            Color(198, 224, 245) to false,        // blue gradient background
            Color(0, 158, 74) to false,           // green evacuation arrow
            Color(214, 33, 39) to false,          // fire extinguisher / break glass
            Color(206, 17, 38) to false,          // Sunrich logo red
            Color(23, 54, 122) to false           // title band navy
        )
        pixels.forEachIndexed { x, (colour, _) -> source.setRGB(x, 0, colour.rgb) }

        val cleaned = decode(preprocessor.clean(plan(source))!!)

        pixels.forEachIndexed { x, (colour, expectInk) ->
            val isInk = Color(cleaned.getRGB(x, 0)).red < 128
            assertThat(isInk)
                .withFailMessage("pixel %s (%s) should%s be kept as linework", x, colour, if (expectInk) "" else " not")
                .isEqualTo(expectInk)
        }
    }

    @Test
    fun `output is pure black and white`() {
        val source = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 4) for (x in 0 until 4) source.setRGB(x, y, Color(x * 60, y * 60, 128).rgb)

        val cleaned = decode(preprocessor.clean(plan(source))!!)

        val distinct = (0 until 4).flatMap { y -> (0 until 4).map { x -> cleaned.getRGB(x, y) and 0xFFFFFF } }.toSet()
        assertThat(distinct).isSubsetOf(setOf(0x000000, 0xFFFFFF))
    }

    @Test
    fun `geometry is preserved - never mirrored, rotated, or stretched`() {
        // An L of ink in the top-left must stay in the top-left, same shape.
        val source = white(40, 20)
        for (x in 0 until 10) source.setRGB(x, 2, Color.BLACK.rgb)
        for (y in 2 until 8) source.setRGB(2, y, Color.BLACK.rgb)

        val result = preprocessor.clean(plan(source))!!
        val cleaned = decode(result)

        assertThat(cleaned.width).isEqualTo(40)
        assertThat(cleaned.height).isEqualTo(20)
        assertThat(result.width).isEqualTo(40)
        for (x in 0 until 10) assertThat(Color(cleaned.getRGB(x, 2)).red).isLessThan(128)
        for (y in 2 until 8) assertThat(Color(cleaned.getRGB(2, y)).red).isLessThan(128)
        // The mirrored positions must stay blank.
        assertThat(Color(cleaned.getRGB(39, 17)).red).isEqualTo(255)
    }

    @Test
    fun `large plans are downscaled uniformly so coordinates still map back`() {
        val result = preprocessor.clean(plan(white(6000, 3000)))!!

        assertThat(maxOf(result.width!!, result.height!!)).isEqualTo(2200)
        // 2:1 in, 2:1 out — a non-uniform scale would misplace every overlay.
        assertThat(result.width!!.toDouble() / result.height!!).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.01))
    }

    @Test
    fun `a decorated plan loses its colour but keeps its walls`() {
        // A miniature evacuation map: tinted ground, a green arrow, a red icon,
        // and the room outline that actually matters.
        val source = BufferedImage(30, 30, BufferedImage.TYPE_INT_RGB)
        source.createGraphics().run {
            paint = Color(198, 224, 245); fillRect(0, 0, 30, 30)          // background wash
            paint = Color.WHITE; fillRect(4, 4, 22, 22)                    // floor
            paint = Color.BLACK; drawRect(4, 4, 22, 22)                    // walls
            paint = Color(0, 158, 74); fillRect(10, 10, 6, 3)              // egress arrow
            paint = Color(214, 33, 39); fillOval(18, 18, 4, 4)             // extinguisher
            dispose()
        }

        val cleaned = decode(preprocessor.clean(plan(source))!!)

        fun ink(x: Int, y: Int) = Color(cleaned.getRGB(x, y)).red < 128
        assertThat(ink(4, 4)).withFailMessage("wall corner must survive").isTrue()
        assertThat(ink(15, 4)).withFailMessage("wall run must survive").isTrue()
        assertThat(ink(0, 0)).withFailMessage("background wash must go").isFalse()
        assertThat(ink(12, 11)).withFailMessage("egress arrow must go").isFalse()
        assertThat(ink(20, 20)).withFailMessage("fire icon must go").isFalse()
    }

    @Test
    fun `undecodable bytes return null so the run falls back to the original`() {
        val broken = PlanImage("not an image".toByteArray(), "image/png", "broken.png", null, null)

        assertThat(preprocessor.clean(broken)).isNull()
    }

    private fun white(width: Int, height: Int) = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().run { paint = Color.WHITE; fillRect(0, 0, width, height); dispose() }
    }

    private fun plan(image: BufferedImage): PlanImage {
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return PlanImage(out.toByteArray(), "image/png", "evacuation-map.png", image.width, image.height)
    }

    private fun decode(image: PlanImage): BufferedImage = ImageIO.read(image.bytes.inputStream())
}
