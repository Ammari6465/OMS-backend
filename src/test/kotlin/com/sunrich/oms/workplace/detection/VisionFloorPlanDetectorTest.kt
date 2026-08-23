package com.sunrich.oms.workplace.detection

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class VisionFloorPlanDetectorTest {

    @Test
    fun `runs a desk-only recovery pass when the general scan omits workstations`() {
        val props = properties()
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val rooms = """[{"type":"MEETING_ROOM","bbox":[0.1,0.1,0.3,0.2]}]"""
        val desks = """[{"type":"DESK","bbox":[0.5,0.2,0.04,0.03]},{"type":"DESK","bbox":[0.6,0.2,0.04,0.03]}]"""
        server.expect(requestTo("https://vision.example.test/v1/chat/completions"))
            .andRespond(withSuccess(response(rooms), MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://vision.example.test/v1/chat/completions"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("only for individual workstations")))
            .andRespond(withSuccess(response(desks), MediaType.APPLICATION_JSON))

        val found = VisionFloorPlanDetector(props, builder, ObjectMapper()).detect(image())

        assertThat(found.map { it.type }).containsExactly(
            DetectedObjectType.MEETING_ROOM,
            DetectedObjectType.DESK,
            DetectedObjectType.DESK
        )
        server.verify()
    }

    @Test
    fun `accepts compact bounding boxes for dense floor plans`() {
        val props = properties()
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val content = """[{"type":"DESK","bbox":[0.1,0.2,0.04,0.03],"rotation":90,"confidence":0.9},{"type":"lift","bbox":{"x":0.8,"y":0.1,"width":0.05,"height":0.08}}]"""
        server.expect(requestTo("https://vision.example.test/v1/chat/completions"))
            .andRespond(withSuccess(response(content), MediaType.APPLICATION_JSON))

        val found = VisionFloorPlanDetector(props, builder, ObjectMapper()).detect(image())

        assertThat(found).hasSize(2)
        assertThat(found.map { it.type }).containsExactly(DetectedObjectType.DESK, DetectedObjectType.ELEVATOR)
        assertThat(found.first().polygon.area).isGreaterThan(0.0)
        server.verify()
    }

    @Test
    fun `recovers complete objects when a dense response is truncated`() {
        val props = properties()
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val content = """[{"type":"DESK","bbox":[0.1,0.2,0.04,0.03]},{"type":"DOOR","bbox":[0.5,0.2,0.02,0.08]},{"type":"CABIN","bbox":[0.6"""
        server.expect(requestTo("https://vision.example.test/v1/chat/completions"))
            .andRespond(withSuccess(response(content), MediaType.APPLICATION_JSON))

        val found = VisionFloorPlanDetector(props, builder, ObjectMapper()).detect(image())

        assertThat(found.map { it.type }).containsExactly(DetectedObjectType.DESK, DetectedObjectType.DOOR)
        server.verify()
    }

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

    private fun properties() = DetectionProperties(
        provider = "vision",
        apiKey = "test-key",
        baseUrl = "https://vision.example.test/v1",
        model = "gemini-3.6-flash",
        apiStyle = "openai",
        preprocess = false
    )

    private fun image() = PlanImage(byteArrayOf(1, 2, 3), "image/png", "floor.png", 100, 100)

    private fun response(content: String) = ObjectMapper().writeValueAsString(
        mapOf("choices" to listOf(mapOf("message" to mapOf("content" to content))))
    )
}
