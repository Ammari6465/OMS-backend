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
 * Additional filters handle edge cases that pure saturation misses:
 * - **Blue dominance**: near-neutral blue-grey from desaturated gradients
 * - **Margin crop**: title bands, logos, and legends outside the floor plan
 * - **Morphological close**: reconnects thin wall lines fragmented by the filter
 * - **Isolation filter**: removes small stray decoration remnants
 *
 * The result is a clean black-and-white image at the original aspect ratio and
 * orientation. No geometry is moved, mirrored, rotated or rescaled beyond an
 * optional uniform downscale, so coordinates map straight back onto the plan.
 */
/**
 * A cleaned plan plus the window it occupies in the original image.
 *
 * Cropping the margins changes what "0..1" means: a coordinate in the cleaned
 * image is not the same coordinate in the plan the user uploaded. Overlays are
 * drawn on the original, so detections must be mapped back through this window
 * or every region lands offset and undersized.
 */
data class PreparedPlan(
    val image: PlanImage,
    val cropX: Double = 0.0,
    val cropY: Double = 0.0,
    val cropWidth: Double = 1.0,
    val cropHeight: Double = 1.0
) {
    val isCropped get() = cropX != 0.0 || cropY != 0.0 || cropWidth != 1.0 || cropHeight != 1.0

    /** Maps a point in the cleaned image back onto the original plan. */
    fun toOriginal(point: Point) =
        Point(cropX + point.x * cropWidth, cropY + point.y * cropHeight)

    fun toOriginal(polygon: Polygon): Polygon =
        if (!isCropped) polygon else Polygon.ofClamped(polygon.points.map(::toOriginal))
}

class FloorPlanPreprocessor(
    /** Above this saturation a pixel is decoration rather than linework. */
    private val saturationThreshold: Double = 0.22,
    /**
     * Below this brightness a neutral pixel is ink. Raised from 0.62 to retain
     * mid-grey furniture and thin room dividers drawn at brightness 0.5–0.72.
     */
    private val inkThreshold: Double = 0.78,
    /** Longest edge of the output. Caps cost without distorting the drawing. */
    private val maxEdge: Int = 2200,
    /**
     * When the blue channel exceeds both red and green by at least this margin,
     * the pixel is classified as decoration regardless of saturation. This
     * catches the desaturated edges of blue background gradients.
     */
    private val blueChannelMargin: Double = 0.08,
    /**
     * Connected ink clusters smaller than this fraction of total pixel count
     * are removed. Catches stray decoration remnants (fire symbols, break-glass
     * icons) whose black outlines survive saturation filtering.
     */
    private val minClusterFraction: Double = 0.0001,
    /**
     * Fraction of image margin to scan for uniform white. Pixels in this zone
     * after binarisation are cropped if the margin is predominantly empty,
     * removing title bands, logos, and legends.
     */
    private val marginScanFraction: Double = 0.08
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Returns a cleaned PNG, or null when the image cannot be decoded — callers
     * then fall back to the original bytes rather than losing the run.
     */
    fun clean(image: PlanImage): PreparedPlan? {
        val source = runCatching { ImageIO.read(image.bytes.inputStream()) }.getOrNull()
        if (source == null) {
            log.warn("Floor plan {} could not be decoded for preprocessing", image.originalName)
            return null
        }
        val scaled = downscale(source)
        var binary = binarise(scaled)
        // Advanced filters only help on real floor plans; they can destroy tiny
        // synthetic test images where the line is a single pixel wide.
        var window = Window(0.0, 0.0, 1.0, 1.0)
        if (binary.width > MIN_FILTER_SIZE && binary.height > MIN_FILTER_SIZE) {
            binary = morphologicalClose(binary)
            binary = removeSmallClusters(binary)
            // The downscale is uniform, so normalised coordinates are unchanged
            // by it; only the crop shifts the frame, and that shift is recorded
            // so detections can be mapped back onto the uploaded plan.
            val cropped = cropMargins(binary)
            binary = cropped.image
            window = cropped.window
        }
        val out = ByteArrayOutputStream()
        if (!ImageIO.write(binary, "png", out)) return null
        log.info(
            "Preprocessed {} from {}x{} to {}x{} (crop {}, {} -> {} bytes)",
            image.originalName, source.width, source.height, binary.width, binary.height,
            window, image.bytes.size, out.size()
        )
        return PreparedPlan(
            image = image.copy(
                bytes = out.toByteArray(),
                mediaType = "image/png",
                width = binary.width,
                height = binary.height
            ),
            cropX = window.x, cropY = window.y, cropWidth = window.width, cropHeight = window.height
        )
    }

    /** Crop window as fractions of the pre-crop image. */
    private data class Window(val x: Double, val y: Double, val width: Double, val height: Double) {
        override fun toString() = "%.3f,%.3f %.3fx%.3f".format(x, y, width, height)
    }

    private data class CroppedImage(val image: BufferedImage, val window: Window)

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

    /**
     * Neutral and dark enough to be linework, rather than coloured decoration.
     * Additional blue-dominance check catches desaturated gradient edges that
     * pass the saturation filter.
     */
    private fun isInk(argb: Int): Boolean {
        val r = (argb shr 16 and 0xFF) / 255.0
        val g = (argb shr 8 and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        val saturation = if (maxChannel <= 0.0) 0.0 else (maxChannel - minChannel) / maxChannel

        // Blue-dominant pixels are gradient remnants, not linework.
        if (b > r + blueChannelMargin && b > g + blueChannelMargin) return false

        if (saturation > saturationThreshold) return false
        return maxChannel < inkThreshold
    }

    /**
     * 3×3 morphological close: dilate then erode. Reconnects thin wall lines
     * that the saturation filter may have fragmented where a coloured pixel sat
     * on a wall edge.
     */
    private fun morphologicalClose(source: BufferedImage): BufferedImage {
        return erode(dilate(source))
    }

    private fun dilate(source: BufferedImage): BufferedImage {
        val target = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                target.setRGB(x, y, if (hasNeighbourInk(source, x, y)) BLACK else WHITE)
            }
        }
        return target
    }

    private fun erode(source: BufferedImage): BufferedImage {
        val target = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                target.setRGB(x, y, if (allNeighboursInk(source, x, y)) BLACK else WHITE)
            }
        }
        return target
    }

    /** True if any pixel in the 3×3 neighbourhood is ink. */
    private fun hasNeighbourInk(img: BufferedImage, cx: Int, cy: Int): Boolean {
        for (dy in -1..1) for (dx in -1..1) {
            val nx = cx + dx
            val ny = cy + dy
            if (nx in 0 until img.width && ny in 0 until img.height && (img.getRGB(nx, ny) and 0xFFFFFF) == BLACK) {
                return true
            }
        }
        return false
    }

    /** True if every pixel in the 3×3 neighbourhood is ink. */
    private fun allNeighboursInk(img: BufferedImage, cx: Int, cy: Int): Boolean {
        for (dy in -1..1) for (dx in -1..1) {
            val nx = cx + dx
            val ny = cy + dy
            if (nx !in 0 until img.width || ny !in 0 until img.height) return false
            if ((img.getRGB(nx, ny) and 0xFFFFFF) != BLACK) return false
        }
        return true
    }

    /**
     * Flood-fill connected component analysis. Removes ink clusters smaller
     * than [minClusterFraction] of total pixels. This catches isolated
     * decoration remnants — fire extinguisher icons, break-glass symbols — whose
     * black outlines survived the saturation filter but whose small size reveals
     * them as non-architectural.
     */
    private fun removeSmallClusters(source: BufferedImage): BufferedImage {
        val w = source.width
        val h = source.height
        val totalPixels = w * h
        val minSize = (totalPixels * minClusterFraction).toInt().coerceAtLeast(4)

        // Label each ink pixel with a cluster id.
        val labels = IntArray(totalPixels) { -1 }
        val isInk = BooleanArray(totalPixels) { (source.getRGB(it % w, it / w) and 0xFFFFFF) == BLACK }
        val clusterSizes = mutableListOf<Int>()
        var nextLabel = 0
        val queue = ArrayDeque<Int>(256)

        for (i in 0 until totalPixels) {
            if (!isInk[i] || labels[i] >= 0) continue
            val label = nextLabel++
            var size = 0
            queue.addLast(i)
            labels[i] = label
            while (queue.isNotEmpty()) {
                val p = queue.removeFirst()
                size++
                val px = p % w
                val py = p / w
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = px + dx
                    val ny = py + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val ni = ny * w + nx
                    if (isInk[ni] && labels[ni] < 0) {
                        labels[ni] = label
                        queue.addLast(ni)
                    }
                }
            }
            clusterSizes.add(size)
        }

        val target = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val label = labels[idx]
                val keep = isInk[idx] && label >= 0 && clusterSizes[label] >= minSize
                target.setRGB(x, y, if (keep) BLACK else WHITE)
            }
        }
        return target
    }

    /**
     * Crops uniform-white margins to remove title bands, logos, legends, and
     * compass roses that sit outside the floor plan footprint. Scans inward
     * from each edge and trims rows/columns that are entirely white.
     */
    private fun cropMargins(source: BufferedImage): CroppedImage {
        val w = source.width
        val h = source.height
        val whole = CroppedImage(source, Window(0.0, 0.0, 1.0, 1.0))
        // At minimum keep 60% of each dimension to prevent over-cropping.
        val minMarginX = (w * marginScanFraction).toInt().coerceAtMost(w / 5)
        val minMarginY = (h * marginScanFraction).toInt().coerceAtMost(h / 5)

        var top = 0
        while (top < minMarginY && isRowWhite(source, top)) top++

        var bottom = h - 1
        while (bottom > h - 1 - minMarginY && isRowWhite(source, bottom)) bottom--

        var left = 0
        while (left < minMarginX && isColumnWhite(source, left, top, bottom)) left++

        var right = w - 1
        while (right > w - 1 - minMarginX && isColumnWhite(source, right, top, bottom)) right--

        val cropW = right - left + 1
        val cropH = bottom - top + 1
        if (cropW < w * 0.6 || cropH < h * 0.6) {
            // Cropping would remove too much — the plan probably fills the image.
            return whole
        }
        if (cropW == w && cropH == h) return whole

        return CroppedImage(
            source.getSubimage(left, top, cropW, cropH),
            Window(left.toDouble() / w, top.toDouble() / h, cropW.toDouble() / w, cropH.toDouble() / h)
        )
    }

    private fun isRowWhite(img: BufferedImage, y: Int): Boolean {
        for (x in 0 until img.width) {
            if ((img.getRGB(x, y) and 0xFFFFFF) != WHITE) return false
        }
        return true
    }

    private fun isColumnWhite(img: BufferedImage, x: Int, topY: Int, bottomY: Int): Boolean {
        for (y in topY..bottomY) {
            if ((img.getRGB(x, y) and 0xFFFFFF) != WHITE) return false
        }
        return true
    }

    private companion object {
        const val BLACK = 0x000000
        const val WHITE = 0xFFFFFF
        /** Advanced filters (morphology, isolation, margin crop) only kick in above this size. */
        const val MIN_FILTER_SIZE = 100
    }
}
