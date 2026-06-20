package com.renan.ordersync.integration

import com.renan.ordersync.domain.enums.OrderStatus
import com.renan.ordersync.fixtures.TestFixtures
import com.renan.ordersync.integration.TestHelpers.getOrder
import com.renan.ordersync.integration.TestHelpers.getOrdersByExternal
import com.renan.ordersync.integration.TestHelpers.postOrder
import com.renan.ordersync.integration.TestHelpers.postOrderNoKey
import com.renan.ordersync.integration.TestHelpers.retryOrder
import com.renan.ordersync.repository.OrderRepository
import com.renan.ordersync.service.OrderProcessingService
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import com.ninjasquad.springmockk.MockkBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Integration tests for [com.renan.ordersync.controller.OrderController].
 *
 * Uses a real PostgreSQL (Testcontainers) + Flyway schema.
 * ERP processing is mocked ([OrderProcessingService.launchProcessing] is a no-op) so that
 * tests stay deterministic and do not depend on async timing.
 */
class OrderControllerIntegrationTest : IntegrationTestBase() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var orderRepository: OrderRepository

    /**
     * Mock only the async processing launch so orders stay in RECEIVED/FAILED state
     * as set by the test. Full processing flow is tested in [OrderProcessingFlowIntegrationTest].
     */
    @MockkBean(relaxed = true)
    lateinit var orderProcessingService: OrderProcessingService

    @AfterEach
    fun cleanUp() {
        orderRepository.deleteAll()
    }

    // ── POST /api/v1/orders ───────────────────────────────────

    @Test
    fun `POST orders returns 202 Accepted with orderId for valid payload`() {
        val key = "key-${UUID.randomUUID()}"
        val body = TestHelpers.createOrderPayload()

        mockMvc.postOrder(key, body)
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.orderId", notNullValue()))
            .andExpect(jsonPath("$.status", equalTo("RECEIVED")))
    }

    @Test
    fun `POST orders returns 202 with same orderId when idempotency key is reused`() {
        val key = "key-${UUID.randomUUID()}"
        val body = TestHelpers.createOrderPayload()

        val firstResponse = mockMvc.postOrder(key, body)
            .andExpect(status().isAccepted)
            .andReturn()

        val firstOrderId = com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(firstResponse.response.contentAsString)
            .get("orderId").asText()

        mockMvc.postOrder(key, body)
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.orderId", equalTo(firstOrderId)))
    }

    @Test
    fun `POST orders returns 400 when Idempotency-Key header is missing`() {
        val body = TestHelpers.createOrderPayload()

        mockMvc.postOrderNoKey(body)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error", notNullValue()))
    }

    @Test
    fun `POST orders returns 400 when payload is invalid (missing customer email)`() {
        val key = "key-${UUID.randomUUID()}"
        val body = """
            {
              "externalOrderId": "EXT-001",
              "sourceSystem": "test",
              "customer": { "name": "Jane" },
              "currency": "BRL",
              "items": [{"sku":"S1","name":"W","quantity":1,"unitPrice":10.00}]
            }
        """.trimIndent()

        mockMvc.postOrder(key, body)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors", hasSize<Any>(1)))
    }

    @Test
    fun `POST orders returns 400 for empty items list`() {
        val key = "key-${UUID.randomUUID()}"
        val body = """
            {
              "externalOrderId": "EXT-001",
              "sourceSystem": "test",
              "customer": {"name":"Jane","email":"j@e.com"},
              "currency": "BRL",
              "items": []
            }
        """.trimIndent()

        mockMvc.postOrder(key, body)
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `POST orders calculates totalAmount from items`() {
        val key = "key-${UUID.randomUUID()}"
        val body = TestHelpers.createOrderPayload(quantity = 3, unitPrice = "10.00")

        mockMvc.postOrder(key, body)
            .andExpect(status().isAccepted)

        val saved = orderRepository.findByIdempotencyKey(key)!!
        org.assertj.core.api.Assertions.assertThat(saved.totalAmount)
            .isEqualByComparingTo(java.math.BigDecimal("30.00"))
    }

    // ── GET /api/v1/orders/{orderId} ──────────────────────────

    @Test
    fun `GET order by id returns full order details`() {
        val key = "key-${UUID.randomUUID()}"
        val externalId = "EXT-${UUID.randomUUID()}"
        val body = TestHelpers.createOrderPayload(externalOrderId = externalId)
        mockMvc.postOrder(key, body).andExpect(status().isAccepted)

        val order = orderRepository.findByIdempotencyKey(key)!!

        mockMvc.getOrder(order.id)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.orderId", equalTo(order.id.toString())))
            .andExpect(jsonPath("$.externalOrderId", equalTo(externalId)))
            .andExpect(jsonPath("$.status", equalTo("RECEIVED")))
            .andExpect(jsonPath("$.items", hasSize<Any>(1)))
            .andExpect(jsonPath("$.events", notNullValue()))
    }

    @Test
    fun `GET order by id returns 404 for unknown orderId`() {
        mockMvc.getOrder(UUID.randomUUID())
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error", equalTo("Not Found")))
    }

    // ── GET /api/v1/orders?externalOrderId= ───────────────────

    @Test
    fun `GET orders by externalOrderId returns matching orders`() {
        val key = "key-${UUID.randomUUID()}"
        val externalId = "EXT-LOOKUP-${UUID.randomUUID()}"
        val body = TestHelpers.createOrderPayload(externalOrderId = externalId)
        mockMvc.postOrder(key, body).andExpect(status().isAccepted)

        mockMvc.getOrdersByExternal(externalId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].externalOrderId", equalTo(externalId)))
    }

    @Test
    fun `GET orders by externalOrderId returns empty list when no match`() {
        mockMvc.getOrdersByExternal("NONEXISTENT")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(0)))
    }

    // ── POST /api/v1/orders/{orderId}/retry ───────────────────

    @Test
    fun `retry returns 202 for FAILED order`() {
        val key = "key-${UUID.randomUUID()}"
        val body = TestHelpers.createOrderPayload()
        mockMvc.postOrder(key, body).andExpect(status().isAccepted)

        val order = orderRepository.findByIdempotencyKey(key)!!
        order.status = OrderStatus.FAILED
        orderRepository.save(order)

        mockMvc.retryOrder(order.id)
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.orderId", equalTo(order.id.toString())))
    }

    @Test
    fun `retry returns 422 for non-FAILED order`() {
        val key = "key-${UUID.randomUUID()}"
        val body = TestHelpers.createOrderPayload()
        mockMvc.postOrder(key, body).andExpect(status().isAccepted)

        val order = orderRepository.findByIdempotencyKey(key)!!
        // Order is RECEIVED by default
        mockMvc.retryOrder(order.id)
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error", equalTo("Business Rule Violation")))
    }

    @Test
    fun `retry returns 404 for unknown orderId`() {
        mockMvc.retryOrder(UUID.randomUUID())
            .andExpect(status().isNotFound)
    }
}
