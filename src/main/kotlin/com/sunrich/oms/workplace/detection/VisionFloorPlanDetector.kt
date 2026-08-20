package com.sunrich.oms.workplace.detection

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.util.Base64

/**
 * Configuration for floor plan recognition. Keys live server-side and are never
 * returned to a client. Detection is off until a key is supplied, so an
 * unconfigured deployment simply reports nothing detected.
 */
@ConfigurationProperties(prefix = "oms.workplace.detection")
data class DetectionProperties(
    /** `vision` to call a model, anything else disables detection. */
    var provider: String = "none",
    var apiKey: String = "",
    /** Anthropic-style messages endpoint by default; OpenAI-compatible also works. */
    var baseUrl: String = "https://api.anthropic.com/v1",
    var model: String = "claude-sonnet-5",
    var apiStyle: String = "anthropic",
    /** Hard ceiling on regions accepted from one response. */
    var maxObjects: Int = 400,
    var timeoutSeconds: Long = 120,
    /**
     * Strip decorative layers before detection. Worth keeping on for evacuation
     * maps and other annotated plans; turn off for a clean CAD export where the
     * drawing itself is coloured.
     */
    var preprocess: Boolean = true
)

/**
 * Detects workplace regions by asking a vision model to read the floor plan.
 *
 * The model is asked for strict JSON in normalised coordinates. Everything it
 * returns is treated as untrusted: types are parsed leniently, geometry is
 * clamped into the plan, and anything malformed is dropped rather than failing
 * the whole run. A partial map a person can correct beats an error page.
 */
class VisionFloorPlanDetector(
    private val props: DetectionProperties,
    restClientBuilder: RestClient.Builder,
    private val mapper: ObjectMapper,
    private val preprocessor: FloorPlanPreprocessor = FloorPlanPreprocessor()
) : FloorPlanDetector {

    private val log = LoggerFactory.getLogger(javaClass)
    private val client = restClientBuilder.baseUrl(props.baseUrl).build()

    override val name = "vision:${props.model}"
    override val available get() = props.apiKey.isNotBlank()

    override fun detect(image: PlanImage): List<DetectionCandidate> {
        if (!available) return emptyList()
        if (!SUPPORTED_IMAGE_TYPES.contains(image.mediaType)) {
            // PDFs and SVGs need rasterising before a vision model can read them.
            log.warn("Floor plan media type {} cannot be sent to the vision model", image.mediaType)
            return emptyList()
        }
        // Strip evacuation decoration first. Coordinates are unaffected: the
        // cleaned image keeps the original aspect ratio and orientation, and
        // results are normalised, so overlays land on the plan as uploaded.
        val prepared = if (props.preprocess) preprocessor.clean(image) ?: image else image
        return try {
            val content = request(prepared)
            parse(content).take(props.maxObjects)
        } catch (ex: Exception) {
            log.warn("Floor plan detection failed for {}", image.originalName, ex)
            emptyList()
        }
    }

    private fun request(image: PlanImage): String {
        val encoded = Base64.getEncoder().encodeToString(image.bytes)
        val anthropic = props.apiStyle.equals("anthropic", ignoreCase = true)
        val body = if (anthropic) anthropicBody(encoded, image.mediaType) else openAiBody(encoded, image.mediaType)
        val spec = client.post()
            .uri(if (anthropic) "/messages" else "/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
        if (anthropic) {
            spec.header("x-api-key", props.apiKey).header("anthropic-version", ANTHROPIC_VERSION)
        } else {
            spec.header("Authorization", "Bearer ${props.apiKey}")
        }
        val response = spec.body(body).retrieve().body<JsonNode>()
            ?: throw IllegalStateException("Empty response from the vision model")
        return if (anthropic) {
            response.path("content").firstOrNull { it.path("type").asText() == "text" }?.path("text")?.asText()
        } else {
            response.path("choices").firstOrNull()?.path("message")?.path("content")?.asText()
        } ?: throw IllegalStateException("Vision model returned no text content")
    }

    private fun anthropicBody(encoded: String, mediaType: String) = mapOf(
        "model" to props.model,
        "max_tokens" to MAX_TOKENS,
        "messages" to listOf(
            mapOf(
                "role" to "user",
                "content" to listOf(
                    mapOf(
                        "type" to "image",
                        "source" to mapOf("type" to "base64", "media_type" to mediaType, "data" to encoded)
                    ),
                    mapOf("type" to "text", "text" to PROMPT)
                )
            )
        )
    )

    private fun openAiBody(encoded: String, mediaType: String) = mapOf(
        "model" to props.model,
        "max_tokens" to MAX_TOKENS,
        "messages" to listOf(
            mapOf(
                "role" to "user",
                "content" to listOf(
                    mapOf("type" to "text", "text" to PROMPT),
                    mapOf(
                        "type" to "image_url",
                        "image_url" to mapOf("url" to "data:$mediaType;base64,$encoded")
                    )
                )
            )
        )
    )

    /**
     * Reads the model's JSON. Models wrap JSON in prose or fences often enough
     * that we extract the outermost array rather than trusting the whole body.
     */
    private fun parse(content: String): List<DetectionCandidate> {
        val json = content.substringAfter("```json", content).substringBefore("```").trim()
        val start = json.indexOf('[')
        val end = json.lastIndexOf(']')
        if (start < 0 || end <= start) {
            log.warn("Vision model response contained no JSON array")
            return emptyList()
        }
        val array = mapper.readTree(json.substring(start, end + 1))
        return array.mapNotNull(::candidate)
    }

    private fun candidate(node: JsonNode): DetectionCandidate? = try {
        val points = node.path("polygon").mapNotNull { point ->
            val x = point.path("x").takeIf { it.isNumber }?.asDouble()
            val y = point.path("y").takeIf { it.isNumber }?.asDouble()
            if (x == null || y == null) null else Point(x, y)
        }
        if (points.size < 3) null else DetectionCandidate(
            type = DetectedObjectType.parse(node.path("type").asText(null)),
            polygon = Polygon.ofClamped(points),
            name = node.path("name").asText(null)?.trim()?.take(200)?.takeIf { it.isNotEmpty() },
            ocrText = node.path("text").asText(null)?.trim()?.take(1000)?.takeIf { it.isNotEmpty() },
            rotation = node.path("rotation").asInt(0).mod(360),
            confidence = node.path("confidence").asDouble(0.5).coerceIn(0.0, 1.0)
        )
    } catch (ex: Exception) {
        log.debug("Skipping malformed detection entry", ex)
        null
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MAX_TOKENS = 16000
        val SUPPORTED_IMAGE_TYPES = setOf("image/png", "image/jpeg")

        val PROMPT = """
            You are analysing an office floor plan, which may be an evacuation map
            with the decorative layer already stripped out. Identify every distinct
            space and workstation from the architecture and the furniture.

            Return ONLY a JSON array. Each element must be:
            {
              "type": one of DESK, CABIN, CONFERENCE_ROOM, MEETING_ROOM, RECEPTION, PANTRY,
                      WASHROOM, SERVER_ROOM, STORAGE, ZONE, WALKWAY, EXIT,
              "name": the label printed on the plan, or null if unlabelled,
              "text": any other text you can read inside the region, or null,
              "polygon": [{"x":0.0,"y":0.0}, ...] tracing the region boundary,
              "rotation": integer degrees the furniture faces, 0 if unclear,
              "confidence": 0.0 to 1.0
            }

            Rules:
            - Coordinates are fractions of the image: x from 0 (left) to 1 (right),
              y from 0 (top) to 1 (bottom). Never use pixels.
            - Emit one DESK per individual seat, including each seat of back-to-back
              rows, bench runs and cubicle clusters. Do not merge a row into one desk.
            - Use the printed label to choose the type when there is one. Otherwise
              infer it from the furniture:
                * large table ringed by chairs -> CONFERENCE_ROOM (smaller, 4-6
                  chairs, still enclosed -> MEETING_ROOM)
                * enclosed room with one executive desk and one or two visitor
                  chairs -> CABIN
                * dense grid of workstations -> ZONE, plus one DESK per seat
                * large curved desk with nearby waiting seating -> RECEPTION
                * toilet and basin fixtures -> WASHROOM, one per enclosed cubicle
                  space rather than one for the whole block
                * counters and utility fittings -> PANTRY
            - Mark circulation space and corridors as WALKWAY, following the
              corridor exactly as drawn. Marked egress points are EXIT.
            - Trace the walls for enclosed rooms so the polygon follows the real
              boundary. A simple rectangle is fine for a desk.
            - Ignore anything that is not building fabric: title text, logos,
              legends, compass roses, "you are here" markers, egress arrows, and
              fire equipment symbols. They are wayfinding, not architecture.
            - Report only what you can actually see. Omit a region rather than
              guessing at one; a person will add anything you miss.
            - No commentary, no markdown, JSON array only.
        """.trimIndent()
    }
}
