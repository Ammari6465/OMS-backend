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
        val vision = if (props.provider.equals("vision", ignoreCase = true) && props.apiKey.isNotBlank()) {
            VisionFloorPlanDetector(props, restClientBuilder, mapper)
        } else null

        return object : FloorPlanDetector {
            override val name: String get() = vision?.name ?: heuristic.name
            override val available: Boolean get() = true

            /** Readable if either engine can actually open this file. */
            override fun supports(image: PlanImage) =
                heuristic.supports(image) || vision?.supports(image) == true

            override fun detect(image: PlanImage): List<DetectionCandidate> {
                if (heuristic.supports(image)) {
                    // A broken SVG is worth reporting rather than swallowing —
                    // unless a vision engine can still read the file, in which
                    // case try that before giving up on the plan.
                    val heuristicResults = try {
                        heuristic.detect(image)
                    } catch (ex: UnreadablePlanException) {
                        if (vision?.supports(image) != true) throw ex
                        log.info("SVG parse failed ({}); falling back to the vision detector", ex.message)
                        emptyList()
                    }
                    if (heuristicResults.isNotEmpty()) return heuristicResults
                }
                if (vision == null) return emptyList()

                val result = vision.detect(image)
                if (result.size >= MIN_OBJECTS) return result

                // Too few objects — the preprocessor may have been too aggressive
                // or the response was truncated. Retry with the original image if
                // preprocessing was active (the VisionFloorPlanDetector preprocesses
                // internally, so we force a retry by temporarily disabling it).
                if (props.preprocess && result.size < MIN_OBJECTS) {
                    log.info(
                        "Vision detector returned only {} objects; retrying with preprocessing disabled",
                        result.size
                    )
                    val savedPreprocess = props.preprocess
                    try {
                        props.preprocess = false
                        val retry = vision.detect(image)
                        if (retry.size > result.size) return retry
                    } finally {
                        props.preprocess = savedPreprocess
                    }
                }
                return result
            }
        }
    }

    private companion object {
        /** Minimum objects expected from a real floor plan scan. */
        const val MIN_OBJECTS = 3
    }
}
