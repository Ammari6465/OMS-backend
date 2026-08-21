package com.sunrich.oms.workplace.detection

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * Test engine for the detector seam: returns whatever a test sets, so the
 * pipeline can be exercised without a model, a network call, or image decoding.
 */
object StubDetector : FloorPlanDetector {
    var candidates: List<DetectionCandidate> = emptyList()

    /** Lets a test simulate an unconfigured deployment. */
    var configured: Boolean = true

    /** Lets a test simulate a file no engine can open, such as a raster PNG. */
    var readable: Boolean = true

    override val name = "stub"
    override val available: Boolean get() = configured
    override fun supports(image: PlanImage) = readable
    override fun detect(image: PlanImage) = candidates
}

@TestConfiguration
class StubDetectorConfig {
    @Bean
    @Primary
    fun stubDetector(): FloorPlanDetector = StubDetector
}
