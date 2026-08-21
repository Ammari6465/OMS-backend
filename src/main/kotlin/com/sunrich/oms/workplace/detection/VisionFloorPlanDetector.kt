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
    var preprocess: Boolean = true,
    /**
     * Output token budget. Kept at a value every provider accepts: Gemini Flash
     * and most free tiers cap output around 8k, and asking for more is rejected
     * outright rather than clamped — which surfaces as "no objects found".
     * Raise it on a provider that allows more if a dense plan gets truncated.
     */
    var maxTokens: Int = 8000
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

    override val readableMediaTypes get() = if (available) SUPPORTED_IMAGE_TYPES else emptySet()

    /** Raster formats only; PDFs and SVGs would need rasterising first. */
    override fun supports(image: PlanImage) =
        available && SUPPORTED_IMAGE_TYPES.contains(image.mediaType)

    override fun detect(image: PlanImage): List<DetectionCandidate> {
        if (!available) return emptyList()
        if (!SUPPORTED_IMAGE_TYPES.contains(image.mediaType)) {
            // PDFs and SVGs need rasterising before a vision model can read them.
            log.warn("Floor plan media type {} cannot be sent to the vision model", image.mediaType)
            return emptyList()
        }
        // Strip evacuation decoration first. Preprocessing may also crop away
        // title bands and legends, which moves the frame the model reports
        // against, so every polygon is mapped back onto the uploaded plan
        // before it leaves this method.
        // A plan the server rendered is already clean line art at the right
        // size; preprocessing it again buys nothing and costs several full-size
        // buffers, which is fatal on a container with little headroom.
        val prepared = if (props.preprocess && !image.prepared) preprocessor.clean(image) else null
        return try {
            val content = request(prepared?.image ?: image)
            val candidates = parse(content).take(props.maxObjects)
            if (prepared == null || !prepared.isCropped) candidates
            else candidates.map { it.copy(polygon = prepared.toOriginal(it.polygon)) }
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
        "max_tokens" to props.maxTokens,
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
        "max_tokens" to props.maxTokens,
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
        // Scan the raw body for the outermost array rather than stripping code
        // fences first. Fencing styles vary by model — ```json, a bare ```, or
        // none at all — and fence-stripping that assumes one style silently
        // truncates the others down to prose, yielding "no objects found".
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start < 0 || end <= start) {
            log.warn(
                "Vision model returned no JSON array. First {} chars of the response: {}",
                RESPONSE_LOG_CHARS, content.take(RESPONSE_LOG_CHARS)
            )
            return emptyList()
        }
        return try {
            mapper.readTree(content.substring(start, end + 1)).mapNotNull(::candidate)
        } catch (ex: Exception) {
            // A truncated response leaves unbalanced JSON. Say so plainly: the
            // usual fix is a larger token budget, not a different plan.
            log.warn(
                "Vision model returned malformed JSON ({}). It may have been truncated; " +
                    "consider raising oms.workplace.detection.max-tokens (currently {}).",
                ex.message, props.maxTokens
            )
            emptyList()
        }
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
        const val RESPONSE_LOG_CHARS = 400
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

            DESK SPLITTING (critical):
            - Emit one DESK per individual seat. Never merge a row into one desk.
            - For dense open-office workstation blocks, count seats by the repeating
              desk-and-chair unit visible in the layout pattern. Each repeating unit
              is one DESK. If you see a block of 8 paired workstations, that is 16
              individual desks (8 on each side).
            - For back-to-back rows and bench runs, each chair position is a desk.
            - For cubicle clusters, each partitioned workspace is one DESK.
            - Also emit one ZONE polygon that encloses the entire workstation block.

            CABIN DETECTION:
            - Enclosed offices along the building perimeter — typically containing
              one large executive desk and 1-2 visitor chairs — are CABIN type.
            - Look for rows of identically sized enclosed rooms along an exterior
              wall. 4-6 such rooms in a row is a common pattern.
            - Each cabin gets its own polygon following its walls.

            CONFERENCE AND MEETING ROOMS:
            - A large table ringed by many chairs (8+) in an enclosed room is a
              CONFERENCE_ROOM.
            - A smaller enclosed room with a table and 4-6 chairs is a MEETING_ROOM.
            - Use the printed label if available. Otherwise infer from furniture.

            RECEPTION:
            - A large curved or L-shaped desk near the entrance, with waiting
              chairs or sofas nearby, is RECEPTION.

            WASHROOM SPLITTING:
            - For washroom blocks, emit one WASHROOM polygon per identifiable
              enclosed stall or partition, not one for the whole block.
            - If individual stalls cannot be distinguished, emit one WASHROOM per
              visible toilet fixture.
            - Basin/sink areas without stalls can be part of the nearest stall.

            PANTRY:
            - Counters, sinks, and utility fittings in a service area are PANTRY.

            CORRIDORS AND WALKWAYS:
            - Trace WALKWAY polygons along the main circulation spine and any
              branch corridors. Follow the actual drawn corridor boundaries.
            - Include the central spine corridor that connects the main areas.
            - Each distinct corridor segment should be a separate WALKWAY.

            GENERAL:
            - Use the printed label to choose the type when there is one.
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
