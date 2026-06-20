package com.renan.ordersync.mapper

import com.renan.ordersync.domain.entity.Order
import com.renan.ordersync.domain.entity.OrderItem
import com.renan.ordersync.domain.enums.OrderStatus
import com.renan.ordersync.dto.request.CreateOrderRequest
import com.renan.ordersync.dto.response.*
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class OrderMapper {

    /**
     * Maps a validated [CreateOrderRequest] to an [Order] entity, including its items.
     * `totalAmount` is derived from the sum of each item's `quantity × unitPrice`.
     * All item references are wired to the returned order instance.
     */
    fun toEntity(request: CreateOrderRequest, idempotencyKey: String): Order {
        val order = Order(
            externalOrderId = request.externalOrderId,
            sourceSystem = request.sourceSystem,
            customerName = request.customer.name!!, // non-null guaranteed by @NotBlank validation
            customerEmail = request.customer.email!!, // non-null guaranteed by @NotBlank validation
            currency = request.currency,
            totalAmount = BigDecimal.ZERO, // recalculated below
            status = OrderStatus.RECEIVED,
            idempotencyKey = idempotencyKey,
        )

        val items = request.items.map { itemRequest ->
            val totalPrice = itemRequest.unitPrice.multiply(BigDecimal.valueOf(itemRequest.quantity.toLong()))
            OrderItem(
                order = order,
                sku = itemRequest.sku,
                name = itemRequest.name,
                quantity = itemRequest.quantity,
                unitPrice = itemRequest.unitPrice,
                totalPrice = totalPrice,
            )
        }

        order.items.addAll(items)
        order.totalAmount = items.sumOf { it.totalPrice }

        return order
    }

    fun toCreateResponse(order: Order): CreateOrderResponse =
        CreateOrderResponse(
            orderId = order.id,
            externalOrderId = order.externalOrderId,
            status = order.status,
            message = "Order received and queued for processing",
        )

    fun toDetailResponse(order: Order): OrderDetailResponse =
        OrderDetailResponse(
            orderId = order.id,
            externalOrderId = order.externalOrderId,
            sourceSystem = order.sourceSystem,
            customer = CustomerResponse(
                name = order.customerName,
                email = order.customerEmail,
            ),
            currency = order.currency,
            totalAmount = order.totalAmount,
            status = order.status,
            createdAt = order.createdAt,
            updatedAt = order.updatedAt,
            items = order.items.map { item ->
                OrderItemResponse(
                    id = item.id,
                    sku = item.sku,
                    name = item.name,
                    quantity = item.quantity,
                    unitPrice = item.unitPrice,
                    totalPrice = item.totalPrice,
                )
            },
            events = order.events
                .sortedBy { it.createdAt }
                .map { event ->
                    ProcessingEventResponse(
                        id = event.id,
                        eventType = event.eventType,
                        message = event.message,
                        attemptNumber = event.attemptNumber,
                        createdAt = event.createdAt,
                    )
                },
        )

    fun toRetryResponse(order: Order): RetryOrderResponse =
        RetryOrderResponse(
            orderId = order.id,
            status = order.status,
            message = "Retry triggered. Order queued for reprocessing.",
        )
}
