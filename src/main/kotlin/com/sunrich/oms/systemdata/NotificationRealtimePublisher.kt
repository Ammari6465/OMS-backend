package com.sunrich.oms.systemdata

import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap

/** Authenticated, recipient-targeted SSE fan-out. No notification is sent to a public topic. */
@Component
class NotificationRealtimePublisher {
    private val emitters = ConcurrentHashMap<Long, MutableSet<SseEmitter>>()

    fun subscribe(userId: Long): SseEmitter {
        val emitter = SseEmitter(30L * 60L * 1000L)
        emitters.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(emitter)
        val remove = { emitters[userId]?.remove(emitter); Unit }
        emitter.onCompletion(remove)
        emitter.onTimeout(remove)
        emitter.onError { remove() }
        emitter.send(SseEmitter.event().name("connected").data("ok"))
        return emitter
    }

    fun publish(userId: Long, notification: NotificationResponse) {
        emitters[userId]?.toList()?.forEach { emitter ->
            try { emitter.send(SseEmitter.event().name("notification").id(notification.id.toString()).data(notification)) }
            catch (_: Exception) { emitters[userId]?.remove(emitter) }
        }
    }
}
