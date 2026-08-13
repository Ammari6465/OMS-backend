package com.sunrich.oms.systemdata

import com.sunrich.oms.common.enums.NotificationType
import com.sunrich.oms.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/** Single trusted write path for business notifications, including short-window duplicate suppression and real-time delivery. */
@Service
class NotificationDeliveryService(
    private val repository: NotificationRepository,
    private val realtime: NotificationRealtimePublisher,
    private val systemData: SystemDataService
) {
    @Transactional
    fun deliver(recipient: User, type: NotificationType, message: String, link: String? = null, entityType: String? = null, entityId: Long? = null) {
        val userId = recipient.id ?: return
        val duplicate = repository.findFirstByRecipientIdAndTypeAndMessageOrderByCreatedAtDesc(userId, type, message)
        if (duplicate != null && duplicate.createdAt.isAfter(LocalDateTime.now().minusSeconds(3))) return
        val saved = repository.save(Notification(recipient, type, message, link = link, entityType = entityType, entityId = entityId))
        realtime.publish(userId, systemData.toNotificationResponse(saved))
    }
}
