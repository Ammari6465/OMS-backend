package com.sunrich.oms.realtime

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

data class OrganogramChange(
    val entityType: String,
    val action: String,
    val entityId: Long,
    val occurredAt: Instant = Instant.now()
)

@Service
class OrganogramUpdatePublisher(private val messaging: SimpMessagingTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val emitters = CopyOnWriteArrayList<SseEmitter>()

    fun subscribe(): SseEmitter {
        val emitter = SseEmitter(0L)
        emitters += emitter
        emitter.onCompletion { emitters.remove(emitter) }
        emitter.onTimeout { emitters.remove(emitter) }
        emitter.onError { emitters.remove(emitter) }
        return emitter
    }

    fun subscriberCount(): Int = emitters.size

    fun publish(entityType: String, action: String, entityId: Long) {
        val event = OrganogramChange(entityType, action, entityId)
        messaging.convertAndSend("/topic/organogram", event)
        emitters.forEach { emitter ->
            try {
                emitter.send(SseEmitter.event().name("organogram-change").data(event))
            } catch (ex: Exception) {
                emitters.remove(emitter)
                log.debug("Removed disconnected SSE subscriber", ex)
            }
        }
    }
}

@Service("realtime")
class RealtimeHealthIndicator(private val updates: OrganogramUpdatePublisher) : HealthIndicator {
    override fun health(): Health = Health.up()
        .withDetail("stompDestination", "/topic/organogram")
        .withDetail("sseSubscribers", updates.subscriberCount())
        .build()
}

@RestController
@RequestMapping("/sse")
class OrganogramSseController(private val updates: OrganogramUpdatePublisher) {
    @GetMapping("/organogram", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(): SseEmitter = updates.subscribe()
}
