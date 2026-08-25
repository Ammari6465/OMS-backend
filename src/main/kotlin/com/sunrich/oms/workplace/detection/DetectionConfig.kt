package com.sunrich.oms.workplace.detection

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

/**
 * Selects the active detection engine. The seam is deliberately a single bean,
 * so swapping in OpenCV, YOLO or a CAD parser later means adding one class and
 * one branch here — nothing else in the module changes.
 *
 * When the vision detector returns very few objects (< 3), the result is likely
 * a truncated response or the preprocessor stripped too much, so the composite
 * retries with the original unpreprocessed image before giving up.
 */
@Configuration
@EnableConfigurationProperties(DetectionProperties::class)
class DetectionConfig {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    @Bean
    fun floorPlanDetector(
        props: DetectionProperties,
        restClientBuilder: RestClient.Builder,
        mapper: ObjectMapper
    ): FloorPlanDetector {
        val heuristic = HeuristicSvgFloorPlanDetector()
        val rasterizer = SvgRasterizer()
        val vision = if (props.provider.equals("vision", ignoreCase = true) && props.apiKey.isNotBlank()) {
            // Apply the configured provider timeout as both a connection and a
            // response (read) timeout on a cloned builder, so a hung vision
            // endpoint fails fast instead of holding the scan open indefinitely.
            val timedBuilder = restClientBuilder.clone().requestFactory(
                org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(java.time.Duration.ofSeconds(props.timeoutSeconds.coerceIn(1, 30)))
                    setReadTimeout(java.time.Duration.ofSeconds(props.timeoutSeconds.coerceAtLeast(1)))
                }
            )
            VisionFloorPlanDetector(props, timedBuilder, mapper)
        } else null

        return object : FloorPlanDetector {
            override val name: String get() = vision?.name ?: heuristic.name

            // The heuristic engine is always present, so something can always
            // run. Reporting a blanket `true` here, though, meant the service's
            // "recognition is not configured" message could never fire and an
            // unconfigured deployment only ever explained itself through
            // downstream errors about individual files.
            override val available: Boolean get() = heuristic.available || vision?.available == true

            override val readableMediaTypes: Set<String>
                get() = heuristic.readableMediaTypes + (vision?.readableMediaTypes ?: emptySet())

            /** Readable if either engine can actually open this file. */
            override fun supports(image: PlanImage) =
                heuristic.supports(image) || vision?.supports(image) == true

            override fun detect(image: PlanImage): List<DetectionCandidate> {
                var forVision = image
                if (heuristic.supports(image)) {
                    // A broken or uninterpretable SVG is worth reporting rather
                    // than swallowing — but only once rasterising has been tried,
                    // because a vision engine can read a picture of the plan even
                    // when the markup itself carries no usable structure.
                    val heuristicResults = try {
                        heuristic.detect(image)
                    } catch (ex: UnreadablePlanException) {
                        forVision = rasterize(image) ?: throw ex
                        log.info("SVG unusable ({}); rasterised it for the vision detector", ex.message)
                        emptyList()
                    }
                    if (heuristicResults.isNotEmpty()) return heuristicResults
                    // Parsed cleanly but found nothing worth showing. A vector
                    // plan drawn without rectangles looks identical to an empty
                    // one here, so give vision the rendered image too.
                    if (forVision === image && vision != null) {
                        forVision = rasterize(image) ?: image
                    }
                }
                if (vision == null) return emptyList()
                if (!vision.supports(forVision)) return emptyList()

                val result = vision.detect(forVision)
                if (result.size >= MIN_OBJECTS) return result

                // Too few objects — the preprocessor may have been too aggressive
                // or the response was truncated. Retry with preprocessing off, but
                // as a request-local flag: mutating the shared props.preprocess here
                // would flip preprocessing off for every concurrent scan mid-flight.
                if (props.preprocess && result.size < MIN_OBJECTS) {
                    log.info(
                        "Vision detector returned only {} objects; retrying with preprocessing disabled",
                        result.size
                    )
                    val retry = vision.detect(forVision, preprocess = false)
                    if (retry.size > result.size) return retry
                }
                return result
            }

            /** Renders a vector plan so the vision engine has something to look at. */
            private fun rasterize(image: PlanImage): PlanImage? =
                if (vision == null) null else rasterizer.toPng(image)
        }
    }

    private companion object {
        /** Minimum objects expected from a real floor plan scan. */
        const val MIN_OBJECTS = 3
    }
}
