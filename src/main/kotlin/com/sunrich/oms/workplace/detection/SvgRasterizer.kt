package com.sunrich.oms.workplace.detection

import org.apache.batik.transcoder.TranscoderException
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream

/**
 * Renders an SVG plan to a PNG so a vision engine can read it.
 *
 * Vision models take images; a vector plan is markup. Without this step an SVG
 * upload has no route to detection at all — the SVG parser is the only engine
 * that can open it, and on a plan it cannot interpret (an auto-traced bitmap,
 * or a drawing with no rectangles) the answer is simply "nothing found". Which
 * left a real floor plan undetectable purely because of the format it arrived
 * in.
 *
 * Rendering is capped by [MAX_EDGE]: plans arrive up to the 10MB upload limit,
 * and an unbounded raster of one is both slow and, on a memory-constrained
 * container, fatal.
 */
class SvgRasterizer(private val maxEdge: Int = MAX_EDGE) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Returns the plan as a PNG image, or null if it could not be rendered.
     *
     * A failure here is not fatal: the caller still has whatever the SVG parser
     * made of the file, so rasterising is an added route rather than a
     * replacement one.
     */
    fun toPng(image: PlanImage): PlanImage? {
        val started = System.currentTimeMillis()
        return try {
            val output = ByteArrayOutputStream(INITIAL_BUFFER)
            val transcoder = PNGTranscoder().apply {
                // Constrain the longest edge. Width alone would let a tall plan
                // render at an unbounded height.
                val width = image.width ?: 0
                val height = image.height ?: 0
                if (width > 0 && height > 0 && (width > maxEdge || height > maxEdge)) {
                    if (width >= height) addTranscodingHint(PNGTranscoder.KEY_WIDTH, maxEdge.toFloat())
                    else addTranscodingHint(PNGTranscoder.KEY_HEIGHT, maxEdge.toFloat())
                } else {
                    addTranscodingHint(PNGTranscoder.KEY_MAX_WIDTH, maxEdge.toFloat())
                    addTranscodingHint(PNGTranscoder.KEY_MAX_HEIGHT, maxEdge.toFloat())
                }
                // Plans are drawn for paper: ink on white. Without a background
                // the transparent areas render black and the drawing vanishes.
                addTranscodingHint(PNGTranscoder.KEY_BACKGROUND_COLOR, java.awt.Color.WHITE)
            }
            // Parse from decoded text so the encoding repairs in SvgSource apply
            // here too; Batik reading raw bytes would fail on the same files.
            SvgSource.reader(image.bytes).use { reader ->
                transcoder.transcode(TranscoderInput(reader), TranscoderOutput(output))
            }
            val png = output.toByteArray()
            if (png.isEmpty()) return null
            log.info(
                "Rasterised {} ({} KB SVG) to {} KB PNG in {} ms",
                image.originalName, image.bytes.size / 1024, png.size / 1024,
                System.currentTimeMillis() - started
            )
            PlanImage(png, "image/png", image.originalName, image.width, image.height)
        } catch (ex: TranscoderException) {
            log.warn("Could not rasterise {}: {}", image.originalName, ex.message)
            null
        } catch (ex: Exception) {
            // Batik reaches for AWT, fonts and image codecs; a headless
            // container can fail in ways that are not TranscoderException.
            log.warn("Rasterising {} failed unexpectedly", image.originalName, ex)
            null
        }
    }

    private companion object {
        /**
         * Longest rendered edge. Matches the preprocessor's own ceiling, so a
         * plan is not rendered large only to be scaled straight back down.
         */
        const val MAX_EDGE = 2200
        const val INITIAL_BUFFER = 512 * 1024
    }
}
