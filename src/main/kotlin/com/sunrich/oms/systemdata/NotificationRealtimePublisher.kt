package com.sunrich.oms.systemdata

import org.springframework.stereotype.Component
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap

/** Authenticated, recipient-targeted SSE fan-out. No notification is sent to a public topic. */
@Component
class NotificationRealtimePublisher {
    private val emitters = ConcurrentHashMap<Long, MutableSet<SseEmitter>>()

    fun subscribe(userId: Long): SseEmitter {
        // Railway caps requests at 15 minutes. Complete just before that limit so
        // the client can reconnect cleanly instead of receiving an edge-level 502.
        val emitter = SseEmitter(14L * 60L * 1000L)
        emitters.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(emitter)
        val remove = { emitters[userId]?.remove(emitter); Unit }
        emitter.onCompletion(remove)
        emitter.onTimeout(remove)
        emitter.onError { remove() }
        emitter.send(SseEmitter.event().name("connected").data("ok"))
        return emitter
    }

    /** Keep idle SSE responses flowing through reverse proxies and load balancers. */
    @Scheduled(fixedRateString = "\${oms.notifications.heartbeat-ms:25000}")
    fun heartbeat() {
        emitters.forEach { (userId, userEmitters) ->
            userEmitters.toList().forEach { emitter ->
                try { emitter.send(SseEmitter.event().comment("keepalive")) }
                catch (_: Exception) { emitters[userId]?.remove(emitter) }
            }
        }
    }

    fun publish(userId: Long, notification: NotificationResponse) {
        emitters[userId]?.toList()?.forEach { emitter ->
            try { emitter.send(SseEmitter.event().name("notification").id(notification.id.toString()).data(notification)) }
            catch (_: Exception) { emitters[userId]?.remove(emitter) }
        }
    }
}
