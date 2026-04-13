package com.renan.ordersync.integration

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.renan.ordersync.domain.enums.OrderStatus
import com.renan.ordersync.integration.TestHelpers.awaitOrderStatus
import com.renan.ordersync.integration.TestHelpers.postOrder
import com.renan.ordersync.repository.IntegrationAttemptRepository
import com.renan.ordersync.repository.OrderRepository
import com.renan.ordersync.repository.ProcessingEventRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * End-to-end integration tests for the async order processing flow.
 *
 * Stack:
 * - PostgreSQL via Testcontainers (inherited from [IntegrationTestBase])
 * - WireMock standalone server simulating the ERP
 * - Real Resilience4j retry (fast wait duration set via test application properties)
 * - Real coroutine processing
 *
 * These tests exercise the full pipeline:
 *   HTTP POST → persist → async coroutine → ERP call → status transition → audit trail
 */
class OrderProcessingFlowIntegrationTest : IntegrationTestBase() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var orderRepository: OrderRepository
    @Autowired lateinit var processingEventRepository: ProcessingEventRepository
    @Autowired lateinit var integrationAttemptRepository: IntegrationAttemptRepository

    companion object {
        private lateinit var wireMockServer: WireMockServer

        @BeforeAll
        @JvmStatic
        fun startWireMock() {
            wireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())
            wireMockServer.start()
        }

        @AfterAll
        @JvmStatic
        fun stopWireMock() {
            wireMockServer.stop()
        }

        @DynamicPropertySource
        @JvmStatic
        fun configureErpUrl(registry: DynamicPropertyRegistry) {
            // Override ERP base URL to point to the test WireMock server.
            // DB properties are already set by IntegrationTestBase.configureDataSource.
            registry.add("erp.base-url") { "http://localhost:${wireMockServer.port()}" }
            // Use short retry wait so tests finish fast
            registry.add("resilience4j.retry.instances.erp-client.wait-duration") { "50ms" }
            registry.add("resilience4j.retry.instances.erp-client.enable-exponential-backoff") { "false" }
        }
    }

    @BeforeEach
    fun resetWireMock() {
        wireMockServer.resetAll()
    }

    @AfterEach
    fun cleanUp() {
        integrationAttemptRepository.deleteAll()
        processingEventRepository.deleteAll()
        orderRepository.deleteAll()
    }

    // ── Scenario A — ERP returns 200 immediately ──────────────

    @Test
    fun `Scenario A - order reaches SYNCED when ERP returns 200`() {
        wireMockServer.stubFor(
            post(urlEqualTo("/api/erp/orders"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"erpOrderId":"ERP-${UUID.randomUUID()}","status":"ACCEPTED","message":null}"""),
                ),
        )

        val key = "scenario-a-${UUID.randomUUID()}"
        mockMvc.postOrder(key, TestHelpers.createOrderPayload())
            .andExpect(status().isAccepted)

        val order = orderRepository.findByIdempotencyKey(key)!!
        awaitOrderStatus(orderRepository, order.id, OrderStatus.SYNCED)

        // Verify final state
        val synced = orderRepository.findById(order.id).get()
        assertThat(synced.status).isEqualTo(OrderStatus.SYNCED)

        // Exactly one successful attempt
        val attempts = integrationAttemptRepository.findByOrderIdOrderByCreatedAtAsc(order.id)
        assertThat(attempts).hasSize(1)
        assertThat(attempts[0].success).isTrue()

        // Events: ORDER_RECEIVED → PROCESSING_STARTED → ERP_SYNC_ATTEMPTED → ERP_SYNC_SUCCEEDED
        val events = processingEventRepository.findByOrderIdOrderByCreatedAtAsc(order.id)
        val eventTypes = events.map { it.eventType.name }
        assertThat(eventTypes).containsSequence(
            "ORDER_RECEIVED",
            "PROCESSING_STARTED",
            "ERP_SYNC_ATTEMPTED",
            "ERP_SYNC_SUCCEEDED",
        )
    }

    // ── Scenario B — ERP fails twice then succeeds ────────────

    @Test
    fun `Scenario B - order reaches SYNCED after transient failures and eventual success`() {
        // First two calls return 503, third returns 200
        wireMockServer.stubFor(
            post(urlEqualTo("/api/erp/orders"))
                .inScenario("transient-then-success")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503).withBody("Service Unavailable"))
                .willSetStateTo("first-failure"),
        )
        wireMockServer.stubFor(
            post(urlEqualTo("/api/erp/orders"))
                .inScenario("transient-then-success")
                .whenScenarioStateIs("first-failure")
                .willReturn(aResponse().withStatus(503).withBody("Still Down"))
                .willSetStateTo("second-failure"),
        )
        wireMockServer.stubFor(
            post(urlEqualTo("/api/erp/orders"))
                .inScenario("transient-then-success")
                .whenScenarioStateIs("second-failure")
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"erpOrderId":"ERP-RETRY-OK","status":"ACCEPTED","message":null}"""),
                ),
        )

        val key = "scenario-b-${UUID.randomUUID()}"
        mockMvc.postOrder(key, TestHelpers.createOrderPayload())
            .andExpect(status().isAccepted)

        val order = orderRepository.findByIdempotencyKey(key)!!
        awaitOrderStatus(orderRepository, order.id, OrderStatus.SYNCED, timeoutSeconds = 15)

        // Must have 3 attempts (2 failures + 1 success)
        val attempts = integrationAttemptRepository.findByOrderIdOrderByCreatedAtAsc(order.id)
        assertThat(attempts).hasSize(3)
        assertThat(attempts[0].success).isFalse()
        assertThat(attempts[1].success).isFalse()
        assertThat(attempts[2].success).isTrue()

        // ERP_SYNC_FAILED events: 2 failures
        val events = processingEventRepository.findByOrderIdOrderByCreatedAtAsc(order.id)
        val failedEvents = events.filter { it.eventType.name == "ERP_SYNC_FAILED" }
        assertThat(failedEvents).hasSize(2)

        // Final event is ERP_SYNC_SUCCEEDED
        val successEvents = events.filter { it.eventType.name == "ERP_SYNC_SUCCEEDED" }
        assertThat(successEvents).hasSize(1)
    }

    // ── Scenario C — ERP returns 400 (permanent error) ────────

    @Test
    fun `Scenario C - order reaches FAILED on permanent ERP error (4xx) with no retry`() {
        wireMockServer.stubFor(
            post(urlEqualTo("/api/erp/orders"))
                .willReturn(
                    aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"error":"invalid payload"}"""),
                ),
        )

        val key = "scenario-c-${UUID.randomUUID()}"
        mockMvc.postOrder(key, TestHelpers.createOrderPayload())
            .andExpect(status().isAccepted)

        val order = orderRepository.findByIdempotencyKey(key)!!
        awaitOrderStatus(orderRepository, order.id, OrderStatus.FAILED)

        // Only ONE attempt (no retry for permanent errors)
        val attempts = integrationAttemptRepository.findByOrderIdOrderByCreatedAtAsc(order.id)
        assertThat(attempts).hasSize(1)
        assertThat(attempts[0].success).isFalse()

        // WireMock was called exactly once
        wireMockServer.verify(1, WireMock.postRequestedFor(urlEqualTo("/api/erp/orders")))
    }

    // ── Scenario D — ERP always fails (exhausts retries) ──────

    @Test
    fun `Scenario D - order reaches FAILED after exhausting all retry attempts`() {
        wireMockServer.stubFor(
            post(urlEqualTo("/api/erp/orders"))
                .willReturn(aResponse().withStatus(503).withBody("Unavailable")),
        )

        val key = "scenario-d-${UUID.randomUUID()}"
        mockMvc.postOrder(key, TestHelpers.createOrderPayload())
            .andExpect(status().isAccepted)

        val order = orderRepository.findByIdempotencyKey(key)!!
        awaitOrderStatus(orderRepository, order.id, OrderStatus.FAILED, timeoutSeconds = 15)

        // Exactly 3 attempts (max-attempts from config)
        val attempts = integrationAttemptRepository.findByOrderIdOrderByCreatedAtAsc(order.id)
        assertThat(attempts).hasSize(3)
        attempts.forEach { assertThat(it.success).isFalse() }

        // WireMock was called exactly 3 times
        wireMockServer.verify(3, WireMock.postRequestedFor(urlEqualTo("/api/erp/orders")))
    }
}
