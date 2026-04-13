package com.renan.ordersync.integration

import com.renan.ordersync.integration.TestHelpers.postOrder
import com.renan.ordersync.repository.OrderRepository
import com.renan.ordersync.service.OrderProcessingService
import com.ninja_squad.springmockk.MockkBean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrency test for idempotency guarantee.
 *
 * Sends N simultaneous requests with the same `Idempotency-Key` and asserts that:
 * 1. Only one order record is created in the database.
 * 2. No request fails with 5xx (the race condition is handled gracefully).
 * 3. All successful responses carry the same `orderId`.
 *
 * The async ERP processing is mocked so the test focuses exclusively on the
 * idempotency / persistence layer.
 */
class OrderIdempotencyConcurrencyIntegrationTest : IntegrationTestBase() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var orderRepository: OrderRepository

    @MockkBean(relaxed = true)
    lateinit var orderProcessingService: OrderProcessingService

    @AfterEach
    fun cleanUp() {
        orderRepository.deleteAll()
    }

    @Test
    fun `concurrent requests with same idempotency key create exactly one order and return 2xx`() {
        val sharedKey = "concurrent-key-${UUID.randomUUID()}"
        val body = TestHelpers.createOrderPayload()
        val concurrency = 8

        val startLatch = CountDownLatch(1)     // holds all threads until we release
        val doneLatch = CountDownLatch(concurrency)
        val successCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        val orderIds = mutableSetOf<String>()
        val lock = Any()

        val executor = Executors.newFixedThreadPool(concurrency)

        repeat(concurrency) {
            executor.submit {
                try {
                    startLatch.await(5, TimeUnit.SECONDS)
                    val result = mockMvc.postOrder(sharedKey, body).andReturn()
                    val statusCode = result.response.status

                    if (statusCode == 202) {
                        successCount.incrementAndGet()
                        val content = result.response.contentAsString
                        val orderId = com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(content).get("orderId").asText()
                        synchronized(lock) { orderIds.add(orderId) }
                    } else {
                        errorCount.incrementAndGet()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // Release all threads simultaneously
        startLatch.countDown()
        val finished = doneLatch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertThat(finished).isTrue()

        // All requests must succeed (202) — no 5xx from constraint violation
        assertThat(errorCount.get())
            .withFailMessage("Expected 0 error responses but got ${errorCount.get()}")
            .isEqualTo(0)

        assertThat(successCount.get()).isEqualTo(concurrency)

        // Exactly one order was persisted
        val ordersInDb = orderRepository.findByIdempotencyKey(sharedKey)
        assertThat(ordersInDb).isNotNull()

        val allOrders = orderRepository.findAll().filter { it.idempotencyKey == sharedKey }
        assertThat(allOrders).hasSize(1)

        // All responses referenced the same orderId
        assertThat(orderIds)
            .withFailMessage("Concurrent requests returned different orderIds: $orderIds")
            .hasSize(1)
        assertThat(orderIds.first()).isEqualTo(allOrders[0].id.toString())
    }

    @Test
    fun `sequential requests with same key return the same orderId`() {
        val key = "sequential-key-${UUID.randomUUID()}"
        val body = TestHelpers.createOrderPayload()

        val response1 = mockMvc.postOrder(key, body)
            .andExpect(status().isAccepted)
            .andReturn()

        val response2 = mockMvc.postOrder(key, body)
            .andExpect(status().isAccepted)
            .andReturn()

        val orderId1 = com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(response1.response.contentAsString).get("orderId").asText()
        val orderId2 = com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(response2.response.contentAsString).get("orderId").asText()

        assertThat(orderId1).isEqualTo(orderId2)

        val allOrders = orderRepository.findAll().filter { it.idempotencyKey == key }
        assertThat(allOrders).hasSize(1)
    }
}
