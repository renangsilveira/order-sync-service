package com.renan.ordersync.mapper

import com.renan.ordersync.domain.enums.OrderStatus
import com.renan.ordersync.domain.enums.ProcessingEventType
import com.renan.ordersync.dto.request.OrderItemRequest
import com.renan.ordersync.fixtures.TestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OrderMapperTest {

    private val mapper = OrderMapper()

    // ── toEntity ──────────────────────────────────────────────

    @Test
    fun `toEntity sets status to RECEIVED`() {
        val request = TestFixtures.createOrderRequest()

        val order = mapper.toEntity(request, TestFixtures.DEFAULT_IDEMPOTENCY_KEY)

        assertThat(order.status).isEqualTo(OrderStatus.RECEIVED)
    }

    @Test
    fun `toEntity stores idempotency key`() {
        val request = TestFixtures.createOrderRequest()

        val order = mapper.toEntity(request, "key-xyz")

        assertThat(order.idempotencyKey).isEqualTo("key-xyz")
    }

    @Test
    fun `toEntity calculates total as sum of quantity times unitPrice`() {
        val request = TestFixtures.createOrderRequest(
            items = listOf(
                OrderItemRequest(sku = "A", name = "Alpha", quantity = 2, unitPrice = BigDecimal("10.00")),
                OrderItemRequest(sku = "B", name = "Beta",  quantity = 3, unitPrice = BigDecimal("20.00")),
            ),
        )

        val order = mapper.toEntity(request, "key")

        // 2 × 10 + 3 × 20 = 20 + 60 = 80
        assertThat(order.totalAmount).isEqualByComparingTo(BigDecimal("80.00"))
    }

    @Test
    fun `toEntity creates correct number of items`() {
        val request = TestFixtures.createOrderRequest(
            items = listOf(
                TestFixtures.defaultItemRequest(sku = "S1"),
                TestFixtures.defaultItemRequest(sku = "S2"),
                TestFixtures.defaultItemRequest(sku = "S3"),
            ),
        )

        val order = mapper.toEntity(request, "key")

        assertThat(order.items).hasSize(3)
    }

    @Test
    fun `toEntity wires all items back to the same order instance`() {
        val request = TestFixtures.createOrderRequest(
            items = listOf(
                TestFixtures.defaultItemRequest(sku = "S1"),
                TestFixtures.defaultItemRequest(sku = "S2"),
            ),
        )

        val order = mapper.toEntity(request, "key")

        order.items.forEach { item ->
            assertThat(item.order).isSameAs(order)
        }
    }

    @Test
    fun `toEntity computes totalPrice per item as quantity times unitPrice`() {
        val request = TestFixtures.createOrderRequest(
            items = listOf(
                OrderItemRequest(sku = "X", name = "Widget", quantity = 5, unitPrice = BigDecimal("3.50")),
            ),
        )

        val order = mapper.toEntity(request, "key")

        val item = order.items.single()
        assertThat(item.totalPrice).isEqualByComparingTo(BigDecimal("17.50"))
    }

    @Test
    fun `toEntity maps customer fields correctly`() {
        val request = TestFixtures.createOrderRequest(
            customerName = "Alice Smith",
            customerEmail = "alice@example.com",
        )

        val order = mapper.toEntity(request, "key")

        assertThat(order.customerName).isEqualTo("Alice Smith")
        assertThat(order.customerEmail).isEqualTo("alice@example.com")
    }

    // ── toCreateResponse ──────────────────────────────────────

    @Test
    fun `toCreateResponse maps all fields`() {
        val order = TestFixtures.createOrder()

        val response = mapper.toCreateResponse(order)

        assertThat(response.orderId).isEqualTo(order.id)
        assertThat(response.externalOrderId).isEqualTo(order.externalOrderId)
        assertThat(response.status).isEqualTo(OrderStatus.RECEIVED)
        assertThat(response.message).isNotBlank()
    }

    // ── toDetailResponse ──────────────────────────────────────

    @Test
    fun `toDetailResponse maps customer block`() {
        val order = TestFixtures.createOrder(customerName = "Bob", customerEmail = "bob@test.com")

        val response = mapper.toDetailResponse(order)

        assertThat(response.customer.name).isEqualTo("Bob")
        assertThat(response.customer.email).isEqualTo("bob@test.com")
    }

    @Test
    fun `toDetailResponse includes all items`() {
        val order = TestFixtures.createOrder() // has 1 item by default

        val response = mapper.toDetailResponse(order)

        assertThat(response.items).hasSize(1)
        assertThat(response.items[0].sku).isEqualTo("SKU-001")
    }

    @Test
    fun `toDetailResponse sorts events by createdAt ascending`() {
        val order = TestFixtures.createOrder()
        val e1 = TestFixtures.createProcessingEvent(order, ProcessingEventType.ORDER_RECEIVED)
        val e2 = TestFixtures.createProcessingEvent(order, ProcessingEventType.PROCESSING_STARTED)

        // Force e2.createdAt to be after e1.createdAt
        Thread.sleep(5)
        e2.createdAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)

        order.events.addAll(listOf(e2, e1)) // intentionally add in reverse order

        val response = mapper.toDetailResponse(order)

        assertThat(response.events.map { it.eventType })
            .containsExactly(ProcessingEventType.ORDER_RECEIVED, ProcessingEventType.PROCESSING_STARTED)
    }

    // ── toRetryResponse ───────────────────────────────────────

    @Test
    fun `toRetryResponse maps orderId and status`() {
        val order = TestFixtures.createFailedOrder()

        val response = mapper.toRetryResponse(order)

        assertThat(response.orderId).isEqualTo(order.id)
        assertThat(response.status).isEqualTo(OrderStatus.FAILED)
        assertThat(response.message).isNotBlank()
    }
}
