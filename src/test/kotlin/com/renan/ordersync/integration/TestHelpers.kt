package com.renan.ordersync.integration

import com.renan.ordersync.domain.enums.OrderStatus
import com.renan.ordersync.repository.OrderRepository
import org.awaitility.Awaitility
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.time.Duration
import java.util.UUID

/**
 * Integration-test utilities: payload builders and async-polling helpers.
 */
object TestHelpers {

    // ── Payload builders ──────────────────────────────────────

    fun createOrderPayload(
        externalOrderId: String = "EXT-${UUID.randomUUID()}",
        sourceSystem: String = "test-client",
        customerName: String = "Jane Doe",
        customerEmail: String = "jane@example.com",
        currency: String = "BRL",
        itemSku: String = "SKU-001",
        quantity: Int = 2,
        unitPrice: String = "49.99",
    ): String = """
        {
          "externalOrderId": "$externalOrderId",
          "sourceSystem": "$sourceSystem",
          "customer": {
            "name": "$customerName",
            "email": "$customerEmail"
          },
          "currency": "$currency",
          "items": [
            {
              "sku": "$itemSku",
              "name": "Widget",
              "quantity": $quantity,
              "unitPrice": $unitPrice
            }
          ]
        }
    """.trimIndent()

    // ── MockMvc helpers ───────────────────────────────────────

    fun MockMvc.postOrder(
        idempotencyKey: String,
        body: String,
    ): ResultActions =
        perform(
            MockMvcRequestBuilders.post("/api/v1/orders")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )

    fun MockMvc.postOrderNoKey(body: String): ResultActions =
        perform(
            MockMvcRequestBuilders.post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )

    fun MockMvc.getOrder(orderId: UUID): ResultActions =
        perform(MockMvcRequestBuilders.get("/api/v1/orders/$orderId"))

    fun MockMvc.getOrdersByExternal(externalOrderId: String): ResultActions =
        perform(MockMvcRequestBuilders.get("/api/v1/orders").param("externalOrderId", externalOrderId))

    fun MockMvc.retryOrder(orderId: UUID): ResultActions =
        perform(MockMvcRequestBuilders.post("/api/v1/orders/$orderId/retry"))

    // ── Async polling ─────────────────────────────────────────

    /**
     * Polls the order in the database until it reaches the expected [status] or the timeout elapses.
     * Uses Awaitility to avoid arbitrary sleeps.
     */
    fun awaitOrderStatus(
        orderRepository: OrderRepository,
        orderId: UUID,
        expectedStatus: OrderStatus,
        timeoutSeconds: Long = 10,
    ) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(timeoutSeconds))
            .pollInterval(Duration.ofMillis(200))
            .until {
                orderRepository.findById(orderId)
                    .map { it.status == expectedStatus }
                    .orElse(false)
            }
    }
}
