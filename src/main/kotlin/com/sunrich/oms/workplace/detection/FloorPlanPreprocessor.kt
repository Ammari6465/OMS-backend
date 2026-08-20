package com.sunrich.oms.workplace.detection

import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reduces an evacuation map to the architectural drawing underneath it.
 *
 * Evacuation maps carry a lot of material that is not the building: a tinted
 * background, a company logo, a title band, a legend, a compass, green egress
 * arrows, extinguisher and break-glass icons, and a "YOU ARE HERE" marker. A
 * detector fed the raw image spends its attention on those.
 *
 * The separation used here is colour saturation, not shape matching. Building
 * fabric — walls, doors, desks, chairs, tables, fixtures — is drawn in black or
 * neutral grey on white. Every decorative element is coloured: the arrows are
 * green, the fire symbols red, the background and title band blue. Discarding
 * saturated pixels therefore removes the decoration and keeps the drawing,
 * without needing to recognise any of it first.
 *
 * The result is a clean black-and-white image at the original aspect ratio and
 * orientation. No geometry is moved, mirrored, rotated or rescaled beyond an
 * optional uniform downscale, so coordinates map straight back onto the plan.
 */
class FloorPlanPreprocessor(
    /** Above this saturation a pixel is decoration rather than linework. */
    private val saturationThreshold: Double = 0.22,
    /** Below this brightness a neutral pixel is ink. */
    private val inkThreshold: Double = 0.62,
    /** Longest edge of the output. Caps cost without distorting the drawing. */
    private val maxEdge: Int = 2200
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Returns a cleaned PNG, or null when the image cannot be decoded — callers
     * then fall back to the original bytes rather than losing the run.
     */
    fun clean(image: PlanImage): PlanImage? {
        val source = runCatching { ImageIO.read(image.bytes.inputStream()) }.getOrNull()
        if (source == null) {
            log.warn("Floor plan {} could not be decoded for preprocessing", image.originalName)
            return null
        }
        val scaled = downscale(source)
        val binary = binarise(scaled)
        val out = ByteArrayOutputStream()
        if (!ImageIO.write(binary, "png", out)) return null
        log.info(
            "Preprocessed {} from {}x{} to {}x{} ({} -> {} bytes)",
            image.originalName, source.width, source.height, binary.width, binary.height,
            image.bytes.size, out.size()
        )
        return image.copy(
            bytes = out.toByteArray(),
            mediaType = "image/png",
            width = binary.width,
            height = binary.height
        )
    }

    /** Uniform downscale only — aspect ratio and orientation are preserved. */
    private fun downscale(source: BufferedImage): BufferedImage {
        val longest = max(source.width, source.height)
        if (longest <= maxEdge) return source
        val ratio = maxEdge.toDouble() / longest
        val width = max(1, (source.width * ratio).roundToInt())
        val height = max(1, (source.height * ratio).roundToInt())
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        graphics.drawImage(source.getScaledInstance(width, height, BufferedImage.SCALE_SMOOTH), 0, 0, null)
        graphics.dispose()
        return target
    }

    private fun binarise(source: BufferedImage): BufferedImage {
        val target = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                target.setRGB(x, y, if (isInk(source.getRGB(x, y))) BLACK else WHITE)
            }
        }
        return target
    }

    /** Neutral and dark enough to be linework, rather than coloured decoration. */
    private fun isInk(argb: Int): Boolean {
        val r = (argb shr 16 and 0xFF) / 255.0
        val g = (argb shr 8 and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        val saturation = if (maxChannel <= 0.0) 0.0 else (maxChannel - minChannel) / maxChannel
        if (saturation > saturationThreshold) return false
        return maxChannel < inkThreshold
    }

    private companion object {
        const val BLACK = 0x000000
        const val WHITE = 0xFFFFFF
    }
}
