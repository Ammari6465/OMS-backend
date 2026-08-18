package com.sunrich.oms.realtime

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class OrganogramRealtimeIntegrationTest {
    @Autowired lateinit var publisher: OrganogramUpdatePublisher
    @Autowired lateinit var healthIndicator: RealtimeHealthIndicator

    @Test
    fun `subscribing to SSE registers emitter and updates subscriber count`() {
        val initialCount = publisher.subscriberCount()

        val emitter = publisher.subscribe(42L)

        assertThat(publisher.subscriberCount()).isEqualTo(initialCount + 1)
        assertThat(emitter).isNotNull
    }

    @Test
    fun `realtime health indicator reports UP status and subscriber count`() {
        val health = healthIndicator.health()

        assertThat(health.status).isEqualTo(Status.UP)
        assertThat(health.details["stompDestination"]).isEqualTo("/topic/organogram/{companyId}")
        assertThat(health.details).containsKey("sseSubscribers")
    }

    @Test
    fun `publishing event dispatches without throwing exceptions`() {
        val emitter = publisher.subscribe(42L)

        publisher.publish(42L, "DEPARTMENT", "CREATE", 101L, 1L)

        assertThat(publisher.subscriberCount()).isGreaterThanOrEqualTo(1)
    }
}
