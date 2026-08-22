package com.sunrich.oms.workplace.detection

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient

class VisionFloorPlanDetectorTest {

    @Test
    fun `surfaces a retired model instead of reporting an empty floor plan`() {
        val props = DetectionProperties(
            provider = "vision",
            apiKey = "test-key",
            baseUrl = "https://vision.example.test/v1",
            model = "gemini-2.0-flash",
            apiStyle = "openai",
            preprocess = false
        )
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://vision.example.test/v1/chat/completions"))
            .andRespond(
                withStatus(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        """[{"error":{"code":404,"message":"This model is no longer available. Use gemini-3.6-flash."}}]"""
                    )
            )
        val detector = VisionFloorPlanDetector(props, builder, ObjectMapper())
        val image = PlanImage(byteArrayOf(1, 2, 3), "image/png", "floor.png", 100, 100)

        assertThatThrownBy { detector.detect(image) }
            .isInstanceOf(UnreadablePlanException::class.java)
            .hasMessageContaining("provider error")
            .hasMessageContaining("gemini-3.6-flash")
        server.verify()
    }
}
