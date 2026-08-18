package com.sunrich.oms.realtime

import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.exception.ForbiddenException
import com.sunrich.oms.security.SecurityUtils
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.http.MediaType
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

data class OrganogramChange(
    val companyId: Long,
    val entityType: String,
    val entityId: Long,
    val action: String,
    val version: Long,
    val timestamp: Instant = Instant.now()
)

@Service
class OrganogramUpdatePublisher(private val messaging: SimpMessagingTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val emitters = ConcurrentHashMap<Long, CopyOnWriteArraySet<SseEmitter>>()

    fun subscribe(companyId: Long): SseEmitter {
        val emitter = SseEmitter(14L * 60L * 1000L)
        emitters.computeIfAbsent(companyId) { CopyOnWriteArraySet() }.add(emitter)
        val remove = { emitters[companyId]?.remove(emitter); Unit }
        emitter.onCompletion(remove)
        emitter.onTimeout(remove)
        emitter.onError { remove() }
        emitter.send(SseEmitter.event().name("connected").data(mapOf("companyId" to companyId)))
        return emitter
    }

    fun subscriberCount(): Int = emitters.values.sumOf { it.size }

    /** Send only after the surrounding data transaction has committed. */
    fun publish(companyId: Long, entityType: String, action: String, entityId: Long, version: Long = 0) {
        val send = { sendNow(OrganogramChange(companyId, entityType, entityId, action, version)) }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = send()
            })
        } else send()
    }

    /** Older mutation paths remain functional but cannot emit an unsafe unscoped event. */
    fun publish(entityType: String, action: String, entityId: Long) {
        log.debug("Skipped unscoped organogram event for {} {}", entityType, entityId)
    }

    private fun sendNow(event: OrganogramChange) {
        messaging.convertAndSend("/topic/organogram/${event.companyId}", event)
        emitters[event.companyId]?.forEach { emitter ->
            try {
                emitter.send(SseEmitter.event().name("organogram-change")
                    .id("${event.entityType}:${event.entityId}:${event.version}").data(event))
            } catch (ex: Exception) {
                emitters[event.companyId]?.remove(emitter)
                log.debug("Removed disconnected company-scoped SSE subscriber", ex)
            }
        }
    }
}

@Service("realtime")
class RealtimeHealthIndicator(private val updates: OrganogramUpdatePublisher) : HealthIndicator {
    override fun health(): Health = Health.up()
        .withDetail("stompDestination", "/topic/organogram/{companyId}")
        .withDetail("sseSubscribers", updates.subscriberCount())
        .build()
}

@RestController
@RequestMapping("/organogram")
class OrganogramSseController(private val updates: OrganogramUpdatePublisher) {
    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@RequestParam companyId: Long): SseEmitter {
        val principal = SecurityUtils.currentPrincipal()
        if (principal.role != Role.SUPER_ADMIN && principal.companyId != companyId) throw ForbiddenException()
        return updates.subscribe(companyId)
    }
}
