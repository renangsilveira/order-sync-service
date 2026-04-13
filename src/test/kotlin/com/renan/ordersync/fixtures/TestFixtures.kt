package com.renan.ordersync.fixtures

import com.renan.ordersync.domain.entity.IntegrationAttempt
import com.renan.ordersync.domain.entity.Order
import com.renan.ordersync.domain.entity.OrderItem
import com.renan.ordersync.domain.entity.ProcessingEvent
import com.renan.ordersync.domain.enums.OrderStatus
import com.renan.ordersync.domain.enums.ProcessingEventType
import com.renan.ordersync.dto.request.CreateOrderRequest
import com.renan.ordersync.dto.request.CustomerRequest
import com.renan.ordersync.dto.request.OrderItemRequest
import java.math.BigDecimal
import java.util.UUID

/**
 * Shared test fixtures to avoid duplication across unit and integration tests.
 * All returned objects are fully wired (items reference their parent order, etc.).
 */
object TestFixtures {

    const val DEFAULT_IDEMPOTENCY_KEY = "test-key-abc-123"
    const val DEFAULT_EXTERNAL_ORDER_ID = "EXT-001"
    const val DEFAULT_SOURCE_SYSTEM = "mobile-app"

    fun createOrderRequest(
        externalOrderId: String = DEFAULT_EXTERNAL_ORDER_ID,
        sourceSystem: String = DEFAULT_SOURCE_SYSTEM,
        customerName: String = "Jane Doe",
        customerEmail: String = "jane@example.com",
        currency: String = "BRL",
        items: List<OrderItemRequest> = listOf(defaultItemRequest()),
    ) = CreateOrderRequest(
        externalOrderId = externalOrderId,
        sourceSystem = sourceSystem,
        customer = CustomerRequest(name = customerName, email = customerEmail),
        currency = currency,
        items = items,
    )

    fun defaultItemRequest(
        sku: String = "SKU-001",
        name: String = "Widget",
        quantity: Int = 2,
        unitPrice: BigDecimal = BigDecimal("49.99"),
    ) = OrderItemRequest(
        sku = sku,
        name = name,
        quantity = quantity,
        unitPrice = unitPrice,
    )

    fun createOrder(
        id: UUID = UUID.randomUUID(),
        externalOrderId: String = DEFAULT_EXTERNAL_ORDER_ID,
        sourceSystem: String = DEFAULT_SOURCE_SYSTEM,
        customerName: String = "Jane Doe",
        customerEmail: String = "jane@example.com",
        currency: String = "BRL",
        totalAmount: BigDecimal = BigDecimal("99.98"),
        status: OrderStatus = OrderStatus.RECEIVED,
        idempotencyKey: String = DEFAULT_IDEMPOTENCY_KEY,
    ): Order {
        val order = Order(
            externalOrderId = externalOrderId,
            sourceSystem = sourceSystem,
            customerName = customerName,
            customerEmail = customerEmail,
            currency = currency,
            totalAmount = totalAmount,
            status = status,
            idempotencyKey = idempotencyKey,
        ).also { it.id = id }

        val item = OrderItem(
            order = order,
            sku = "SKU-001",
            name = "Widget",
            quantity = 2,
            unitPrice = BigDecimal("49.99"),
            totalPrice = BigDecimal("99.98"),
        )
        order.items.add(item)

        return order
    }

    fun createFailedOrder(idempotencyKey: String = DEFAULT_IDEMPOTENCY_KEY): Order =
        createOrder(status = OrderStatus.FAILED, idempotencyKey = idempotencyKey)

    fun createProcessingEvent(
        order: Order,
        eventType: ProcessingEventType = ProcessingEventType.ORDER_RECEIVED,
        message: String? = null,
        attemptNumber: Int = 0,
    ) = ProcessingEvent(
        order = order,
        eventType = eventType,
        message = message,
        attemptNumber = attemptNumber,
    )

    fun createIntegrationAttempt(
        order: Order,
        attemptNumber: Int = 1,
        success: Boolean = false,
        errorMessage: String? = null,
    ) = IntegrationAttempt(
        order = order,
        attemptNumber = attemptNumber,
        requestPayload = "orderId=${order.id}",
        success = success,
        errorMessage = errorMessage,
    )
}
