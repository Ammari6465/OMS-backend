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
    ): FloorPlanDetector = when {
        !props.provider.equals("vision", ignoreCase = true) ->
            UnavailableDetector("oms.workplace.detection.provider is '${props.provider}'")
        props.apiKey.isBlank() ->
            UnavailableDetector("oms.workplace.detection.api-key is not set")
        else -> VisionFloorPlanDetector(props, restClientBuilder, mapper)
    }
}
