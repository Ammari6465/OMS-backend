package com.sunrich.oms.integration.email

import com.google.auth.oauth2.ServiceAccountCredentials
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Properties

interface NotificationEmailService {
    fun sendPasswordReset(to: String, resetUrl: String)
}

@Service
@ConditionalOnProperty(prefix = "oms.gmail", name = ["enabled"], havingValue = "true")
class GmailApiNotificationEmailService(
    @Value("\${oms.gmail.service-account-json}") private val serviceAccountJson: String,
    @Value("\${oms.gmail.sender}") private val sender: String,
    private val restClientBuilder: RestClient.Builder
) : NotificationEmailService {
    override fun sendPasswordReset(to: String, resetUrl: String) {
        require(serviceAccountJson.isNotBlank()) { "GMAIL_SERVICE_ACCOUNT_JSON is required when Gmail is enabled" }
        require(sender.isNotBlank()) { "GMAIL_SENDER is required when Gmail is enabled" }

        val credentials = ServiceAccountCredentials
            .fromStream(ByteArrayInputStream(serviceAccountJson.toByteArray(StandardCharsets.UTF_8)))
            .createScoped(listOf("https://www.googleapis.com/auth/gmail.send"))
            .createDelegated(sender)
        val token = credentials.refreshAccessToken().tokenValue

        restClientBuilder.baseUrl("https://gmail.googleapis.com").build()
            .post()
            .uri("/gmail/v1/users/me/messages/send")
            .headers { it.setBearerAuth(token) }
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("raw" to createMimeMessage(to, resetUrl)))
            .retrieve()
            .toBodilessEntity()
    }

    private fun createMimeMessage(to: String, resetUrl: String): String {
        val message = MimeMessage(Session.getInstance(Properties())).apply {
            setFrom(sender)
            setRecipient(Message.RecipientType.TO, InternetAddress(to))
            subject = "Reset your OMS password"
            setText("Use this link to reset your OMS password: $resetUrl", StandardCharsets.UTF_8.name())
        }
        return ByteArrayOutputStream().use { output ->
            message.writeTo(output)
            Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
        }
    }
}

@Service
@ConditionalOnProperty(prefix = "oms.gmail", name = ["enabled"], havingValue = "false", matchIfMissing = true)
class DisabledNotificationEmailService : NotificationEmailService {
    private val log = LoggerFactory.getLogger(javaClass)
    override fun sendPasswordReset(to: String, resetUrl: String) {
        log.warn("Gmail API is disabled; password-reset email for {} was not sent", to)
    }
}
