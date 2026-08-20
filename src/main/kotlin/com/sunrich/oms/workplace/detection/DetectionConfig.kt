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
 */
@Configuration
@EnableConfigurationProperties(DetectionProperties::class)
class DetectionConfig {
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

            override fun detect(image: PlanImage): List<DetectionCandidate> {
                if (image.mediaType == "image/svg+xml" || String(image.bytes, Charsets.UTF_8).contains("<svg", ignoreCase = true)) {
                    val heuristicResults = heuristic.detect(image)
                    if (heuristicResults.isNotEmpty()) return heuristicResults
                }
                return vision?.detect(image) ?: heuristic.detect(image)
            }
        }
    }
}

